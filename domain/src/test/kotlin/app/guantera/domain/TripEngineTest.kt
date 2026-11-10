package app.guantera.domain

import app.guantera.domain.motion.DrivingState
import app.guantera.domain.parking.ParkingOrigin
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.trip.LocationFix
import app.guantera.domain.trip.TripClosure
import app.guantera.domain.trip.TripPhase
import app.guantera.domain.usecase.ManualParkResult
import app.guantera.domain.usecase.RecoveryResult
import app.guantera.domain.usecase.TripEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {
    private val app = TestApp()
    private val db = app.db
    private val clock = app.clock
    private val engine = app.engine

    private suspend fun TripEngine.at(fix: LocationFix) {
        clock.mono = fix.monoMs
        onFix(fix)
    }

    private suspend fun driveTo320m(target: TripEngine = engine) {
        target.at(fix(0.0, 1.0))
        target.at(fix(20.0, 3.0))
        target.at(fix(320.0, 33.0))
    }

    private suspend fun tickAt(ms: Long, target: TripEngine = engine) {
        clock.mono = ms
        target.onTick()
    }

    @Test fun startingATripSavesTheActiveSlotAtOnce() = runTest {
        engine.at(fix(0.0, 1.0))
        assertNull(db.active)
        engine.at(fix(20.0, 3.0))
        assertNotNull(db.active)
        assertEquals(1, db.active!!.heartbeat.bootCount)
    }

    @Test fun r2_thereIsOnlyEverOneActiveSlot() = runTest {
        driveTo320m()
        val first = db.active!!.trip.startMonoMs
        engine.at(fix(420.0, 43.0))
        assertEquals(first, db.active!!.trip.startMonoMs)
        assertTrue(engine.view.value.phase is TripPhase.InTrip)
    }

    @Test fun heartbeatEveryTenSecondsExactly() = runTest {
        engine.at(fix(0.0, 1.0))
        engine.at(fix(20.0, 3.0))
        assertEquals(1, db.heartbeatWrites)
        tickAt(12_999)
        assertEquals(1, db.heartbeatWrites)
        tickAt(13_000)
        assertEquals(2, db.heartbeatWrites)
    }

    @Test fun heartbeatTimeComesFromTheFixNotTheSystemClock() = runTest {
        engine.at(fix(0.0, 1.0))
        clock.utc = 5L
        engine.at(fix(20.0, 3.0))
        assertEquals(BASE_UTC + 3_000, db.active!!.heartbeat.utcMs)
    }

    @Test fun heartbeatKeepsTheStopCandidate() = runTest {
        driveTo320m()
        engine.at(fix(320.0, 34.0, speed = 0.0))
        tickAt(44_000)
        assertEquals(34_000L, db.active!!.stopCandidateMonoMs)
    }

    @Test fun closingWritesTripSpotAndClearsTheSlotTogether() = runTest {
        driveTo320m()
        engine.at(fix(320.0, 34.0, speed = 0.0))
        tickAt(34_000 + 180_000)
        assertEquals(1, db.trips.size)
        assertEquals(TripClosure.AUTOMATIC, db.trips[0].closure)
        assertNull(db.active)
        assertEquals(ParkingOrigin.TRIP_END, db.spots.single().origin)
        assertEquals(1, app.store.saves)
    }

    @Test fun discardedTripStillMovesTheParkingSpot() = runTest {
        engine.at(fix(0.0, 1.0, speed = 2.0))
        engine.at(fix(15.0, 9.0, speed = 2.0))
        engine.at(fix(15.0, 10.0, speed = 0.0))
        tickAt(10_000 + 180_000)
        assertTrue(db.trips.isEmpty())
        assertNull(db.active)
        assertEquals(1, db.spots.size)
    }

    @Test fun failedCloseStaysInMemoryAndIsRetriedOnce() = runTest {
        driveTo320m()
        engine.at(fix(320.0, 34.0, speed = 0.0))
        db.failure = StorageFailure.FULL
        tickAt(34_000 + 180_000)
        assertEquals(TripPhase.Parked, engine.view.value.phase)
        assertEquals(StorageFailure.FULL, engine.view.value.storageFailure)
        assertTrue(db.trips.isEmpty())
        db.failure = null
        tickAt(34_000 + 181_000)
        tickAt(34_000 + 182_000)
        assertEquals(1, db.trips.size)
        assertEquals(1, db.spots.size)
        assertNull(engine.view.value.storageFailure)
    }

    @Test fun lateCloseDoesNotWipeTheNextTrip() = runTest {
        driveTo320m()
        engine.at(fix(320.0, 34.0, speed = 0.0))
        db.failure = StorageFailure.IO
        tickAt(34_000 + 180_000)
        db.failure = null
        engine.at(fix(320.0, 215.0))
        db.failure = StorageFailure.IO
        engine.at(fix(340.0, 217.0))
        db.failure = null
        tickAt(218_000)
        assertEquals(1, db.trips.size)
        assertNotNull(db.active)
    }

    @Test fun powerCutMidTrip_newProcessClosesAsRecoveredAfterStopTimeout() = runTest {
        driveTo320m()
        tickAt(33_000 + 10_000)
        val savedBeat = db.active!!.heartbeat
        val reborn = app.newEngine()
        clock.mono = savedBeat.monoMs + 180_001
        val result = reborn.recover()
        assertTrue(result is RecoveryResult.Closed)
        assertEquals(TripClosure.RECOVERED, db.trips.single().closure)
        assertEquals(savedBeat.utcMs, db.trips.single().endUtcMs)
        assertNull(db.active)
        assertTrue(db.spots.single().isLastSignalPoint)
        assertTrue(db.settings.recordingGapPending)
    }

    @Test fun appKilledBriefly_resumesAndKeepsCounting() = runTest {
        driveTo320m()
        val reborn = app.newEngine()
        clock.mono = 40_000
        assertEquals(RecoveryResult.Resumed, reborn.recover())
        assertTrue(reborn.view.value.phase is TripPhase.InTrip)
        reborn.at(fix(420.0, 45.0))
        tickAt(45_001, reborn)
        assertEquals(420.0, (reborn.view.value.phase as TripPhase.InTrip).trip.distanceMeters, 1.0)
    }

    @Test fun rebootClosesEvenWithSmallMonotonicValues() = runTest {
        driveTo320m()
        val reborn = app.newEngine()
        clock.boot = 2
        clock.mono = 500
        assertTrue(reborn.recover() is RecoveryResult.Closed)
        assertEquals(TripClosure.RECOVERED, db.trips.single().closure)
    }

    @Test fun firstFixRunsRecoveryOnItsOwn() = runTest {
        driveTo320m()
        val reborn = app.newEngine()
        clock.boot = 2
        reborn.at(fix(0.0, 1.0, speed = 0.0))
        assertEquals(1, db.trips.size)
        assertNull(db.active)
    }

    @Test fun recoverWithNothingActive() = runTest {
        assertEquals(RecoveryResult.NothingToRecover, engine.recover())
    }

    @Test fun recoverFailsTypedWhenRecoveryWriteFails() = runTest {
        driveTo320m()
        val reborn = app.newEngine()
        clock.boot = 2
        db.failure = StorageFailure.IO
        assertTrue(reborn.recover() is RecoveryResult.Closed)
        assertEquals(StorageFailure.IO, reborn.view.value.storageFailure)
        assertNotNull(db.active)
    }

    @Test fun manualCloseThroughEngine() = runTest {
        driveTo320m()
        assertEquals(ManualParkResult.NotStopping, engine.closeManually())
        engine.at(fix(320.0, 34.0, speed = 0.0))
        clock.mono = 34_000 + 20_000
        val result = engine.closeManually() as ManualParkResult.Closed
        assertEquals(TripClosure.MANUAL, result.trip.closure)
        assertTrue(result.persisted)
        assertEquals(1, db.spots.size)
    }

    @Test fun lastFixTimeIsThrottledAndOnlyFromFixes() = runTest {
        engine.at(fix(0.0, 1.0, speed = 0.0))
        assertEquals(BASE_UTC + 1_000, db.lastFix!!.utcMs)
        engine.at(fix(0.0, 60.0, speed = 0.0))
        assertEquals(BASE_UTC + 1_000, db.lastFix!!.utcMs)
        engine.at(fix(0.0, 61.0, speed = 0.0))
        assertEquals(BASE_UTC + 61_000, db.lastFix!!.utcMs)
    }

    @Test fun leavingParkedRevokesTheManualConfirmationForGood() = runTest {
        clock.mono = 1_000
        app.confirmation.grant(1_000)
        engine.at(fix(0.0, 2.0))
        assertFalse(app.confirmation.isValid(3_000))
        tickAt(2_000 + 15_001)
        assertEquals(TripPhase.Parked, engine.view.value.phase)
        assertFalse(app.confirmation.isValid(18_000))
    }

    @Test fun drivingStateFollowsTheEngine() = runTest {
        assertEquals(DrivingState.DESCONOCIDO, app.driving.current())
        engine.at(fix(0.0, 1.0, speed = 0.0))
        assertEquals(DrivingState.DETENIDO, app.driving.current())
        engine.at(fix(0.0, 2.0))
        assertEquals(DrivingState.EN_MOVIMIENTO, app.driving.current())
    }
}
