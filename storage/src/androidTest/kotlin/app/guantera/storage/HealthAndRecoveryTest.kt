package app.guantera.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guantera.domain.port.BackupWriteResult
import app.guantera.domain.port.StorageException
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.usecase.CheckDataHealthAndRecover
import app.guantera.domain.usecase.DataHealthOutcome
import app.guantera.domain.usecase.RecoveryReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class HealthAndRecoveryTest {
    private lateinit var storage: StorageComponents
    private lateinit var dir: File
    private lateinit var store: FileBackupStore

    @Before fun setUp() {
        resetStorageFiles()
        storage = StorageFactory.create(appContext)
        dir = newBackupDir("recovery")
        store = FileBackupStore(dir)
    }
    @After fun tearDown() { runCatching { storage.handle.close() } }

    private val everything = UtcWindow(null, null)

    private suspend fun populate(rows: Int = 400) {
        storage.transactions.run { repeat(rows) { storage.trips.insertClosed(trip(start = it.toLong())) } }
        storage.handle.close()
    }

    private fun fillWithGarbage(): ByteArray {
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        val garbage = ByteArray(dbFile.length().toInt().coerceAtLeast(8192)) { (it * 31).toByte() }
        dbFile.writeBytes(garbage)
        return garbage
    }

    private fun damagePagesKeepingTheHeader(): ByteArray {
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        RandomAccessFile(dbFile, "rw").use { file ->
            file.seek(4096)
            file.write(ByteArray((file.length() - 4096).toInt()) { 0x5A })
        }
        return dbFile.readBytes()
    }

    private fun reopen() { storage = StorageFactory.create(appContext) }

    @Test fun checkOnAHealthyDatabaseLeavesRoomWorking() = runBlocking<Unit> {
        storage.trips.insertClosed(trip())
        val report = storage.health.check()
        assertTrue(report.databaseIntact)
        assertFalse(report.migrationFailed)
        storage.trips.insertClosed(trip(start = 7))
        assertEquals(2, storage.trips.closed(everything).size)
    }

    @Test fun checkWithNoDatabaseYetIsHealthy() = runBlocking<Unit> {
        assertTrue(storage.health.check().databaseIntact)
        storage.trips.insertClosed(trip())
        assertEquals(1, storage.trips.closed(everything).size)
    }

    @Test fun garbageIsDetectedAndNotDeletedByTheCheck() = runBlocking<Unit> {
        populate()
        val garbage = fillWithGarbage()
        reopen()
        assertFalse(storage.health.check().databaseIntact)
        assertTrue("the check destroyed the damaged file", dbFile.exists() && garbage.contentEquals(dbFile.readBytes()))
    }

    private fun overwriteWith(bytes: ByteArray) {
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        dbFile.writeBytes(bytes)
    }

    private suspend fun assertDamagedAndKept(bytes: ByteArray) {
        populate(5)
        overwriteWith(bytes)
        reopen()
        assertFalse("${bytes.size} bytes passed as a database", storage.health.check().databaseIntact)
        assertTrue("the check destroyed the damaged file", dbFile.exists() && bytes.contentEquals(dbFile.readBytes()))
    }

    // SQLite reads these as an empty database: integrity_check says "ok" and Room writes over them.
    @Test fun aOneByteFileIsDamaged() = runBlocking<Unit> { assertDamagedAndKept(byteArrayOf(0x78)) }

    @Test fun aFileShorterThanTheHeaderIsDamaged() = runBlocking<Unit> { assertDamagedAndKept(ByteArray(15) { 0x41 }) }

    @Test fun theHeaderCutShortIsDamaged() = runBlocking<Unit> { assertDamagedAndKept("SQLite format 3".toByteArray()) }

    @Test fun anEmptyFileIsAnEmptyDatabase() = runBlocking<Unit> {
        populate(5)
        overwriteWith(ByteArray(0))
        reopen()
        assertTrue(storage.health.check().databaseIntact)
        storage.trips.insertClosed(trip())
        assertEquals(1, storage.trips.closed(everything).size)
    }

    @Test fun aOneByteFileIsSetAsideByTheRecoveryAndRoomStillWorks() = runBlocking<Unit> {
        populate(5)
        overwriteWith(byteArrayOf(0x78))
        reopen()
        val outcome = CheckDataHealthAndRecover(storage.health, store, storage.access())()
        assertEquals(RecoveryReason.NO_VALID_COPY, (outcome as DataHealthOutcome.NeedsUser).reason)
        assertTrue(byteArrayOf(0x78).contentEquals(File(quarantined().single(), "guantera.db").readBytes()))
        storage.trips.insertClosed(trip())
        assertEquals(1, storage.trips.closed(everything).size)
    }

    @Test fun damagedPagesAreDetectedAndNotDeletedByTheCheck() = runBlocking<Unit> {
        populate()
        val damaged = damagePagesKeepingTheHeader()
        reopen()
        assertFalse(storage.health.check().databaseIntact)
        assertTrue("the check destroyed the damaged file", dbFile.exists() && damaged.contentEquals(dbFile.readBytes()))
    }

    @Test fun roomNeverDeletesACorruptFileOnItsOwn() = runBlocking<Unit> {
        populate()
        val garbage = fillWithGarbage()
        reopen()
        runCatching { storage.trips.closed(everything) }
        runCatching { storage.trips.insertClosed(trip()) }
        runCatching { storage.handle.close() }
        assertTrue("Room replaced the damaged file", dbFile.exists() && garbage.contentEquals(dbFile.readBytes()))
    }

    @Test fun roomKeepsAFileWhosePagesGoBadWhileReading() = runBlocking<Unit> {
        populate()
        val damaged = damagePagesKeepingTheHeader()
        reopen()
        runCatching { storage.trips.closed(everything) }
        runCatching { storage.handle.close() }
        assertTrue("Room deleted the damaged file", dbFile.exists() && damaged.contentEquals(dbFile.readBytes()))
    }

    @Test fun quarantineKeepsTheBytesAndLeavesAWorkingEmptyDatabase() = runBlocking<Unit> {
        populate()
        val garbage = fillWithGarbage()
        reopen()
        assertTrue(storage.health.quarantineDatabase())
        val aside = quarantined().single()
        assertTrue(garbage.contentEquals(File(aside, "guantera.db").readBytes()))
        assertTrue(storage.trips.closed(everything).isEmpty())
        storage.trips.insertClosed(trip())
        assertEquals(1, storage.trips.closed(everything).size)
    }

    @Test fun quarantineMovesTheWalToo() = runBlocking<Unit> {
        storage.trips.insertClosed(trip())
        val wal = File("${dbFile.path}-wal")
        val walBytes = wal.readBytes()
        assertTrue(walBytes.isNotEmpty())
        // A clean close checkpoints and removes the WAL; a power cut leaves it, so put it back.
        storage.handle.close()
        wal.writeBytes(walBytes)
        assertTrue(storage.health.quarantineDatabase())
        assertFalse(wal.exists())
        assertTrue(walBytes.contentEquals(File(quarantined().single(), "guantera.db-wal").readBytes()))
        assertTrue(storage.trips.closed(everything).isEmpty())
    }

    @Test fun quarantineWithNothingToMoveSucceeds() = runBlocking<Unit> {
        assertTrue(storage.health.quarantineDatabase())
        storage.trips.insertClosed(trip())
    }

    @Test fun twoQuarantinesNeverOverwriteEachOther() = runBlocking<Unit> {
        repeat(5) {
            storage.trips.insertClosed(trip())
            assertTrue(storage.health.quarantineDatabase())
        }
        assertEquals(5, quarantined().size)
    }

    @Test fun recoveryRestoresTheLatestValidCopy() = runBlocking<Unit> {
        storage.access().replace(richData())
        val snapshot = storage.access().read()
        assertTrue(store.save(snapshot, 1_000) is BackupWriteResult.Validated)
        val damagedCopy = (store.save(snapshot, 2_000) as BackupWriteResult.Validated).backup
        File(dir, "${damagedCopy.id}.json").writeText("{roto")
        populate(50)
        fillWithGarbage()
        reopen()
        val outcome = CheckDataHealthAndRecover(storage.health, store, storage.access())()
        assertTrue("got $outcome", outcome is DataHealthOutcome.Restored)
        assertEquals(richData().sorted(), storage.access().read().sorted())
        assertEquals(1, quarantined().size)
        assertEquals("the damaged copy was not set aside", 1, store.list().size)
        assertTrue(dir.list()!!.any { it.contains("quarantine") })
    }

    @Test fun recoveryWithoutCopiesAsksTheUserAndRoomStillWorks() = runBlocking<Unit> {
        populate(50)
        fillWithGarbage()
        reopen()
        val outcome = CheckDataHealthAndRecover(storage.health, store, storage.access())()
        assertEquals(RecoveryReason.NO_VALID_COPY, (outcome as DataHealthOutcome.NeedsUser).reason)
        storage.trips.insertClosed(trip())
        assertEquals(1, storage.trips.closed(everything).size)
    }

    @Test fun aDatabaseFromTheFutureIsReportedAndKept() = runBlocking<Unit> {
        storage.trips.insertClosed(trip())
        storage.handle.close()
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
        reopen()
        val report = storage.health.check()
        assertTrue(report.databaseIntact)
        assertTrue("a version Room cannot open has to be reported", report.migrationFailed)
        val failure = try { storage.trips.insertClosed(trip()); null } catch (e: Exception) { e }
        assertTrue("escaped as $failure", failure is StorageException)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(99, db.version)
            db.rawQuery("SELECT COUNT(*) FROM trips", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    private fun checker(boot: Int? = 1, stable: Long = 3_600_000) = RoomDataHealthChecker(appContext, storage.handle, stable, { boot })

    @Test fun crashesAtOpeningAreCountedAcrossProcesses() = runBlocking<Unit> {
        assertEquals(listOf(0, 1, 2, 3), (1..4).map { checker().check().consecutiveFailedOpens })
    }

    @Test fun oneProcessCountsOnce() = runBlocking<Unit> {
        val one = checker()
        assertEquals(listOf(0, 0), listOf(one.check(), one.check()).map { it.consecutiveFailedOpens })
    }

    @Test fun aNewBootStartsTheCountOver() = runBlocking<Unit> {
        repeat(3) { checker(boot = 1).check() }
        assertEquals(0, checker(boot = 2).check().consecutiveFailedOpens)
    }

    @Test fun anOpeningThatSurvivesClearsTheCount() = runBlocking<Unit> {
        repeat(3) { checker().check() }
        checker(stable = 50).check()
        kotlinx.coroutines.delay(1_000)
        assertEquals(0, checker().check().consecutiveFailedOpens)
    }

    @Test fun aQuarantineInterruptedHalfwayIsFinishedOnTheNextCheck() = runBlocking<Unit> {
        storage.trips.insertClosed(trip())
        storage.handle.close()
        val pending = File(dbFile.parentFile, "quarantine-1-0.pending").apply { mkdirs() }
        assertTrue(dbFile.renameTo(File(pending, dbFile.name)))
        val wal = File("${dbFile.path}-wal")
        wal.writeBytes(byteArrayOf(1, 2, 3))
        reopen()
        // Damaged: the opening that started it never restored anything, so recovery must still run.
        assertFalse(storage.health.check().databaseIntact)
        assertFalse(wal.exists())
        val aside = quarantined().single()
        assertEquals("quarantine-1-0", aside.name)
        assertTrue(File(aside, "guantera.db-wal").exists() && File(aside, "guantera.db").exists())
    }

    @Test fun aQuarantineCutHalfwayStillEndsInTheRestoredCopy() = runBlocking<Unit> {
        populate(3)
        val copy = storage.access().read()
        assertTrue(store.save(copy, 1_000L) is BackupWriteResult.Validated)
        val pending = File(dbFile.parentFile, "quarantine-1-0.pending").apply { mkdirs() }
        assertTrue(dbFile.renameTo(File(pending, dbFile.name)))
        reopen()
        val outcome = CheckDataHealthAndRecover(storage.health, store, storage.access())()
        assertTrue("got $outcome", outcome is DataHealthOutcome.Restored)
        assertEquals(3, storage.trips.closed(everything).size)
        assertTrue(File(quarantined().single(), "guantera.db").exists())
    }

    @Test fun aCorruptFileRoomCannotOpenIsATypedWriteFailure() = runBlocking<Unit> {
        populate(10)
        fillWithGarbage()
        reopen()
        val failure = try { storage.trips.insertClosed(trip()); null } catch (e: Exception) { e }
        assertTrue("escaped as $failure", failure is StorageException)
    }
}
