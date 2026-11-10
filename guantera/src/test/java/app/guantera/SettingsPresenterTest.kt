package app.guantera

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.port.AppSettings
import app.guantera.presentation.port.BackupActions
import app.guantera.presentation.port.ExportOutcome
import app.guantera.presentation.port.FormOutcome
import app.guantera.presentation.port.ImportOutcome
import app.guantera.presentation.settings.SettingsDialog
import app.guantera.presentation.port.DateTimeInfo
import app.guantera.presentation.port.DateTimeSettings
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.SettingsStore
import app.guantera.presentation.settings.DateTimeUi
import app.guantera.presentation.settings.OdometerResult
import app.guantera.presentation.settings.SettingsPresenter
import app.guantera.sonda.domain.media.ControlDiagnostic
import app.guantera.sonda.domain.media.ControlKind
import app.guantera.sonda.domain.media.ControlRoute
import app.guantera.sonda.domain.media.Effect
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

class FakeBackups : BackupActions {
    var exportResult: ExportOutcome = ExportOutcome.Done("/pendrive/Guantera/respaldos/backup-1.json")
    var importResult: ImportOutcome = ImportOutcome.Done(12)
    var exports = 0
    var imports = 0
    var lastCopy: Long? = null
    override suspend fun export(): ExportOutcome { exports += 1; return exportResult }
    override suspend fun importFromDrive(): ImportOutcome { imports += 1; return importResult }
    override suspend fun lastCopyUtcMs(): Long? = lastCopy
}

class FakeSettingsStore(private val now: Long = 0L) : SettingsStore {
    override val settings = MutableStateFlow(
        AppSettings(48_250, now - 12 * DAY, "$", false, null, now - 1000, now - 45 * DAY),
    )
    override val backups = FakeBackups()

    /** What the next writes answer, in order; once empty, every write is saved. */
    val answers = ArrayDeque<FormOutcome>()
    var restored = 0
    var stoppedAnswers = 0
    var refreshes = 0

    private fun write(change: () -> Unit): FormOutcome {
        val outcome = answers.removeFirstOrNull() ?: FormOutcome.Saved
        if (outcome == FormOutcome.Saved) change()
        return outcome
    }

    override suspend fun correctOdometer(km: Int) = write { settings.value = settings.value.copy(odometerKm = km, odometerCalibratedAtMillis = now) }
    override suspend fun setCurrency(currency: String) = write { settings.value = settings.value.copy(currency = currency) }
    override suspend fun setOpenAtBoot(enabled: Boolean) = write { settings.value = settings.value.copy(openAtBoot = enabled) }
    override suspend fun restoreCatalog() = write { restored += 1 }
    override fun confirmStopped() { stoppedAnswers += 1 }
    override fun refreshBackupInfo() { refreshes += 1 }
    private companion object { const val DAY = 86_400_000L }
}

