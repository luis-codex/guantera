package app.guantera.sonda.domain

import app.guantera.sonda.domain.model.GpsFix
import app.guantera.sonda.domain.model.LocationEvent
import app.guantera.sonda.domain.model.StartResult
import app.guantera.sonda.domain.rule.IntervalSamplingPolicy
import app.guantera.sonda.domain.usecase.TrackLocation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackLocationTest {
    private val source = FakeLocationSource()
    private val log = FakeEventLog()
    private val clock = FakeClock(elapsed = 100_000)
    private val track = TrackLocation(source, log, clock, IntervalSamplingPolicy(30_000))
    private val fix = GpsFix(1.5, 2.5, speedMetersPerSecond = 3f, accuracyMeters = 20f, usedSatellites = 7)

    @Test fun logsStartOutcome() {
        track.resume()
        assertEquals(listOf("GPS listener iniciado; proveedor activo=true"), log.lines)
    }

    @Test fun staysSilentWhenAlreadyActive() {
        track.resume()
        log.lines.clear()
        assertEquals(StartResult.AlreadyActive, track.resume())
        assertTrue(log.lines.isEmpty())
    }

    @Test fun logsMissingPermission() {
        source.hasPermission = false
        track.resume()
        assertEquals(listOf("GPS sin permiso ACCESS_FINE_LOCATION"), log.lines)
    }

    @Test fun logsStartFailureReason() {
        source.startFailure = "sin proveedor"
        assertEquals(StartResult.Failed("sin proveedor"), track.resume())
        assertEquals(listOf("Error al iniciar GPS: sin proveedor"), log.lines)
    }

    @Test fun logsFirstFixThenSamplesByPolicy() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { track.collectEvents() }
        source.flow.emit(LocationEvent.Fix(fix))
        clock.elapsed += 10_000
        source.flow.emit(LocationEvent.Fix(fix))
        clock.elapsed += 20_000
        source.flow.emit(LocationEvent.Fix(fix))
        job.cancel()

        assertEquals("Primer fix GPS", log.lines[0])
        assertEquals(
            "Fix GPS lat=1.5, lon=2.5, velocidad=3.0 m/s, precisión=20.0 m, satélites usados=7",
            log.lines[1],
        )
        assertEquals(3, log.lines.size)
        assertEquals(fix, track.latestFix.value)
    }

    @Test fun describesFixWithoutOptionalData() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { track.collectEvents() }
        source.flow.emit(LocationEvent.Fix(GpsFix(1.0, 2.0, null, null, null)))
        job.cancel()
        assertEquals("Fix GPS lat=1.0, lon=2.0, velocidad=sin velocidad, precisión=sin precisión, satélites usados=-1", log.lines[1])
    }

    @Test fun logsProviderChanges() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { track.collectEvents() }
        source.flow.emit(LocationEvent.ProviderStateChanged("gps", enabled = false))
        source.flow.emit(LocationEvent.ProviderStateChanged("gps", enabled = true))
        job.cancel()
        assertEquals(listOf("Proveedor deshabilitado: gps", "Proveedor habilitado: gps"), log.lines)
    }

    @Test fun stopReleasesTheSource() {
        track.resume()
        track.stop()
        assertEquals(false, source.active)
    }

    @Test fun rearmRegistersAgainEvenWhenAlreadyActive() {
        track.resume()
        val result = track.rearm()
        assertEquals(StartResult.Started(true), result)
        assertEquals(2, source.registrations)
        assertEquals(
            listOf(
                "GPS listener iniciado; proveedor activo=true",
                "GPS re-registro solicitado al despertar",
                "GPS listener iniciado; proveedor activo=true",
            ),
            log.lines,
        )
    }

    @Test fun repeatedIdenticalFailureIsLoggedOnce() {
        source.startFailure = "sin proveedor"
        repeat(5) { track.resume() }
        assertEquals(1, log.lines.size)
    }

    @Test fun retryAfterFailureRecovers() {
        source.startFailure = "sin proveedor"
        track.resume()
        source.startFailure = null
        assertEquals(StartResult.Started(true), track.resume())
        assertTrue(source.active)
    }

    @Test fun missingPermissionIsLoggedOncePerStreak() {
        source.hasPermission = false
        repeat(3) { track.resume() }
        assertEquals(1, log.lines.size)
    }

    @Test fun newSessionReportsFirstFixAgainAndForgetsOldPosition() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { track.collectEvents() }
        source.flow.emit(LocationEvent.Fix(fix))
        track.stop()
        assertNull(track.latestFix.value)
        log.lines.clear()
        source.flow.emit(LocationEvent.Fix(fix))
        job.cancel()
        assertEquals("Primer fix GPS", log.lines.first())
        assertTrue(log.lines.any { it.startsWith("Fix GPS") })
    }

    @Test fun keepsWorkingWhenTheJournalRejectsLines() = runTest(UnconfinedTestDispatcher()) {
        log.rejectAppends = true
        val job = launch { track.collectEvents() }
        track.resume()
        source.flow.emit(LocationEvent.Fix(fix))
        job.cancel()
        assertEquals(fix, track.latestFix.value)
    }
}
