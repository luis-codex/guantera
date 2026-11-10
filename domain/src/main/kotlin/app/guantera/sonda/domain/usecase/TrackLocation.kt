package app.guantera.sonda.domain.usecase

import app.guantera.sonda.domain.model.GpsFix
import app.guantera.sonda.domain.model.LocationEvent
import app.guantera.sonda.domain.model.StartResult
import app.guantera.sonda.domain.port.Clock
import app.guantera.sonda.domain.port.EventLog
import app.guantera.sonda.domain.port.LocationSource
import app.guantera.sonda.domain.rule.FixSamplingPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TrackLocation(
    private val source: LocationSource,
    private val eventLog: EventLog,
    private val clock: Clock,
    private val samplingPolicy: FixSamplingPolicy,
) {
    private val mutableLatestFix = MutableStateFlow<GpsFix?>(null)
    private var firstFixSeen = false
    private var lastRecordedAtMillis: Long? = null
    private var lastLoggedFailure: StartResult? = null

    val latestFix: StateFlow<GpsFix?> = mutableLatestFix
    val hasPermission: Boolean get() = source.hasPermission

    /** Registers the GPS listener if it is not active; a no-op when it already is. Repeated identical failures are logged once. */
    fun resume(): StartResult {
        val result = source.start()
        if (result.isFailure() && result == lastLoggedFailure) return result
        lastLoggedFailure = result.takeIf { it.isFailure() }
        describe(result)?.let(eventLog::append)
        return result
    }

    /** Drops and registers the listener again: after a suspension the old registration may be dead. */
    fun rearm(): StartResult {
        eventLog.append("GPS re-registro solicitado al despertar")
        source.stop()
        return resume()
    }

    /** Ends the tracking session; the next one reports its own first fix and no stale position. */
    fun stop() {
        source.stop()
        firstFixSeen = false
        lastRecordedAtMillis = null
        lastLoggedFailure = null
        mutableLatestFix.value = null
    }

    suspend fun collectEvents() = source.events.collect(::handle)

    private fun handle(event: LocationEvent) {
        when (event) {
            is LocationEvent.Fix -> handleFix(event.fix)
            is LocationEvent.ProviderStateChanged -> eventLog.append(
                "Proveedor ${if (event.enabled) "habilitado" else "deshabilitado"}: ${event.provider}",
            )
        }
    }

    private fun handleFix(fix: GpsFix) {
        mutableLatestFix.value = fix
        if (!firstFixSeen) {
            firstFixSeen = true
            eventLog.append("Primer fix GPS")
        }
        val now = clock.elapsedRealtimeMillis()
        if (samplingPolicy.shouldRecord(lastRecordedAtMillis, now)) {
            lastRecordedAtMillis = now
            eventLog.append(describe(fix))
        }
    }

    private fun StartResult.isFailure() = this is StartResult.PermissionDenied || this is StartResult.Failed

    private fun describe(result: StartResult): String? = when (result) {
        is StartResult.Started -> "GPS listener iniciado; proveedor activo=${result.gpsProviderEnabled}"
        StartResult.PermissionDenied -> "GPS sin permiso ACCESS_FINE_LOCATION"
        is StartResult.Failed -> "Error al iniciar GPS: ${result.reason}"
        StartResult.AlreadyActive -> null
    }

    private fun describe(fix: GpsFix): String {
        val speed = fix.speedMetersPerSecond?.let { "$it m/s" } ?: "sin velocidad"
        val accuracy = fix.accuracyMeters?.let { "$it m" } ?: "sin precisión"
        return "Fix GPS lat=${fix.latitude}, lon=${fix.longitude}, velocidad=$speed, " +
            "precisión=$accuracy, satélites usados=${fix.usedSatellites ?: UNKNOWN_SATELLITES}"
    }

    private companion object {
        const val UNKNOWN_SATELLITES = -1
    }
}
