package app.guantera.sonda.data

import app.guantera.sonda.domain.model.AppendResult
import app.guantera.sonda.domain.model.LogCopyResult
import app.guantera.sonda.domain.model.LogHealth
import app.guantera.sonda.domain.model.LogRead
import app.guantera.sonda.domain.port.Clock
import app.guantera.sonda.domain.port.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Journal on disk: `sonda.log` plus rotated `sonda.log.1` (newest) .. `sonda.log.(maxFiles-1)` (oldest).
 *
 * - One daemon writer thread does all file access, so callers never block on disk and lines keep their order.
 * - Every line is `fsync`ed before it counts as written: whatever was confirmed survives a power cut.
 * - Disk usage is bounded by [maxFileBytes] x [maxFiles]; the oldest file is dropped on rotation.
 * - Nothing here throws: failures become [health] / [LogRead.Unreadable] / [LogCopyResult.Failed].
 */
class FileEventLog(
    private val directory: File,
    private val clock: Clock,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
) : EventLog {
    private val formatter = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(queueCapacity)) { task ->
        Thread(task, WRITER_THREAD_NAME).apply { isDaemon = true }
    }
    private val writtenCount = MutableStateFlow(0L)
    private val mutableHealth = MutableStateFlow<LogHealth>(LogHealth.Healthy)

    // Touched only from the writer thread.
    private var stream: FileOutputStream? = null
    private var currentSize = 0L

    override val health: StateFlow<LogHealth> = mutableHealth

    override fun append(message: String): AppendResult {
        val line = "${timestamp()} | $message\n"
        return try {
            executor.execute { write(line) }
            AppendResult.Accepted
        } catch (e: RejectedExecutionException) {
            AppendResult.Rejected("registro ocupado o cerrado")
        }
    }

    override fun observeRecent(count: Int): Flow<LogRead> =
        writtenCount.map { onWriter(LogRead::Unreadable) { readTail(count) } }.flowOn(Dispatchers.IO)

    override fun copyTo(out: Writer): LogCopyResult = onWriter(LogCopyResult::Failed) { copyAll(out) }

    /** Waits for queued lines, closes the file and stops the writer thread. */
    fun close() {
        try {
            executor.execute { closeStream() }
        } catch (e: RejectedExecutionException) {
            return
        }
        executor.shutdown()
        executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun timestamp(): String = synchronized(formatter) { formatter.format(Date(clock.nowMillis())) }

    /** Runs [task] after every queued line, so reads see what was already accepted. */
    private fun <T> onWriter(failure: (String) -> T, task: () -> T): T = try {
        executor.submit<T>(task).get()
    } catch (e: RejectedExecutionException) {
        failure("registro ocupado o cerrado")
    } catch (e: ExecutionException) {
        failure(e.cause?.toString() ?: e.toString())
    }

    private fun write(line: String) {
        try {
            val bytes = line.toByteArray(Charsets.UTF_8)
            if (currentSize > 0 && currentSize + bytes.size > maxFileBytes) rotate()
            val output = openStream()
            output.write(bytes)
            output.flush()
            sync(output)
            currentSize += bytes.size
            mutableHealth.value = LogHealth.Healthy
        } catch (e: Exception) {
            closeStream()
            mutableHealth.value = LogHealth.WriteFailing(e.message ?: e.toString())
        } finally {
            writtenCount.value++
        }
    }

    private fun openStream(): FileOutputStream {
        stream?.let { return it }
        directory.mkdirs()
        val opened = FileOutputStream(currentFile(), true)
        currentSize = currentFile().length()
        stream = opened
        return opened
    }

    private fun closeStream() {
        try {
            stream?.close()
        } catch (e: IOException) {
            // The stream is being discarded; the failure that led here is already reported through health.
        }
        stream = null
        currentSize = 0L
    }

    private fun rotate() {
        closeStream()
        rotatedFile(maxFiles - 1).delete()
        for (index in maxFiles - 2 downTo 1) {
            val source = rotatedFile(index)
            if (source.exists() && !source.renameTo(rotatedFile(index + 1))) throw IOException("No se pudo rotar ${source.name}")
        }
        if (maxFiles > 1 && !currentFile().renameTo(rotatedFile(1))) throw IOException("No se pudo rotar ${currentFile().name}")
        if (maxFiles == 1) currentFile().delete()
    }

    private fun currentFile() = File(directory, LOG_NAME)

    private fun rotatedFile(index: Int) = File(directory, "$LOG_NAME.$index")

    private fun filesOldestFirst(): List<File> = (maxFiles - 1 downTo 1).map(::rotatedFile) + currentFile()

    private fun readTail(count: Int): LogRead = try {
        val collected = ArrayDeque<String>()
        for (file in filesOldestFirst().asReversed()) {
            if (collected.size >= count || !file.exists()) continue
            collected.addAll(0, tailLines(file, count - collected.size))
        }
        LogRead.Lines(collected.toList())
    } catch (e: IOException) {
        LogRead.Unreadable(e.message ?: e.toString())
    }

    private fun tailLines(file: File, count: Int): List<String> = RandomAccessFile(file, "r").use { reader ->
        val start = maxOf(0L, reader.length() - TAIL_CHUNK_BYTES)
        val bytes = ByteArray((reader.length() - start).toInt())
        reader.seek(start)
        reader.readFully(bytes)
        val lines = String(bytes, Charsets.UTF_8).split('\n').dropLast(1)
        (if (start > 0) lines.drop(1) else lines).takeLast(count)
    }

    private fun copyAll(out: Writer): LogCopyResult {
        val buffer = CharArray(COPY_BUFFER_CHARS)
        for (file in filesOldestFirst().filter { it.exists() }) {
            val reader = try {
                file.reader(Charsets.UTF_8)
            } catch (e: IOException) {
                return LogCopyResult.Failed(e.message ?: e.toString())
            }
            reader.use {
                while (true) {
                    val read = try {
                        it.read(buffer)
                    } catch (e: IOException) {
                        return LogCopyResult.Failed(e.message ?: e.toString())
                    }
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
        }
        return LogCopyResult.Copied
    }

    companion object {
        const val LOG_NAME = "sonda.log"

        /** Worst case on disk: 5 files x 1 MiB = 5 MiB, roughly four weeks at one sampled fix every 30 s. */
        const val DEFAULT_MAX_FILE_BYTES = 1024L * 1024L
        const val DEFAULT_MAX_FILES = 5
        private const val DEFAULT_QUEUE_CAPACITY = 1000
        private const val TAIL_CHUNK_BYTES = 64L * 1024L
        private const val COPY_BUFFER_CHARS = 8 * 1024
        private const val CLOSE_TIMEOUT_SECONDS = 5L
        private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
        private const val WRITER_THREAD_NAME = "event-log-writer"
    }
}
