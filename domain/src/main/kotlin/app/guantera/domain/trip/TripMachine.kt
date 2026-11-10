package app.guantera.domain.trip

import app.guantera.domain.trip.TripThresholds.FIX_FRESH_MS
import app.guantera.domain.trip.TripThresholds.MANUAL_CLOSE_MIN_STOPPED_MS
import app.guantera.domain.trip.TripThresholds.MAX_SPEED_PLAUSIBLE
import app.guantera.domain.trip.TripThresholds.MIN_STEP_DIST
import app.guantera.domain.trip.TripThresholds.START_MIN_DIST
import app.guantera.domain.trip.TripThresholds.START_TIMEOUT_MS
import app.guantera.domain.trip.TripThresholds.STOP_TIMEOUT_MS
import app.guantera.domain.trip.TripThresholds.TUNNEL_TIMEOUT_MS
import app.guantera.domain.trip.TripThresholds.V_MIN_MOVE
import app.guantera.domain.trip.TripThresholds.V_STOP

sealed interface TripPhase {
    data object Parked : TripPhase
    data class Starting(val anchor: CleanFix) : TripPhase
    data class InTrip(val trip: ActiveTrip) : TripPhase
    data class Stopping(val trip: ActiveTrip, val stopCandidateMonoMs: Long) : TripPhase
}

val TripPhase.activeTrip: ActiveTrip?
    get() = when (this) {
        is TripPhase.InTrip -> trip
        is TripPhase.Stopping -> trip
        else -> null
    }

/** [lastClean] feeds the sanitizer and the driving state, independently of the phase. */
data class MachineState(val phase: TripPhase = TripPhase.Parked, val lastClean: CleanFix? = null)

/** How a trip ended. [kept] is false for a garage maneuver, but the parking spot is saved anyway (R5). */
data class TripFinish(
    val trip: Trip,
    val kept: Boolean,
    val isLastSignalPoint: Boolean,
    val lastValidFix: CleanFix,
)

sealed interface TripOutcome {
    data class Started(val trip: ActiveTrip) : TripOutcome
    data class Finished(val finish: TripFinish) : TripOutcome
}

data class MachineStep(val state: MachineState, val outcome: TripOutcome? = null)

sealed interface ManualCloseDecision {
    data class Accepted(val step: MachineStep) : ManualCloseDecision
    data object NotStopping : ManualCloseDecision
    data class TooEarly(val remainingMs: Long) : ManualCloseDecision
}

/** The pure state machine of deteccion-viajes.md, section 3 (R1, R11). */
object TripMachine {
    private const val MILLIS_PER_SECOND = 1000.0

    fun onPoint(state: MachineState, fix: LocationFix): MachineStep {
        val clean = FixSanitizer.sanitize(fix, state.lastClean) ?: return MachineStep(state)
        // A fix proves time has passed, so a timeout that is due fires even if no tick arrived in between.
        val timed = onTick(state, clean.monoMs)
        val advance = advance(timed.state.phase, clean) ?: return timed
        return MachineStep(MachineState(advance.first, clean), timed.outcome ?: advance.second)
    }

    fun onTick(state: MachineState, nowMonoMs: Long): MachineStep = when (val phase = state.phase) {
        TripPhase.Parked -> MachineStep(state)
        is TripPhase.Starting ->
            if (nowMonoMs - phase.anchor.monoMs > START_TIMEOUT_MS) {
                MachineStep(state.copy(phase = TripPhase.Parked))
            } else {
                MachineStep(state)
            }
        is TripPhase.InTrip ->
            if (nowMonoMs - phase.trip.lastValidFix.monoMs > TUNNEL_TIMEOUT_MS) {
                val last = phase.trip.lastValidFix
                finish(state, phase.trip, last.monoMs, TripClosure.AUTOMATIC, isLastSignalPoint = true)
            } else {
                MachineStep(state)
            }
        is TripPhase.Stopping ->
            if (nowMonoMs - phase.stopCandidateMonoMs >= STOP_TIMEOUT_MS) {
                finish(state, phase.trip, phase.stopCandidateMonoMs, TripClosure.AUTOMATIC, signalLost(state, nowMonoMs))
            } else {
                MachineStep(state)
            }
    }

    /** R11: "Ya estacioné". */
    fun closeManually(state: MachineState, nowMonoMs: Long): ManualCloseDecision {
        val phase = state.phase as? TripPhase.Stopping ?: return ManualCloseDecision.NotStopping
        val stoppedMs = nowMonoMs - phase.stopCandidateMonoMs
        if (stoppedMs < MANUAL_CLOSE_MIN_STOPPED_MS) {
            return ManualCloseDecision.TooEarly(MANUAL_CLOSE_MIN_STOPPED_MS - stoppedMs)
        }
        val step = finish(state, phase.trip, phase.stopCandidateMonoMs, TripClosure.MANUAL, signalLost(state, nowMonoMs))
        return ManualCloseDecision.Accepted(step)
    }

