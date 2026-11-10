package app.guantera.storage

import android.content.Context
import android.content.pm.PackageManager
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Environment
import android.provider.Settings as SystemSettings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.guantera.domain.backup.BackupData
import app.guantera.domain.backup.CURRENT_BACKUP_SCHEMA
import app.guantera.domain.backup.BackupFile
import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.backup.StoredBackup
import app.guantera.domain.expense.Expense
import app.guantera.domain.expense.ExpenseCategory
import app.guantera.domain.maintenance.DataOrigin
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.maintenance.ServiceRecord
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.parking.ParkingOrigin
import app.guantera.domain.parking.ParkingSpot
import app.guantera.domain.port.BackupStore
import app.guantera.domain.port.BackupWriteResult
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
import app.guantera.domain.port.TransactionRunner
import app.guantera.domain.port.TripRepository
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.settings.Settings
import app.guantera.domain.time.CalendarDay
import app.guantera.domain.time.DayRange
import app.guantera.domain.time.FixTime
import app.guantera.domain.trip.ActiveTrip
import app.guantera.domain.trip.ActiveTripRecord
import app.guantera.domain.trip.CleanFix
import app.guantera.domain.trip.GeoPoint
import app.guantera.domain.trip.Heartbeat
import app.guantera.domain.trip.Trip
import app.guantera.domain.trip.TripClosure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

@Entity(tableName = "trips")
data class TripEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val distanceMeters: Double,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val closure: String,
)

@Entity(tableName = "active_trip")
data class ActiveTripEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val json: String,
)

@Entity(tableName = "calibrations")
data class CalibrationEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateUtcMs: Long,
    val odoRealKm: Double,
    val gpsAccumulatedKm: Double,
)

@Entity(tableName = "maintenance_items")
data class MaintenanceItemEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    val fixedDueDay: Int?,
    val lastServiceKm: Double?,
    val lastServiceDay: Int?,
    val origin: String,
)

@Entity(tableName = "services")
data class ServiceEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val dateDay: Int,
    val km: Double?,
    val note: String?,
)

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val amountMinor: Long,
    val dateDay: Int,
    val note: String?,
    val serviceRecordId: Long?,
)

@Entity(tableName = "parking")
data class ParkingEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Double,
    val instantUtcMs: Long,
    val origin: String,
    val note: String?,
    val isLastSignalPoint: Boolean,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val currency: String,
    val openAtBoot: Boolean,
    val firstUseComplete: Boolean,
    val recordingGapPending: Boolean,
    val lastExportUtcMs: Long?,
    val catalogRestoredUtcMs: Long?,
    val lastFixUtcMs: Long?,
    val lastFixMonoMs: Long?,
    val lastFixBootCount: Int?,
)

@Entity(tableName = "change_log")
data class ChangeEntity(@androidx.room.PrimaryKey val id: Int = 1, val version: Long)

