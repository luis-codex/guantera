package app.guantera.domain

import app.guantera.domain.trip.ActiveTrip
import app.guantera.domain.trip.ActiveTripRecord
import app.guantera.domain.trip.CleanFix
import app.guantera.domain.trip.FixSanitizer
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
import app.guantera.domain.trip.activeTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun MachineState.feed(vararg fixes: LocationFix): MachineState =
    fixes.fold(this) { state, fix -> TripMachine.onPoint(state, fix).state }

private fun MachineState.tick(ms: Long): MachineStep = TripMachine.onTick(this, ms)

private val MachineStep.finish: TripFinish get() = (outcome as TripOutcome.Finished).finish
private val MachineStep.closed: Trip get() = finish.trip
private val MachineState.distance: Double get() = phase.activeTrip!!.distanceMeters

/** IN_TRIP since t=1 s, 20 m from the start, 20 m accumulated. */
private fun inTrip() = MachineState().feed(fix(0.0, 1.0), fix(20.0, 3.0))

/** IN_TRIP having travelled 320 m, last fix at t=33 s. */
private fun longTrip() = inTrip().feed(fix(320.0, 33.0, speed = 10.0))

class TripMachineDocumentedCasesTest {
    @Test fun case1_stoppedWithoutFixes_closesAt180sWithLastFixTime() {
        val stopping = longTrip().feed(fix(320.0, 34.0, speed = 0.0))
        assertTrue(stopping.phase is TripPhase.Stopping)
        val step = stopping.tick(34_000 + 180_000)
        assertEquals(TripPhase.Parked, step.state.phase)
        assertEquals(BASE_UTC + 33_000, step.closed.endUtcMs)
        assertTrue(step.finish.kept)
    }

    @Test fun case2_tunnel_closesAfter301sKeepingDistance() {
        var state = inTrip()
        var north = 20.0
        var seconds = 3.0
        repeat(10) {
            north += 1000
            seconds += 40
            state = state.feed(fix(north, seconds, speed = 25.0))
        }
        val before = state.distance
        val step = state.tick(((seconds + 301) * 1000).toLong())
        assertEquals(TripPhase.Parked, step.state.phase)
        assertEquals(before, step.closed.distanceMeters, 0.001)
        assertEquals(10_020.0, step.closed.distanceMeters, 1.0)
        assertEquals(BASE_UTC + (seconds * 1000).toLong(), step.closed.endUtcMs)
        assertEquals(TripClosure.AUTOMATIC, step.closed.closure)
        assertTrue(step.finish.isLastSignalPoint)
    }

    @Test fun case3_trafficLight_continuesSameTrip() {
        val stopped = longTrip().feed(fix(320.0, 34.0, speed = 0.0), fix(320.0, 94.0, speed = 0.0))
        assertTrue(stopped.phase is TripPhase.Stopping)
        val resumed = TripMachine.onPoint(stopped, fix(340.0, 96.0, speed = 8.33))
        assertTrue(resumed.state.phase is TripPhase.InTrip)
        assertNull(resumed.outcome)
        assertEquals(1_000L, resumed.state.phase.activeTrip!!.startMonoMs)
        assertEquals(340.0, resumed.state.distance, 0.5)
    }

    @Test fun case4_garageManeuver_isDiscardedButKeepsParkingPoint() {
        val moved = MachineState().feed(fix(0.0, 1.0, speed = 1.5), fix(11.0, 9.0, speed = 1.5), fix(35.0, 41.0, speed = 1.5))
        val stopped = moved.feed(fix(35.0, 42.0, speed = 0.0))
        val step = stopped.tick(42_000 + 180_000)
        assertFalse(step.finish.kept)
        assertEquals(TripPhase.Parked, step.state.phase)
        assertEquals(35.0, step.finish.lastValidFix.point.latitude * 111_194.9266, 0.01)
    }

    @Test fun case5_walkingPace_accumulatesAgainstFixedAnchor() {
        val base = inTrip()
        val start = base.distance
        var state = base
        val sums = (1..8).map { index ->
            state = state.feed(fix(20.0 + 0.8 * index, 3.0 + index, speed = 1.0))
            state.distance - start
        }
        assertEquals(0.0, sums[1], 0.001)
        assertEquals(2.4, sums[2], 0.001)
        assertEquals(2.4, sums[4], 0.001)
        assertEquals(4.8, sums[5], 0.001)
        assertEquals(4.8, sums[7], 0.001)
    }

