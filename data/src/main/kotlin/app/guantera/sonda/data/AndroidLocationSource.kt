package app.guantera.sonda.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import app.guantera.sonda.domain.model.GpsFix
import app.guantera.sonda.domain.model.LocationEvent
import app.guantera.sonda.domain.model.StartResult
import app.guantera.sonda.domain.port.LocationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class AndroidLocationSource(private val context: Context) : LocationSource {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainLooper = Looper.getMainLooper()
    private val mutableEvents = MutableSharedFlow<LocationEvent>(extraBufferCapacity = EVENT_BUFFER)
    private var active = false
    private var usedSatellites: Int? = null

    override val events: Flow<LocationEvent> = mutableEvents

    override val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            mutableEvents.tryEmit(LocationEvent.Fix(location.toFix()))
        }

        override fun onProviderEnabled(provider: String) {
            mutableEvents.tryEmit(LocationEvent.ProviderStateChanged(provider, enabled = true))
        }

        override fun onProviderDisabled(provider: String) {
            mutableEvents.tryEmit(LocationEvent.ProviderStateChanged(provider, enabled = false))
        }

        @Deprecated("Deprecated by framework")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            usedSatellites = (0 until status.satelliteCount).count(status::usedInFix)
        }
    }

    override fun start(): StartResult {
        if (!hasPermission) return StartResult.PermissionDenied
        if (active) return StartResult.AlreadyActive
        return try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_INTERVAL_MILLIS, MIN_DISTANCE_METERS, listener, mainLooper)
            manager.registerGnssStatusCallback(gnssCallback, Handler(mainLooper))
            active = true
            StartResult.Started(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        } catch (e: RuntimeException) {
            manager.removeUpdates(listener)
            StartResult.Failed(e.toString())
        }
    }

    override fun stop() {
        manager.removeUpdates(listener)
        manager.unregisterGnssStatusCallback(gnssCallback)
        active = false
    }

    private fun Location.toFix() = GpsFix(
        latitude = latitude,
        longitude = longitude,
        speedMetersPerSecond = speed.takeIf { hasSpeed() },
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        usedSatellites = usedSatellites,
    )

    private companion object {
        const val MIN_INTERVAL_MILLIS = 1000L
        const val MIN_DISTANCE_METERS = 0f
        const val EVENT_BUFFER = 16
    }
}