@Dao
interface StorageDao {
    @Query("SELECT * FROM trips ORDER BY startUtcMs DESC, id DESC") suspend fun trips(): List<TripEntity>
    @Query("SELECT * FROM trips WHERE id = :id LIMIT 1") suspend fun trip(id: Long): TripEntity?
    @Insert suspend fun insertTrip(entity: TripEntity): Long
    @Query("DELETE FROM trips") suspend fun clearTrips()
    @Query("DELETE FROM trips WHERE id = :id") suspend fun deleteTrip(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveActive(entity: ActiveTripEntity)
    @Query("SELECT * FROM active_trip WHERE id = 1") suspend fun active(): ActiveTripEntity?
    @Query("DELETE FROM active_trip") suspend fun clearActive()
    @Query("SELECT * FROM calibrations") suspend fun calibrations(): List<CalibrationEntity>
    @Insert suspend fun insertCalibration(entity: CalibrationEntity)
    @Query("DELETE FROM calibrations") suspend fun clearCalibrations()
    @Query("SELECT * FROM maintenance_items") suspend fun items(): List<MaintenanceItemEntity>
    @Query("SELECT * FROM maintenance_items WHERE id = :id LIMIT 1") suspend fun item(id: Long): MaintenanceItemEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveItem(entity: MaintenanceItemEntity): Long
    @Query("DELETE FROM maintenance_items WHERE id = :id") suspend fun deleteItem(id: Long)
    @Query("SELECT * FROM services") suspend fun services(): List<ServiceEntity>
    @Insert suspend fun insertService(entity: ServiceEntity): Long
    @Query("DELETE FROM maintenance_items") suspend fun clearItems()
    @Query("DELETE FROM services") suspend fun clearServices()
    @Query("SELECT * FROM expenses") suspend fun expenses(): List<ExpenseEntity>
    @Query("SELECT * FROM expenses WHERE id = :id LIMIT 1") suspend fun expense(id: Long): ExpenseEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveExpense(entity: ExpenseEntity): Long
    @Query("DELETE FROM expenses WHERE id = :id") suspend fun deleteExpense(id: Long)
    @Query("DELETE FROM expenses") suspend fun clearExpenses()
    @Query("SELECT * FROM parking") suspend fun parking(): List<ParkingEntity>
    @Insert suspend fun insertParking(entity: ParkingEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveParking(entity: ParkingEntity)
    @Query("DELETE FROM parking") suspend fun clearParking()
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun settings(): SettingsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSettings(entity: SettingsEntity)
    @Query("SELECT * FROM change_log WHERE id = :channel") fun changes(channel: Int): Flow<ChangeEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun bumpChange(entity: ChangeEntity)
}

internal const val DB_VERSION = 1

/** Every schema change adds its migration here. Nothing may ever fall back to a destructive rebuild. */
internal val MIGRATIONS = emptyArray<Migration>()

@Database(
    entities = [
        TripEntity::class, ActiveTripEntity::class, CalibrationEntity::class,
        MaintenanceItemEntity::class, ServiceEntity::class, ExpenseEntity::class,
        ParkingEntity::class, SettingsEntity::class, ChangeEntity::class,
    ],
    version = DB_VERSION,
    exportSchema = true,
)
abstract class GuanteraDatabase : RoomDatabase() {
    abstract fun dao(): StorageDao
}

private fun CalendarDay.toEpoch() = epochDay
private fun Int.toDay() = CalendarDay.fromEpochDay(this)
private fun GeoPoint.toJson() = JSONObject().put("lat", latitude).put("lon", longitude)
private fun JSONObject.point() = GeoPoint(getDouble("lat"), getDouble("lon"))
private fun CleanFix.toJson() = JSONObject().put("point", point.toJson()).put("speed", speedMps)
    .put("accuracy", accuracyMeters).put("utc", utcMs).put("mono", monoMs)
private fun JSONObject.cleanFix() = CleanFix(getJSONObject("point").point(), getDouble("speed"), getDouble("accuracy"), getLong("utc"), getLong("mono"))

private object ActiveCodec {
    fun encode(value: ActiveTripRecord): String {
        val t = value.trip
        return JSONObject().put("startUtc", t.startUtcMs).put("startMono", t.startMonoMs)
            .put("startPoint", t.startPoint.toJson()).put("distance", t.distanceMeters)
            .put("lastDistance", t.lastDistanceFix.toJson()).put("lastValid", t.lastValidFix.toJson())
            .put("stop", value.stopCandidateMonoMs ?: JSONObject.NULL)
            .put("heartbeat", JSONObject().put("mono", value.heartbeat.monoMs).put("utc", value.heartbeat.utcMs)
                .put("boot", value.heartbeat.bootCount ?: JSONObject.NULL)).toString()
    }

    fun decode(raw: String): ActiveTripRecord {
        val o = JSONObject(raw)
        val heartbeat = o.getJSONObject("heartbeat")
        val trip = ActiveTrip(o.getLong("startUtc"), o.getLong("startMono"), o.getJSONObject("startPoint").point(),
            o.getDouble("distance"), o.getJSONObject("lastDistance").cleanFix(), o.getJSONObject("lastValid").cleanFix())
        return ActiveTripRecord(trip, if (o.isNull("stop")) null else o.getLong("stop"),
            Heartbeat(heartbeat.getLong("mono"), heartbeat.getLong("utc"), if (heartbeat.isNull("boot")) null else heartbeat.getInt("boot")))
    }
}

private object Mappers {
    fun trip(e: TripEntity) = Trip(e.id, e.startUtcMs, e.endUtcMs, e.distanceMeters, GeoPoint(e.startLat, e.startLon), GeoPoint(e.endLat, e.endLon), TripClosure.valueOf(e.closure))
    fun trip(t: Trip) = TripEntity(t.id, t.startUtcMs, t.endUtcMs, t.distanceMeters, t.startPoint.latitude, t.startPoint.longitude, t.endPoint.latitude, t.endPoint.longitude, t.closure.name)
    fun item(e: MaintenanceItemEntity) = MaintenanceItem(e.id, e.name, e.intervalKm, e.intervalMonths, e.fixedDueDay?.toDay(), e.lastServiceKm, e.lastServiceDay?.toDay(), DataOrigin.valueOf(e.origin))
    fun item(v: MaintenanceItem) = MaintenanceItemEntity(v.id, v.name, v.intervalKm, v.intervalMonths, v.fixedDueDate?.epochDay, v.lastServiceKm, v.lastServiceDate?.epochDay, v.origin.name)
    fun service(e: ServiceEntity) = ServiceRecord(e.id, e.itemId, e.dateDay.toDay(), e.km, e.note)
    fun service(v: ServiceRecord) = ServiceEntity(v.id, v.itemId, v.date.epochDay, v.km, v.note)
    fun expense(e: ExpenseEntity) = Expense(e.id, ExpenseCategory.valueOf(e.category), e.amountMinor, e.dateDay.toDay(), e.note, e.serviceRecordId)
    fun expense(v: Expense) = ExpenseEntity(v.id, v.category.name, v.amountMinor, v.date.epochDay, v.note, v.serviceRecordId)
    fun parking(e: ParkingEntity) = ParkingSpot(e.id, GeoPoint(e.lat, e.lon), e.accuracyMeters, e.instantUtcMs, ParkingOrigin.valueOf(e.origin), e.note, e.isLastSignalPoint)
    fun parking(v: ParkingSpot) = ParkingEntity(v.id, v.point.latitude, v.point.longitude, v.accuracyMeters, v.instantUtcMs, v.origin.name, v.note, v.isLastSignalPoint)
}

private fun SettingsEntity.domain() = Settings(currency, openAtBoot, firstUseComplete, recordingGapPending, lastExportUtcMs, catalogRestoredUtcMs)
private fun Settings.domain(fix: FixTime?) = SettingsEntity(1, currency, openAtBoot, firstUseComplete, recordingGapPending, lastExportUtcMs, catalogRestoredUtcMs, fix?.utcMs, fix?.monoMs, fix?.bootCount)

/**
 * The one way to reach Room. Closing a RoomDatabase cancels its internal scope for good, so the
 * instance is replaced instead of reused: whoever needs the file to itself closes it here, and the
 * next operation opens a new one. Repositories and flows stay valid across that.
 */
internal class DatabaseHandle(private val build: () -> GuanteraDatabase) {
    private val lock = Any()
    private var open: GuanteraDatabase? = null
    val generation = MutableStateFlow(0L)

    val database: GuanteraDatabase get() = synchronized(lock) { open ?: build().also { open = it } }
    val dao: StorageDao get() = database.dao()

    fun close() = synchronized(lock) {
        open?.close()
        open = null
        generation.value++
    }
}

/** What Android and Room do by default on corruption is delete the file. R37 forbids it. */
internal class KeepCorruptFilesFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val base = configuration.callback
        val keeping = object : SupportSQLiteOpenHelper.Callback(base.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = base.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = base.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = base.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = base.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = base.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) = Unit
        }
        return delegate.create(
            SupportSQLiteOpenHelper.Configuration(configuration.context, configuration.name, keeping, configuration.useNoBackupDirectory, configuration.allowDataLossOnRecovery),
        )
    }
}

