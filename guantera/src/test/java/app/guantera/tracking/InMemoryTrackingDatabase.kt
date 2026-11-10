package app.guantera.tracking

import app.guantera.domain.backup.BackupData
import app.guantera.domain.backup.BackupFile
import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.backup.CURRENT_BACKUP_SCHEMA
import app.guantera.domain.backup.StoredBackup
import app.guantera.domain.expense.Expense
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.maintenance.ServiceRecord
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.parking.ParkingSpot
import app.guantera.domain.port.BackupStore
import app.guantera.domain.port.BackupWriteResult
import app.guantera.domain.port.ExpenseRepository
import app.guantera.domain.port.MaintenanceRepository
import app.guantera.domain.port.OdometerRepository
import app.guantera.domain.port.PageRequest
import app.guantera.domain.port.ParkingRepository
import app.guantera.domain.port.SettingsRepository
import app.guantera.domain.port.StorageException
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.port.TransactionRunner
import app.guantera.domain.port.TripRepository
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.settings.Settings
import app.guantera.domain.time.DayRange
import app.guantera.domain.time.FixTime
import app.guantera.domain.trip.ActiveTripRecord
import app.guantera.domain.trip.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe reentrant in-memory database used by TrackingGraph when running stand-alone or before :storage is wired.
 */
class InMemoryTrackingDatabase {
    private val lock = ReentrantLock()
    private val version = MutableStateFlow(0)
    val changes: Flow<Unit> = version.map { }

    var simulatedFailure: StorageFailure? = null

    /** Thrown as is by every write, for what real adapters do that the port does not allow. */
    var simulatedThrowable: Throwable? = null

    private var nextId = 1L
    private var trips = listOf<Trip>()
    private var active: ActiveTripRecord? = null
    private var calibrations = listOf<OdometerCalibration>()
    private var items = listOf<MaintenanceItem>()
    private var services = listOf<ServiceRecord>()
    private var expenses = listOf<Expense>()
    private var spots = listOf<ParkingSpot>()
    private var settings = Settings()
    private var lastFix: FixTime? = null

    private fun checkWriteLocked() {
        simulatedThrowable?.let { throw it }
        simulatedFailure?.let { throw StorageException(it) }
        version.value++
    }

    private fun nextIdLocked() = nextId++

