package app.guantera

import app.guantera.media.MusicModeStore
import app.guantera.media.SwitchableMediaPlayerControl
import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.port.AppSettings
import app.guantera.presentation.port.DateTimeInfo
import app.guantera.presentation.port.DateTimeSettings
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.MusicSource
import app.guantera.presentation.port.SettingsStore
import app.guantera.presentation.settings.DateTimeUi
import app.guantera.presentation.settings.OdometerResult
import app.guantera.presentation.settings.SettingsPresenter
import app.guantera.sonda.domain.media.MediaCapabilities
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.MediaSnapshot
import app.guantera.sonda.domain.media.NowPlaying
import app.guantera.sonda.domain.media.PlaybackState
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

class FakeSettingsStore(private val now: Long = 0L) : SettingsStore {
    override val settings = MutableStateFlow(
        AppSettings(48_250, now - 12 * DAY, "$", false, null, now - 1000, now - 45 * DAY),
    )
    var restored = 0
    override fun correctOdometer(km: Int) { settings.value = settings.value.copy(odometerKm = km, odometerCalibratedAtMillis = now) }
    override fun setCurrency(currency: String) { settings.value = settings.value.copy(currency = currency) }
    override fun setOpenAtBoot(enabled: Boolean) { settings.value = settings.value.copy(openAtBoot = enabled) }
    override fun restoreCatalog() { restored += 1 }
    private companion object { const val DAY = 86_400_000L }
}

class FakeMusicSource : MusicSource {
    override val isReal = MutableStateFlow(false)
    override fun setReal(real: Boolean) { isReal.value = real }
}

class FakeDateTime(var info: DateTimeInfo = DateTimeInfo(true, true, "America/Argentina/Buenos_Aires (GMT-03:00)", true)) : DateTimeSettings {
    var reads = 0
    override fun read(): DateTimeInfo { reads += 1; return info }
}

