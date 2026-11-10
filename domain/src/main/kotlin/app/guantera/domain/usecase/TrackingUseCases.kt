package app.guantera.domain.usecase

import app.guantera.domain.motion.DrivingState
import app.guantera.domain.motion.DrivingStateRules
import app.guantera.domain.motion.GpsStatus
import app.guantera.domain.motion.ManualConfirmation
import app.guantera.domain.port.Clock
import app.guantera.domain.port.SettingsRepository
import app.guantera.domain.port.TrackingHealthSource
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.time.CalendarDay
import app.guantera.domain.time.DayRange
import app.guantera.domain.time.TrustedTime
import app.guantera.domain.time.TrustedTimeRules
import app.guantera.domain.trip.LocationFix
import app.guantera.domain.trip.TripPhase
import app.guantera.domain.trip.activeTrip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** CU-01. */
class ProcessLocationFix(private val engine: TripEngine) {
    suspend operator fun invoke(fix: LocationFix) = engine.onFix(fix)
}

/** CU-01. The service calls it once per second, with or without fixes. */
class ProcessTick(private val engine: TripEngine) {
    suspend operator fun invoke() = engine.onTick()
}

/** CU-02. */
class RecoverTripAfterWake(private val engine: TripEngine) {
    suspend operator fun invoke(): RecoveryResult = engine.recover()
}

data class TripProgress(val distanceMeters: Double, val elapsedSeconds: Long, val stoppingSeconds: Long?)

data class DrivingSnapshot(val state: DrivingState, val progress: TripProgress?, val gps: GpsStatus)

/** CU-03 and the status half of CU-22. */
class ObserveDrivingState(
    private val engine: TripEngine,
    private val healthSource: TrackingHealthSource,
    private val clock: Clock,
) {
    fun current(): DrivingState {
        val view = engine.view.value
        return DrivingStateRules.evaluate(view.phase, view.lastClean, clock.monoMs(), healthSource.health.value)
    }

    fun gpsStatus(): GpsStatus {
        val view = engine.view.value
        return DrivingStateRules.gpsStatus(healthSource.health.value, view.lastClean, clock.monoMs())
    }

    fun observe(): Flow<DrivingSnapshot> = combine(engine.view, healthSource.health) { view, health ->
        val now = clock.monoMs()
        DrivingSnapshot(
            state = DrivingStateRules.evaluate(view.phase, view.lastClean, now, health),
            progress = progressOf(view.phase, now),
            gps = DrivingStateRules.gpsStatus(health, view.lastClean, now),
        )
    }

    private fun progressOf(phase: TripPhase, now: Long): TripProgress? = when (phase) {
        TripPhase.Parked -> null
        is TripPhase.Starting -> TripProgress(0.0, seconds(now - phase.anchor.monoMs), null)
        is TripPhase.InTrip, is TripPhase.Stopping -> {
            val trip = phase.activeTrip!!
            val stopping = (phase as? TripPhase.Stopping)?.let { seconds(now - it.stopCandidateMonoMs) }
            TripProgress(trip.distanceMeters, seconds(now - trip.startMonoMs), stopping)
        }
    }

    private fun seconds(ms: Long) = ms.coerceAtLeast(0) / 1000
}

enum class ConfirmParkedResult { CONFIRMED, NOT_APPLICABLE }

/** CU-04, R9. Not to be confused with "Ya estacioné", which is CU-20. */
class ConfirmParked(
    private val driving: ObserveDrivingState,
    private val confirmation: ManualConfirmation,
    private val clock: Clock,
) {
    operator fun invoke(): ConfirmParkedResult {
        if (driving.current() != DrivingState.DESCONOCIDO) return ConfirmParkedResult.NOT_APPLICABLE
        confirmation.grant(clock.monoMs())
        return ConfirmParkedResult.CONFIRMED
    }
}

/** CU-22. */
class CheckTrackingHealth(private val driving: ObserveDrivingState) {
    operator fun invoke(): GpsStatus = driving.gpsStatus()
}

/** Turns instants into local calendar days and back, for "today" and every range. */
class LocalCalendar(private val clock: Clock) {
    fun dayOf(utcMs: Long): CalendarDay =
        CalendarDay.fromEpochDay(Math.floorDiv(utcMs + clock.utcOffsetMs(utcMs), DAY_MS).toInt())

    fun window(range: DayRange) = UtcWindow(range.from?.let(::startOf), range.toExclusive?.let(::startOf))

    private fun startOf(day: CalendarDay): Long {
        val utcMidnight = day.epochDay * DAY_MS
        // Twice, because the offset that applies depends on the instant being computed.
        val guess = utcMidnight - clock.utcOffsetMs(utcMidnight)
        return utcMidnight - clock.utcOffsetMs(guess)
    }

    private companion object { const val DAY_MS = 86_400_000L }
}

/** CU-23. */
class ResolveTrustedTime(
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val calendar: LocalCalendar,
) {
    suspend operator fun invoke(): TrustedTime =
        TrustedTimeRules.resolve(clock.utcMs(), clock.monoMs(), clock.bootCount(), settings.lastFixTime())

    /** The instant to stamp data with. Without any reference the system clock is all there is. */
    suspend fun bestEffortUtcMs(): Long = when (val time = invoke()) {
        is TrustedTime.Reliable -> time.utcMs
        is TrustedTime.Estimated -> time.utcMs
        TrustedTime.Unavailable -> clock.utcMs()
    }

    /** Null when no date can be trusted (R29). */
    suspend fun today(): CalendarDay? = when (val time = invoke()) {
        is TrustedTime.Reliable -> calendar.dayOf(time.utcMs)
        is TrustedTime.Estimated -> calendar.dayOf(time.utcMs)
        TrustedTime.Unavailable -> null
    }
}
