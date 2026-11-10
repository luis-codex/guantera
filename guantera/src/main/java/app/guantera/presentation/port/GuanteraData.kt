package app.guantera.presentation.port

import kotlinx.coroutines.flow.StateFlow

/** Boundary between the presentation layer and wherever the car's data comes from. */
interface GuanteraData {
    val status: StateFlow<VehicleStatus>
    val home: StateFlow<HomeData>
    val profile: StateFlow<VehicleProfile>

    /** Non-null while the car is EN_MOVIMIENTO, which replaces the whole app with driving mode. */
    val driving: StateFlow<DrivingData?>

    /** Every closed trip, newest first. */
    val trips: StateFlow<List<TripRecord>>

    /** Closed trips that started from [fromMillis] (included) to [toMillis] (excluded), newest first. */
    fun tripsBetween(fromMillis: Long, toMillis: Long): List<TripRecord> =
        trips.value.filter { it.startMillis >= fromMillis && it.startMillis < toMillis }

    /** The current parking spot (R26), or null when none was ever saved. */
    val parkingSpot: StateFlow<ParkingDetail?>

    /** "Ya estacioné": closes the current trip; the spot is saved by the engine. */
    fun confirmParked()

    /** "Registro interrumpido": touching the banner closes it. */
    fun dismissRecordingGap()

    /** Answers "¿El auto está estacionado?" with yes (CU-04). Every form that was blocked can then be retried. */
    fun confirmStopped()

    /** "Guardar acá ahora". Rejected while moving or without a valid GPS position (R27). */
    suspend fun saveSpotHere(): SaveSpotResult

    /** Replaces the note of the current spot. A blank note removes it. */
    suspend fun saveSpotNote(note: String): FormOutcome
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

enum class SaveSpotResult { Saved, NoValidFix, CarMoving, NeedsConfirmation, Failed }

enum class GpsStatus { Good, Searching, Off, NoPermission }

enum class MotionStatus { Stopped, Moving, Unknown }

data class VehicleStatus(val gps: GpsStatus, val motion: MotionStatus)

/** [odometerKm] is null while no odometer was ever entered (R14): the screens say "sin datos". */
data class VehicleProfile(val name: String, val odometerKm: Int?, val currency: String)

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
)