internal fun isNoSpace(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any {
    (it is ErrnoException && (it.errno == OsConstants.ENOSPC || it.errno == OsConstants.EDQUOT)) ||
        it.message?.contains("ENOSPC") == true
}

private fun failureOf(error: Throwable): StorageFailure =
    if (error is android.database.sqlite.SQLiteFullException || isNoSpace(error)) StorageFailure.FULL else StorageFailure.IO

private fun storageError(error: Throwable): StorageException =
    error as? StorageException ?: StorageException(failureOf(error), error)

/**
 * SQLite may undo a transaction by itself on a full disk, and then closing it fails with a different
 * error that hides the real one. So the first failure inside the block is what gets reported.
 */
private suspend fun <T> DatabaseHandle.transact(passThrough: (Throwable) -> Boolean, block: suspend (StorageDao) -> T): T =
    withContext(Dispatchers.IO) {
        var first: Throwable? = null
        try {
            val db = database
            db.withTransaction {
                try {
                    block(db.dao())
                } catch (e: Throwable) {
                    if (first == null) first = e
                    throw e
                }
            }
        } catch (e: Exception) {
            val cause = first ?: e
            if (passThrough(cause)) throw cause
            // Only our own cancellation may go through: Room's would silently end the caller.
            if (cause is CancellationException) currentCoroutineContext().ensureActive()
            throw storageError(cause)
        }
    }

private suspend fun <T> DatabaseHandle.write(block: suspend (StorageDao) -> T): T = transact({ false }, block)

private suspend fun <T> DatabaseHandle.read(block: suspend (StorageDao) -> T): T = withContext(Dispatchers.IO) { block(dao) }

private object Channel {
    const val TRIPS = 1
    const val ODOMETER = 2
    const val MAINTENANCE = 3
    const val EXPENSES = 4
    const val PARKING = 5
    const val SETTINGS = 6
}

private suspend fun StorageDao.bump(channel: Int) = bumpChange(ChangeEntity(channel, System.nanoTime()))

/** Emits on collection and after each commit that touched [channel]; survives the database being reopened. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun DatabaseHandle.changesOf(channel: Int): Flow<Unit> = generation.flatMapLatest {
    dao.changes(channel).map { it?.version }.distinctUntilChanged().map { }.catch { emit(Unit) }
}

internal class RoomTransactionRunner(private val handle: DatabaseHandle) : TransactionRunner {
    /** What the block itself throws comes back untouched; only database failures become [StorageException]. */
    override suspend fun <T> run(block: suspend () -> T): T =
        handle.transact({ it !is SQLiteException && it !is IOException }) { block() }
}

internal class RoomTripRepository(private val handle: DatabaseHandle) : TripRepository {
    override val changes: Flow<Unit> = handle.changesOf(Channel.TRIPS)
    override suspend fun activeTrip() = handle.read { dao -> dao.active()?.let { ActiveCodec.decode(it.json) } }
    override suspend fun saveActive(record: ActiveTripRecord) = handle.write { it.saveActive(ActiveTripEntity(json = ActiveCodec.encode(record))) }
    override suspend fun clearActive() = handle.write { it.clearActive() }
    override suspend fun insertClosed(trip: Trip): Long = handle.write { dao -> dao.insertTrip(Mappers.trip(trip.copy(id = 0))).also { dao.bump(Channel.TRIPS) } }
    override suspend fun closed(window: UtcWindow, page: PageRequest?): List<Trip> = handle.read { dao ->
        val from = window.fromMs
        val to = window.toExclusiveMs
        dao.trips().asSequence().filter { (from == null || it.startUtcMs >= from) && (to == null || it.startUtcMs < to) }
            .map(Mappers::trip).let { if (page == null) it.toList() else it.drop(page.offset).take(page.limit).toList() }
    }
    override suspend fun find(id: Long) = handle.read { dao -> dao.trip(id)?.let(Mappers::trip) }
    override suspend fun totalMeters(window: UtcWindow) = closed(window).sumOf { it.distanceMeters }
    override suspend fun replaceAll(trips: List<Trip>) = handle.write { dao -> dao.clearTrips(); trips.forEach { dao.insertTrip(Mappers.trip(it)) }; dao.clearActive(); dao.bump(Channel.TRIPS) }
}

