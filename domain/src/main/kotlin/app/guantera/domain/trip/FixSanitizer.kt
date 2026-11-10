package app.guantera.domain.trip

/** deteccion-viajes.md, section 2. Runs before the machine, in every state. */
object FixSanitizer {
    private const val MILLIS_PER_SECOND = 1000.0

    /** [previous] is the last fix accepted here, whatever the machine state was. Null rejects [fix]. */
    fun sanitize(fix: LocationFix, previous: CleanFix?): CleanFix? {
        val accuracy = fix.accuracyMeters ?: return null
        if (accuracy.isNaN() || accuracy > TripThresholds.ACCURACY_MAX) return null
        if (!fix.point.isOnEarth()) return null
        if (previous != null && fix.monoMs <= previous.monoMs) return null
        return CleanFix(fix.point, speedOf(fix, previous), accuracy, fix.utcMs, fix.monoMs)
    }

    private fun speedOf(fix: LocationFix, previous: CleanFix?): Double {
        fix.speedMps?.takeUnless { it.isNaN() }?.let { return it }
        if (previous == null) return 0.0
        val seconds = (fix.monoMs - previous.monoMs) / MILLIS_PER_SECOND
        return Geo.distanceMeters(previous.point, fix.point) / seconds
    }

    private fun GeoPoint.isOnEarth() = latitude in -90.0..90.0 && longitude in -180.0..180.0
}
