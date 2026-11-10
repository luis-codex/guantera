package app.guantera.domain.trip

data class GeoPoint(val latitude: Double, val longitude: Double)

/** A fix as the GPS delivered it. Speed and accuracy are null when the receiver did not report them. */
data class LocationFix(
    val point: GeoPoint,
    val speedMps: Double?,
    val accuracyMeters: Double?,
    val utcMs: Long,
    val monoMs: Long,
)

/** A fix that passed sanitization: it has an accuracy and a speed, reported or inferred. */
data class CleanFix(
    val point: GeoPoint,
    val speedMps: Double,
    val accuracyMeters: Double,
    val utcMs: Long,
    val monoMs: Long,
)

enum class TripClosure { AUTOMATIC, MANUAL, RECOVERED }

/** A closed trip (R3): immutable once stored. [id] is 0 until the repository assigns one. */
data class Trip(
    val id: Long,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val distanceMeters: Double,
    val startPoint: GeoPoint,
    val endPoint: GeoPoint,
    val closure: TripClosure,
) {
    init {
        require(endUtcMs >= startUtcMs) { "R3: a trip cannot end before it starts" }
        require(distanceMeters >= 0) { "R3: distance cannot be negative" }
    }

    val distanceKm: Double get() = distanceMeters / METERS_PER_KM
    val durationMs: Long get() = endUtcMs - startUtcMs

    private companion object { const val METERS_PER_KM = 1000.0 }
}

/** The trip in progress. There is at most one (R2), which is why the repository keeps it in a single slot. */
data class ActiveTrip(
    val startUtcMs: Long,
    val startMonoMs: Long,
    val startPoint: GeoPoint,
    val distanceMeters: Double,
    val lastDistanceFix: CleanFix,
    val lastValidFix: CleanFix,
)

data class Heartbeat(val monoMs: Long, val utcMs: Long, val bootCount: Int?)

/** What is saved every 10 s so a trip survives a power cut. */
data class ActiveTripRecord(
    val trip: ActiveTrip,
    /** Non-null when the machine was in STOPPING. */
    val stopCandidateMonoMs: Long?,
    val heartbeat: Heartbeat,
)
