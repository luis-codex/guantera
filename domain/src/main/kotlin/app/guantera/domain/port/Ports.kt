package app.guantera.domain.port

import app.guantera.domain.backup.BackupData
import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.backup.StoredBackup
import app.guantera.domain.expense.Expense
import app.guantera.domain.maintenance.MaintenanceAlert
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.maintenance.ServiceRecord
import app.guantera.domain.motion.TrackingHealth
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.parking.ParkingSpot
import app.guantera.domain.settings.Settings
import app.guantera.domain.time.DayRange
import app.guantera.domain.time.FixTime
import app.guantera.domain.trip.ActiveTripRecord
import app.guantera.domain.trip.LocationFix
import app.guantera.domain.trip.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class StorageFailure { FULL, IO }

/**
 * The only exception that crosses a repository port: a write that failed. It is what makes
 * [TransactionRunner] roll back; use cases turn it into a typed result.
 */
class StorageException(val failure: StorageFailure, cause: Throwable? = null) : Exception(failure.name, cause)

data class PageRequest(val limit: Int, val offset: Int)

/** Half-open UTC window; a null bound leaves that side open. */
data class UtcWindow(val fromMs: Long?, val toExclusiveMs: Long?)

interface LocationSource {
    /** 1 Hz with minDistance 0 while collected; cancelling stops the GPS. Never throws. */
    val fixes: Flow<LocationFix>
}

interface TrackingHealthSource {
    val health: StateFlow<TrackingHealth>
}

interface Clock {
    fun utcMs(): Long

    /** Monotonic, keeps counting during suspension, restarts at zero on reboot. */
    fun monoMs(): Long

    /** Null when it cannot be read. */
    fun bootCount(): Int?

    /** Offset of the local zone at that instant. */
    fun utcOffsetMs(atUtcMs: Long): Int
}

interface TransactionRunner {
    /** All or nothing: rolls back and rethrows if [block] throws. */
    suspend fun <T> run(block: suspend () -> T): T
}

interface TripRepository {
    /** Emits on collection and after every committed change to closed trips. */
    val changes: Flow<Unit>
    suspend fun activeTrip(): ActiveTripRecord?

    /** R2: replaces the single active slot. */
    suspend fun saveActive(record: ActiveTripRecord)
    suspend fun clearActive()

    /** R3: closed trips are only ever inserted. Returns the new id. */
    suspend fun insertClosed(trip: Trip): Long

    /** By start instant within [window], newest first. A null [page] returns all of them. */
    suspend fun closed(window: UtcWindow, page: PageRequest? = null): List<Trip>
    suspend fun find(id: Long): Trip?
    suspend fun totalMeters(window: UtcWindow): Double

    /** Replaces every closed trip and clears the active slot. */
    suspend fun replaceAll(trips: List<Trip>)
}

interface OdometerRepository {
    val changes: Flow<Unit>
    suspend fun latest(): OdometerCalibration?
    suspend fun add(calibration: OdometerCalibration)
    suspend fun all(): List<OdometerCalibration>
    suspend fun replaceAll(calibrations: List<OdometerCalibration>)
}

interface MaintenanceRepository {
    val changes: Flow<Unit>
    suspend fun items(): List<MaintenanceItem>
    suspend fun find(id: Long): MaintenanceItem?

    /** Creates the item when its id is 0, otherwise replaces it. Returns the id. */
    suspend fun save(item: MaintenanceItem): Long

    /** Removes only the item: its service records and expenses stay. */
    suspend fun delete(id: Long)
    suspend fun addService(record: ServiceRecord): Long
    suspend fun services(): List<ServiceRecord>
    suspend fun replaceAll(items: List<MaintenanceItem>, services: List<ServiceRecord>)
}

interface ExpenseRepository {
    val changes: Flow<Unit>
    suspend fun find(id: Long): Expense?

    /** Creates the expense when its id is 0, otherwise replaces it. Returns the id. */
    suspend fun save(expense: Expense): Long
    suspend fun delete(id: Long)

    /** By date within [range], newest first. A null [page] returns all of them. */
    suspend fun inRange(range: DayRange, page: PageRequest? = null): List<Expense>
    suspend fun totalMinor(range: DayRange): Long
    suspend fun replaceAll(expenses: List<Expense>)
}

interface ParkingRepository {
    val changes: Flow<Unit>

    /** R26: the spot with the most recent instant. */
    suspend fun current(): ParkingSpot?
    suspend fun insert(spot: ParkingSpot): Long
    suspend fun updateNote(id: Long, note: String?)
    suspend fun all(): List<ParkingSpot>
    suspend fun replaceAll(spots: List<ParkingSpot>)
}

interface SettingsRepository {
    val changes: Flow<Unit>
    suspend fun get(): Settings

    /** Atomic read-modify-write. */
    suspend fun update(transform: (Settings) -> Settings)

    /** The reference of R28. Only fix times are stored here, never the system clock. */
    suspend fun lastFixTime(): FixTime?
    suspend fun saveLastFixTime(time: FixTime)
}

interface AlertPublisher {
    /** Shows [alert] in the persistent notification; null removes it. Silent. */
    suspend fun publish(alert: MaintenanceAlert?)
}

sealed interface BackupWriteResult {
    /** Written, synced, read back with a matching checksum and only then renamed (R32, R33). */
    data class Validated(val backup: StoredBackup) : BackupWriteResult
    data object StorageFull : BackupWriteResult
    data class Failed(val reason: String) : BackupWriteResult
}

interface BackupStore {
    suspend fun save(data: BackupData, createdUtcMs: Long): BackupWriteResult

    /** The copies not set aside, in any order. */
    suspend fun list(): List<StoredBackup>
    suspend fun read(id: String): BackupReadResult
    suspend fun delete(ids: List<String>)

    /** R37: moves a damaged copy out of the way without deleting it. */
    suspend fun quarantine(id: String)
}

sealed interface ExternalExportResult {
    /** Read back from the drive and verified (R32). */
    data class Exported(val location: String) : ExternalExportResult
    data object DriveMissing : ExternalExportResult
    data object DriveFull : ExternalExportResult
    data class Failed(val reason: String) : ExternalExportResult
}

data class ExternalBackup(val reference: String, val displayName: String)

interface ExternalBackupTransfer {
    suspend fun export(data: BackupData, createdUtcMs: Long): ExternalExportResult
    suspend fun available(): List<ExternalBackup>
    suspend fun read(reference: String): BackupReadResult
}

/** What the checks found when the app opened. */
data class DataHealthReport(
    val databaseIntact: Boolean,
    val migrationFailed: Boolean,
    val consecutiveFailedOpens: Int,
)

interface DataHealthChecker {
    suspend fun check(): DataHealthReport

    /** R37: sets the database aside and leaves a new empty one. False if it could not be moved. */
    suspend fun quarantineDatabase(): Boolean
}
