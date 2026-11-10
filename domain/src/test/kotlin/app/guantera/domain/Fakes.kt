package app.guantera.domain

import app.guantera.domain.backup.BackupData
import app.guantera.domain.backup.BackupFile
import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.backup.CURRENT_BACKUP_SCHEMA
import app.guantera.domain.backup.StoredBackup
import app.guantera.domain.expense.Expense
import app.guantera.domain.maintenance.MaintenanceAlert
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.maintenance.ServiceRecord
import app.guantera.domain.motion.ManualConfirmation
import app.guantera.domain.motion.TrackingHealth
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.parking.ParkingSpot
import app.guantera.domain.port.AlertPublisher
import app.guantera.domain.port.BackupStore
import app.guantera.domain.port.BackupWriteResult
import app.guantera.domain.port.Clock
import app.guantera.domain.port.DataHealthChecker
import app.guantera.domain.port.DataHealthReport
import app.guantera.domain.port.ExpenseRepository
import app.guantera.domain.port.ExternalBackup
import app.guantera.domain.port.ExternalBackupTransfer
import app.guantera.domain.port.ExternalExportResult
import app.guantera.domain.port.MaintenanceRepository
import app.guantera.domain.port.OdometerRepository
import app.guantera.domain.port.PageRequest
import app.guantera.domain.port.ParkingRepository
import app.guantera.domain.port.SettingsRepository
import app.guantera.domain.port.StorageException
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.port.TrackingHealthSource
import app.guantera.domain.port.TransactionRunner
import app.guantera.domain.port.TripRepository
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.settings.Settings
import app.guantera.domain.time.DayRange
import app.guantera.domain.time.FixTime
import app.guantera.domain.trip.ActiveTripRecord
import app.guantera.domain.trip.GeoPoint
import app.guantera.domain.trip.LocationFix
import app.guantera.domain.trip.Trip
import app.guantera.domain.usecase.BackupDataAccess
import app.guantera.domain.usecase.CreateAutomaticBackup
import app.guantera.domain.usecase.FormGate
import app.guantera.domain.usecase.FormSubmission
import app.guantera.domain.usecase.LocalCalendar
import app.guantera.domain.usecase.ObserveDrivingState
import app.guantera.domain.usecase.OdometerReader
import app.guantera.domain.usecase.ResolveTrustedTime
import app.guantera.domain.usecase.TripEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

const val BASE_UTC = 1_750_000_000_000L
private const val METERS_PER_DEGREE = 111_194.9266

/** A fix [north] meters north of the origin, [seconds] after boot. */
fun fix(
    north: Double,
    seconds: Double,
    speed: Double? = 10.0,
    accuracy: Double? = 5.0,
    utc: Long = BASE_UTC + (seconds * 1000).toLong(),
) = LocationFix(GeoPoint(north / METERS_PER_DEGREE, 0.0), speed, accuracy, utc, (seconds * 1000).toLong())

class FakeClock(
    var utc: Long = BASE_UTC,
    var mono: Long = 0,
    var boot: Int? = 1,
    var offset: Int = 0,
) : Clock {
    override fun utcMs() = utc
    override fun monoMs() = mono
    override fun bootCount() = boot
    override fun utcOffsetMs(atUtcMs: Long) = offset
}

/** In-memory database. A transaction restores every table if its block throws. */
class FakeDatabase {
    var trips = listOf<Trip>()
    var active: ActiveTripRecord? = null
    var calibrations = listOf<OdometerCalibration>()
    var items = listOf<MaintenanceItem>()
    var services = listOf<ServiceRecord>()
    var expenses = listOf<Expense>()
    var spots = listOf<ParkingSpot>()
    var settings = Settings()
    var lastFix: FixTime? = null
    var nextId = 1L

    /** While set, every write throws. */
    var failure: StorageFailure? = null
    var heartbeatWrites = 0
    private val version = MutableStateFlow(0)

    fun write() {
        failure?.let { throw StorageException(it) }
        version.value++
    }

    fun id() = nextId++
    val changes: Flow<Unit> = version.map { }

