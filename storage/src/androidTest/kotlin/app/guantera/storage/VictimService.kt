package app.guantera.storage

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import app.guantera.domain.port.UtcWindow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread

/** Runs in its own process (":victim") so that dying here is a real process death for the storage. */
class VictimService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val scenario = intent.getStringExtra(SCENARIO)!!
        val markers = markerDir(this)
        File(markers, PID).writeText(Process.myPid().toString())
        thread { runBlocking { run(scenario, intent.getStringExtra(BACKUP_DIR), intent.getStringExtra(STAGE), markers) } }
        return START_NOT_STICKY
    }

    private suspend fun run(scenario: String, backupDir: String?, stage: String?, markers: File) {
        when (scenario) {
            TX_SELF_KILL -> {
                val storage = StorageFactory.create(this)
                storage.transactions.run {
                    repeat(20) { storage.trips.insertClosed(trip(start = 900_000L + it)) }
                    storage.expenses.save(expense(date = 999))
                    Process.killProcess(Process.myPid())
                    Thread.sleep(10_000)
                }
            }
            TX_LOOP -> {
                val storage = StorageFactory.create(this)
                var round = 0L
                while (true) {
                    storage.transactions.run {
                        repeat(TRIPS_PER_ROUND) { storage.trips.insertClosed(trip(start = round * 100 + it)) }
                        storage.expenses.save(expense(date = 100))
                    }
                    round++
                    storage.trips.closed(UtcWindow(null, 1))
                    File(markers, STARTED).createNewFile()
                }
            }
            COPY_LOOP -> {
                val store = FileBackupStore(File(backupDir!!))
                val data = bigData()
                var created = 1L
                while (true) {
                    store.save(data, created++)
                    File(markers, STARTED).createNewFile()
                }
            }
            COPY_STAGE -> {
                val store = FileBackupStore(File(backupDir!!), { reached ->
                    if (reached.name == stage) {
                        Process.killProcess(Process.myPid())
                        Thread.sleep(10_000)
                    }
                }, { File(it.parentFile, "${it.name}.tmp") })
                store.save(richData(), 999)
            }
            else -> error(scenario)
        }
    }

    companion object {
        const val SCENARIO = "scenario"
        const val BACKUP_DIR = "dir"
        const val PID = "pid"
        const val STARTED = "started"
        const val TX_SELF_KILL = "tx-self-kill"
        const val TX_LOOP = "tx-loop"
        const val COPY_LOOP = "copy-loop"
        const val COPY_STAGE = "copy-stage"
        const val STAGE = "stage"
        const val TRIPS_PER_ROUND = 25

        fun markerDir(context: android.content.Context) = File(context.filesDir, "victim").apply { mkdirs() }
    }
}
