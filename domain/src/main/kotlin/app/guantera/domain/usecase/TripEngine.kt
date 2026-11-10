package app.guantera.domain.usecase

import app.guantera.domain.motion.ManualConfirmation
import app.guantera.domain.parking.ParkingOrigin
import app.guantera.domain.parking.ParkingSpot
import app.guantera.domain.port.Clock
import app.guantera.domain.port.ParkingRepository
import app.guantera.domain.port.SettingsRepository
import app.guantera.domain.port.StorageException
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.port.TransactionRunner
import app.guantera.domain.port.TripRepository
import app.guantera.domain.time.FixTime
import app.guantera.domain.trip.ActiveTripRecord
import app.guantera.domain.trip.CleanFix
import app.guantera.domain.trip.Heartbeat
import app.guantera.domain.trip.LocationFix
import app.guantera.domain.trip.MachineState
import app.guantera.domain.trip.MachineStep
import app.guantera.domain.trip.ManualCloseDecision
import app.guantera.domain.trip.RecoveryDecision
import app.guantera.domain.trip.Trip
import app.guantera.domain.trip.TripClosure
import app.guantera.domain.trip.TripFinish
import app.guantera.domain.trip.TripMachine
import app.guantera.domain.trip.TripOutcome
import app.guantera.domain.trip.TripPhase
import app.guantera.domain.trip.TripRecovery
import app.guantera.domain.trip.TripThresholds
import app.guantera.domain.trip.activeTrip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the rest of the app can see of the engine. Changes on every point and tick. */
data class EngineView(
    val phase: TripPhase,
    val lastClean: CleanFix?,
    val nowMonoMs: Long,
    val storageFailure: StorageFailure?,
)

sealed interface RecoveryResult {
    data object NothingToRecover : RecoveryResult
    data object Resumed : RecoveryResult
    data class Closed(val finish: TripFinish) : RecoveryResult
    data class Failed(val failure: StorageFailure) : RecoveryResult
}

sealed interface ManualParkResult {
    /** [persisted] is false when the disk failed: the closure stays in memory and is retried on every tick. */
    data class Closed(val trip: Trip, val kept: Boolean, val persisted: Boolean) : ManualParkResult
    data object NotStopping : ManualParkResult
    data class TooEarly(val remainingMs: Long) : ManualParkResult
}

/**
 * Owns the single [MachineState], feeds it and persists what it decides. A failed write never stops
 * tracking: the machine goes on in memory, the failure shows in [view] and the write is retried.
 */