internal class RoomOdometerRepository(private val handle: DatabaseHandle) : OdometerRepository {
    override val changes: Flow<Unit> = handle.changesOf(Channel.ODOMETER)
    private fun entity(c: OdometerCalibration) = CalibrationEntity(dateUtcMs = c.dateUtcMs, odoRealKm = c.odoRealKm, gpsAccumulatedKm = c.gpsAccumulatedKm)
    override suspend fun latest() = all().maxByOrNull { it.dateUtcMs }
    override suspend fun add(calibration: OdometerCalibration) = handle.write { dao -> dao.insertCalibration(entity(calibration)); dao.bump(Channel.ODOMETER) }
    override suspend fun all() = handle.read { dao -> dao.calibrations().map { OdometerCalibration(it.dateUtcMs, it.odoRealKm, it.gpsAccumulatedKm) } }
    override suspend fun replaceAll(calibrations: List<OdometerCalibration>) = handle.write { dao -> dao.clearCalibrations(); calibrations.forEach { dao.insertCalibration(entity(it)) }; dao.bump(Channel.ODOMETER) }
}

internal class RoomMaintenanceRepository(private val handle: DatabaseHandle) : MaintenanceRepository {
    override val changes: Flow<Unit> = handle.changesOf(Channel.MAINTENANCE)
    override suspend fun items() = handle.read { dao -> dao.items().map(Mappers::item) }
    override suspend fun find(id: Long) = handle.read { dao -> dao.item(id)?.let(Mappers::item) }
    override suspend fun save(item: MaintenanceItem): Long = handle.write { dao -> dao.saveItem(Mappers.item(item)).also { dao.bump(Channel.MAINTENANCE) } }
    override suspend fun delete(id: Long) = handle.write { dao -> dao.deleteItem(id); dao.bump(Channel.MAINTENANCE) }
    override suspend fun addService(record: ServiceRecord): Long = handle.write { dao -> dao.insertService(Mappers.service(record.copy(id = 0))).also { dao.bump(Channel.MAINTENANCE) } }
    override suspend fun services() = handle.read { dao -> dao.services().map(Mappers::service) }
    override suspend fun replaceAll(items: List<MaintenanceItem>, services: List<ServiceRecord>) = handle.write { dao -> dao.clearItems(); dao.clearServices(); items.forEach { dao.saveItem(Mappers.item(it)) }; services.forEach { dao.insertService(Mappers.service(it)) }; dao.bump(Channel.MAINTENANCE) }
}

internal class RoomExpenseRepository(private val handle: DatabaseHandle) : ExpenseRepository {
    override val changes: Flow<Unit> = handle.changesOf(Channel.EXPENSES)
    override suspend fun find(id: Long) = handle.read { dao -> dao.expense(id)?.let(Mappers::expense) }
    override suspend fun save(expense: Expense): Long = handle.write { dao -> dao.saveExpense(Mappers.expense(expense)).also { dao.bump(Channel.EXPENSES) } }
    override suspend fun delete(id: Long) = handle.write { dao -> dao.deleteExpense(id); dao.bump(Channel.EXPENSES) }
    override suspend fun inRange(range: DayRange, page: PageRequest?): List<Expense> = handle.read { dao ->
        dao.expenses().asSequence().filter { it.dateDay.toDay() in range }.sortedWith(compareByDescending<ExpenseEntity> { it.dateDay }.thenByDescending { it.id }).map(Mappers::expense).let { if (page == null) it.toList() else it.drop(page.offset).take(page.limit).toList() }
    }
    override suspend fun totalMinor(range: DayRange) = inRange(range).sumOf { it.amountMinor }
    override suspend fun replaceAll(expenses: List<Expense>) = handle.write { dao -> dao.clearExpenses(); expenses.forEach { dao.saveExpense(Mappers.expense(it)) }; dao.bump(Channel.EXPENSES) }
}

internal class RoomParkingRepository(private val handle: DatabaseHandle) : ParkingRepository {
    override val changes: Flow<Unit> = handle.changesOf(Channel.PARKING)
    override suspend fun current() = all().maxWithOrNull(compareBy({ it.instantUtcMs }, { it.id }))
    override suspend fun insert(spot: ParkingSpot): Long = handle.write { dao -> dao.insertParking(Mappers.parking(spot.copy(id = 0))).also { dao.bump(Channel.PARKING) } }
    override suspend fun updateNote(id: Long, note: String?) = handle.write { dao -> dao.parking().firstOrNull { it.id == id }?.let { dao.saveParking(it.copy(note = note)) }; dao.bump(Channel.PARKING) }
    override suspend fun all() = handle.read { dao -> dao.parking().map(Mappers::parking) }
    override suspend fun replaceAll(spots: List<ParkingSpot>) = handle.write { dao -> dao.clearParking(); spots.forEach { dao.insertParking(Mappers.parking(it)) }; dao.bump(Channel.PARKING) }
}

