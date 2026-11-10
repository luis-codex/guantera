package app.guantera.domain.motion

import app.guantera.domain.trip.CleanFix
import app.guantera.domain.trip.TripPhase
import app.guantera.domain.trip.TripThresholds
import java.util.concurrent.atomic.AtomicReference

enum class DrivingState { DETENIDO, EN_MOVIMIENTO, DESCONOCIDO }

enum class GpsStatus { GOOD, SEARCHING, OFF, NO_PERMISSION }

data class TrackingHealth(val hasPermission: Boolean, val gpsEnabled: Boolean) {
    val canTrack: Boolean get() = hasPermission && gpsEnabled
}

/** R6 to R8, and the health column of specification section 5. */
object DrivingStateRules {
    fun evaluate(phase: TripPhase, lastClean: CleanFix?, nowMonoMs: Long, health: TrackingHealth): DrivingState = when {
        phase != TripPhase.Parked -> DrivingState.EN_MOVIMIENTO
        health.canTrack && isStill(lastClean, nowMonoMs) -> DrivingState.DETENIDO
        else -> DrivingState.DESCONOCIDO
    }

    fun gpsStatus(health: TrackingHealth, lastClean: CleanFix?, nowMonoMs: Long): GpsStatus = when {
        !health.hasPermission -> GpsStatus.NO_PERMISSION
        !health.gpsEnabled -> GpsStatus.OFF
        isFresh(lastClean, nowMonoMs) -> GpsStatus.GOOD
        else -> GpsStatus.SEARCHING
    }

    fun isFresh(fix: CleanFix?, nowMonoMs: Long): Boolean =
        fix != null && nowMonoMs - fix.monoMs <= TripThresholds.FIX_FRESH_MS

    private fun isStill(fix: CleanFix?, nowMonoMs: Long): Boolean =
        fix != null && isFresh(fix, nowMonoMs) &&
            fix.accuracyMeters <= TripThresholds.ACCURACY_MAX && fix.speedMps < TripThresholds.V_STOP
}

/** R9: the "¿El auto está estacionado?" answer. Kept in memory, so a reboot asks again. */
class ManualConfirmation {
    private val validUntilMono = AtomicReference<Long?>(null)

    fun grant(nowMonoMs: Long) = validUntilMono.set(nowMonoMs + VALIDITY_MS)

    fun revoke() = validUntilMono.set(null)

    fun isValid(nowMonoMs: Long): Boolean = validUntilMono.get()?.let { nowMonoMs < it } ?: false

    companion object { const val VALIDITY_MS = 10L * 60 * 1000 }
}
