package app.guantera.sonda.domain

import app.guantera.sonda.domain.model.ExportResult
import app.guantera.sonda.domain.model.LogHealth
import app.guantera.sonda.domain.model.LogRead
import app.guantera.sonda.domain.rule.IntervalSamplingPolicy
import app.guantera.sonda.domain.usecase.Dashboard
import app.guantera.sonda.domain.usecase.ExportReport
import app.guantera.sonda.domain.usecase.MonitorSuspension
import app.guantera.sonda.domain.usecase.RecordEvent
import app.guantera.sonda.domain.usecase.TrackLocation
import app.guantera.sonda.domain.usecase.TrackingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorSuspensionTest {
    private val clock = FakeClock(elapsed = 1_000, uptime = 1_000)
    private val log = FakeEventLog()
    private val source = FakeLocationSource()
    private val track = TrackLocation(source, log, clock, IntervalSamplingPolicy())
    private val monitor = MonitorSuspension(clock, log, track)

    @Test fun staysSilentWhenClocksAdvanceTogether() {
        clock.advance(awakeMillis = 10_000)
        monitor.beat()
        assertTrue(log.lines.none { it.startsWith("SUSPENSIÓN") })
    }

    @Test fun reportsSuspensionAndResumesGps() {
        clock.advance(awakeMillis = 10_000, sleptMillis = 120_000)
        monitor.beat()
        assertEquals("SUSPENSIÓN detectada de 120 s", log.lines.first())
    }

    @Test fun suspensionReallyRegistersTheListenerAgain() {
        track.resume()
        clock.advance(awakeMillis = 10_000, sleptMillis = 120_000)
        monitor.beat()
        assertEquals(2, source.registrations)
        assertTrue(source.active)
        assertTrue(log.lines.contains("GPS re-registro solicitado al despertar"))
    }

    @Test fun everyBeatRetriesGpsWhileItIsNotActive() {
        source.startFailure = "sin proveedor"
        clock.advance(awakeMillis = 10_000)
        monitor.beat()
        source.startFailure = null
        clock.advance(awakeMillis = 10_000)
        monitor.beat()
        assertTrue(source.active)
    }

    @Test fun beatDoesNotReRegisterAnActiveListener() {
        track.resume()
        clock.advance(awakeMillis = 10_000)
        monitor.beat()
        assertEquals(1, source.registrations)
    }

    @Test fun comparesAgainstPreviousBeatNotTheFirst() {
        clock.advance(awakeMillis = 10_000, sleptMillis = 60_000)
        monitor.beat()
        clock.advance(awakeMillis = 10_000)
        monitor.beat()
        assertEquals(1, log.lines.count { it.startsWith("SUSPENSIÓN") })
    }

    @Test fun runBeatsEveryHeartbeatInterval() = runTest {
        val job = launch { monitor.run() }
        runCurrent()
        clock.advance(awakeMillis = 10_000, sleptMillis = 30_000)
        advanceTimeBy(10_001)
        job.cancel()
        assertEquals("SUSPENSIÓN detectada de 30 s", log.lines.first())
    }
}

class ExportReportTest {
    private val log = FakeEventLog().apply { append("a"); append("b") }
    private val store = FakeReportStore()
    private val clock = FakeClock(now = 42)
    private val export = ExportReport(FakeDiagnosticsSource(), log, store, clock) { d -> "${d.model}|\n" }

    @Test fun streamsHeaderThenJournalUnderTimestampedName() {
        assertEquals(ExportResult.Saved("/Download/report.txt"), export())
        assertEquals("guantera-sonda-42.txt" to "M|\na\nb\n", store.saved.single())
    }

    @Test fun propagatesTypedFailures() {
        store.result = ExportResult.Failed("disco lleno")
        assertEquals(ExportResult.Failed("disco lleno"), export())
        store.result = ExportResult.StoragePermissionRequired
        assertEquals(ExportResult.StoragePermissionRequired, export())
    }

    @Test fun unreadableJournalStillExportsDiagnostics() {
        log.readFailure = "EIO"
        assertEquals(ExportResult.Saved("/Download/report.txt"), export())
        val content = store.saved.single().second
        assertTrue(content.startsWith("M|"))
        assertTrue(content.contains("[registro no disponible: EIO]"))
    }
}

class RecordEventTest {
    private val log = FakeEventLog()
    private val record = RecordEvent(log)

    @Test fun formatsBroadcastWithExtras() {
        record.broadcast("com.qf.action.ACC_OFF", mapOf("a" to "1", "b" to "2"))
        assertEquals("Broadcast com.qf.action.ACC_OFF: a=1, b=2", log.lines.single())
    }

    @Test fun formatsBroadcastWithoutExtras() {
        record.broadcast("android.intent.action.SCREEN_ON", emptyMap())
        assertEquals("Broadcast android.intent.action.SCREEN_ON", log.lines.single())
    }

    @Test fun recordsUnexpectedError() {
        record.unexpectedError("receptor", IllegalStateException("boom"))
        assertEquals("Error inesperado en receptor: java.lang.IllegalStateException: boom", log.lines.single())
    }

    @Test fun neverThrowsWhenTheJournalRejectsEverything() {
        log.rejectAppends = true
        record.processStarted()
        record.broadcast("x", mapOf("a" to "b"))
        record.unexpectedError("x", RuntimeException())
    }

    @Test fun recordsBootStartFailure() {
        record.bootStartFailed("bloqueado")
        assertEquals("No se inició servicio tras arranque: bloqueado", log.lines.single())
    }
}

class DashboardTest {
    private val log = FakeEventLog()
    private val status = TrackingStatus()
    private val dashboard = Dashboard(FakeDiagnosticsSource(), log, status)

    @Test fun combinesServiceStatusWithRecentEvents() = runTest {
        repeat(40) { log.append("e$it") }
        status.markRunning(true)
        val state = dashboard.observe().first()
        assertTrue(state.serviceRunning)
        val lines = (state.log as LogRead.Lines).lines
        assertEquals(30, lines.size)
        assertEquals("e39", lines.last())
    }

    @Test fun surfacesUnreadableJournal() = runTest {
        log.readFailure = "EIO"
        assertEquals(LogRead.Unreadable("EIO"), dashboard.observe().first().log)
    }

    @Test fun surfacesFailingWrites() = runTest {
        log.health.value = LogHealth.WriteFailing("sin espacio")
        assertEquals(LogHealth.WriteFailing("sin espacio"), dashboard.observe().first().logHealth)
    }
}