    val transactions = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T {
            val saved = listOf(trips, active, calibrations, items, services, expenses, spots, settings)
            try {
                return block()
            } catch (e: StorageException) {
                @Suppress("UNCHECKED_CAST")
                run {
                    trips = saved[0] as List<Trip>
                    active = saved[1] as ActiveTripRecord?
                    calibrations = saved[2] as List<OdometerCalibration>
                    items = saved[3] as List<MaintenanceItem>
                    services = saved[4] as List<ServiceRecord>
                    expenses = saved[5] as List<Expense>
                    spots = saved[6] as List<ParkingSpot>
                    settings = saved[7] as Settings
                }
                throw e
            }
        }
    }

    val tripRepo = object : TripRepository {
        override val changes get() = this@FakeDatabase.changes
        override suspend fun activeTrip() = active
        override suspend fun saveActive(record: ActiveTripRecord) { write(); active = record; heartbeatWrites++ }
        override suspend fun clearActive() { write(); active = null }
        override suspend fun insertClosed(trip: Trip): Long { write(); val id = id(); trips = trips + trip.copy(id = id); return id }
        override suspend fun closed(window: UtcWindow, page: PageRequest?): List<Trip> {
            val found = trips.filter { inWindow(it, window) }.sortedByDescending { it.startUtcMs }
            return if (page == null) found else found.drop(page.offset).take(page.limit)
        }
        override suspend fun find(id: Long) = trips.firstOrNull { it.id == id }
        override suspend fun totalMeters(window: UtcWindow) = trips.filter { inWindow(it, window) }.sumOf { it.distanceMeters }
        override suspend fun replaceAll(trips: List<Trip>) { write(); this@FakeDatabase.trips = trips; active = null }
        private fun inWindow(trip: Trip, window: UtcWindow) =
            (window.fromMs == null || trip.startUtcMs >= window.fromMs) &&
                (window.toExclusiveMs == null || trip.startUtcMs < window.toExclusiveMs)
    }

    val odometerRepo = object : OdometerRepository {
        override val changes get() = this@FakeDatabase.changes
        override suspend fun latest() = calibrations.maxByOrNull { it.dateUtcMs }
        override suspend fun add(calibration: OdometerCalibration) { write(); calibrations = calibrations + calibration }
        override suspend fun all() = calibrations
        override suspend fun replaceAll(calibrations: List<OdometerCalibration>) { write(); this@FakeDatabase.calibrations = calibrations }
    }

    val maintenanceRepo = object : MaintenanceRepository {
        override val changes get() = this@FakeDatabase.changes
        override suspend fun items() = items
        override suspend fun find(id: Long) = items.firstOrNull { it.id == id }
        override suspend fun save(item: MaintenanceItem): Long {
            write()
            val id = if (item.id == 0L) id() else item.id
            items = items.filterNot { it.id == id } + item.copy(id = id)
            return id
        }
        override suspend fun delete(id: Long) { write(); items = items.filterNot { it.id == id } }
        override suspend fun addService(record: ServiceRecord): Long { write(); val id = id(); services = services + record.copy(id = id); return id }
        override suspend fun services() = services
        override suspend fun replaceAll(items: List<MaintenanceItem>, services: List<ServiceRecord>) {
            write(); this@FakeDatabase.items = items; this@FakeDatabase.services = services
        }
    }

    val expenseRepo = object : ExpenseRepository {
        override val changes get() = this@FakeDatabase.changes
        override suspend fun find(id: Long) = expenses.firstOrNull { it.id == id }
        override suspend fun save(expense: Expense): Long {
            write()
            val id = if (expense.id == 0L) id() else expense.id
            expenses = expenses.filterNot { it.id == id } + expense.copy(id = id)
            return id
        }
        override suspend fun delete(id: Long) { write(); expenses = expenses.filterNot { it.id == id } }
        override suspend fun inRange(range: DayRange, page: PageRequest?): List<Expense> {
            val found = expenses.filter { it.date in range }.sortedByDescending { it.date }
            return if (page == null) found else found.drop(page.offset).take(page.limit)
        }
        override suspend fun totalMinor(range: DayRange) = expenses.filter { it.date in range }.sumOf { it.amountMinor }
        override suspend fun replaceAll(expenses: List<Expense>) { write(); this@FakeDatabase.expenses = expenses }
    }

    val parkingRepo = object : ParkingRepository {
        override val changes get() = this@FakeDatabase.changes
        override suspend fun current() = spots.maxWithOrNull(compareBy({ it.instantUtcMs }, { it.id }))
        override suspend fun insert(spot: ParkingSpot): Long { write(); val id = id(); spots = spots + spot.copy(id = id); return id }
        override suspend fun updateNote(id: Long, note: String?) { write(); spots = spots.map { if (it.id == id) it.copy(note = note) else it } }
        override suspend fun all() = spots
        override suspend fun replaceAll(spots: List<ParkingSpot>) { write(); this@FakeDatabase.spots = spots }
    }

    val settingsRepo = object : SettingsRepository {
        override val changes get() = this@FakeDatabase.changes
        override suspend fun get() = settings
        override suspend fun update(transform: (Settings) -> Settings) { write(); settings = transform(settings) }
        override suspend fun lastFixTime() = lastFix
        override suspend fun saveLastFixTime(time: FixTime) { write(); lastFix = time }
    }
}

