package app.guantera.storage

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.port.PageRequest
import app.guantera.domain.port.StorageException
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.settings.Settings
import app.guantera.domain.time.DayRange
import app.guantera.domain.time.FixTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoriesTest {
    private class Boom : RuntimeException()
    private lateinit var storage: StorageComponents

    @Before fun setUp() { resetStorageFiles(); storage = StorageFactory.create(appContext) }
    @After fun tearDown() { storage.handle.close() }

    private val everything = UtcWindow(null, null)

    @Test fun sqliteIsConfiguredForPowerCuts() = runBlocking<Unit> {
        storage.trips.insertClosed(trip())
        // Inside a transaction, so the pragma is read on the connection that writes.
        storage.database.withTransaction {
            val db = storage.database.openHelper.writableDatabase
            db.query("PRAGMA journal_mode").use { it.moveToFirst(); assertEquals("wal", it.getString(0).lowercase()) }
            db.query("PRAGMA synchronous").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
        }
    }

    @Test fun rollbackUndoesEveryRepositoryAndRethrowsTheSameError() = runBlocking<Unit> {
        storage.expenses.save(expense(note = "antes"))
        val boom = Boom()
        try {
            storage.transactions.run {
                storage.trips.insertClosed(trip())
                storage.parking.insert(spot())
                storage.settings.update { it.copy(currency = "X") }
                storage.expenses.save(expense(note = "dentro"))
                throw boom
            }
            @Suppress("UNREACHABLE_CODE") fail("did not rethrow")
        } catch (e: Boom) {
            assertTrue(e === boom)
        }
        assertTrue(storage.trips.closed(everything).isEmpty())
        assertTrue(storage.parking.all().isEmpty())
        assertEquals("$", storage.settings.get().currency)
        assertEquals(listOf("antes"), storage.expenses.inRange(DayRange()).map { it.note })
    }

    @Test fun nestedTransactionsCommitAndRollBackAsOne() = runBlocking<Unit> {
        storage.transactions.run {
            storage.trips.insertClosed(trip(start = 1))
            storage.transactions.run { storage.trips.insertClosed(trip(start = 2)) }
        }
        assertEquals(2, storage.trips.closed(everything).size)
        runCatching {
            storage.transactions.run {
                storage.trips.insertClosed(trip(start = 3))
                storage.transactions.run { storage.trips.insertClosed(trip(start = 4)) }
                throw Boom()
            }
        }
        assertEquals(2, storage.trips.closed(everything).size)
        runCatching {
            storage.transactions.run {
                storage.trips.insertClosed(trip(start = 5))
                storage.transactions.run { throw Boom() }
            }
        }
        assertEquals(2, storage.trips.closed(everything).size)
    }

    @Test fun changesWorkOnAnEmptyDatabaseAndOnlyAfterCommit() = runBlocking<Unit> {
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { storage.trips.changes.collect { signals.send(Unit) } }
        withTimeout(5_000) { signals.receive() }
        delay(500)
        while (signals.tryReceive().isSuccess) Unit

        storage.transactions.run {
            storage.trips.insertClosed(trip())
            delay(500)
            assertTrue("emitted before the commit", signals.isEmpty)
        }
        withTimeout(5_000) { signals.receive() }

        delay(500)
        while (signals.tryReceive().isSuccess) Unit
        runCatching { storage.transactions.run { storage.trips.insertClosed(trip()); throw Boom() } }
        delay(700)
        assertTrue("emitted for a rolled back transaction", signals.isEmpty)
        job.cancel()
    }

    @Test fun activeTripLivesInASingleRow() = runBlocking<Unit> {
        storage.trips.saveActive(activeRecord(1))
        storage.trips.saveActive(activeRecord(2))
        assertEquals(activeRecord(2), storage.trips.activeTrip())
        storage.database.query("SELECT COUNT(*) FROM active_trip", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        storage.trips.clearActive()
        assertNull(storage.trips.activeTrip())
    }

    @Test fun closedTripsUseHalfOpenWindowsNewestFirstAndAreOnlyInserted() = runBlocking<Unit> {
        val id = storage.trips.insertClosed(trip(id = 999, start = 100))
        assertTrue(id != 999L)
        storage.trips.insertClosed(trip(start = 200))
        storage.trips.insertClosed(trip(start = 300))
        assertEquals(listOf(200L, 100L), storage.trips.closed(UtcWindow(100, 300)).map { it.startUtcMs })
        assertEquals(listOf(300L), storage.trips.closed(UtcWindow(300, null)).map { it.startUtcMs })
        assertEquals(listOf(200L), storage.trips.closed(everything, PageRequest(1, 1)).map { it.startUtcMs })
        assertEquals(1234.5 * 2, storage.trips.totalMeters(UtcWindow(null, 300)), 0.0001)
        assertEquals(100L, storage.trips.find(id)!!.startUtcMs)
    }

    @Test fun replaceAllTripsClearsTheActiveSlot() = runBlocking<Unit> {
        storage.trips.saveActive(activeRecord())
        storage.trips.replaceAll(listOf(trip(id = 50)))
        assertNull(storage.trips.activeTrip())
        assertEquals(listOf(50L), storage.trips.closed(everything).map { it.id })
    }

    @Test fun expensesUseHalfOpenRangesAndOpenBounds() = runBlocking<Unit> {
        listOf(100, 101, 102).forEach { storage.expenses.save(expense(date = it, amount = it.toLong())) }
        assertEquals(listOf(101, 100), storage.expenses.inRange(DayRange(day(100), day(102))).map { it.date.epochDay })
        assertEquals(listOf(102, 101, 100), storage.expenses.inRange(DayRange()).map { it.date.epochDay })
        assertEquals(100L, storage.expenses.totalMinor(DayRange(null, day(101))))
        val id = storage.expenses.save(expense(date = 100, amount = 1))
        storage.expenses.save(expense(id = id, date = 100, amount = 2))
        assertEquals(2L, storage.expenses.find(id)!!.amountMinor)
        storage.expenses.delete(id)
        assertNull(storage.expenses.find(id))
    }

    @Test fun parkingCurrentIsLatestInstantThenHighestId() = runBlocking<Unit> {
        storage.parking.insert(spot(instant = 300))
        storage.parking.insert(spot(instant = 100))
        val winner = storage.parking.insert(spot(instant = 300))
        assertEquals(winner, storage.parking.current()!!.id)
        storage.parking.updateNote(winner, "acá")
        assertEquals("acá", storage.parking.current()!!.note)
        assertEquals(3, storage.parking.all().size)
    }

    @Test fun odometerOnlyAppends() = runBlocking<Unit> {
        storage.odometer.add(OdometerCalibration(200, 2.0, 2.0))
        storage.odometer.add(OdometerCalibration(100, 1.0, 1.0))
        assertEquals(2, storage.odometer.all().size)
        assertEquals(200L, storage.odometer.latest()!!.dateUtcMs)
    }

    @Test fun deletingAnItemKeepsItsServices() = runBlocking<Unit> {
        val id = storage.maintenance.save(item())
        storage.maintenance.addService(app.guantera.domain.maintenance.ServiceRecord(0, id, day(10), null, null))
        storage.maintenance.delete(id)
        assertNull(storage.maintenance.find(id))
        assertEquals(1, storage.maintenance.services().size)
    }

    @Test fun settingsDefaultAndKeepTheFixTimeAcrossUpdates() = runBlocking<Unit> {
        assertEquals(Settings(), storage.settings.get())
        assertNull(storage.settings.lastFixTime())
        val fix = FixTime(1, 2, null)
        storage.settings.saveLastFixTime(fix)
        storage.settings.update { it.copy(currency = "U") }
        assertEquals(fix, storage.settings.lastFixTime())
        assertEquals("U", storage.settings.get().currency)
    }

    @Test fun aRealFullDiskIsReportedAsFullAndLeavesTheDatabaseUsable() = runBlocking<Unit> {
        storage.expenses.save(expense(note = "antes"))
        limitDatabaseToItsCurrentSize()
        val failure = try {
            repeat(5_000) { storage.expenses.save(expense(note = "x".repeat(2_000))) }
            null
        } catch (e: Exception) { e }
        assertNotNull("never filled up", failure)
        assertTrue("escaped as $failure", failure is StorageException)
        assertEquals(StorageFailure.FULL, (failure as StorageException).failure)
        assertTrue(storage.expenses.inRange(DayRange()).any { it.note == "antes" })
    }

    @Test fun aFullDiskDuringATransactionIsATypedFailureAndRollsBack() = runBlocking<Unit> {
        limitDatabaseToItsCurrentSize()
        val failure = try {
            storage.transactions.run { repeat(5_000) { storage.expenses.save(expense(note = "x".repeat(2_000))) } }
            null
        } catch (e: Exception) { e }
        assertTrue("escaped as $failure", failure is StorageException)
        assertTrue(storage.expenses.inRange(DayRange()).isEmpty())
    }

    @Test fun aSqliteErrorThatIsNotFullBecomesIo() = runBlocking<Unit> {
        val failure = try { storage.trips.replaceAll(listOf(trip(id = 1), trip(id = 1))); null } catch (e: Exception) { e }
        assertTrue("escaped as $failure", failure is StorageException)
        assertEquals(StorageFailure.IO, (failure as StorageException).failure)
    }

    @Test fun anUnencodableActiveTripBecomesIo() = runBlocking<Unit> {
        val fix = activeRecord().trip.lastValidFix.copy(speedMps = Double.NaN)
        val bad = activeRecord().let { it.copy(trip = it.trip.copy(lastValidFix = fix)) }
        val failure = try { storage.trips.saveActive(bad); null } catch (e: Exception) { e }
        assertTrue("escaped as $failure", failure is StorageException)
        assertFalse(storage.database.inTransaction())
    }

    private suspend fun limitDatabaseToItsCurrentSize() {
        storage.settings.update { it }
        storage.database.withTransaction {
            val db = storage.database.openHelper.writableDatabase
            val pages = db.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) }
            db.query("PRAGMA max_page_count=$pages").use { it.moveToFirst() }
        }
    }

    @Test fun changesOnlyWakeTheirOwnChannelAndSkipTheHeartbeat() = runBlocking<Unit> {
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { storage.trips.changes.collect { signals.send(Unit) } }
        withTimeout(5_000) { signals.receive() }
        delay(400)
        storage.trips.saveActive(activeRecord())
        storage.odometer.add(OdometerCalibration(1, 1.0, 1.0))
        storage.settings.saveLastFixTime(FixTime(1, 2, 3))
        delay(700)
        assertTrue("woken by something that did not change closed trips", signals.isEmpty)
        storage.trips.insertClosed(trip())
        withTimeout(5_000) { signals.receive() }
        job.cancel()
    }

    @Test fun changesKeepWorkingAfterTheDatabaseIsReopened() = runBlocking<Unit> {
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { storage.expenses.changes.collect { signals.send(Unit) } }
        withTimeout(5_000) { signals.receive() }
        storage.health.check()
        withTimeout(5_000) { signals.receive() }
        delay(300)
        while (signals.tryReceive().isSuccess) Unit
        storage.expenses.save(expense())
        withTimeout(5_000) { signals.receive() }
        job.cancel()
    }

    @Test fun readingSettingsNeverWrites() = runBlocking<Unit> {
        assertEquals(Settings(), storage.settings.get())
        storage.database.query("SELECT COUNT(*) FROM settings", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
}
