package app.guantera

import app.guantera.presentation.port.MotionStatus
import app.guantera.sample.FakeMediaPlayerControl
import app.guantera.sample.SampleData
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.sonda.domain.media.VolumeCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SampleDataTest {
    private val clock = FakeClock(now = 100 * 60 * 60_000L)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private fun TestScope.sample() = SampleData(clock, backgroundScope)

    private fun TestScope.advanceSeconds(seconds: Long) {
        advanceTimeBy(seconds * 1_000)
        runCurrent()
    }

    @Test fun `simulated trip starts moving`() = test {
        val sample = sample()
        assertNull(sample.driving.value)
        sample.startSimulatedTrip()
        assertNotNull(sample.driving.value)
        assertEquals(MotionStatus.Moving, sample.status.value.motion)
    }

    @Test fun `simulated trip advances and then enters stopping`() = test {
        val sample = sample()
        sample.startSimulatedTrip()
        val start = sample.driving.value!!.distanceKm
        assertNull(sample.driving.value!!.stoppingSeconds)
        advanceSeconds(30)
        assertEquals(start + 1, sample.driving.value!!.distanceKm, 0.001)
        advanceSeconds(35)
        assertEquals(20L, sample.driving.value!!.stoppingSeconds)
    }

    @Test fun `ending the simulation restores the stopped state`() = test {
        val sample = sample()
        sample.startSimulatedTrip()
        sample.stopSimulatedTrip()
        assertNull(sample.driving.value)
        assertEquals(MotionStatus.Stopped, sample.status.value.motion)
    }

    @Test fun `confirming parked adds the trip and saves the spot`() = test {
        val sample = sample()
        val before = sample.home.value
        sample.startSimulatedTrip()
        sample.confirmParked()
        val after = sample.home.value
        assertNull(sample.driving.value)
        assertEquals(before.today.tripCount + 1, after.today.tripCount)
        assertTrue(after.today.kilometers > before.today.kilometers)
        assertEquals(clock.now, after.parked!!.savedAtMillis)
    }

    @Test fun `confirming parked without a trip changes nothing`() = test {
        val sample = sample()
        val before = sample.home.value
        sample.confirmParked()
        assertEquals(before, sample.home.value)
    }

    @Test fun `fake player toggles playback and cycles tracks both ways`() {
        val player = FakeMediaPlayerControl()
        val first = player.state.value.nowPlaying
        player.send(MediaCommand.PlayPause)
        assertEquals(PlaybackState.Paused, player.state.value.playback)
        player.send(MediaCommand.Next)
        assertNotEquals(first, player.state.value.nowPlaying)
        player.send(MediaCommand.Previous)
        assertEquals(first, player.state.value.nowPlaying)
    }

    @Test fun `fake player keeps the volume within range`() {
        val player = FakeMediaPlayerControl()
        repeat(40) { player.adjustVolume(VolumeCommand.Up) }
        assertEquals(15, player.state.value.volume!!.current)
        repeat(40) { player.adjustVolume(VolumeCommand.Down) }
        assertEquals(0, player.state.value.volume!!.current)
    }
}