class FakeHealth(value: TrackingHealth = TrackingHealth(hasPermission = true, gpsEnabled = true)) : TrackingHealthSource {
    override val health = MutableStateFlow(value)
}

class FakeAlertPublisher : AlertPublisher {
    val published = mutableListOf<MaintenanceAlert?>()
    override suspend fun publish(alert: MaintenanceAlert?) { published += alert }
}

fun backupFile(
    data: BackupData,
    schema: Int = CURRENT_BACKUP_SCHEMA,
    declared: String = "ok",
    actual: String = "ok",
    declaredCount: Int = data.recordCount,
) = BackupFile(schema, BASE_UTC, declaredCount, declared, actual, data)

class FakeBackupStore : BackupStore {
    val copies = linkedMapOf<String, Pair<StoredBackup, BackupReadResult>>()
    val quarantined = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    var writeResults = ArrayDeque<BackupWriteResult>()
    var saves = 0
    private var next = 0

    fun add(createdUtcMs: Long, result: BackupReadResult): StoredBackup {
        val backup = StoredBackup("copy${next++}", createdUtcMs, 0)
        copies[backup.id] = backup to result
        return backup
    }

    override suspend fun save(data: BackupData, createdUtcMs: Long): BackupWriteResult {
        saves++
        writeResults.removeFirstOrNull()?.let { if (it !is BackupWriteResult.Validated) return it }
        val backup = add(createdUtcMs, BackupReadResult.Read(backupFile(data)))
        return BackupWriteResult.Validated(backup)
    }
    override suspend fun list() = copies.values.map { it.first }
    override suspend fun read(id: String) = copies[id]?.second ?: BackupReadResult.Unreadable("missing")
    override suspend fun delete(ids: List<String>) { deleted += ids; ids.forEach(copies::remove) }
    override suspend fun quarantine(id: String) { quarantined += id; copies.remove(id) }
}

class FakeTransfer : ExternalBackupTransfer {
    var exportResult: ExternalExportResult = ExternalExportResult.Exported("/usb/copia.json")
    var readResult: BackupReadResult = BackupReadResult.Unreadable("none")
    override suspend fun export(data: BackupData, createdUtcMs: Long) = exportResult
    override suspend fun available() = listOf(ExternalBackup("r", "copia.json"))
    override suspend fun read(reference: String) = readResult
}

class FakeHealthChecker(var report: DataHealthReport = DataHealthReport(true, false, 0)) : DataHealthChecker {
    var canQuarantine = true
    var quarantines = 0
    override suspend fun check() = report
    override suspend fun quarantineDatabase(): Boolean { quarantines++; return canQuarantine }
}

/** The domain wired against fakes, as AppContainer will do against adapters. */
class TestApp(
    val db: FakeDatabase = FakeDatabase(),
    val clock: FakeClock = FakeClock(),
    val store: FakeBackupStore = FakeBackupStore(),
) {
    val health = FakeHealth()
    val confirmation = ManualConfirmation()
    val calendar = LocalCalendar(clock)
    val time = ResolveTrustedTime(db.settingsRepo, clock, calendar)
    val data = BackupDataAccess(
        db.tripRepo, db.odometerRepo, db.maintenanceRepo, db.expenseRepo, db.parkingRepo, db.settingsRepo, db.transactions,
    )
    val backup = CreateAutomaticBackup(data, store, time)
    val engine = newEngine()
    val driving = ObserveDrivingState(engine, health, clock)
    val gate = FormGate(driving, confirmation, clock)
    val form = FormSubmission(gate, backup)
    val reader = OdometerReader(db.odometerRepo, db.tripRepo)

    /** Another process over the same database, as after the system kills the app. */
    fun newEngine() = TripEngine(db.tripRepo, db.parkingRepo, db.settingsRepo, db.transactions, clock, confirmation, backup)

    /** One clean, stopped fix so that the driving state is DETENIDO. */
    suspend fun parkWithFix(seconds: Double = 1.0) {
        clock.mono = (seconds * 1000).toLong()
        engine.onFix(fix(0.0, seconds, speed = 0.0))
    }
}
