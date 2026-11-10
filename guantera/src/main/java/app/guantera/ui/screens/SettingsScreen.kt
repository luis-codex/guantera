package app.guantera.ui.screens

import android.content.Context
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import app.guantera.R
import app.guantera.databinding.ScreenSettingsBinding
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.settings.DateTimeUi
import app.guantera.presentation.settings.OdometerResult
import app.guantera.presentation.settings.SettingsDialog
import app.guantera.presentation.settings.SettingsPresenter
import app.guantera.presentation.settings.SettingsViewState
import app.guantera.sonda.domain.media.ControlKind
import app.guantera.ui.Screen
import app.guantera.ui.openSystemSettings

/** P11: the car, location, startup, saved data and media diagnostics. */
class SettingsScreen(
    parent: ViewGroup,
    private val presenter: SettingsPresenter,
    private val onKeyboardClosed: () -> Unit,
    private val onGrantLocation: () -> Unit,
) : Screen() {
    private val binding = ScreenSettingsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    private val context = parent.context
    override val view = binding.root
    private var latest: SettingsViewState? = null
    private var onAccept: (() -> Boolean)? = null
    private var onCancel: (() -> Unit)? = null
    /** True while the prompt shows what the presenter asked or reported, so that clearing it closes the prompt. */
    private var promptFromPresenter = false
    private var dateSettingsFailed = false

    init {
        listOf(binding.rowOdometer, binding.rowCurrency, binding.rowGps, binding.rowTime, binding.rowBoot, binding.rowCatalog,
            binding.rowExportAction, binding.rowImportAction).forEach { it.setSecondary() }

        binding.rowOdometer.setOnAction(::askOdometer)
        binding.rowCurrency.setOnAction(presenter::nextCurrency)
        binding.rowGps.setOnAction(::grantLocation)
        binding.rowTime.setOnAction { openDateSettings() }
        binding.rowBoot.setOnAction(presenter::toggleOpenAtBoot)
        binding.rowCatalog.setOnAction(::askCatalog)
        binding.rowExportAction.setTexts(context.getString(R.string.settings_export_title), context.getString(R.string.settings_export_sub))
        binding.rowExportAction.setAction(context.getString(R.string.settings_export_action))
        binding.rowExportAction.setOnAction(presenter::export)
        binding.rowImportAction.setTexts(context.getString(R.string.settings_import_title), context.getString(R.string.settings_import_sub))
        binding.rowImportAction.setAction(context.getString(R.string.settings_import_action))
        binding.rowImportAction.setOnAction(presenter::askImport)

        binding.promptCancel.setOnClickListener { onCancel?.invoke() ?: closePrompt() }
        binding.promptAccept.setOnClickListener { if (onAccept?.invoke() == true && !promptFromPresenter) closePrompt() }
    }

    override suspend fun observe() {
        presenter.refreshDateTime() // may have changed in the system screen we just came back from
        presenter.refreshBackupInfo()
        presenter.state.collect(::render)
    }

    private fun render(state: SettingsViewState) {
        latest = state
        binding.rowCar.setValue(state.carName)
        binding.rowOdometer.setTexts(
            context.getString(R.string.settings_odometer_title),
            state.odometerText?.let { context.getString(R.string.settings_odometer_sub, it, calibration(state.calibrationDays)) }
                ?: context.getString(R.string.settings_odometer_none),
        )
        binding.rowOdometer.setAction(context.getString(R.string.settings_odometer_action))
        binding.rowCurrency.setTexts(
            context.getString(R.string.settings_currency_title),
            context.getString(R.string.settings_currency_sub, state.currency),
        )
        binding.rowCurrency.setAction(context.getString(R.string.settings_currency_action))

        binding.rowGps.setTexts(context.getString(R.string.settings_gps_title), gpsText(state.gps))
        binding.rowGps.setAction(if (state.gps == GpsStatus.NoPermission) context.getString(R.string.settings_gps_grant) else null)

        renderDateTime(state.dateTime)

        binding.rowBoot.setTexts(
            context.getString(R.string.settings_boot_title),
            context.getString(if (state.openAtBoot) R.string.settings_boot_on else R.string.settings_boot_off),
        )
        binding.rowBoot.setAction(context.getString(if (state.openAtBoot) R.string.settings_boot_disable else R.string.settings_boot_enable))

        binding.rowCatalog.setTexts(
            context.getString(R.string.settings_catalog_title),
            state.catalogRestoredDays?.let { context.getString(R.string.settings_catalog_done, age(it).lowercase()) }
                ?: context.getString(R.string.settings_catalog_sub),
        )
        binding.rowCatalog.setAction(context.getString(R.string.settings_catalog_action))

        binding.rowBackup.setValue(state.backupDays?.let(::age) ?: context.getString(R.string.settings_never))
        binding.rowExport.setValue(state.exportDays?.let(::age) ?: context.getString(R.string.settings_never))

        renderMusicControls(state)
        renderDialog(state.dialog)
    }

    private fun renderMusicControls(state: SettingsViewState) {
        val rows = mapOf(
            ControlKind.PlayPause to binding.musicRowPlay,
            ControlKind.Previous to binding.musicRowPrevious,
            ControlKind.Next to binding.musicRowNext,
            ControlKind.VolumeDown to binding.musicRowVolDown,
            ControlKind.VolumeUp to binding.musicRowVolUp,
            ControlKind.Mute to binding.musicRowMute,
        )
        state.musicControls.forEach { rows[it.control]?.setValue(context.diagnosticText(it)) }
    }

    private fun calibration(days: Int?): String = when (days) {
        null -> context.getString(R.string.settings_calibrated_never)
        0 -> context.getString(R.string.settings_calibrated_today)
        1 -> context.getString(R.string.settings_calibrated_day)
        else -> context.getString(R.string.settings_calibrated_days, days)
    }

    private fun age(days: Int): String = when (days) {
        0 -> context.getString(R.string.age_today)
        1 -> context.getString(R.string.age_yesterday)
        else -> context.getString(R.string.age_days, days)
    }

    private fun gpsText(gps: GpsStatus): String = context.getString(
        when (gps) {
            GpsStatus.Good -> R.string.settings_gps_good
            GpsStatus.Searching -> R.string.settings_gps_searching
            GpsStatus.Off -> R.string.settings_gps_off
            GpsStatus.NoPermission -> R.string.settings_gps_no_permission
        },
    )

    private fun renderDateTime(ui: DateTimeUi) {
        val title = context.getString(R.string.settings_time_title)
        val unavailable = context.getString(R.string.settings_time_unavailable)
        when (ui) {
            DateTimeUi.Unavailable -> {
                binding.rowTime.setTexts(title, unavailable)
                binding.rowTime.setAction(null)
                binding.rowZone.setValue(unavailable)
            }
            is DateTimeUi.Known -> {
                val canOpen = ui.canOpenSettings && !dateSettingsFailed
                val state = context.getString(if (ui.autoTime) R.string.settings_time_on else R.string.settings_time_off)
                binding.rowTime.setTexts(title, if (canOpen) state else "$state\n$unavailable")
                binding.rowTime.setAction(if (canOpen) context.getString(R.string.settings_time_action) else null)
                binding.rowZone.setValue(
                    when {
                        ui.timeZoneLabel == null -> unavailable
                        ui.autoTimeZone == true -> context.getString(R.string.settings_timezone_auto, ui.timeZoneLabel)
                        else -> ui.timeZoneLabel
                    },
                )
            }
        }
    }

    private fun openDateSettings() {
        if (!context.openSystemSettings(Settings.ACTION_DATE_SETTINGS)) {
            dateSettingsFailed = true
            latest?.let(::render)
        }
    }

    /** The row only offers the permission while it is missing; the platform dialog or, if refused for good, the app's page. */
    private fun grantLocation() = onGrantLocation()

    private fun askOdometer() {
        openPrompt(
            text(R.string.prompt_odometer_title), text(R.string.prompt_odometer_message),
            text(R.string.prompt_cancel), text(R.string.prompt_odometer_save), withInput = true,
        ) {
            when (presenter.correctOdometer(binding.promptInput.text.toString())) {
                OdometerResult.Saved -> true
                OdometerResult.Invalid -> {
                    binding.promptError.setText(R.string.prompt_odometer_invalid)
                    binding.promptError.isVisible = true
                    false
                }
            }
        }
        latest?.odometerText?.let { binding.promptInput.setText(it.replace(".", "")) }
        binding.promptInput.selectAll()
    }

    private fun askCatalog() {
        openPrompt(
            text(R.string.prompt_catalog_title), text(R.string.prompt_catalog_message),
            text(R.string.prompt_cancel), text(R.string.prompt_catalog_accept), withInput = false,
        ) {
            presenter.restoreCatalog()
            true
        }
    }

    /** What the presenter asks or reports takes over the prompt; once it clears, the prompt goes away. */
    private fun renderDialog(dialog: SettingsDialog?) {
        if (dialog == null) {
            if (promptFromPresenter) { promptFromPresenter = false; closePrompt() }
            return
        }
        val info = { presenter.dismissDialog(); true }
        when (dialog) {
            SettingsDialog.AskStopped -> fromPresenter(
                text(R.string.prompt_stopped_title), text(R.string.prompt_stopped_message_retry),
                text(R.string.prompt_stopped_no), text(R.string.prompt_stopped_yes),
                onCancel = { presenter.answerStopped(false) },
            ) { presenter.answerStopped(true); true }
            is SettingsDialog.SaveFailed -> fromPresenter(
                text(if (dialog.diskFull) R.string.settings_save_failed_full else R.string.er_save_failed), null,
                text(R.string.rp_close), text(R.string.rp_retry),
                onCancel = presenter::dismissDialog,
            ) { presenter.retryWrite(); true }
            SettingsDialog.ConfirmImport -> fromPresenter(
                text(R.string.rp_confirm_import_title), text(R.string.rp_confirm_import_message),
                text(R.string.prompt_cancel), text(R.string.rp_confirm_import_yes),
                onCancel = presenter::dismissDialog,
            ) { presenter.importNewest(); true }
            SettingsDialog.Working -> fromPresenter(text(R.string.rp_working), null, null, text(R.string.rp_working), onCancel = null) { false }
                .also { binding.promptAccept.isEnabled = false }
            is SettingsDialog.ExportReport -> fromPresenter(
                text(R.string.rp_export_title), context.exportText(dialog.outcome), null, text(R.string.rp_ok), onCancel = null, accept = info,
            )
            is SettingsDialog.ImportReport -> fromPresenter(
                text(R.string.rp_import_title), context.importText(dialog.outcome), null, text(R.string.rp_ok), onCancel = null, accept = info,
            )
            SettingsDialog.CarMoving -> fromPresenter(
                text(R.string.rp_car_moving), null, null, text(R.string.rp_ok), onCancel = null, accept = info,
            )
        }
    }

    private fun fromPresenter(
        title: CharSequence, message: CharSequence?, cancel: CharSequence?, acceptText: CharSequence,
        onCancel: (() -> Unit)?, accept: () -> Boolean,
    ) {
        promptFromPresenter = true
        openPrompt(title, message, cancel, acceptText, withInput = false, onCancel = onCancel, onAccept = accept)
        binding.promptAccept.isEnabled = true
    }

    private fun text(id: Int): CharSequence = context.getString(id)

    private fun openPrompt(
        title: CharSequence, message: CharSequence?, cancel: CharSequence?, accept: CharSequence, withInput: Boolean,
        onCancel: (() -> Unit)? = null, onAccept: () -> Boolean,
    ) {
        this.onAccept = onAccept
        this.onCancel = onCancel
        binding.promptTitle.text = title
        binding.promptMessage.isVisible = message != null
        binding.promptMessage.text = message
        binding.promptCancel.isVisible = cancel != null
        binding.promptCancel.text = cancel
        binding.promptAccept.text = accept
        binding.promptError.isVisible = false
        val wasVisible = binding.prompt.isVisible && binding.promptInput.isVisible
        binding.promptInput.isVisible = withInput
        binding.prompt.isVisible = true
        if (withInput && !wasVisible) {
            binding.promptInput.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(binding.promptInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closePrompt() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.promptInput.windowToken, 0)
        binding.prompt.isVisible = false
        onAccept = null
        onCancel = null
        onKeyboardClosed() // closing the keyboard clears the hide flags of the system bars
    }
}
