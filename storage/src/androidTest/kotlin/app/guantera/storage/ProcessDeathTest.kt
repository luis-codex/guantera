package app.guantera.storage

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.backup.BackupValidator
import app.guantera.domain.backup.BackupVerdict
import app.guantera.domain.port.BackupWriteResult
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.time.DayRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/** The victim is a real separate process and dies by a real kill, in the middle of the work. */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    private val markers get() = VictimService.markerDir(appContext)
    private val everything = UtcWindow(null, null)

    @Before fun setUp() { resetStorageFiles(); markers.listFiles()?.forEach { it.delete() } }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun startVictim(scenario: String, dir: File? = null, stage: String? = null): Int {
        markers.listFiles()?.forEach { it.delete() }
        val intent = Intent().setComponent(ComponentName(appContext, VictimService::class.java))
            .putExtra(VictimService.SCENARIO, scenario).putExtra(VictimService.BACKUP_DIR, dir?.path).putExtra(VictimService.STAGE, stage)
        val pidFile = File(markers, VictimService.PID)
        // Right after a kill the system can hand the start to the process that just died, and it is lost.
        repeat(8) {
            appContext.startService(intent)
            val end = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < end) {
                if (pidFile.exists() && pidFile.readText().isNotEmpty()) return pidFile.readText().toInt()
                Thread.sleep(20)
            }
        }
        error("the victim never started")
    }

    private fun awaitDeath(pid: Int) = waitFor("process $pid to die") { !File("/proc/$pid").exists() }

    private fun killMidWork(pid: Int) {
        waitFor("the first committed round", 60_000) { File(markers, VictimService.STARTED).exists() }
        Thread.sleep(Random.nextLong(5, 120))
        Process.killProcess(pid)
        awaitDeath(pid)
    }

    @Test fun deathInsideATransactionLeavesOnlyWhatWasCommitted() = runBlocking<Unit> {
        val before = StorageFactory.create(appContext)
        before.trips.insertClosed(trip(start = 1))
        before.handle.close()

        awaitDeath(startVictim(VictimService.TX_SELF_KILL))

        val after = StorageFactory.create(appContext)
        val report = after.health.check()
        assertTrue(report.databaseIntact)
        assertEquals(listOf(1L), after.trips.closed(everything).map { it.startUtcMs })
        assertTrue(after.expenses.inRange(DayRange()).isEmpty())
        after.trips.insertClosed(trip(start = 2))
        assertEquals(2, after.trips.closed(everything).size)
        after.handle.close()
    }

    @Test fun repeatedDeathsDuringTransactionsNeverLeaveHalfARound() = runBlocking<Unit> {
        repeat(4) { attempt ->
            killMidWork(startVictim(VictimService.TX_LOOP))
            val after = StorageFactory.create(appContext)
            assertTrue("attempt $attempt: database damaged", after.health.check().databaseIntact)
            val trips = after.trips.closed(everything).size
            val rounds = after.expenses.inRange(DayRange()).size
            assertTrue("attempt $attempt: nothing committed", rounds > 0)
            assertEquals("attempt $attempt", rounds * VictimService.TRIPS_PER_ROUND, trips)
            after.handle.close()
        }
    }

    @Test fun repeatedDeathsDuringACopyNeverDamageTheCopies() = runBlocking<Unit> {
        val dir = newBackupDir("killed")
        val store = FileBackupStore(dir)
        val first = (store.save(bigData(), 1) as BackupWriteResult.Validated).backup
        val firstBytes = File(dir, "${first.id}.json").readBytes()

        repeat(4) { attempt ->
            killMidWork(startVictim(VictimService.COPY_LOOP, dir))
            val finalFiles = dir.listFiles { f -> f.extension == "json" }!!
            assertTrue(finalFiles.size >= 2)
            finalFiles.forEach { file ->
                val read = store.read(file.nameWithoutExtension)
                assertTrue("attempt $attempt: ${file.name} is $read", read is BackupReadResult.Read)
                assertTrue(BackupValidator.validate((read as BackupReadResult.Read).file) is BackupVerdict.Valid)
            }
        }
        assertTrue("the old copy changed", firstBytes.contentEquals(File(dir, "${first.id}.json").readBytes()))
        assertTrue(store.save(bigData(1), 9_999_999) is BackupWriteResult.Validated)
        assertFalse("temporaries survived the next save", dir.list()!!.any { it.endsWith(".tmp") })
    }

    @Test fun deathAtEachStageOfACopyKeepsTheOldOneAndNeverLeavesABadOne() = runBlocking<Unit> {
        BackupStage.values().forEach { stage ->
            val dir = newBackupDir("stage-$stage")
            val store = FileBackupStore(dir)
            val old = (store.save(richData(), 1) as BackupWriteResult.Validated).backup
            val oldBytes = File(dir, "${old.id}.json").readBytes()

            awaitDeath(startVictim(VictimService.COPY_STAGE, dir, stage.name))

            val copies = dir.list()!!.filter { it.endsWith(".json") }
            val renamed = stage >= BackupStage.RENAMED
            assertEquals("$stage", if (renamed) 2 else 1, copies.size)
            assertEquals("$stage: temporary", !renamed, dir.list()!!.any { it.endsWith(".tmp") })
            assertTrue("$stage", oldBytes.contentEquals(File(dir, "${old.id}.json").readBytes()))
            store.list().forEach { backup ->
                val file = (store.read(backup.id) as BackupReadResult.Read).file
                assertTrue("$stage", BackupValidator.validate(file) is BackupVerdict.Valid)
            }
            assertTrue(store.save(richData(), 2) is BackupWriteResult.Validated)
            assertFalse("$stage", dir.list()!!.any { it.endsWith(".tmp") })
        }
    }
}