class MemoryModeStore(var real: Boolean = false) : MusicModeStore {
    override fun isReal() = real
    override fun save(real: Boolean) { this.real = real }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPresenterTest {
    private val clock = FakeClock(now = 100 * 86_400_000L)
    private val data = FakeData()
    private val store = FakeSettingsStore(clock.now)
    private val music = FakeMusicSource()
    private val dateTime = FakeDateTime()
    private val format = DisplayFormat(TimeZone.getTimeZone("UTC"))

    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private fun TestScope.presenter() =
        SettingsPresenter(data, FakeDemo(), store, music, dateTime, format, clock, backgroundScope).also { p ->
            backgroundScope.launch { p.state.collect {} }
        }

    @Test fun `ages are whole days and the old export is overdue`() = test {
        val state = presenter().state.value
        assertEquals(12, state.calibrationDays)
        assertEquals(0, state.backupDays)
        assertEquals(45, state.exportDays)
        assertTrue(state.exportOverdue)
        assertNull(state.catalogRestoredDays)
        assertFalse(state.openAtBoot)
    }

    @Test fun `odometer correction accepts thousands separators and rejects junk`() = test {
        val p = presenter()
        assertEquals(OdometerResult.Saved, p.correctOdometer(" 50.125 "))
        assertEquals("50.125", p.state.value.odometerText)
        assertEquals(0, p.state.value.calibrationDays)
        for (bad in listOf("", "abc", "-5", "12,5", "99999999999")) {
            assertEquals(bad, OdometerResult.Invalid, p.correctOdometer(bad))
        }
        assertEquals("50.125", p.state.value.odometerText)
    }

    @Test fun `currency cycles and wraps`() = test {
        val p = presenter()
        repeat(SettingsPresenter.CURRENCIES.size) { p.nextCurrency() }
        assertEquals("$", p.state.value.currency)
        p.nextCurrency()
        assertEquals("US$", p.state.value.currency)
    }

    @Test fun `open at boot toggles and starts off`() = test {
        val p = presenter()
        p.toggleOpenAtBoot()
        assertTrue(p.state.value.openAtBoot)
        p.toggleOpenAtBoot()
        assertFalse(p.state.value.openAtBoot)
    }

    @Test fun `restore catalog reaches the store and music choice reaches the source`() = test {
        val p = presenter()
        p.restoreCatalog()
        assertEquals(1, store.restored)
        p.setMusicReal(true)
        assertTrue(p.state.value.musicReal)
    }

    @Test fun `unknown movement asks once and the answer lasts ten minutes`() = test {
        val p = presenter()
        assertFalse(p.requiresStoppedConfirmation())
        data.status.value = data.status.value.copy(motion = MotionStatus.Unknown)
        assertTrue(p.requiresStoppedConfirmation())
        p.confirmStopped()
        assertFalse(p.requiresStoppedConfirmation())
        clock.now += 11 * 60_000L
        assertTrue(p.requiresStoppedConfirmation())
    }

    @Test fun `switchable player follows the choice, persists it and restarts the right side`() = test {
        val demo = FakePlayer(MediaSnapshot.Empty.copy(playback = PlaybackState.Playing))
        val real = FakePlayer()
        val saved = MemoryModeStore()
        val player = SwitchableMediaPlayerControl(demo, real, saved, backgroundScope)
        assertFalse(player.isReal.value)
        assertEquals(PlaybackState.Playing, player.state.value.playback)

        player.start()
        player.send(MediaCommand.Next)
        assertEquals(1, demo.commands.size)

        player.setReal(true)
        assertTrue(saved.real)
        assertEquals(1, demo.stopCalls)
        assertEquals(1, real.startCalls)
        assertEquals(PlaybackState.Unknown, player.state.value.playback)
        player.send(MediaCommand.Next)
        assertEquals(1, real.commands.size)

        real.state.value = MediaSnapshot(
            NowPlaying("T", "A", null, "p", "App"), PlaybackState.Playing,
            MediaCapabilities(true, true, true, true), null,
        )
        assertEquals("T", player.state.value.nowPlaying?.title)
        player.stop()
        assertEquals(1, real.stopCalls)
    }

    @Test fun `switchable player starts from the saved choice`() = test {
        val player = SwitchableMediaPlayerControl(FakePlayer(), FakePlayer(), MemoryModeStore(real = true), backgroundScope)
        assertTrue(player.isReal.value)
    }

    @Test fun `date and time reflects the radio's own readings`() = test {
        val p = presenter()
        val ui = p.state.value.dateTime as DateTimeUi.Known
        assertTrue(ui.autoTime)
        assertEquals(true, ui.autoTimeZone)
        assertEquals("America/Argentina/Buenos_Aires (GMT-03:00)", ui.timeZoneLabel)
        assertTrue(ui.canOpenSettings)
    }

    @Test fun `automatic time off is reported and refresh picks up the change`() = test {
        dateTime.info = DateTimeInfo(false, false, "UTC (GMT+00:00)", true)
        val p = presenter()
        assertFalse((p.state.value.dateTime as DateTimeUi.Known).autoTime)
        dateTime.info = DateTimeInfo(true, false, "UTC (GMT+00:00)", true)
        val reads = dateTime.reads
        p.refreshDateTime()
        assertEquals(reads + 1, dateTime.reads)
        assertTrue((p.state.value.dateTime as DateTimeUi.Known).autoTime)
    }

    @Test fun `an unreadable setting is unavailable, never a guess`() = test {
        dateTime.info = DateTimeInfo(null, null, null, true)
        assertEquals(DateTimeUi.Unavailable, presenter().state.value.dateTime)
    }

    @Test fun `a ROM without the date screen shows the readings but no button`() = test {
        dateTime.info = DateTimeInfo(true, null, null, settingsScreenAvailable = false)
        val ui = presenter().state.value.dateTime as DateTimeUi.Known
        assertFalse(ui.canOpenSettings)
        assertNull(ui.autoTimeZone)
        assertNull(ui.timeZoneLabel)
    }
}
