package app.guantera.presentation.settings

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.port.AppSettings
import app.guantera.presentation.port.DateTimeInfo
import app.guantera.presentation.port.DateTimeSettings
import app.guantera.presentation.port.ExportOutcome
import app.guantera.presentation.port.FormOutcome
import app.guantera.presentation.port.ImportOutcome
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.port.SettingsStore
import app.guantera.sonda.domain.media.ControlDiagnostic
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ages are whole days: 0 is today, null is "never". */
data class SettingsViewState(
    val carName: String,
    /** Null: the odometer was never entered, and the screen says so. */
    val odometerText: String?,
    val calibrationDays: Int?,
    val currency: String,
    val gps: GpsStatus,
    val openAtBoot: Boolean,
    val catalogRestoredDays: Int?,
    val backupDays: Int?,
    val exportDays: Int?,
    val exportOverdue: Boolean,
    /** Per music control, the last route tried and whether it worked. */
    val musicControls: List<ControlDiagnostic>,
    val dateTime: DateTimeUi,
    /** What the screen has to ask or tell right now, over everything else. */
    val dialog: SettingsDialog?,
)

/** What the "Fecha y hora" block shows. Readings come straight from the radio's own settings. */
sealed interface DateTimeUi {
    /** The setting cannot be read on this ROM. */
    data object Unavailable : DateTimeUi
    data class Known(
        val autoTime: Boolean,
        val autoTimeZone: Boolean?,
        val timeZoneLabel: String?,
        /** False when the ROM has no date settings screen, so the button must not exist. */
        val canOpenSettings: Boolean,
    ) : DateTimeUi
}

sealed interface OdometerResult {
    data object Saved : OdometerResult
    data object Invalid : OdometerResult
}

/** Questions and results that take over P11 until the driver answers. */
sealed interface SettingsDialog {
    /** "¿El auto está estacionado?" (CU-04), after a change came back needing it. Yes tries the change again. */
    data object AskStopped : SettingsDialog

    /** The change was not written. Retry runs it again. */
    data class SaveFailed(val diskFull: Boolean) : SettingsDialog

    /** Importing replaces what is there: asked first. */
    data object ConfirmImport : SettingsDialog
    data object Working : SettingsDialog
    data class ExportReport(val outcome: ExportOutcome) : SettingsDialog
    data class ImportReport(val outcome: ImportOutcome) : SettingsDialog

    /** The car moves: nothing was changed. */
    data object CarMoving : SettingsDialog
}