class TripEngine(
    private val trips: TripRepository,
    private val parking: ParkingRepository,
    private val settings: SettingsRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val confirmation: ManualConfirmation,
    private val backup: CreateAutomaticBackup,
) {
    private val mutex = Mutex()
    private var machine = MachineState()
    private var recovered = false
    private var lastHeartbeatMono: Long? = null
    private var lastFixTimeSavedMono: Long? = null
    private var storageFailure: StorageFailure? = null
    private val pendingFinishes = ArrayDeque<TripFinish>()

    private val mutableView = MutableStateFlow(EngineView(machine.phase, null, clock.monoMs(), null))
    val view: StateFlow<EngineView> = mutableView.asStateFlow()

    suspend fun onFix(fix: LocationFix) {
        val closed = mutex.withLock {
            ensureRecovered()
            val before = machine.lastClean
            val closedNow = apply(TripMachine.onPoint(machine, fix), fix.monoMs)
            if (machine.lastClean !== before) saveFixTime(machine.lastClean)
            closedNow
        }
        if (closed) backup.run()
    }

    suspend fun onTick() {
        val closed = mutex.withLock {
            ensureRecovered()
            val now = clock.monoMs()
            apply(TripMachine.onTick(machine, now), now)
        }
        if (closed) backup.run()
    }

    /** R4. Safe to call on every wake: a live machine keeps its fresher in-memory state. */
    suspend fun recover(): RecoveryResult {
        val result = mutex.withLock { recoverLocked() }
        if (result is RecoveryResult.Closed) backup.run()
        return result
    }

    /** R11. */
    suspend fun closeManually(): ManualParkResult {
        val outcome = mutex.withLock {
            ensureRecovered()
            val now = clock.monoMs()
            when (val decision = TripMachine.closeManually(machine, now)) {
                ManualCloseDecision.NotStopping -> ManualParkResult.NotStopping
                is ManualCloseDecision.TooEarly -> ManualParkResult.TooEarly(decision.remainingMs)
                is ManualCloseDecision.Accepted -> {
                    val finish = (decision.step.outcome as TripOutcome.Finished).finish
                    val persisted = apply(decision.step, now)
                    ManualParkResult.Closed(finish.trip, finish.kept, persisted)
                }
            }
        }
        if (outcome is ManualParkResult.Closed && outcome.persisted) backup.run()
        return outcome
    }

    private suspend fun ensureRecovered() {
        if (!recovered) recoverLocked()
    }

    private suspend fun recoverLocked(): RecoveryResult {
        val record = try {
            trips.activeTrip()
        } catch (e: StorageException) {
            return RecoveryResult.Failed(e.failure)
        }
        recovered = true
        record ?: return RecoveryResult.NothingToRecover
        return when (val decision = TripRecovery.decide(record, clock.monoMs(), clock.bootCount())) {
            is RecoveryDecision.Resume -> {
                if (machine.phase.activeTrip == null) {
                    machine = decision.state
                    lastHeartbeatMono = record.heartbeat.monoMs
                    publishView(clock.monoMs())
                }
                RecoveryResult.Resumed
            }
            is RecoveryDecision.Close -> {
                machine = machine.copy(phase = TripPhase.Parked)
                pendingFinishes.addLast(decision.finish)
                flushPendingFinishes()
                publishView(clock.monoMs())
                RecoveryResult.Closed(decision.finish)
            }
        }
    }

    /** Returns true when a closure reached the disk during this step. */
    private suspend fun apply(step: MachineStep, nowMono: Long): Boolean {
        machine = step.state
        if (machine.phase != TripPhase.Parked) confirmation.revoke() // R9: leaving PARKED ends it for good.
        val outcome = step.outcome
        if (outcome is TripOutcome.Finished) pendingFinishes.addLast(outcome.finish)
        val closed = flushPendingFinishes()
        saveHeartbeatIfDue(nowMono, force = outcome is TripOutcome.Started)
        publishView(nowMono)
        return closed
    }

    private suspend fun flushPendingFinishes(): Boolean {
        var closed = false
        while (pendingFinishes.isNotEmpty()) {
            if (!write { persist(pendingFinishes.first()) }) break
            pendingFinishes.removeFirst()
            closed = true
        }
        return closed
    }

    /** R5: the trip, the end of the active slot and the parking spot change together or not at all. */
    private suspend fun persist(finish: TripFinish) = transactions.run {
        if (finish.kept) trips.insertClosed(finish.trip)
        // A new trip may have started while this closure waited for a disk that was failing.
        if (machine.phase.activeTrip == null) trips.clearActive()
        parking.insert(spotOf(finish))
        if (finish.trip.closure == TripClosure.RECOVERED) {
            settings.update { it.copy(recordingGapPending = true) }
        }
    }

    private fun spotOf(finish: TripFinish) = ParkingSpot(
        id = 0,
        point = finish.lastValidFix.point,
        accuracyMeters = finish.lastValidFix.accuracyMeters,
        instantUtcMs = finish.trip.endUtcMs,
        origin = ParkingOrigin.TRIP_END,
        note = null,
        isLastSignalPoint = finish.isLastSignalPoint,
    )

    private suspend fun saveHeartbeatIfDue(nowMono: Long, force: Boolean) {
        val phase = machine.phase
        val trip = phase.activeTrip ?: return
        val last = lastHeartbeatMono
        if (!force && last != null && nowMono - last < TripThresholds.HEARTBEAT_MS) return
        // Derived from the last fix, so a wrong system clock cannot reach a recovered trip.
        val utc = trip.lastValidFix.utcMs + (nowMono - trip.lastValidFix.monoMs)
        val record = ActiveTripRecord(
            trip,
            (phase as? TripPhase.Stopping)?.stopCandidateMonoMs,
            Heartbeat(nowMono, utc, clock.bootCount()),
        )
        if (write { trips.saveActive(record) }) lastHeartbeatMono = nowMono
    }

    private suspend fun saveFixTime(fix: CleanFix?) {
        fix ?: return
        val last = lastFixTimeSavedMono
        if (last != null && fix.monoMs - last < FIX_TIME_SAVE_MS) return
        if (write { settings.saveLastFixTime(FixTime(fix.utcMs, fix.monoMs, clock.bootCount())) }) {
            lastFixTimeSavedMono = fix.monoMs
        }
        publishView(fix.monoMs)
    }

    private suspend fun write(block: suspend () -> Unit): Boolean = try {
        block()
        storageFailure = null
        true
    } catch (e: StorageException) {
        storageFailure = e.failure
        false
    }

    private fun publishView(nowMono: Long) {
        mutableView.value = EngineView(machine.phase, machine.lastClean, nowMono, storageFailure)
    }

    private companion object {
        /** Parked radios must not write to flash every 10 s just to remember the time. */
        const val FIX_TIME_SAVE_MS = 60_000L
    }
}