    @Test fun case6_spuriousFixes_doNotMoveTheMachine() {
        val states = listOf(MachineState(), MachineState().feed(fix(0.0, 1.0)), inTrip(), inTrip().feed(fix(20.0, 4.0, speed = 0.0)))
        for (state in states) {
            assertEquals(state, TripMachine.onPoint(state, fix(500.0, 90.0, accuracy = null)).state)
            assertEquals(state, TripMachine.onPoint(state, fix(500.0, 90.0, accuracy = 45.0)).state)
        }
    }

    @Test fun case6_jumpOf300mIn1s_isRejected() {
        val state = inTrip()
        val after = TripMachine.onPoint(state, fix(320.0, 4.0, speed = 20.0)).state
        assertEquals(state.distance, after.distance, 0.0)
        assertEquals(state.phase.activeTrip!!.lastValidFix, after.phase.activeTrip!!.lastValidFix)
    }
}

class TripMachineLimitsTest {
    @Test fun startsAtExactlyVMinMove() {
        assertTrue(MachineState().feed(fix(0.0, 1.0, speed = 1.4)).phase is TripPhase.Starting)
        assertEquals(TripPhase.Parked, MachineState().feed(fix(0.0, 1.0, speed = 1.39)).phase)
    }

    @Test fun startingNeedsTenMeters() {
        val starting = MachineState().feed(fix(0.0, 1.0))
        assertTrue(starting.feed(fix(9.99, 3.0)).phase is TripPhase.Starting)
        assertTrue(starting.feed(fix(10.01, 3.0)).phase is TripPhase.InTrip)
    }

    @Test fun startingNeedsSpeedToo() {
        val starting = MachineState().feed(fix(0.0, 1.0))
        assertTrue(starting.feed(fix(30.0, 3.0, speed = 1.39)).phase is TripPhase.Starting)
    }

    @Test fun startingTimesOutOnlyAfter15s() {
        val starting = MachineState().feed(fix(0.0, 1.0))
        assertTrue(starting.tick(1_000 + 15_000).state.phase is TripPhase.Starting)
        assertEquals(TripPhase.Parked, starting.tick(1_000 + 15_001).state.phase)
    }

    @Test fun tripStartsAtTheAnchorAndEmitsStarted() {
        val starting = MachineState().feed(fix(0.0, 1.0))
        val step = TripMachine.onPoint(starting, fix(20.0, 3.0))
        val started = (step.outcome as TripOutcome.Started).trip
        assertEquals(BASE_UTC + 1_000, started.startUtcMs)
        assertEquals(20.0, started.distanceMeters, 0.01)
    }

    @Test fun stopsBelowVStopButNotAtIt() {
        assertTrue(inTrip().feed(fix(25.0, 4.0, speed = 0.8)).phase is TripPhase.InTrip)
        assertTrue(inTrip().feed(fix(25.0, 4.0, speed = 0.79)).phase is TripPhase.Stopping)
    }

    @Test fun resumesAtVMinMoveHysteresis() {
        val stopping = inTrip().feed(fix(20.0, 4.0, speed = 0.0))
        assertTrue(stopping.feed(fix(25.0, 5.0, speed = 1.39)).phase is TripPhase.Stopping)
        assertTrue(stopping.feed(fix(25.0, 5.0, speed = 1.4)).phase is TripPhase.InTrip)
    }

    @Test fun stopTimeoutFiresAtExactly180s() {
        val stopping = longTrip().feed(fix(320.0, 34.0, speed = 0.0))
        assertTrue(stopping.tick(34_000 + 179_999).state.phase is TripPhase.Stopping)
        assertEquals(TripPhase.Parked, stopping.tick(34_000 + 180_000).state.phase)
    }

    @Test fun tunnelTimeoutFiresOnlyAfter300s() {
        val trip = longTrip()
        assertTrue(trip.tick(33_000 + 300_000).state.phase is TripPhase.InTrip)
        assertEquals(TripPhase.Parked, trip.tick(33_000 + 300_001).state.phase)
    }