class SettingsPresenter(
    data: GuanteraData,
    private val store: SettingsStore,
    private val musicControls: StateFlow<List<ControlDiagnostic>>,
    private val dateTime: DateTimeSettings,
    private val format: DisplayFormat,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val dialog = MutableStateFlow<SettingsDialog?>(null)
    private var retry: (() -> Unit)? = null

    private val dateTimeInfo = MutableStateFlow(dateTime.read())

    private val facts = combine(data.profile, data.status, store.settings) { profile, status, settings -> Triple(profile, status, settings) }

    val state: StateFlow<SettingsViewState> = combine(facts, musicControls, dateTimeInfo, dialog) { (profile, status, settings), controls, clockInfo, asking ->
        reduce(profile.name, status.gps, settings, controls, clockInfo, asking)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(data.profile.value.name, data.status.value.gps, store.settings.value, musicControls.value, dateTimeInfo.value, null),
    )

    /** Re-reads the clock settings, e.g. after coming back from the system screen. */
    fun refreshDateTime() { dateTimeInfo.value = dateTime.read() }

    /** Re-reads what only the disk knows, e.g. after coming back to the screen. */
    fun refreshBackupInfo() = store.refreshBackupInfo()

    /** "Sí" to "¿El auto está estacionado?": the domain learns it and the change that was waiting goes through. */
    fun answerStopped(parked: Boolean) {
        val waiting = retry
        retry = null
        dialog.value = null
        if (parked) {
            store.confirmStopped()
            waiting?.invoke()
        }
    }

    /** "Reintentar" after a failed write. */
    fun retryWrite() {
        val waiting = retry
        retry = null
        dialog.value = null
        waiting?.invoke()
    }

    fun dismissDialog() {
        retry = null
        dialog.value = null
    }

    /** The odometer typed in the prompt. Invalid input is answered at once; the write is answered by [state]. */
    fun correctOdometer(input: String): OdometerResult {
        val digits = input.trim().replace(".", "").replace(" ", "")
        val km = digits.toIntOrNull()
        if (km == null || km < 0 || km > MAX_ODOMETER) return OdometerResult.Invalid
        write { store.correctOdometer(km) }
        return OdometerResult.Saved
    }

    fun nextCurrency() {
        val current = CURRENCIES.indexOf(store.settings.value.currency)
        val next = CURRENCIES[(current + 1) % CURRENCIES.size]
        write { store.setCurrency(next) }
    }

    fun toggleOpenAtBoot() {
        val enabled = !store.settings.value.openAtBoot
        write { store.setOpenAtBoot(enabled) }
    }

    fun restoreCatalog() = write { store.restoreCatalog() }

    fun askImport() { dialog.value = SettingsDialog.ConfirmImport }

    fun export() {
        dialog.value = SettingsDialog.Working
        scope.launch {
            dialog.value = SettingsDialog.ExportReport(store.backups.export())
            store.refreshBackupInfo()
        }
    }

    fun importNewest() {
        dialog.value = SettingsDialog.Working
        scope.launch {
            dialog.value = SettingsDialog.ImportReport(store.backups.importFromDrive())
            store.refreshBackupInfo()
        }
    }

    /** Every change goes through the domain, which decides if it is allowed (R10); the screen only reports. */
    private fun write(change: suspend () -> FormOutcome) {
        scope.launch {
            when (change()) {
                FormOutcome.Saved -> dialog.value = null
                FormOutcome.NeedsConfirmation -> { retry = { write(change) }; dialog.value = SettingsDialog.AskStopped }
                FormOutcome.CarMoving -> dialog.value = SettingsDialog.CarMoving
                FormOutcome.StorageFull -> { retry = { write(change) }; dialog.value = SettingsDialog.SaveFailed(diskFull = true) }
                FormOutcome.Rejected, FormOutcome.StorageFailed -> { retry = { write(change) }; dialog.value = SettingsDialog.SaveFailed(diskFull = false) }
            }
        }
    }

    private fun reduce(carName: String, gps: GpsStatus, s: AppSettings, controls: List<ControlDiagnostic>, clockInfo: DateTimeInfo, asking: SettingsDialog?) = SettingsViewState(
        carName = carName,
        odometerText = s.odometerKm?.let(format::whole),
        calibrationDays = days(s.odometerCalibratedAtMillis),
        currency = s.currency,
        gps = gps,
        openAtBoot = s.openAtBoot,
        catalogRestoredDays = days(s.catalogRestoredAtMillis),
        backupDays = days(s.lastBackupMillis),
        exportDays = days(s.lastExportMillis),
        exportOverdue = s.lastExportMillis == null || (days(s.lastExportMillis) ?: 0) >= EXPORT_OVERDUE_DAYS,
        musicControls = controls,
        dateTime = clockInfo.toUi(),
        dialog = asking,
    )

    private fun DateTimeInfo.toUi(): DateTimeUi =
        if (autoTime == null) DateTimeUi.Unavailable
        else DateTimeUi.Known(autoTime, autoTimeZone, timeZoneLabel, settingsScreenAvailable)

    private fun days(at: Long?): Int? = at?.let { ((clock.nowMillis() - it).coerceAtLeast(0) / DAY_MILLIS).toInt() }

    companion object {
        val CURRENCIES = listOf("$", "US$", "R$", "€")
        const val MAX_ODOMETER = 9_999_999
        const val EXPORT_OVERDUE_DAYS = 30
        private const val DAY_MILLIS = 86_400_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
