package app.guantera.domain.usecase

import app.guantera.domain.maintenance.MaintenanceAlert
import app.guantera.domain.motion.DrivingStateRules
import app.guantera.domain.parking.ParkingOrigin
import app.guantera.domain.parking.ParkingSpot
import app.guantera.domain.port.Clock
import app.guantera.domain.port.ParkingRepository
import app.guantera.domain.port.SettingsRepository
import app.guantera.domain.port.StorageException
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.settings.Notice
import app.guantera.domain.settings.NoticeInputs
import app.guantera.domain.settings.NoticeRules
import app.guantera.domain.time.DayRange
import app.guantera.domain.time.TrustedTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class TodaySummary(val km: Double, val tripCount: Int)

data class Dashboard(
    val today: TodaySummary,
    /** Null when nothing needs attention. */
    val urgentAlert: MaintenanceAlert?,
    val parkingSpot: ParkingSpot?,
    val notices: List<Notice>,
)

/** The banners of specification section 6 and what makes them change. */
class ReadNotices(
    private val driving: ObserveDrivingState,
    private val time: ResolveTrustedTime,
    private val reader: OdometerReader,
    private val settings: SettingsRepository,
    private val engine: TripEngine,
) {
    /** Only emits when a banner could change, so the 1 Hz ticks do not reach the database. */
    val changes: Flow<Unit>
        get() = combine(settings.changes, signals()) { _, _ -> }

    suspend fun load(): List<Notice> {
        val current = settings.get()
        return NoticeRules.notices(
            NoticeInputs(
                gps = driving.gpsStatus(),
                driving = driving.current(),
                hasTrustedTime = time() != TrustedTime.Unavailable,
                hasOdometer = reader.currentKm() != null,
                recordingGapPending = current.recordingGapPending,
                storageFull = engine.view.value.storageFailure == StorageFailure.FULL,
                lastExportUtcMs = current.lastExportUtcMs,
                nowUtcMs = time.bestEffortUtcMs(),
            ),
        )
    }

    private fun signals() = combine(driving.observe(), engine.view) { snapshot, view ->
        Triple(snapshot.state, snapshot.gps, view.storageFailure)
    }.distinctUntilChanged()
}

/** CU-05. */
class GetDashboard(
    private val listTrips: ListTrips,
    private val alerts: GetPendingAlerts,
    private val maintenance: ListMaintenanceStatus,
    private val parking: ParkingRepository,
    private val notices: ReadNotices,
    private val time: ResolveTrustedTime,
    private val calendar: LocalCalendar,
) {
    suspend fun load(): Dashboard {
        val todayTrips = listTrips.inRange(DayRange.day(calendar.dayOf(time.bestEffortUtcMs())))
        return Dashboard(
            today = TodaySummary(todayTrips.sumOf { it.distanceKm }, todayTrips.size),
            urgentAlert = alerts.load().firstOrNull(),
            parkingSpot = parking.current(),
            notices = notices.load(),
        )
    }

    fun observe(): Flow<Dashboard> =
        combine(listTrips.changes, maintenance.changes, parking.changes, notices.changes) { _, _, _, _ -> load() }
}

/** CU-19, R26. */
class GetLastParkingSpot(private val parking: ParkingRepository) {
    suspend fun load(): ParkingSpot? = parking.current()

    fun observe(): Flow<ParkingSpot?> = parking.changes.map { load() }
}

/** CU-20: "Ya estacioné" from P2 and "Guardar acá ahora" from P10. */
class MarkParkedNow(
    private val engine: TripEngine,
    private val parking: ParkingRepository,
    private val clock: Clock,
    private val form: FormSubmission,
) {
    /** R11. */
    suspend fun finishTrip(): ManualParkResult = engine.closeManually()

    /** R27: the fix must be fresh, because the spot has to be where the car is now. */
    suspend fun saveHere(): FormResult<Long> {
        form.gate.check()?.let { return FormResult.Blocked(it) }
        val fix = engine.view.value.lastClean
            ?.takeIf { DrivingStateRules.isFresh(it, clock.monoMs()) }
            ?: return FormResult.Invalid(FormError.NO_VALID_FIX)
        val spot = ParkingSpot(0, fix.point, fix.accuracyMeters, fix.utcMs, ParkingOrigin.MANUAL, null, false)
        return form.submit { parking.insert(spot) }
    }
}

/** CU-21. A blank note removes it. */
class SaveParkingNote(private val parking: ParkingRepository, private val form: FormSubmission) {
    suspend operator fun invoke(note: String): FormResult<Unit> {
        val spot = parking.current() ?: return FormResult.Invalid(FormError.NO_PARKING_SPOT)
        return form.submit { parking.updateNote(spot.id, note.trim().ifBlank { null }) }
    }
}

/** CU-24. A null argument leaves that setting as it is. */
class SaveSettings(
    private val settings: SettingsRepository,
    private val form: FormSubmission,
) {
    suspend operator fun invoke(
        currency: String? = null,
        openAtBoot: Boolean? = null,
        firstUseComplete: Boolean? = null,
    ): FormResult<Unit> {
        if (currency != null && currency.isBlank()) return FormResult.Invalid(FormError.CURRENCY_BLANK)
        return form.submit {
            settings.update {
                it.copy(
                    currency = currency?.trim() ?: it.currency,
                    openAtBoot = openAtBoot ?: it.openAtBoot,
                    firstUseComplete = firstUseComplete ?: it.firstUseComplete,
                )
            }
        }
    }

    /** Touching "Registro interrumpido". Not a form, so it is allowed at any time. False if the disk failed. */
    suspend fun dismissRecordingGap(): Boolean = try {
        settings.update { it.copy(recordingGapPending = false) }
        true
    } catch (_: StorageException) {
        false
    }
}