internal class RoomSettingsRepository(private val handle: DatabaseHandle) : SettingsRepository {
    override val changes: Flow<Unit> = handle.changesOf(Channel.SETTINGS)
    private suspend fun StorageDao.row() = settings() ?: Settings().domain(null)
    // A read never writes: a full disk must not be able to break it.
    override suspend fun get() = handle.read { dao -> dao.row().domain() }
    override suspend fun update(transform: (Settings) -> Settings) = handle.write { dao -> val current = dao.row(); dao.saveSettings(transform(current.domain()).domain(current.fix())); dao.bump(Channel.SETTINGS) }
    override suspend fun lastFixTime() = handle.read { dao -> dao.settings()?.fix() }
    override suspend fun saveLastFixTime(time: FixTime) = handle.write { dao -> dao.saveSettings(dao.row().copy(lastFixUtcMs = time.utcMs, lastFixMonoMs = time.monoMs, lastFixBootCount = time.bootCount)); dao.bump(Channel.SETTINGS) }
}

private fun SettingsEntity.fix() = if (lastFixUtcMs == null || lastFixMonoMs == null) null else FixTime(lastFixUtcMs, lastFixMonoMs, lastFixBootCount)

private object BackupJson {
    private fun <T> array(values: List<T>, put: (JSONObject, T) -> Unit) = JSONArray().also { a -> values.forEach { v -> a.put(JSONObject().also { put(it, v) }) } }
    fun encode(data: BackupData, createdUtcMs: Long): Pair<String, String> {
        val body = JSONObject()
            .put("trips", array(data.trips) { o, t -> o.put("id", t.id).put("start", t.startUtcMs).put("end", t.endUtcMs).put("distance", t.distanceMeters).put("startPoint", t.startPoint.toJson()).put("endPoint", t.endPoint.toJson()).put("closure", t.closure.name) })
            .put("calibrations", array(data.calibrations) { o, c -> o.put("date", c.dateUtcMs).put("odo", c.odoRealKm).put("gps", c.gpsAccumulatedKm) })
            .put("items", array(data.items) { o, i -> o.put("id", i.id).put("name", i.name).put("km", i.intervalKm ?: JSONObject.NULL).put("months", i.intervalMonths ?: JSONObject.NULL).put("fixed", i.fixedDueDate?.epochDay ?: JSONObject.NULL).put("lastKm", i.lastServiceKm ?: JSONObject.NULL).put("lastDate", i.lastServiceDate?.epochDay ?: JSONObject.NULL).put("origin", i.origin.name) })
            .put("services", array(data.services) { o, s -> o.put("id", s.id).put("item", s.itemId).put("date", s.date.epochDay).put("km", s.km ?: JSONObject.NULL).put("note", s.note ?: JSONObject.NULL) })
            .put("expenses", array(data.expenses) { o, e -> o.put("id", e.id).put("category", e.category.name).put("amount", e.amountMinor).put("date", e.date.epochDay).put("note", e.note ?: JSONObject.NULL).put("service", e.serviceRecordId ?: JSONObject.NULL) })
            .put("parking", array(data.parkingSpots) { o, p -> o.put("id", p.id).put("lat", p.point.latitude).put("lon", p.point.longitude).put("accuracy", p.accuracyMeters).put("instant", p.instantUtcMs).put("origin", p.origin.name).put("note", p.note ?: JSONObject.NULL).put("last", p.isLastSignalPoint) })
            .put("settings", JSONObject().put("currency", data.settings.currency).put("open", data.settings.openAtBoot).put("first", data.settings.firstUseComplete).put("gap", data.settings.recordingGapPending).put("export", data.settings.lastExportUtcMs ?: JSONObject.NULL).put("catalog", data.settings.catalogRestoredUtcMs ?: JSONObject.NULL))
        // Hashed as it will read back: JSON does not keep every number's spelling (-0.0 comes back as 0).
        val canonical = JSONObject(body.toString())
        val checksum = sha256(canonical.toString())
        return JSONObject().put("schema", CURRENT_BACKUP_SCHEMA).put("created", createdUtcMs).put("count", data.recordCount).put("checksum", checksum).put("data", canonical).toString() to checksum
    }