class FakeDateTime(var info: DateTimeInfo = DateTimeInfo(true, true, "America/Argentina/Buenos_Aires (GMT-03:00)", true)) : DateTimeSettings {
    var reads = 0
    override fun read(): DateTimeInfo { reads += 1; return info }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPresenterTest {
    private val clock = FakeClock(now = 100 * 86_400_000L)
    private val data = FakeData()
    private val store = FakeSettingsStore(clock.now)
    private val music = MutableStateFlow(ControlDiagnostic.Initial)
    private val dateTime = FakeDateTime()
    private val format = DisplayFormat(TimeZone.getTimeZone("UTC"))

    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private fun TestScope.presenter() =
        SettingsPresenter(data, store, music, dateTime, format, clock, backgroundScope).also { p ->
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

    @Test fun `restore catalog reaches the store`() = test {
        val p = presenter()
        p.restoreCatalog()
        assertEquals(1, store.restored)
    }

    @Test fun `music controls start untried and follow what the adapter reports`() = test {
        val p = presenter()
        assertTrue(p.state.value.musicControls.all { it.route == null && it.effect == null })
        assertEquals(ControlKind.entries, p.state.value.musicControls.map { it.control })
        music.value = music.value.map {
            if (it.control == ControlKind.PlayPause) ControlDiagnostic(it.control, ControlRoute.KeyHeadsetHook, Effect.NoEffect, listOf(ControlRoute.Transport, ControlRoute.KeyHeadsetHook)) else it
        }
        val play = p.state.value.musicControls.first { it.control == ControlKind.PlayPause }
        assertEquals(ControlRoute.KeyHeadsetHook, play.route)
        assertEquals(Effect.NoEffect, play.effect)
    }

    @Test fun `a change blocked for lack of confirmation asks, and goes through after yes`() = test {
        val p = presenter()
        store.answers += FormOutcome.NeedsConfirmation
        p.nextCurrency()
        assertEquals(SettingsDialog.AskStopped, p.state.value.dialog)
        assertEquals("$", p.state.value.currency)
        p.answerStopped(true)
        assertEquals(1, store.stoppedAnswers)
        assertEquals("US$", p.state.value.currency)
        assertEquals(null, p.state.value.dialog)
    }

    @Test fun `answering no drops the change and tells the domain nothing`() = test {
        val p = presenter()
        store.answers += FormOutcome.NeedsConfirmation
        p.toggleOpenAtBoot()
        p.answerStopped(false)
        assertEquals(0, store.stoppedAnswers)
        assertFalse(p.state.value.openAtBoot)
        assertEquals(null, p.state.value.dialog)
    }

    @Test fun `a change blocked by movement says so and writes nothing`() = test {
        val p = presenter()
        store.answers += FormOutcome.CarMoving
        p.nextCurrency()
        assertEquals(SettingsDialog.CarMoving, p.state.value.dialog)
        assertEquals("$", p.state.value.currency)
        p.dismissDialog()
        assertEquals(null, p.state.value.dialog)
    }

    @Test fun `a failed save keeps the old value and retrying writes it`() = test {
        val p = presenter()
        store.answers += FormOutcome.StorageFailed
        assertEquals(OdometerResult.Saved, p.correctOdometer("50.000"))
        assertEquals(SettingsDialog.SaveFailed(diskFull = false), p.state.value.dialog)
        assertEquals("48.250", p.state.value.odometerText)
        p.retryWrite()
        assertEquals("50.000", p.state.value.odometerText)
        assertEquals(null, p.state.value.dialog)
    }

    @Test fun `a full disk is told apart from other failures`() = test {
        val p = presenter()
        store.answers += FormOutcome.StorageFull
        p.restoreCatalog()
        assertEquals(SettingsDialog.SaveFailed(diskFull = true), p.state.value.dialog)
    }

    @Test fun `exporting reports each result and never invents success`() = test {
        val p = presenter()
        listOf(
            ExportOutcome.Done("/pendrive/x.json"), ExportOutcome.CarMoving, ExportOutcome.DriveMissing,
            ExportOutcome.DriveFull, ExportOutcome.Failed,
        ).forEach { result ->
            store.backups.exportResult = result
            p.export()
            assertEquals(SettingsDialog.ExportReport(result), p.state.value.dialog)
            p.dismissDialog()
        }
        assertEquals(5, store.backups.exports)
        assertTrue(store.refreshes >= 5)
    }

    @Test fun `importing asks first, replaces nothing without a yes, and reports each result`() = test {
        val p = presenter()
        p.askImport()
        assertEquals(SettingsDialog.ConfirmImport, p.state.value.dialog)
        assertEquals(0, store.backups.imports)
        p.dismissDialog()
        assertEquals(0, store.backups.imports)
        listOf(
            ImportOutcome.Done(340), ImportOutcome.CarMoving, ImportOutcome.DriveMissing, ImportOutcome.NoCopy,
            ImportOutcome.Unreadable, ImportOutcome.Damaged, ImportOutcome.NewerVersion,
            ImportOutcome.SafetyCopyFailed, ImportOutcome.StorageFailed,
        ).forEach { result ->
            store.backups.importResult = result
            p.askImport()
            p.importNewest()
            assertEquals(SettingsDialog.ImportReport(result), p.state.value.dialog)
            p.dismissDialog()
        }
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