    /** Applies the closing rules, shared with the recovery after a power cut (R3, R4). */
    fun finishTrip(
        trip: ActiveTrip,
        endUtcMs: Long,
        endMonoMs: Long,
        closure: TripClosure,
        isLastSignalPoint: Boolean,
    ): TripFinish {
        val durationMs = (endMonoMs - trip.startMonoMs).coerceAtLeast(0)
        val kept = trip.distanceMeters >= TripThresholds.TRIP_MIN_DIST ||
            durationMs / 1000 >= TripThresholds.TRIP_MIN_DURATION_S
        val closed = Trip(
            id = 0,
            startUtcMs = trip.startUtcMs,
            // If UTC went backwards, the monotonic duration keeps R3 true.
            endUtcMs = if (endUtcMs < trip.startUtcMs) trip.startUtcMs + durationMs else endUtcMs,
            distanceMeters = trip.distanceMeters,
            startPoint = trip.startPoint,
            endPoint = trip.lastValidFix.point,
            closure = closure,
        )
        return TripFinish(closed, kept, isLastSignalPoint, trip.lastValidFix)
    }

    /** Null means the fix is an implausible jump and must not move the machine. */
    private fun advance(phase: TripPhase, clean: CleanFix): Pair<TripPhase, TripOutcome?>? = when (phase) {
        TripPhase.Parked ->
            (if (clean.speedMps >= V_MIN_MOVE) TripPhase.Starting(clean) else phase) to null
        is TripPhase.Starting -> advanceStarting(phase, clean)
        is TripPhase.InTrip -> when {
            !isPlausible(phase.trip.lastDistanceFix, clean) -> null
            clean.speedMps < V_STOP -> TripPhase.Stopping(phase.trip, clean.monoMs) to null
            else -> TripPhase.InTrip(accumulate(phase.trip, clean)) to null
        }
        is TripPhase.Stopping -> when {
            clean.speedMps < V_MIN_MOVE -> phase to null
            !isPlausible(phase.trip.lastDistanceFix, clean) -> null
            else -> TripPhase.InTrip(accumulate(phase.trip, clean)) to null
        }
    }

    private fun advanceStarting(phase: TripPhase.Starting, clean: CleanFix): Pair<TripPhase, TripOutcome?>? {
        val anchor = phase.anchor
        if (!isPlausible(anchor, clean)) return null
        val moved = Geo.distanceMeters(anchor.point, clean.point) >= START_MIN_DIST
        if (!moved || clean.speedMps < V_MIN_MOVE) return phase to null
        val opened = ActiveTrip(anchor.utcMs, anchor.monoMs, anchor.point, 0.0, anchor, clean)
        val trip = accumulate(opened, clean)
        return TripPhase.InTrip(trip) to TripOutcome.Started(trip)
    }

    /** The anchor only advances when at least 2 m were added, so walking pace loses nothing. */
    private fun accumulate(trip: ActiveTrip, clean: CleanFix): ActiveTrip {
        val step = Geo.distanceMeters(trip.lastDistanceFix.point, clean.point)
        return if (step >= MIN_STEP_DIST) {
            trip.copy(distanceMeters = trip.distanceMeters + step, lastDistanceFix = clean, lastValidFix = clean)
        } else {
            trip.copy(lastValidFix = clean)
        }
    }

    private fun isPlausible(from: CleanFix, to: CleanFix): Boolean {
        val seconds = (to.monoMs - from.monoMs) / MILLIS_PER_SECOND
        return seconds > 0 && Geo.distanceMeters(from.point, to.point) / seconds <= MAX_SPEED_PLAUSIBLE
    }

    private fun signalLost(state: MachineState, nowMonoMs: Long): Boolean {
        val last = state.lastClean ?: return true
        return nowMonoMs - last.monoMs > FIX_FRESH_MS
    }

    private fun finish(
        state: MachineState,
        trip: ActiveTrip,
        endMonoMs: Long,
        closure: TripClosure,
        isLastSignalPoint: Boolean,
    ): MachineStep {
        val finish = finishTrip(trip, trip.lastValidFix.utcMs, endMonoMs, closure, isLastSignalPoint)
        return MachineStep(state.copy(phase = TripPhase.Parked), TripOutcome.Finished(finish))
    }
}

sealed interface RecoveryDecision {
    /** The cut was short enough: the machine continues from what was saved. */
    data class Resume(val state: MachineState) : RecoveryDecision
    data class Close(val finish: TripFinish) : RecoveryDecision
}

/** R4 and the heartbeat rules of deteccion-viajes.md, section 4. */
object TripRecovery {
    fun decide(record: ActiveTripRecord, nowMonoMs: Long, bootCount: Int?): RecoveryDecision {
        val beat = record.heartbeat
        val sameBoot = bootCount != null && beat.bootCount == bootCount
        if (!sameBoot || nowMonoMs - beat.monoMs > STOP_TIMEOUT_MS) return close(record)
        val phase = record.stopCandidateMonoMs
            ?.let { TripPhase.Stopping(record.trip, it) }
            ?: TripPhase.InTrip(record.trip)
        return RecoveryDecision.Resume(MachineState(phase, record.trip.lastValidFix))
    }

    private fun close(record: ActiveTripRecord): RecoveryDecision.Close = RecoveryDecision.Close(
        TripMachine.finishTrip(
            record.trip,
            record.heartbeat.utcMs,
            record.heartbeat.monoMs,
            TripClosure.RECOVERED,
            isLastSignalPoint = true,
        ),
    )
}
