package app.guantera.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.guantera.GuanteraApp
import app.guantera.R
import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.port.CopyInfo
import app.guantera.presentation.port.RecoveryCause
import app.guantera.presentation.port.RestoreOutcome
import app.guantera.presentation.recovery.RecoveryDialog
import app.guantera.presentation.recovery.RecoveryPresenter
import app.guantera.presentation.recovery.RecoveryViewState
import app.guantera.ui.components.StepButton
import app.guantera.ui.components.StepPanelView
import app.guantera.ui.screens.exportText
import app.guantera.ui.screens.importText
import kotlinx.coroutines.launch
import java.util.Date

/**
 * P12. It does not use the container, the shell, the service or any presenter of the rest of the
 * app: it only needs [GuanteraApp.recovery]. If anything else is broken, this still opens.
 */
class RecoveryActivity : AppCompatActivity() {
    private lateinit var panel: StepPanelView
    private lateinit var presenter: RecoveryPresenter
    private val format = DisplayFormat()
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GuanteraApp
        presenter = RecoveryPresenter(app.recovery, app.startup, lifecycleScope)
        panel = StepPanelView(this)
        setContentView(panel)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { presenter.state.collect(::render) }
        }
    }

    private fun text(id: Int): CharSequence = getString(id)

    private fun render(state: RecoveryViewState) {
        if (state.finished) {
            finishRecovery()
            return
        }
        panel.hideInput()
        panel.setError(null)
        val dialog = state.dialog
        when {
            dialog != null -> renderDialog(dialog)
            state.restored != null -> renderRestored(state.restored)
            else -> renderOptions(state)
        }
    }

    private fun renderRestored(copy: CopyInfo) {
        panel.show(
            text(R.string.rc_kicker), text(R.string.rc_restored_title),
            getString(R.string.rc_restored_message, dateOf(copy), format.whole(copy.recordCount)),
        )
        panel.setButtons(listOf(StepButton(text(R.string.rp_ok), primary = true) { presenter.acknowledgeRestored() }))
    }

    private fun renderOptions(state: RecoveryViewState) {
        val (title, message) = when (state.cause) {
            RecoveryCause.NoValidCopy -> R.string.rc_title_no_copy to R.string.rc_message_no_copy
            RecoveryCause.DatabaseNotMoved -> R.string.rc_title_not_moved to R.string.rc_message_not_moved
            RecoveryCause.MigrationFailed -> R.string.rc_title_migration to R.string.rc_message_migration
            RecoveryCause.RepeatedCrashes -> R.string.rc_title_crashes to R.string.rc_message_crashes
            RecoveryCause.RestoreFailed -> R.string.rc_title_restore_failed to R.string.rc_message_restore_failed
            RecoveryCause.CheckFailed, null -> R.string.rc_title_check_failed to R.string.rc_message_check_failed
        }
        val copies = if (state.copies.isEmpty()) text(R.string.rc_no_copies) else text(R.string.rc_copies)
        panel.show(text(R.string.rc_kicker), text(title), "${getString(message)}\n\n$copies")
        val buttons = mutableListOf<StepButton>()
        state.copies.forEachIndexed { index, copy ->
            buttons += StepButton(
                getString(R.string.rc_restore_copy, dateOf(copy), format.whole(copy.recordCount)),
                primary = index == 0,
            ) { presenter.restore(copy.id) }
        }
        buttons += StepButton(text(R.string.rc_import), primary = state.copies.isEmpty()) { presenter.importFromDrive() }
        buttons += StepButton(text(R.string.rc_export), primary = false) { presenter.export() }
        buttons += StepButton(text(R.string.rc_fresh), primary = false) { presenter.askStartFresh() }
        panel.setButtons(buttons)
    }

    private fun renderDialog(dialog: RecoveryDialog) {
        val close = StepButton(text(R.string.rp_ok), primary = true) { presenter.dismissDialog() }
        when (dialog) {
            RecoveryDialog.ConfirmFresh -> {
                panel.show(text(R.string.rc_kicker), text(R.string.rc_fresh_confirm_title), text(R.string.rc_fresh_confirm_message))
                panel.setButtons(
                    listOf(
                        StepButton(text(R.string.rc_fresh_confirm_yes), primary = true) { presenter.confirmStartFresh() },
                        StepButton(text(R.string.prompt_cancel), primary = false) { presenter.dismissDialog() },
                    ),
                )
            }
            RecoveryDialog.Working -> {
                panel.show(text(R.string.rc_kicker), text(R.string.rp_working), null)
                panel.setButtons(emptyList())
            }
            is RecoveryDialog.RestoreFailed -> {
                val message = when (dialog.outcome) {
                    RestoreOutcome.Unreadable -> R.string.rc_restore_unreadable
                    RestoreOutcome.Damaged -> R.string.rc_restore_damaged
                    RestoreOutcome.NewerVersion -> R.string.rc_restore_newer
                    RestoreOutcome.Restored, RestoreOutcome.Failed -> R.string.rc_restore_failed
                }
                panel.show(text(R.string.rc_kicker), text(message), null)
                panel.setButtons(listOf(close))
            }
            is RecoveryDialog.ImportFailed -> {
                panel.show(text(R.string.rc_kicker), text(R.string.rp_import_title), importText(dialog.outcome))
                panel.setButtons(listOf(close))
            }
            is RecoveryDialog.ExportReport -> {
                panel.show(text(R.string.rc_kicker), text(R.string.rp_export_title), exportText(dialog.outcome))
                panel.setButtons(listOf(close))
            }
            RecoveryDialog.FreshFailed -> {
                panel.show(text(R.string.rc_kicker), text(R.string.rc_fresh_failed), null)
                panel.setButtons(listOf(close))
            }
        }
    }

    private fun dateOf(copy: CopyInfo): String = DateFormat.format("dd/MM/yyyy HH:mm", Date(copy.createdUtcMs)).toString()

    private fun finishRecovery() {
        if (finishing) return
        finishing = true
        (application as GuanteraApp).recoveryFinished()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
