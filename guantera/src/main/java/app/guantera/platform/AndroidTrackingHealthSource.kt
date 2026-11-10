package app.guantera.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import app.guantera.domain.motion.TrackingHealth
import app.guantera.domain.port.TrackingHealthSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidTrackingHealthSource(
    private val context: Context,
    private val locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager,
) : TrackingHealthSource {

    private val _health = MutableStateFlow(queryCurrentHealth())
    override val health: StateFlow<TrackingHealth> = _health.asStateFlow()

    fun refresh() {
        _health.value = queryCurrentHealth()
    }

    fun onPermissionChanged(granted: Boolean) {
        _health.value = _health.value.copy(hasPermission = granted)
    }

    fun onProviderStateChanged(provider: String, enabled: Boolean) {
        if (provider == LocationManager.GPS_PROVIDER) {
            _health.value = _health.value.copy(gpsEnabled = enabled)
        }
    }

    private fun queryCurrentHealth(): TrackingHealth {
        val hasPermission = try {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        val gpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        return TrackingHealth(hasPermission = hasPermission, gpsEnabled = gpsEnabled)
    }
}
