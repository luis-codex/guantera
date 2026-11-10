package app.guantera.sonda.domain.port

import app.guantera.sonda.domain.model.AppendResult
import app.guantera.sonda.domain.model.DeviceDiagnostics
import app.guantera.sonda.domain.model.ExportResult
import app.guantera.sonda.domain.model.LocationEvent
import app.guantera.sonda.domain.model.LogCopyResult
import app.guantera.sonda.domain.model.LogHealth
import app.guantera.sonda.domain.model.LogRead
import app.guantera.sonda.domain.model.StartResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.Writer

interface Clock {
    fun nowMillis(): Long
    fun elapsedRealtimeMillis(): Long
    fun uptimeMillis(): Long
}

/**
 * Persistent event journal. No method throws: failures are values ([AppendResult], [LogRead],
 * [LogCopyResult]) or state ([health]), so a broken disk can never take the app down.
 */
interface EventLog {
    val health: StateFlow<LogHealth>
    fun append(message: String): AppendResult
    fun observeRecent(count: Int): Flow<LogRead>

    /** Streams the whole journal, oldest line first, into [out]. Errors raised by [out] propagate. */
    fun copyTo(out: Writer): LogCopyResult
}

interface LocationSource {
    val hasPermission: Boolean
    val events: Flow<LocationEvent>
    fun start(): StartResult
    fun stop()
}

interface DiagnosticsSource {
    fun read(): DeviceDiagnostics
}

interface ReportStore {
    /** Writes the report produced by [body] so that a partial file never appears under [fileName]. */
    fun save(fileName: String, body: (Writer) -> Unit): ExportResult
}