    fun decode(raw: String): BackupFile {
        val root = JSONObject(raw)
        val schema = root.getInt("schema")
        // R36: a newer app may have changed the body, and that must read as "newer", not as "damaged".
        if (schema > CURRENT_BACKUP_SCHEMA) {
            val empty = BackupData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Settings())
            return BackupFile(schema, root.optLong("created"), root.optInt("count"), root.optString("checksum"), root.optString("checksum"), empty)
        }
        val body = root.getJSONObject("data")
        val trips = body.getJSONArray("trips").objects { o -> Trip(o.getLong("id"), o.getLong("start"), o.getLong("end"), o.getDouble("distance"), o.getJSONObject("startPoint").point(), o.getJSONObject("endPoint").point(), TripClosure.valueOf(o.getString("closure"))) }
        val calibrations = body.getJSONArray("calibrations").objects { o -> OdometerCalibration(o.getLong("date"), o.getDouble("odo"), o.getDouble("gps")) }
        val items = body.getJSONArray("items").objects { o -> MaintenanceItem(o.getLong("id"), o.getString("name"), o.optionalInt("km"), o.optionalInt("months"), o.optionalInt("fixed")?.toDay(), o.optionalDouble("lastKm"), o.optionalInt("lastDate")?.toDay(), DataOrigin.valueOf(o.getString("origin"))) }
        val services = body.getJSONArray("services").objects { o -> ServiceRecord(o.getLong("id"), o.getLong("item"), o.getInt("date").toDay(), o.optionalDouble("km"), o.optionalString("note")) }
        val expenses = body.getJSONArray("expenses").objects { o -> Expense(o.getLong("id"), ExpenseCategory.valueOf(o.getString("category")), o.getLong("amount"), o.getInt("date").toDay(), o.optionalString("note"), o.optionalLong("service")) }
        val parking = body.getJSONArray("parking").objects { o -> ParkingSpot(o.getLong("id"), GeoPoint(o.getDouble("lat"), o.getDouble("lon")), o.getDouble("accuracy"), o.getLong("instant"), ParkingOrigin.valueOf(o.getString("origin")), o.optionalString("note"), o.getBoolean("last")) }
        val s = body.getJSONObject("settings")
        val settings = Settings(s.getString("currency"), s.getBoolean("open"), s.getBoolean("first"), s.getBoolean("gap"), s.optionalLong("export"), s.optionalLong("catalog"))
        val data = BackupData(trips, calibrations, items, services, expenses, parking, settings)
        return BackupFile(schema, root.getLong("created"), root.getInt("count"), root.getString("checksum"), sha256(body.toString()), data)
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun <T> JSONArray.objects(map: (JSONObject) -> T) = (0 until length()).map { map(getJSONObject(it)) }
    private fun JSONObject.optionalString(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.optionalLong(key: String) = if (isNull(key)) null else getLong(key)
    private fun JSONObject.optionalInt(key: String) = if (isNull(key)) null else getInt(key)
    private fun JSONObject.optionalDouble(key: String) = if (isNull(key)) null else getDouble(key)
}

/** Durable rename needs the directory on disk too. Some volumes (FAT, FUSE) cannot sync a directory. */
internal fun fsyncDirectory(directory: File) {
    val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } catch (e: ErrnoException) {
        if (e.errno != OsConstants.EINVAL && e.errno != OsConstants.ENOTSUP) throw e
    } finally {
        Os.close(fd)
    }
}

internal enum class BackupStage { TEMP_SYNCED, VERIFIED, RENAMED, DIRECTORY_SYNCED }

private const val QUARANTINE_SUFFIX = ".quarantine.json"
private val BACKUP_NAME = Regex("backup-(\\d+)-\\d+")

