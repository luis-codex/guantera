package app.guantera.storage

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.guantera.domain.backup.BackupData
import app.guantera.domain.expense.Expense
import app.guantera.domain.expense.ExpenseCategory
import app.guantera.domain.maintenance.DataOrigin
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.maintenance.ServiceRecord
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.parking.ParkingOrigin
import app.guantera.domain.parking.ParkingSpot
import app.guantera.domain.settings.Settings
import app.guantera.domain.time.CalendarDay
import app.guantera.domain.trip.ActiveTrip
import app.guantera.domain.trip.ActiveTripRecord
import app.guantera.domain.trip.CleanFix
import app.guantera.domain.trip.GeoPoint
import app.guantera.domain.trip.Heartbeat
import app.guantera.domain.trip.Trip
import app.guantera.domain.trip.TripClosure
import app.guantera.domain.usecase.BackupDataAccess
import java.io.File

internal val appContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
internal val dbFile: File get() = appContext.getDatabasePath("guantera.db")
internal fun day(epoch: Int) = CalendarDay.fromEpochDay(epoch)
internal val point = GeoPoint(-34.6037, -58.3816)

internal fun trip(id: Long = 0, start: Long = 1_000, distance: Double = 1234.5) =
    Trip(id, start, start + 60_000, distance, point, GeoPoint(-34.7, -58.5), TripClosure.AUTOMATIC)

internal fun expense(id: Long = 0, date: Int = 100, amount: Long = 1500, note: String? = null) =
    Expense(id, ExpenseCategory.COMBUSTIBLE, amount, day(date), note, null)

internal fun spot(id: Long = 0, instant: Long = 5_000, note: String? = null) =
    ParkingSpot(id, point, 8.0, instant, ParkingOrigin.TRIP_END, note, false)

internal fun item(id: Long = 0, name: String = "Aceite") =
    MaintenanceItem(id, name, 10_000, 12, null, 50_000.0, day(90), DataOrigin.OWN)

internal fun activeRecord(heartbeatMono: Long = 42): ActiveTripRecord {
    val fix = CleanFix(point, 11.5, 6.0, 9_000, 8_000)
    return ActiveTripRecord(ActiveTrip(1_000, 500, point, 77.5, fix, fix), null, Heartbeat(heartbeatMono, 9_000, 3))
}

/** Every entity, with the values that tend to break a round trip through JSON. */
internal fun richData() = BackupData(
    trips = listOf(trip(11, 2_000, 1.0), trip(12, 3_000, 0.1 + 0.2), trip(13, Long.MAX_VALUE / 4, 0.0)),
    calibrations = listOf(OdometerCalibration(10, 100.25, 0.0), OdometerCalibration(20, 1e15, 1.5e-7)),
    items = listOf(item(5), MaintenanceItem(6, "Fecha fija \"ñ\" \\ / \n", null, null, day(400), null, null, DataOrigin.VERIFIED)),
    services = listOf(ServiceRecord(7, 5, day(95), null, "null"), ServiceRecord(8, 99, day(96), 3.5, null)),
    expenses = listOf(expense(21, 200, Long.MAX_VALUE / 2, "café ☕ 😀"), expense(22, 100, 0, null)),
    parkingSpots = listOf(spot(31, 10, "nota rara"), spot(32, 20, "")),
    settings = Settings("ARS", openAtBoot = true, firstUseComplete = true, recordingGapPending = true, lastExportUtcMs = 77, catalogRestoredUtcMs = null),
)

internal fun bigData(trips: Int = 3_000) = BackupData(
    (1..trips).map { trip(it.toLong(), it * 1_000L) }, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Settings(),
)

internal fun StorageComponents.access() = BackupDataAccess(trips, odometer, maintenance, expenses, parking, settings, transactions)

/** BackupData compares lists in order, but the order of the tables is not part of the contract. */
internal fun BackupData.sorted() = copy(
    trips = trips.sortedBy { it.id },
    items = items.sortedBy { it.id },
    services = services.sortedBy { it.id },
    expenses = expenses.sortedBy { it.id },
    parkingSpots = parkingSpots.sortedBy { it.id },
)

internal fun resetStorageFiles() {
    val context = appContext
    context.deleteDatabase("guantera.db")
    dbFile.parentFile?.listFiles { f -> f.name.startsWith("quarantine-") }?.forEach { it.deleteRecursively() }
    context.getSharedPreferences("storage-health", Context.MODE_PRIVATE).edit().clear().commit()
}

internal fun quarantined(): List<File> =
    dbFile.parentFile?.listFiles { f -> f.isDirectory && f.name.startsWith("quarantine-") }?.toList().orEmpty()

internal fun newBackupDir(name: String) = File(appContext.filesDir, "qa/$name").apply { deleteRecursively(); mkdirs() }