    val transactions = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T { lock.lock(); try {
            val savedTrips = trips
            val savedActive = active
            val savedCalibrations = calibrations
            val savedItems = items
            val savedServices = services
            val savedExpenses = expenses
            val savedSpots = spots
            val savedSettings = settings
            val savedLastFix = lastFix
            return try {
                block()
            } catch (e: StorageException) {
                trips = savedTrips
                active = savedActive
                calibrations = savedCalibrations
                items = savedItems
                services = savedServices
                expenses = savedExpenses
                spots = savedSpots
                settings = savedSettings
                lastFix = savedLastFix
                throw e
            }
        } finally { lock.unlock() } }
    }

    val tripRepo = object : TripRepository {
        override val changes: Flow<Unit> get() = this@InMemoryTrackingDatabase.changes

        override suspend fun activeTrip(): ActiveTripRecord? = lock.withLock { active }

        override suspend fun saveActive(record: ActiveTripRecord) = lock.withLock {
            checkWriteLocked()
            active = record
        }

        override suspend fun clearActive() = lock.withLock {
            checkWriteLocked()
            active = null
        }

        override suspend fun insertClosed(trip: Trip): Long = lock.withLock {
            checkWriteLocked()
            val id = nextIdLocked()
            trips = trips + trip.copy(id = id)
            id
        }

        override suspend fun closed(window: UtcWindow, page: PageRequest?): List<Trip> = lock.withLock {
            val found = trips.filter { inWindow(it, window) }.sortedByDescending { it.startUtcMs }
            if (page == null) found else found.drop(page.offset).take(page.limit)
        }

        override suspend fun find(id: Long): Trip? = lock.withLock {
            trips.firstOrNull { it.id == id }
        }

        override suspend fun totalMeters(window: UtcWindow): Double = lock.withLock {
            trips.filter { inWindow(it, window) }.sumOf { it.distanceMeters }
        }

        override suspend fun replaceAll(trips: List<Trip>) = lock.withLock {
            checkWriteLocked()
            this@InMemoryTrackingDatabase.trips = trips
            active = null
        }

        private fun inWindow(trip: Trip, window: UtcWindow): Boolean {
            val from = window.fromMs
            val to = window.toExclusiveMs
            return (from == null || trip.startUtcMs >= from) &&
                (to == null || trip.startUtcMs < to)
        }
    }

    val odometerRepo = object : OdometerRepository {
        override val changes: Flow<Unit> get() = this@InMemoryTrackingDatabase.changes

        override suspend fun latest(): OdometerCalibration? = lock.withLock {
            calibrations.maxByOrNull { it.dateUtcMs }
        }

        override suspend fun add(calibration: OdometerCalibration) = lock.withLock {
            checkWriteLocked()
            calibrations = calibrations + calibration
        }

        override suspend fun all(): List<OdometerCalibration> = lock.withLock { calibrations }

        override suspend fun replaceAll(calibrations: List<OdometerCalibration>) = lock.withLock {
            checkWriteLocked()
            this@InMemoryTrackingDatabase.calibrations = calibrations
        }
    }

    val maintenanceRepo = object : MaintenanceRepository {
        override val changes: Flow<Unit> get() = this@InMemoryTrackingDatabase.changes

        override suspend fun items(): List<MaintenanceItem> = lock.withLock { items }

        override suspend fun find(id: Long): MaintenanceItem? = lock.withLock {
            items.firstOrNull { it.id == id }
        }

        override suspend fun save(item: MaintenanceItem): Long = lock.withLock {
            checkWriteLocked()
            val id = if (item.id == 0L) nextIdLocked() else item.id
            items = items.filterNot { it.id == id } + item.copy(id = id)
            id
        }

        override suspend fun delete(id: Long) = lock.withLock {
            checkWriteLocked()
            items = items.filterNot { it.id == id }
        }

        override suspend fun addService(record: ServiceRecord): Long = lock.withLock {
            checkWriteLocked()
            val id = nextIdLocked()
            services = services + record.copy(id = id)
            id
        }

        override suspend fun services(): List<ServiceRecord> = lock.withLock { services }

        override suspend fun replaceAll(items: List<MaintenanceItem>, services: List<ServiceRecord>) = lock.withLock {
            checkWriteLocked()
            this@InMemoryTrackingDatabase.items = items
            this@InMemoryTrackingDatabase.services = services
        }
    }

    val expenseRepo = object : ExpenseRepository {
        override val changes: Flow<Unit> get() = this@InMemoryTrackingDatabase.changes

        override suspend fun find(id: Long): Expense? = lock.withLock {
            expenses.firstOrNull { it.id == id }
        }

        override suspend fun save(expense: Expense): Long = lock.withLock {
            checkWriteLocked()
            val id = if (expense.id == 0L) nextIdLocked() else expense.id
            expenses = expenses.filterNot { it.id == id } + expense.copy(id = id)
            id
        }

        override suspend fun delete(id: Long) = lock.withLock {
            checkWriteLocked()
            expenses = expenses.filterNot { it.id == id }
        }

        override suspend fun inRange(range: DayRange, page: PageRequest?): List<Expense> = lock.withLock {
            val found = expenses.filter { it.date in range }.sortedByDescending { it.date }
            if (page == null) found else found.drop(page.offset).take(page.limit)
        }

        override suspend fun totalMinor(range: DayRange): Long = lock.withLock {
            expenses.filter { it.date in range }.sumOf { it.amountMinor }
        }

        override suspend fun replaceAll(expenses: List<Expense>) = lock.withLock {
            checkWriteLocked()
            this@InMemoryTrackingDatabase.expenses = expenses
        }
    }

    val parkingRepo = object : ParkingRepository {
        override val changes: Flow<Unit> get() = this@InMemoryTrackingDatabase.changes

        override suspend fun current(): ParkingSpot? = lock.withLock {
            spots.maxWithOrNull(compareBy({ it.instantUtcMs }, { it.id }))
        }

        override suspend fun insert(spot: ParkingSpot): Long = lock.withLock {
            checkWriteLocked()
            val id = nextIdLocked()
            spots = spots + spot.copy(id = id)
            id
        }

        override suspend fun updateNote(id: Long, note: String?) = lock.withLock {
            checkWriteLocked()
            spots = spots.map { if (it.id == id) it.copy(note = note) else it }
        }

        override suspend fun all(): List<ParkingSpot> = lock.withLock { spots }

        override suspend fun replaceAll(spots: List<ParkingSpot>) = lock.withLock {
            checkWriteLocked()
            this@InMemoryTrackingDatabase.spots = spots
        }
    }

    val settingsRepo = object : SettingsRepository {
        override val changes: Flow<Unit> get() = this@InMemoryTrackingDatabase.changes

        override suspend fun get(): Settings = lock.withLock { settings }

        override suspend fun update(transform: (Settings) -> Settings) = lock.withLock {
            checkWriteLocked()
            settings = transform(settings)
        }

        override suspend fun lastFixTime(): FixTime? = lock.withLock { lastFix }

        override suspend fun saveLastFixTime(time: FixTime) = lock.withLock {
            checkWriteLocked()
            lastFix = time
        }
    }

    val backupStore = object : BackupStore {
        private val copies = linkedMapOf<String, Pair<StoredBackup, BackupReadResult>>()
        private var nextBackup = 0

        override suspend fun save(data: BackupData, createdUtcMs: Long): BackupWriteResult = lock.withLock {
            checkWriteLocked()
            val backup = StoredBackup("copy${nextBackup++}", createdUtcMs, 0)
            val file = BackupFile(CURRENT_BACKUP_SCHEMA, createdUtcMs, data.recordCount, "ok", "ok", data)
            copies[backup.id] = backup to BackupReadResult.Read(file)
            BackupWriteResult.Validated(backup)
        }

        override suspend fun list(): List<StoredBackup> = lock.withLock { copies.values.map { it.first } }

        override suspend fun read(id: String): BackupReadResult = lock.withLock {
            copies[id]?.second ?: BackupReadResult.Unreadable("missing")
        }

        override suspend fun delete(ids: List<String>) = lock.withLock {
            checkWriteLocked()
            ids.forEach { copies.remove(it) }
        }

        override suspend fun quarantine(id: String) = lock.withLock {
            checkWriteLocked()
            copies.remove(id)
            Unit
        }
    }
}
