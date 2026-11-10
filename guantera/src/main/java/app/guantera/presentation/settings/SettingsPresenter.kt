package app.guantera.presentation.settings

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.port.AppSettings
import app.guantera.presentation.port.DateTimeInfo
import app.guantera.presentation.port.DateTimeSettings
import app.guantera.presentation.port.DemoControls
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.MusicSource
import app.guantera.presentation.port.SettingsStore
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Ages are whole days: 0 is today, null is "never". */
data class SettingsViewState(
    val carName: String,
    val odometerText: String,
    val calibrationDays: Int?,
    val currency: String,
    val gps: GpsStatus,
    val openAtBoot: Boolean,
    val catalogRestoredDays: Int?,
    val backupDays: Int?,
    val exportDays: Int?,
    val exportOverdue: Boolean,
    val showDemo: Boolean,
    val musicReal: Boolean,
    val dateTime: DateTimeUi,
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

class SettingsPresenter(
    data: GuanteraData,
    private val demo: DemoControls?,
    private val store: SettingsStore,
    private val music: MusicSource,
    private val dateTime: DateTimeSettings,
    private val format: DisplayFormat,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    private val motion = data.status
    private var confirmedUntil = 0L

    private val dateTimeInfo = MutableStateFlow(dateTime.read())

    val state: StateFlow<SettingsViewState> = combine(
        data.profile, data.status, store.settings, music.isReal, dateTimeInfo,
    ) { profile, status, settings, real, clockInfo ->
        reduce(profile.name, status.gps, settings, real, clockInfo)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(data.profile.value.name, data.status.value.gps, store.settings.value, music.isReal.value, dateTimeInfo.value),
    )

    /** Re-reads the clock settings, e.g. after coming back from the system screen. */
    fun refreshDateTime() { dateTimeInfo.value = dateTime.read() }

    /** Spec section 5: with an unknown motion state a change needs "¿El auto está estacionado?" first. */
    fun requiresStoppedConfirmation(): Boolean =
        motion.value.motion == MotionStatus.Unknown && clock.elapsedRealtimeMillis() >= confirmedUntil

    fun confirmStopped() { confirmedUntil = clock.elapsedRealtimeMillis() + CONFIRMATION_MILLIS }

    fun simulateTrip() { demo?.startSimulatedTrip() }

    fun correctOdometer(input: String): OdometerResult {
        val digits = input.trim().replace(".", "").replace(" ", "")
        val km = digits.toIntOrNull()
        if (km == null || km < 0 || km > MAX_ODOMETER) return OdometerResult.Invalid
        store.correctOdometer(km)
        return OdometerResult.Saved
    }

    fun nextCurrency() {
        val current = CURRENCIES.indexOf(store.settings.value.currency)
        store.setCurrency(CURRENCIES[(current + 1) % CURRENCIES.size])
    }

    fun toggleOpenAtBoot() = store.setOpenAtBoot(!store.settings.value.openAtBoot)
    fun restoreCatalog() = store.restoreCatalog()
    fun setMusicReal(real: Boolean) = music.setReal(real)

    private fun reduce(carName: String, gps: GpsStatus, s: AppSettings, musicReal: Boolean, clockInfo: DateTimeInfo) = SettingsViewState(
        carName = carName,
        odometerText = format.whole(s.odometerKm),
        calibrationDays = days(s.odometerCalibratedAtMillis),
        currency = s.currency,
        gps = gps,
        openAtBoot = s.openAtBoot,
        catalogRestoredDays = days(s.catalogRestoredAtMillis),
        backupDays = days(s.lastBackupMillis),
        exportDays = days(s.lastExportMillis),
        exportOverdue = s.lastExportMillis == null || (days(s.lastExportMillis) ?: 0) >= EXPORT_OVERDUE_DAYS,
        showDemo = demo != null,
        musicReal = musicReal,
        dateTime = clockInfo.toUi(),
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
        private const val CONFIRMATION_MILLIS = 10 * 60_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
