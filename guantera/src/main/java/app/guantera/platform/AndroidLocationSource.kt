package app.guantera.platform

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import app.guantera.domain.port.LocationSource
import app.guantera.domain.trip.GeoPoint
import app.guantera.domain.trip.LocationFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidLocationSource(
    private val context: Context,
    private val locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager,
    private val looper: Looper = Looper.getMainLooper(),
    private val onProviderChanged: ((provider: String, enabled: Boolean) -> Unit)? = null,
) : LocationSource {

    private val lock = Any()
    @Volatile
    private var activeListener: LocationListener? = null

    override val fixes: Flow<LocationFix> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toLocationFix())
            }

            override fun onProviderEnabled(provider: String) {
                onProviderChanged?.invoke(provider, true)
            }

            override fun onProviderDisabled(provider: String) {
                onProviderChanged?.invoke(provider, false)
            }

            @Deprecated("Deprecated by framework")
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        synchronized(lock) {
            activeListener = listener
            requestUpdatesSafe(listener)
        }

        awaitClose {
            synchronized(lock) {
                removeUpdatesSafe(listener)
                if (activeListener === listener) {
                    activeListener = null
                }
            }
        }
    }

    /**
     * Rearms the GPS listener: drops updates and registers anew. Safe to call across suspension.
     */
    fun rearm() {
        synchronized(lock) {
            val listener = activeListener ?: return
            removeUpdatesSafe(listener)
            requestUpdatesSafe(listener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdatesSafe(listener: LocationListener) {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                looper,
            )
        } catch (_: Exception) {
            // Never throws across the port
        }
    }

    private fun removeUpdatesSafe(listener: LocationListener) {
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
            // Never throws across the port
        }
    }

    companion object {
        const val MIN_INTERVAL_MS = 1000L
        const val MIN_DISTANCE_M = 0.0f

        fun Location.toLocationFix(): LocationFix {
            val monoMs = elapsedRealtimeNanos / 1_000_000L
            return LocationFix(
                point = GeoPoint(latitude, longitude),
                speedMps = if (hasSpeed()) speed.toDouble() else null,
                accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
                utcMs = time,
                monoMs = monoMs,
            )
        }
    }
}
