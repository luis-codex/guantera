package app.guantera.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import app.guantera.R
import app.guantera.databinding.ScreenSettingsBinding
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.settings.DateTimeUi
import app.guantera.presentation.settings.OdometerResult
import app.guantera.presentation.settings.SettingsPresenter
import app.guantera.presentation.settings.SettingsViewState
import app.guantera.ui.Screen
import app.guantera.ui.openSystemSettings

/** P11: the car, location, startup, saved data and the demonstration switches. */
class SettingsScreen(
    parent: ViewGroup,
    private val presenter: SettingsPresenter,
    private val onKeyboardClosed: () -> Unit,
) : Screen() {
    private val binding = ScreenSettingsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    private val context = parent.context
    override val view = binding.root
    private var latest: SettingsViewState? = null
    private var onAccept: (() -> Boolean)? = null
    private var gpsSettingsFailed = false
    private var dateSettingsFailed = false

    init {
        listOf(binding.rowOdometer, binding.rowCurrency, binding.rowGps, binding.rowTime, binding.rowBoot, binding.rowCatalog,
            binding.rowExportAction, binding.rowImportAction, binding.demoRow).forEach { it.setSecondary() }

        binding.rowOdometer.setOnAction { guarded(::askOdometer) }
        binding.rowCurrency.setOnAction { guarded(presenter::nextCurrency) }
        binding.rowGps.setOnAction { openAppSettings() }
        binding.rowTime.setOnAction { openDateSettings() }
        binding.rowBoot.setOnAction { guarded(presenter::toggleOpenAtBoot) }
        binding.rowCatalog.setOnAction { guarded(::askCatalog) }
        binding.demoRow.setOnAction(presenter::simulateTrip)
        binding.musicSample.setOnClickListener { presenter.setMusicReal(false) }
        binding.musicReal.setOnClickListener { presenter.setMusicReal(true) }

        binding.demoRow.setTexts(context.getString(R.string.demo_title), context.getString(R.string.demo_subtitle))
        binding.demoRow.setAction(context.getString(R.string.demo_action))
        binding.rowExportAction.setTexts(context.getString(R.string.settings_export_title), context.getString(R.string.settings_not_yet))
        binding.rowExportAction.setActionDisabled(context.getString(R.string.settings_export_action))
        binding.rowImportAction.setTexts(context.getString(R.string.settings_import_title), context.getString(R.string.settings_not_yet))
        binding.rowImportAction.setActionDisabled(context.getString(R.string.settings_import_action))

        binding.promptCancel.setOnClickListener { closePrompt() }
        binding.promptAccept.setOnClickListener { if (onAccept?.invoke() == true) closePrompt() }
    }

    override suspend fun observe() {
        presenter.refreshDateTime() // may have changed in the system screen we just came back from
        presenter.state.collect(::render)
    }

    private fun render(state: SettingsViewState) {
        latest = state
        binding.rowCar.setValue(state.carName)
        binding.rowOdometer.setTexts(
            context.getString(R.string.settings_odometer_title),
            context.getString(R.string.settings_odometer_sub, state.odometerText, calibration(state.calibrationDays)),
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

        binding.demoSection.isVisible = state.showDemo
        binding.musicSample.setSelectedLook(!state.musicReal)
        binding.musicReal.setSelectedLook(state.musicReal)
        binding.musicSubtitle.setText(if (state.musicReal) R.string.settings_music_real_sub else R.string.settings_music_sample_sub)
    }

    private fun android.widget.Button.setSelectedLook(selected: Boolean) {
        setBackgroundResource(if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_secondary)
        setTextColor(ContextCompat.getColor(context, if (selected) R.color.background else R.color.ink))
        isSelected = selected
        contentDescription = if (selected) context.getString(R.string.nav_current, text) else text
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

    private fun openAppSettings() {
        val opened = context.openSystemSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        if (!opened) {
            gpsSettingsFailed = true
            binding.rowGps.setTexts(context.getString(R.string.settings_gps_title), context.getString(R.string.settings_gps_open_failed))
        }
    }

    /** Spec section 5: with the car's movement unknown, any change first asks if it is parked. */
    private fun guarded(action: () -> Unit) {
        if (!presenter.requiresStoppedConfirmation()) return action()
        openPrompt(
            R.string.prompt_stopped_title, R.string.prompt_stopped_message,
            R.string.prompt_stopped_no, R.string.prompt_stopped_yes, withInput = false,
        ) {
            presenter.confirmStopped()
            action()
            true
        }
    }

    private fun askOdometer() {
        openPrompt(
            R.string.prompt_odometer_title, R.string.prompt_odometer_message,
            R.string.prompt_cancel, R.string.prompt_odometer_save, withInput = true,
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
        latest?.let { binding.promptInput.setText(it.odometerText.replace(".", "")) }
        binding.promptInput.selectAll()
    }

    private fun askCatalog() {
        openPrompt(
            R.string.prompt_catalog_title, R.string.prompt_catalog_message,
            R.string.prompt_cancel, R.string.prompt_catalog_accept, withInput = false,
        ) {
            presenter.restoreCatalog()
            true
        }
    }

    private fun openPrompt(title: Int, message: Int, cancel: Int, accept: Int, withInput: Boolean, onAccept: () -> Boolean) {
        this.onAccept = onAccept
        binding.promptTitle.setText(title)
        binding.promptMessage.setText(message)
        binding.promptCancel.setText(cancel)
        binding.promptAccept.setText(accept)
        binding.promptError.isVisible = false
        binding.promptInput.isVisible = withInput
        binding.prompt.isVisible = true
        if (withInput) {
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
        onKeyboardClosed() // closing the keyboard clears the hide flags of the system bars
    }
}
