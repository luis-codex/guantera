package app.guantera.presentation.port

import kotlinx.coroutines.flow.StateFlow

/** Boundary between the presentation layer and wherever the car's data comes from. */
interface GuanteraData {
    /** True while the app shows invented data; the top bar must then say so. */
    val isSample: Boolean
    val status: StateFlow<VehicleStatus>
    val home: StateFlow<HomeData>
    val profile: StateFlow<VehicleProfile>

    /** Non-null while the car is EN_MOVIMIENTO, which replaces the whole app with driving mode. */
    val driving: StateFlow<DrivingData?>

    /** Every closed trip, newest first. */
    val trips: StateFlow<List<TripRecord>>

    /** The current parking spot (R26), or null when none was ever saved. */
    val parkingSpot: StateFlow<ParkingDetail?>

    /** "Ya estacioné": closes the current trip and saves the parking spot. */
    fun confirmParked()

    /** "Guardar acá ahora". Rejected while moving or without a valid GPS position (R27). */
    fun saveSpotHere(): SaveSpotResult

    /** Replaces the note of the current spot. A blank note removes it. */
    fun saveSpotNote(note: String)
}

data class GeoPoint(val latitude: Double, val longitude: Double)

enum class TripClosure { Automatic, Manual, Recovered }

data class TripRecord(
    val id: Long,
    val startMillis: Long,
    val endMillis: Long,
    val distanceKm: Double,
    val start: GeoPoint,
    val end: GeoPoint,
    val closure: TripClosure,
)

data class ParkingDetail(
    val savedAtMillis: Long,
    val location: GeoPoint,
    val accuracyMeters: Int,
    val note: String?,
    /** The GPS was lost before parking, so this is where the signal was last seen. */
    val isLastSignalPoint: Boolean,
)

enum class SaveSpotResult { Saved, NoValidFix, CarMoving }

/** Only exists in demonstration builds, so production code cannot start a fake trip. */
interface DemoControls {
    fun startSimulatedTrip()
    fun stopSimulatedTrip()
}

enum class GpsStatus { Good, Searching, Off, NoPermission }

enum class MotionStatus { Stopped, Moving, Unknown }

data class VehicleStatus(val gps: GpsStatus, val motion: MotionStatus)

data class VehicleProfile(val name: String, val odometerKm: Int, val currency: String)

data class TodaySummary(val kilometers: Double, val tripCount: Int)

enum class MaintenanceLevel { Overdue, Soon, UpToDate, NoData }

sealed interface Remaining {
    data class Km(val value: Int) : Remaining
    data class Days(val value: Int) : Remaining
}

data class MaintenanceAlert(val itemName: String, val level: MaintenanceLevel, val remaining: Remaining?)

data class ParkedSpot(val savedAtMillis: Long, val note: String?)

/** Problems from specification section 6, declared from highest to lowest priority. */
enum class AppNotice {
    NoLocationPermission,
    GpsOff,
    UntrustedClock,
    SearchingGps,
    OdometerMissing,
    RecordingGap,
    StorageFull,
    ExportOverdue,
}

data class HomeData(
    val today: TodaySummary,
    /** Most urgent item, or null when everything is up to date. */
    val urgentAlert: MaintenanceAlert?,
    val parked: ParkedSpot?,
    val notices: List<AppNotice>,
)

data class DrivingData(
    val distanceKm: Double,
    val elapsedSeconds: Long,
    /** Seconds spent in STOPPING, or null while the car is moving. */
    val stoppingSeconds: Long?,
    val urgentAlert: MaintenanceAlert?,
    val isSimulated: Boolean,
)
