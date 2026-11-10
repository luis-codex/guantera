package app.guantera.domain.odometer

/** Only ever added, never edited (R13). */
data class OdometerCalibration(val dateUtcMs: Long, val odoRealKm: Double, val gpsAccumulatedKm: Double)

object OdometerRules {
    /** R12 and R14: null without a calibration. */
    fun current(lastCalibration: OdometerCalibration?, gpsAccumulatedKm: Double): Double? =
        lastCalibration?.let { it.odoRealKm + (gpsAccumulatedKm - it.gpsAccumulatedKm) }
}
