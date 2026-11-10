package app.guantera.presentation.recovery

import app.guantera.presentation.port.CopyInfo
import app.guantera.presentation.port.ExportOutcome
import app.guantera.presentation.port.ImportOutcome
import app.guantera.presentation.port.RecoveryActions
import app.guantera.presentation.port.RecoveryCause
import app.guantera.presentation.port.RestoreOutcome
import app.guantera.presentation.port.StartupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface RecoveryDialog {
    /** "Empezar de cero" always asks first: it is the one action that leaves the user with an empty app. */
    data object ConfirmFresh : RecoveryDialog
    data object Working : RecoveryDialog
    data class RestoreFailed(val outcome: RestoreOutcome) : RecoveryDialog
    data class ImportFailed(val outcome: ImportOutcome) : RecoveryDialog
    data class ExportReport(val outcome: ExportOutcome) : RecoveryDialog
    data object FreshFailed : RecoveryDialog
}

/** What P12 shows. [restored] is set when the screen only reports a copy that was already put back. */
data class RecoveryViewState(
    val cause: RecoveryCause?,
    val copies: List<CopyInfo>,
    val restored: CopyInfo?,
    val dialog: RecoveryDialog?,
    /** True once the data can be used and the app may start. */
    val finished: Boolean,
)

/**
 * P12. Written against [RecoveryActions] alone, so a failure anywhere else in the app cannot take
 * away the way back to the data.
 */
class RecoveryPresenter(
    private val actions: RecoveryActions,
    start: StartupResult,
    private val scope: CoroutineScope,
) {
    private val ui = MutableStateFlow(
        when (start) {
            StartupResult.Healthy -> RecoveryViewState(null, emptyList(), null, null, finished = true)
            is StartupResult.Restored -> RecoveryViewState(null, emptyList(), start.copy, null, finished = false)
            is StartupResult.NeedsUser -> RecoveryViewState(start.cause, start.copies, null, null, finished = false)
        },
    )
    val state: StateFlow<RecoveryViewState> = ui

    /** "Entendido" after being told what was restored. */
    fun acknowledgeRestored() { ui.value = ui.value.copy(finished = true) }

    fun restore(copyId: String) {
        ui.value = ui.value.copy(dialog = RecoveryDialog.Working)
        scope.launch {
            when (val outcome = actions.restore(copyId)) {
                RestoreOutcome.Restored -> ui.value = ui.value.copy(dialog = null, finished = true)
                else -> ui.value = ui.value.copy(dialog = RecoveryDialog.RestoreFailed(outcome))
            }
        }
    }

    fun importFromDrive() {
        ui.value = ui.value.copy(dialog = RecoveryDialog.Working)
        scope.launch {
            when (val outcome = actions.importFromDrive()) {
                is ImportOutcome.Done -> ui.value = ui.value.copy(dialog = null, finished = true)
                else -> ui.value = ui.value.copy(dialog = RecoveryDialog.ImportFailed(outcome))
            }
        }
    }

    fun export() {
        ui.value = ui.value.copy(dialog = RecoveryDialog.Working)
        scope.launch { ui.value = ui.value.copy(dialog = RecoveryDialog.ExportReport(actions.export())) }
    }

    fun askStartFresh() { ui.value = ui.value.copy(dialog = RecoveryDialog.ConfirmFresh) }

    /** Only goes through after [askStartFresh]: there is no way to start from zero by accident. */
    fun confirmStartFresh() {
        if (ui.value.dialog != RecoveryDialog.ConfirmFresh) return
        ui.value = ui.value.copy(dialog = RecoveryDialog.Working)
        scope.launch {
            ui.value = if (actions.startFresh()) ui.value.copy(dialog = null, finished = true)
            else ui.value.copy(dialog = RecoveryDialog.FreshFailed)
        }
    }

    fun dismissDialog() { ui.value = ui.value.copy(dialog = null) }
}