    @Test fun fixAfterADueTimeoutClosesTheTripEvenWithoutATick() {
        val step = TripMachine.onPoint(longTrip(), fix(9_000.0, 33.0 + 301, speed = 20.0))
        assertTrue(step.outcome is TripOutcome.Finished)
        assertTrue(step.state.phase is TripPhase.Starting)
    }

    @Test fun tripKeptAtExactly200m() {
        assertFalse(finishOf(199.999, durationMs = 40_000).kept)
        assertTrue(finishOf(200.0, durationMs = 40_000).kept)
    }

    @Test fun tripKeptAtExactly120sCountingWholeSeconds() {
        assertFalse(finishOf(30.0, durationMs = 119_999).kept)
        assertTrue(finishOf(30.0, durationMs = 120_000).kept)
    }

    private fun finishOf(meters: Double, durationMs: Long): TripFinish {
        val trip = inTrip().phase.activeTrip!!.copy(distanceMeters = meters)
        return TripMachine.finishTrip(trip, trip.lastValidFix.utcMs, trip.startMonoMs + durationMs, TripClosure.AUTOMATIC, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun r3_aClosedTripCannotEndBeforeItStarts() {
        Trip(1, 100, 99, 0.0, fix(0.0, 1.0).point, fix(0.0, 1.0).point, TripClosure.AUTOMATIC)
    }

    @Test(expected = IllegalArgumentException::class)
    fun r3_aClosedTripCannotHaveNegativeDistance() {
        Trip(1, 100, 100, -1.0, fix(0.0, 1.0).point, fix(0.0, 1.0).point, TripClosure.AUTOMATIC)
    }

    @Test fun plausibleSpeedLimit() {
        val trip = inTrip()
        val fast = trip.feed(fix(20.0 + 499.0, 13.0, speed = 49.0))
        assertEquals(519.0, fast.distance, 0.5)
        val jump = trip.feed(fix(20.0 + 501.0, 13.0, speed = 50.0))
        assertEquals(20.0, jump.distance, 0.5)
    }

    @Test fun anchorDoesNotAdvanceAfterAJump_soTheRealDistanceIsCountedLater() {
        val after = inTrip().feed(fix(2_000.0, 4.0, speed = 20.0), fix(120.0, 13.0, speed = 10.0))
        assertEquals(120.0, after.distance, 0.5)
    }

    @Test fun utcGoingBackwards_usesMonotonicDuration() {
        val trip = MachineState()
            .feed(fix(0.0, 1.0, utc = BASE_UTC), fix(20.0, 3.0, utc = BASE_UTC + 2_000), fix(320.0, 33.0, utc = BASE_UTC - 500_000))
            .feed(fix(320.0, 34.0, speed = 0.0))
        val step = trip.tick(34_000 + 180_000)
        assertEquals(BASE_UTC + 33_000, step.closed.endUtcMs)
    }

    @Test fun manualClose_needsStoppingAnd20Seconds() {
        assertEquals(ManualCloseDecision.NotStopping, TripMachine.closeManually(inTrip(), 10_000))
        val stopping = longTrip().feed(fix(320.0, 34.0, speed = 0.0))
        assertEquals(ManualCloseDecision.TooEarly(1), TripMachine.closeManually(stopping, 34_000 + 19_999))
        val accepted = TripMachine.closeManually(stopping, 34_000 + 20_000) as ManualCloseDecision.Accepted
        assertEquals(TripClosure.MANUAL, accepted.step.closed.closure)
        assertEquals(TripPhase.Parked, accepted.step.state.phase)
    }

    @Test fun stopTimeoutWithoutRecentFixMarksLastSignalPoint() {
        val stopping = longTrip().feed(fix(320.0, 34.0, speed = 0.0))
        assertTrue(stopping.tick(34_000 + 180_000).finish.isLastSignalPoint)
        val stillReceiving = stopping.feed(fix(320.0, 34.0 + 170, speed = 0.0))
        assertFalse(stillReceiving.tick(34_000 + 180_000).finish.isLastSignalPoint)
    }
}

class FixSanitizerTest {
    private val previous = CleanFix(fix(0.0, 1.0).point, 0.0, 5.0, BASE_UTC, 1_000)

    @Test fun accuracyLimitIsInclusive() {
        assertEquals(25.0, FixSanitizer.sanitize(fix(0.0, 2.0, accuracy = 25.0), null)!!.accuracyMeters, 0.0)
        assertNull(FixSanitizer.sanitize(fix(0.0, 2.0, accuracy = 25.01), null))
    }

    @Test fun reportedSpeedWins() {
        assertEquals(7.0, FixSanitizer.sanitize(fix(100.0, 2.0, speed = 7.0), previous)!!.speedMps, 0.0)
    }

    @Test fun missingSpeedIsInferredFromTheLastAcceptedFix() {
        assertEquals(20.0, FixSanitizer.sanitize(fix(20.0, 2.0, speed = null), previous)!!.speedMps, 0.01)
    }

    @Test fun missingSpeedWithoutReferenceIsZero() {
        assertEquals(0.0, FixSanitizer.sanitize(fix(20.0, 2.0, speed = null), null)!!.speedMps, 0.0)
    }

    @Test fun outOfOrderFixIsRejected() {
        assertNull(FixSanitizer.sanitize(fix(20.0, 1.0), previous))
        assertNull(FixSanitizer.sanitize(fix(20.0, 0.5), previous))
    }

    @Test fun carWithoutReportedSpeedCanStartATrip() {
        val state = MachineState().feed(
            fix(0.0, 1.0, speed = null),
            fix(10.0, 2.0, speed = null),
            fix(25.0, 3.0, speed = null),
        )
        assertTrue(state.phase is TripPhase.InTrip)
    }
}

class TripRecoveryTest {
    private val clean = CleanFix(fix(300.0, 50.0).point, 10.0, 5.0, BASE_UTC + 50_000, 50_000)
    private val trip = ActiveTrip(BASE_UTC, 1_000, fix(0.0, 1.0).point, 300.0, clean, clean)
    private val beat = Heartbeat(60_000, BASE_UTC + 60_000, 7)
    private val record = ActiveTripRecord(trip, null, beat)

    @Test fun sameBootWithin180sResumes() {
        val decision = TripRecovery.decide(record, 60_000 + 180_000, 7)
        assertTrue((decision as RecoveryDecision.Resume).state.phase is TripPhase.InTrip)
    }

    @Test fun resumesInStoppingWhenThatWasSaved() {
        val decision = TripRecovery.decide(record.copy(stopCandidateMonoMs = 55_000), 61_000, 7)
        assertEquals(TripPhase.Stopping(trip, 55_000), (decision as RecoveryDecision.Resume).state.phase)
    }

    @Test fun sameBootPast180sClosesAsRecoveredAtTheHeartbeat() {
        val decision = TripRecovery.decide(record, 60_000 + 180_001, 7) as RecoveryDecision.Close
        assertEquals(TripClosure.RECOVERED, decision.finish.trip.closure)
        assertEquals(beat.utcMs, decision.finish.trip.endUtcMs)
        assertEquals(clean.point, decision.finish.trip.endPoint)
        assertTrue(decision.finish.isLastSignalPoint)
    }

    @Test fun rebootAlwaysCloses_evenIfMonotonicLooksRecent() {
        assertTrue(TripRecovery.decide(record, 60_001, 8) is RecoveryDecision.Close)
    }

    @Test fun unreadableBootCountAlwaysCloses() {
        assertTrue(TripRecovery.decide(record, 60_001, null) is RecoveryDecision.Close)
        assertTrue(TripRecovery.decide(record.copy(heartbeat = beat.copy(bootCount = null)), 60_001, 7) is RecoveryDecision.Close)
    }

    @Test fun recoveredTripBelowMinimumsIsDiscarded() {
        val short = record.copy(
            trip = trip.copy(distanceMeters = 50.0),
            heartbeat = beat.copy(monoMs = 30_000),
        )
        assertFalse((TripRecovery.decide(short, 500_000, 7) as RecoveryDecision.Close).finish.kept)
    }

    @Test fun heartbeatBeforeTripStartInUtcStillSatisfiesR3() {
        val odd = record.copy(heartbeat = beat.copy(utcMs = BASE_UTC - 9_999))
        val finish = (TripRecovery.decide(odd, 500_000, 7) as RecoveryDecision.Close).finish
        assertEquals(BASE_UTC + 59_000, finish.trip.endUtcMs)
    }
}
