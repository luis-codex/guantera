package app.guantera.sonda.data

import app.guantera.sonda.domain.model.AppendResult
import app.guantera.sonda.domain.model.LogCopyResult
import app.guantera.sonda.domain.model.LogHealth
import app.guantera.sonda.domain.model.LogRead
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class FileEventLogTest {
    @get:Rule val temp = TemporaryFolder()
    private val clock = object : Clock {
        override fun nowMillis() = 0L
        override fun elapsedRealtimeMillis() = 0L
        override fun uptimeMillis() = 0L
    }
    private val opened = mutableListOf<FileEventLog>()

    private fun log(
        directory: File = temp.root,
        maxFileBytes: Long = 1024,
        maxFiles: Int = 3,
        queue: Int = 1000,
        sync: (java.io.FileOutputStream) -> Unit = { it.fd.sync() },
    ) = FileEventLog(directory, clock, maxFileBytes, maxFiles, queue, sync).also(opened::add)

    @After fun closeAll() = opened.forEach { it.close() }

    private fun FileEventLog.awaitWrites() { copyTo(StringWriter()) }

    private fun FileEventLog.contents(): List<String> {
        val out = StringWriter()
        assertEquals(LogCopyResult.Copied, copyTo(out))
        return out.toString().lines().filter { it.isNotEmpty() }.map { it.substringAfter(" | ") }
    }

    @Test fun confirmedLinesAreOnDiskWithoutClosing() {
        val log = log()
        log.append("uno")
        log.append("dos")
        log.contents() // copyTo runs after the queued writes
        assertEquals(listOf("uno", "dos"), File(temp.root, "sonda.log").readLines().map { it.substringAfter(" | ") })
    }

    @Test fun everyLineIsForcedToDisk() {
        val syncs = AtomicInteger()
        val log = log(sync = { it.fd.sync(); syncs.incrementAndGet() })
        repeat(5) { log.append("l$it") }
        log.contents()
        assertEquals(5, syncs.get())
    }

    @Test fun keepsOrderUnderConcurrentWriters() {
        val log = log(maxFileBytes = 1_000_000)
        val start = CountDownLatch(1)
        val workers = (0 until 4).map { w -> thread { start.await(); repeat(100) { log.append("w$w-$it") } } }
        start.countDown()
        workers.forEach { it.join() }
        val lines = log.contents()
        assertEquals(400, lines.size)
        for (w in 0 until 4) assertEquals((0 until 100).map { "w$w-$it" }, lines.filter { it.startsWith("w$w-") })
    }

    @Test fun rotationBoundsDiskUsageAndKeepsNewestLines() {
        val log = log(maxFileBytes = 200, maxFiles = 3)
        repeat(60) { log.append("linea-$it") }
        val lines = log.contents()
        assertTrue(lines.isNotEmpty() && lines.size < 60)
        assertEquals("linea-59", lines.last())
        assertEquals(lines.sorted().size, lines.toSet().size)
        val files = temp.root.listFiles { f -> f.name.startsWith("sonda.log") }!!
        assertTrue(files.size <= 3)
        assertTrue(files.all { it.length() <= 200 })
    }

    @Test fun exportIncludesRotatedFilesOldestFirst() {
        val log = log(maxFileBytes = 150, maxFiles = 4)
        repeat(12) { log.append("n%02d".format(it)) }
        val lines = log.contents()
        assertTrue(File(temp.root, "sonda.log.1").exists())
        assertEquals(lines.sorted(), lines)
        assertEquals("n11", lines.last())
    }

    @Test fun tailReadsOnlyTheLastLinesAcrossRotation() = runBlocking {
        val log = log(maxFileBytes = 150, maxFiles = 4)
        repeat(30) { log.append("n%02d".format(it)) }
        val tail = (log.observeRecent(5).first() as LogRead.Lines).lines.map { it.substringAfter(" | ") }
        assertEquals(listOf("n25", "n26", "n27", "n28", "n29"), tail)
    }

    @Test fun diskFailureNeverThrowsAndIsReported() {
        File(temp.root, "sonda.log").mkdirs() // a directory where the file should be
        val log = log()
        assertEquals(AppendResult.Accepted, log.append("x"))
        log.awaitWrites()
        assertTrue(log.health.value is LogHealth.WriteFailing)
        assertTrue(runBlocking { log.observeRecent(5).first() } is LogRead.Unreadable)
        assertTrue(log.copyTo(StringWriter()) is LogCopyResult.Failed)
    }

    @Test fun recoversAfterTheDiskProblemDisappears() {
        val current = File(temp.root, "sonda.log").apply { mkdirs() }
        val log = log()
        log.append("perdida")
        log.awaitWrites()
        current.delete()
        log.append("recuperada")
        assertEquals(listOf("recuperada"), log.contents())
        assertEquals(LogHealth.Healthy, log.health.value)
    }

    @Test fun unwritableDirectoryIsReportedNotThrown() {
        val blocker = temp.newFile("not-a-directory")
        val log = log(directory = File(blocker, "sub"))
        log.append("x")
        log.awaitWrites()
        assertTrue(log.health.value is LogHealth.WriteFailing)
    }

    @Test fun syncFailureIsReportedAsWriteFailure() {
        val log = log(sync = { throw java.io.IOException("EIO") })
        log.append("x")
        log.awaitWrites()
        assertEquals(LogHealth.WriteFailing("EIO"), log.health.value)
    }

    @Test fun fullQueueRejectsInsteadOfBlocking() {
        val gate = CountDownLatch(1)
        val log = log(queue = 2, sync = { gate.await() })
        val results = (0 until 10).map { log.append("l$it") }
        assertTrue(results.any { it is AppendResult.Rejected })
        gate.countDown()
    }

    @Test fun appendAfterCloseIsRejected() {
        val log = log()
        log.close()
        assertTrue(log.append("tarde") is AppendResult.Rejected)
        assertFalse(File(temp.root, "sonda.log").exists())
    }

    @Test fun emptyJournalReadsAsNoLines() = runBlocking {
        assertEquals(LogRead.Lines(emptyList()), log().observeRecent(5).first())
    }
}