class FileBackupStore internal constructor(
    private val directory: File,
    private val checkpoint: (BackupStage) -> Unit,
    private val temporaryFor: (File) -> File,
) : BackupStore {
    constructor(directory: File) : this(directory, {}, { File(it.parentFile, "${it.name}.tmp") })

    private fun file(id: String) = File(directory, "$id.json")
    internal fun has(id: String) = file(id).isFile

    override suspend fun save(data: BackupData, createdUtcMs: Long): BackupWriteResult = withContext(Dispatchers.IO) {
        saveLock.withLock {
            var temp: File? = null
            try {
                createDirectory()
                directory.listFiles { f -> f.name.endsWith(".json.tmp") }?.forEach { it.delete() }
                val id = "backup-$createdUtcMs-${System.nanoTime()}"
                val target = file(id)
                val (text, checksum) = BackupJson.encode(data, createdUtcMs)
                val bytes = text.toByteArray()
                val written = temporaryFor(target).also { temp = it }
                FileOutputStream(written).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
                checkpoint(BackupStage.TEMP_SYNCED)
                val reread = written.readBytes()
                val copy = BackupJson.decode(String(reread))
                if (!reread.contentEquals(bytes) || copy.actualChecksum != checksum || copy.declaredRecordCount != copy.data.recordCount) {
                    written.delete()
                    return@withLock BackupWriteResult.Failed("verification failed")
                }
                checkpoint(BackupStage.VERIFIED)
                if (!written.renameTo(target)) {
                    written.delete()
                    return@withLock BackupWriteResult.Failed("rename failed")
                }
                checkpoint(BackupStage.RENAMED)
                fsyncDirectory(directory)
                checkpoint(BackupStage.DIRECTORY_SYNCED)
                BackupWriteResult.Validated(StoredBackup(id, createdUtcMs, data.recordCount))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A partial file on a full disk would keep it full.
                runCatching { temp?.delete() }
                if (isNoSpace(e)) BackupWriteResult.StorageFull else BackupWriteResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun createDirectory() {
        val missing = generateSequence(directory) { it.parentFile }.takeWhile { !it.exists() }.toList()
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("cannot create $directory")
        missing.mapNotNull { it.parentFile }.forEach { runCatching { fsyncDirectory(it) } }
    }

    /** Damaged copies stay listed, dated by their name, so that recovery can reach them and set them aside. */
    override suspend fun list(): List<StoredBackup> = withContext(Dispatchers.IO) {
        runCatching {
            directory.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.endsWith(QUARANTINE_SUFFIX) }.orEmpty().mapNotNull { entry ->
                val id = entry.name.removeSuffix(".json")
                runCatching { BackupJson.decode(entry.readText()) }.getOrNull()?.let { StoredBackup(id, it.createdUtcMs, it.declaredRecordCount) }
                    ?: BACKUP_NAME.matchEntire(id)?.let { StoredBackup(id, it.groupValues[1].toLong(), 0) }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun read(id: String): BackupReadResult = withContext(Dispatchers.IO) {
        try {
            BackupReadResult.Read(BackupJson.decode(file(id).readText()))
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            BackupReadResult.Unreadable(e.message ?: "unreadable")
        }
    }

    /** R37: retention may discard a good copy, but a damaged one is only ever set aside. */
    override suspend fun delete(ids: List<String>) = withContext(Dispatchers.IO) {
        ids.forEach { id ->
            if (read(id) is BackupReadResult.Unreadable && has(id)) quarantine(id) else file(id).delete()
        }
    }

    override suspend fun quarantine(id: String) = withContext(Dispatchers.IO) {
        val source = file(id)
        if (source.exists() && source.renameTo(File(directory, "$id$QUARANTINE_SUFFIX"))) {
            runCatching { fsyncDirectory(directory) }
        }
        Unit
    }

    private companion object {
        val saveLock = Mutex()
    }
}

/**
 * Copies go to the public folder (they survive an uninstall). Without the permission, or if it fails,
 * they go to the app's own external folder: a copy that an uninstall can erase beats having none.
 */
internal class PublicOrPrivateBackupStore(
    private val public: FileBackupStore,
    private val private: FileBackupStore,
    private val publicUsable: () -> Boolean,
) : BackupStore {
    private fun owner(id: String) = if (public.has(id)) public else private

    override suspend fun save(data: BackupData, createdUtcMs: Long): BackupWriteResult {
        if (publicUsable()) {
            val result = public.save(data, createdUtcMs)
            if (result !is BackupWriteResult.Failed) return result
        }
        return private.save(data, createdUtcMs)
    }

    override suspend fun list() = public.list() + private.list()
    override suspend fun read(id: String) = owner(id).read(id)
    override suspend fun delete(ids: List<String>) = ids.forEach { owner(it).delete(listOf(it)) }
    override suspend fun quarantine(id: String) = owner(id).quarantine(id)
}

private const val BACKUP_FOLDER = "Guantera/respaldos"

/**
 * Only volumes Android exposes to apps. On a stock Android 8 that means SD-like ones: a USB drive is
 * usually not listed and is reachable only through the Storage Access Framework. Head units vary.
 */
private fun removableVolumes(context: Context): List<File> = context.getExternalFilesDirs(null).filterNotNull().filter {
    runCatching { Environment.isExternalStorageRemovable(it) && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }.getOrDefault(false)
}

class UsbBackupTransfer internal constructor(private val volumes: () -> List<File>) : ExternalBackupTransfer {
    constructor(context: Context) : this({ removableVolumes(context) })

    private fun folders() = runCatching { volumes() }.getOrDefault(emptyList()).map { File(it, BACKUP_FOLDER) }

    override suspend fun export(data: BackupData, createdUtcMs: Long): ExternalExportResult {
        val folder = folders().firstOrNull() ?: return ExternalExportResult.DriveMissing
        return when (val result = FileBackupStore(folder).save(data, createdUtcMs)) {
            is BackupWriteResult.Validated -> ExternalExportResult.Exported(File(folder, "${result.backup.id}.json").absolutePath)
            BackupWriteResult.StorageFull -> ExternalExportResult.DriveFull
            is BackupWriteResult.Failed -> if (folder in folders()) ExternalExportResult.Failed(result.reason) else ExternalExportResult.DriveMissing
        }
    }

    override suspend fun available(): List<ExternalBackup> =
        folders().flatMap { FileBackupStore(it).list() }.map { ExternalBackup(it.id, "${it.id}.json") }

    override suspend fun read(reference: String): BackupReadResult {
        val id = File(reference).name.removeSuffix(".json")
        val store = folders().map(::FileBackupStore).firstOrNull { it.has(id) } ?: return BackupReadResult.Unreadable("drive or copy missing")
        return store.read(id)
    }
}

private const val STABLE_AFTER_MS = 30_000L
private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
private const val PENDING = ".pending"

internal class RoomDataHealthChecker(
    context: Context,
    private val handle: DatabaseHandle,
    private val stableAfterMs: Long = STABLE_AFTER_MS,
    private val bootCount: () -> Int? = {
        runCatching { SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT) }.getOrNull()
    },
) : DataHealthChecker {
    private val source = context.getDatabasePath("guantera.db")
    private val prefs = context.getSharedPreferences("storage-health", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var priorFailedOpens: Int? = null
    private val siblings get() = listOf(source.name, "${source.name}-wal", "${source.name}-shm", "${source.name}-journal")

    override suspend fun check(): DataHealthReport = withContext(Dispatchers.IO) {
        handle.close()
        val finishedAside = runCatching { finishPendingQuarantines() }.getOrDefault(false)
        val failedOpens = countThisOpen()
        // A quarantine cut halfway means the last opening found the data damaged and never got to restore
        // it. Reporting the now missing file as a fresh start would hide that; recovery offers the copies.
        if (finishedAside) return@withContext DataHealthReport(false, false, failedOpens)
        if (!source.exists() || source.length() == 0L) return@withContext DataHealthReport(true, false, failedOpens)
        // SQLite reads a file shorter than a page as an empty database, says "ok" and Room writes over it.
        if (!hasSqliteHeader()) return@withContext DataHealthReport(false, false, failedOpens)
        val inspected = runCatching {
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { db ->
                val intact = db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
                intact to db.version
            }
        }.getOrNull() ?: return@withContext DataHealthReport(false, false, failedOpens)
        DataHealthReport(inspected.first, inspected.first && !canOpen(inspected.second), failedOpens)
    }

    private fun hasSqliteHeader(): Boolean = runCatching {
        val header = ByteArray(SQLITE_HEADER.size)
        source.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) break
                read += count
            }
            read == header.size && header.contentEquals(SQLITE_HEADER)
        }
    }.getOrDefault(false)

    /** Version 0 is a file Room has not initialised yet. Room refuses any other version it has no path for. */
    private fun canOpen(version: Int): Boolean {
        if (version == 0 || version == DB_VERSION) return true
        var reached = version
        while (reached < DB_VERSION) reached = MIGRATIONS.firstOrNull { it.startVersion == reached }?.endVersion ?: return false
        return reached == DB_VERSION
    }

    /**
     * A crash at opening is only visible from the next opening, so every opening counts until it has
     * survived a while. Written synchronously: the process may be about to die. A new boot starts over,
     * so that cutting the ignition three times is not mistaken for the app crashing.
     */
    private fun countThisOpen(): Int {
        priorFailedOpens?.let { return it }
        val boot = bootCount()
        val sameBoot = boot == null || !prefs.contains(KEY_BOOT) || prefs.getInt(KEY_BOOT, 0) == boot
        val prior = if (sameBoot) prefs.getInt(KEY_FAILED, 0) else 0
        prefs.edit().putInt(KEY_FAILED, prior + 1).apply { if (boot != null) putInt(KEY_BOOT, boot) }.commit()
        priorFailedOpens = prior
        scope.launch {
            delay(stableAfterMs)
            prefs.edit().putInt(KEY_FAILED, 0).commit()
        }
        return prior
    }

    override suspend fun quarantineDatabase(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            handle.close()
            finishPendingQuarantines()
            val directory = source.parentFile!!
            if (siblings.none { File(directory, it).exists() }) return@runCatching true
            val stamp = System.currentTimeMillis()
            val name = generateSequence(0) { it + 1 }.map { "quarantine-$stamp-$it" }
                .first { !File(directory, it).exists() && !File(directory, it + PENDING).exists() }
            val pending = File(directory, name + PENDING)
            if (!pending.mkdirs()) return@runCatching false
            fsyncDirectory(directory)
            complete(pending)
        }.getOrDefault(false)
    }

    /** True when a quarantine cut halfway was finished now. */
    private fun finishPendingQuarantines(): Boolean =
        source.parentFile?.listFiles { f -> f.isDirectory && f.name.startsWith("quarantine-") && f.name.endsWith(PENDING) }
            ?.map(::complete)?.any { it } ?: false

    /**
     * The files cannot move as one, so the folder says ".pending" until all did, and a death halfway is
     * finished on the next opening. Left alone next to a new empty database, SQLite would delete the old WAL.
     */
    private fun complete(pending: File): Boolean {
        val directory = pending.parentFile!!
        val moved = siblings.map { File(directory, it) }.filter(File::exists).all { it.renameTo(File(pending, it.name)) }
        if (!moved) return false
        fsyncDirectory(pending)
        fsyncDirectory(directory)
        val renamed = pending.renameTo(File(directory, pending.name.removeSuffix(PENDING)))
        if (renamed) fsyncDirectory(directory)
        return renamed
    }

    private companion object {
        const val KEY_FAILED = "failed-opens"
        const val KEY_BOOT = "boot"
    }
}

data class StorageComponents internal constructor(
    internal val handle: DatabaseHandle,
    val transactions: TransactionRunner,
    val trips: TripRepository,
    val odometer: OdometerRepository,
    val maintenance: MaintenanceRepository,
    val expenses: ExpenseRepository,
    val parking: ParkingRepository,
    val settings: SettingsRepository,
    val backups: BackupStore,
    val externalBackups: ExternalBackupTransfer,
    val health: DataHealthChecker,
) {
    /** The instance in use right now. It changes when the data check needs the file: never keep it. */
    val database: GuanteraDatabase get() = handle.database
}

object StorageFactory {
    fun create(context: Context): StorageComponents {
        val app = context.applicationContext
        val handle = DatabaseHandle {
            Room.databaseBuilder(app, GuanteraDatabase::class.java, "guantera.db")
                .openHelperFactory(KeepCorruptFilesFactory())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA synchronous=FULL")
                    }
                })
                .build()
        }
        val backups = PublicOrPrivateBackupStore(
            FileBackupStore(File(Environment.getExternalStorageDirectory(), BACKUP_FOLDER)),
            FileBackupStore(File(app.getExternalFilesDir(null) ?: app.filesDir, BACKUP_FOLDER)),
        ) { hasPublicStorage(app) }
        return StorageComponents(
            handle, RoomTransactionRunner(handle),
            RoomTripRepository(handle), RoomOdometerRepository(handle),
            RoomMaintenanceRepository(handle), RoomExpenseRepository(handle),
            RoomParkingRepository(handle), RoomSettingsRepository(handle),
            backups, UsbBackupTransfer(app), RoomDataHealthChecker(app, handle),
        )
    }

    private fun hasPublicStorage(context: Context) =
        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
}
