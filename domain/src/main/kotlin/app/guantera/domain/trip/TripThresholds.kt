package app.guantera.domain.trip

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Values from docs/investigacion/deteccion-viajes.md, section 1. */
object TripThresholds {
    const val V_MIN_MOVE = 1.4
    const val V_STOP = 0.8
    const val ACCURACY_MAX = 25.0
    const val MAX_SPEED_PLAUSIBLE = 50.0
    const val MIN_STEP_DIST = 2.0
    const val START_MIN_DIST = 10.0
    const val STOP_TIMEOUT_MS = 180_000L
    const val TUNNEL_TIMEOUT_MS = 300_000L
    const val START_TIMEOUT_MS = 15_000L
    const val TRIP_MIN_DIST = 200.0
    const val TRIP_MIN_DURATION_S = 120L
    const val HEARTBEAT_MS = 10_000L

    /** R7 and R27: how old a fix can be and still say where the car is now. */
    const val FIX_FRESH_MS = 30_000L

    /** R11: how long the car must be in STOPPING before "Ya estacioné" is accepted. */
    const val MANUAL_CLOSE_MIN_STOPPED_MS = 20_000L
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }
}
