package app.guantera.domain.parking

import app.guantera.domain.trip.GeoPoint

enum class ParkingOrigin { TRIP_END, MANUAL }

data class ParkingSpot(
    val id: Long,
    val point: GeoPoint,
    val accuracyMeters: Double,
    val instantUtcMs: Long,
    val origin: ParkingOrigin,
    val note: String?,
    val isLastSignalPoint: Boolean,
)
