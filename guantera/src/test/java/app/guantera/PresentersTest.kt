package app.guantera

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.common.PlaybackActions
import app.guantera.presentation.driving.DrivingPresenter
import app.guantera.presentation.home.HomePresenter
import app.guantera.presentation.home.HomeVerdict
import app.guantera.presentation.home.NoticeAction
import app.guantera.presentation.home.ParkedUi
import app.guantera.presentation.music.MusicPresenter
import app.guantera.presentation.port.AppNotice
import app.guantera.presentation.port.DrivingData
import app.guantera.presentation.port.HomeData
import app.guantera.presentation.port.MaintenanceAlert
import app.guantera.presentation.port.MaintenanceLevel
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.ParkedSpot
import app.guantera.presentation.port.Remaining
import app.guantera.presentation.port.TodaySummary
import app.guantera.presentation.settings.SettingsPresenter
import app.guantera.presentation.shell.Destination
import app.guantera.presentation.shell.ShellPresenter
import app.guantera.sonda.domain.media.ControlDiagnostic
import app.guantera.sonda.domain.media.ControlKind
import app.guantera.sonda.domain.media.MediaCapabilities
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.MediaSnapshot
import app.guantera.sonda.domain.media.NowPlaying
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.sonda.domain.media.VolumeCommand
import app.guantera.sonda.domain.media.VolumeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class PresentersTest {
    private val format = DisplayFormat(TimeZone.getTimeZone("UTC"))
    private val clock = FakeClock()
    private val ticks = MutableStateFlow(0L)
    private val data = FakeData()
    private val player = FakePlayer()
    private val actions = PlaybackActions(player, CoroutineScope(Dispatchers.Unconfined))
    private val urgent = MaintenanceAlert("Aceite", MaintenanceLevel.Overdue, Remaining.Km(1_350))

    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private fun TestScope.home() = HomePresenter(data, actions, format, clock, ticks, backgroundScope).also { p ->
        backgroundScope.launch { p.state.collect {} }
    }

    private fun TestScope.shell() = ShellPresenter(data, player, format, clock, ticks, backgroundScope).also { p ->
        backgroundScope.launch { p.state.collect {} }
    }

    private fun TestScope.driving() =
        DrivingPresenter(data, actions, format, backgroundScope).also { p ->
            backgroundScope.launch { p.state.collect {} }
        }

    private fun trip(km: Double = 12.0, seconds: Long = 0, stopping: Long? = null) =
        DrivingData(km, seconds, stopping, null)

    private fun homeData(
        alert: MaintenanceAlert? = null,
        notices: List<AppNotice> = emptyList(),
        parked: ParkedSpot? = null,
    ) = HomeData(TodaySummary(42.8, 3), alert, parked, notices)

    @Test fun `home formats today with an Argentine decimal comma`() = test {
        data.home.value = homeData()
        val state = home().state.value
        assertEquals("42,8", state.todayKmText)
        assertEquals(3, state.todayTrips)
    }

    @Test fun `home says all good when nothing is pending`() = test {
        data.home.value = homeData()
        val state = home().state.value
        assertEquals(HomeVerdict.AllGood, state.verdict)
        assertNull(state.notice)
        assertNull(state.maintenance)
    }

    @Test fun `an overdue service wins the verdict over a notice`() = test {
        data.home.value = homeData(urgent, listOf(AppNotice.ExportOverdue))
        assertEquals(HomeVerdict.ServiceOverdue, home().state.value.verdict)
    }

    @Test fun `a notice wins the verdict over a service that is only soon`() = test {
        data.home.value = homeData(urgent.copy(level = MaintenanceLevel.Soon), listOf(AppNotice.ExportOverdue))
        assertEquals(HomeVerdict.NeedsAttention, home().state.value.verdict)
    }

    @Test fun `only the highest priority notice is shown`() = test {
        data.home.value = homeData(notices = listOf(AppNotice.ExportOverdue, AppNotice.GpsOff, AppNotice.StorageFull))
        val notice = home().state.value.notice!!
        assertEquals(AppNotice.GpsOff, notice.notice)
        assertEquals(NoticeAction.OpenLocationSettings, notice.action)
    }

    @Test fun `a notice nobody can act on has no action`() = test {
        data.home.value = homeData(notices = listOf(AppNotice.SearchingGps))
        assertEquals(NoticeAction.None, home().state.value.notice!!.action)
    }

    @Test fun `every banner of the specification offers its own action`() = test {
        val expected = mapOf(
            AppNotice.NoLocationPermission to NoticeAction.GrantLocation,
            AppNotice.GpsOff to NoticeAction.OpenLocationSettings,
            AppNotice.UntrustedClock to NoticeAction.None,
            AppNotice.SearchingGps to NoticeAction.None,
            AppNotice.OdometerMissing to NoticeAction.OpenSettings,
            AppNotice.RecordingGap to NoticeAction.OpenSettings,
            AppNotice.StorageFull to NoticeAction.None,
            AppNotice.ExportOverdue to NoticeAction.OpenSettings,
        )
        assertEquals(AppNotice.entries.toSet(), expected.keys)
        expected.forEach { (notice, action) ->
            data.home.value = homeData(notices = listOf(notice))
            assertEquals(notice.name, action, home().state.value.notice!!.action)
        }
    }

    @Test fun `touching the recording gap banner closes it, and no other banner does`() = test {
        val presenter = home()
        presenter.noticeTouched(AppNotice.OdometerMissing)
        presenter.noticeTouched(AppNotice.ExportOverdue)
        assertEquals(0, data.gapDismissals)
        presenter.noticeTouched(AppNotice.RecordingGap)
        assertEquals(1, data.gapDismissals)
    }

    @Test fun `maintenance distance carries the thousands separator`() = test {
        data.home.value = homeData(urgent)
        assertEquals("1.350", home().state.value.maintenance!!.remainingKmText)
    }

    @Test fun `parked age follows the minute ticks`() = test {
        data.home.value = homeData(parked = ParkedSpot(0L, "Piso 2"))
        val presenter = home()
        ticks.value = 135 * 60_000L
        val parked = presenter.state.value.parked as ParkedUi.Saved
        assertEquals(135, parked.ageMinutes)
        assertEquals("00:00", parked.timeText)
        assertEquals("Piso 2", parked.note)
    }

    @Test fun `a clock that goes backwards never yields a negative age`() = test {
        data.home.value = homeData(parked = ParkedSpot(10 * 60_000L, null))
        val presenter = home()
        assertEquals(0, (presenter.state.value.parked as ParkedUi.Saved).ageMinutes)
    }

    @Test fun `no spot saved is reported as empty`() = test {
        data.home.value = homeData()
        assertEquals(ParkedUi.Empty, home().state.value.parked)
    }

    @Test fun `home sends transport commands to the player`() = test {
        val presenter = home()
        presenter.previous()
        presenter.playPause()
        presenter.next()
        assertEquals(listOf(MediaCommand.Previous, MediaCommand.PlayPause, MediaCommand.Next), player.commands)
    }

    @Test fun `driving distance only changes once per km`() = test {
        data.driving.value = trip(km = 12.1)
        val presenter = driving()
        assertEquals("12", presenter.state.value.kmText)
        data.driving.value = trip(km = 12.9)
        assertEquals("12", presenter.state.value.kmText)
        data.driving.value = trip(km = 13.0)
        assertEquals("13", presenter.state.value.kmText)
    }

    @Test fun `driving minutes only change once per minute`() = test {
        data.driving.value = trip(seconds = 119)
        val presenter = driving()
        assertEquals("1", presenter.state.value.minutesText)
        data.driving.value = trip(seconds = 120)
        assertEquals("2", presenter.state.value.minutesText)
    }

    @Test fun `parked button appears only after 20 seconds stopping`() = test {
        data.driving.value = trip(stopping = null)
        val presenter = driving()
        assertFalse(presenter.state.value.showParkedButton)
        data.driving.value = trip(stopping = 19)
        assertFalse(presenter.state.value.showParkedButton)
        data.driving.value = trip(stopping = 20)
        assertTrue(presenter.state.value.showParkedButton)
    }

    @Test fun `confirming parked reaches the data source`() = test {
        data.driving.value = trip(stopping = 30)
        driving().confirmParked()
        assertEquals(1, data.parkedConfirmations)
    }

    @Test fun `driving sends volume commands to the player`() = test {
        val presenter = driving()
        presenter.volumeUp()
        presenter.volumeDown()
        assertEquals(listOf(VolumeCommand.Up, VolumeCommand.Down), player.volumes)
    }

    @Test fun `a rejected command is reported and cleared by the next success`() = test {
        val presenter = MusicPresenter(actions, backgroundScope)
        backgroundScope.launch { presenter.state.collect {} }
        player.failing = true
        presenter.next()
        assertEquals(ControlKind.Next, presenter.state.value.playback.failedControl)
        player.failing = false
        presenter.next()
        assertNull(presenter.state.value.playback.failedControl)
    }

    @Test fun `an unknown volume is null, never an invented percentage`() = test {
        player.state.value = MediaSnapshot.Empty.copy(volume = null)
        val presenter = MusicPresenter(actions, backgroundScope)
        backgroundScope.launch { presenter.state.collect {} }
        assertNull(presenter.state.value.playback.volumePercent)
        assertNull(presenter.state.value.playback.title)
    }

    @Test fun `music explains how to read the track when access is missing`() = test {
        val presenter = MusicPresenter(actions, backgroundScope)
        backgroundScope.launch { presenter.state.collect {} }
        assertTrue(presenter.state.value.showAccessBanner)
        assertFalse(presenter.state.value.playback.hasTrack)
    }

    @Test fun `music maps the snapshot and the volume percentage`() = test {
        player.state.value = MediaSnapshot(
            nowPlaying = NowPlaying("Tema", "Artista", null, "com.app", null),
            playback = PlaybackState.Paused,
            capabilities = MediaCapabilities(true, true, true, true),
            volume = VolumeState(current = 5, max = 10, muted = true),
        )
        val presenter = MusicPresenter(actions, backgroundScope)
        backgroundScope.launch { presenter.state.collect {} }
        val playback = presenter.state.value.playback
        assertFalse(presenter.state.value.showAccessBanner)
        assertEquals("Tema", playback.title)
        assertEquals("com.app", playback.sourceApp)
        assertEquals(50, playback.volumePercent)
        assertTrue(playback.muted)
        assertFalse(playback.isPlaying)
    }

    @Test fun `music reports an unknown volume as null, without dividing by zero`() = test {
        player.state.value = MediaSnapshot.Empty.copy(volume = VolumeState(0, 0, false))
        val presenter = MusicPresenter(actions, backgroundScope)
        backgroundScope.launch { presenter.state.collect {} }
        assertNull(presenter.state.value.playback.volumePercent)
    }

    @Test fun `music forwards mute`() = test {
        val presenter = MusicPresenter(actions, backgroundScope)
        presenter.toggleMute()
        assertEquals(listOf(VolumeCommand.ToggleMute), player.volumes)
    }

    @Test fun `shell starts on home and shows the time`() = test {
        val presenter = shell()
        ticks.value = 90 * 60_000L
        val state = presenter.state.value
        assertEquals(Destination.Home, state.destination)
        assertEquals("01:30", state.clockText)
    }

    @Test fun `shell navigates between destinations`() = test {
        val presenter = shell()
        presenter.navigate(Destination.Music)
        assertEquals(Destination.Music, presenter.state.value.destination)
    }

    @Test fun `driving mode takes over and blocks navigation`() = test {
        val presenter = shell()
        presenter.navigate(Destination.Settings)
        data.driving.value = trip()
        assertTrue(presenter.state.value.isDriving)
        presenter.navigate(Destination.Music)
        assertEquals(Destination.Settings, presenter.state.value.destination)
    }

    @Test fun `leaving driving mode returns to the same screen`() = test {
        val presenter = shell()
        presenter.navigate(Destination.Settings)
        data.driving.value = trip()
        data.driving.value = null
        assertFalse(presenter.state.value.isDriving)
        assertEquals(Destination.Settings, presenter.state.value.destination)
    }

    @Test fun `back goes home first, then leaves the app`() = test {
        val presenter = shell()
        presenter.navigate(Destination.Trips)
        assertTrue(presenter.onBack())
        assertEquals(Destination.Home, presenter.state.value.destination)
        assertFalse(presenter.onBack())
    }

    @Test fun `back is ignored while driving`() = test {
        val presenter = shell()
        data.driving.value = trip()
        assertTrue(presenter.onBack())
    }

    @Test fun `shell starts and stops the player with the screen`() = test {
        val presenter = shell()
        presenter.onVisible()
        presenter.onHidden()
        assertEquals(1, player.startCalls)
        assertEquals(1, player.refreshCalls)
        assertEquals(1, player.stopCalls)
    }

    @Test fun `settings formats the odometer and says when it was never entered`() = test {
        val store = FakeSettingsStore()
        val presenter = SettingsPresenter(data, store, MutableStateFlow(ControlDiagnostic.Initial), FakeDateTime(), format, clock, backgroundScope)
        backgroundScope.launch { presenter.state.collect {} }
        assertEquals("48.250", presenter.state.value.odometerText)
        store.settings.value = store.settings.value.copy(odometerKm = null)
        assertEquals(null, presenter.state.value.odometerText)
    }

    @Test fun `moving status passes through to the top bar`() = test {
        val presenter = shell()
        data.status.value = data.status.value.copy(motion = MotionStatus.Moving)
        assertEquals(MotionStatus.Moving, presenter.state.value.motion)
    }
}
