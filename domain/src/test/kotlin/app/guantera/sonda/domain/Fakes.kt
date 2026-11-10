package app.guantera.sonda.domain

import app.guantera.sonda.domain.model.AppendResult
import app.guantera.sonda.domain.model.DeviceDiagnostics
import app.guantera.sonda.domain.model.LogCopyResult
import app.guantera.sonda.domain.model.LogHealth
import app.guantera.sonda.domain.model.LogRead
import app.guantera.sonda.domain.model.PlayServices
import app.guantera.sonda.domain.model.ExportResult
import app.guantera.sonda.domain.model.LocationEvent
import app.guantera.sonda.domain.model.StartResult
import app.guantera.sonda.domain.port.Clock
import app.guantera.sonda.domain.port.DiagnosticsSource
import app.guantera.sonda.domain.port.EventLog
import app.guantera.sonda.domain.port.LocationSource
import app.guantera.sonda.domain.port.ReportStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.StringWriter
import java.io.Writer

class FakeClock(var now: Long = 0, var elapsed: Long = 0, var uptime: Long = 0) : Clock {
    override fun nowMillis() = now
    override fun elapsedRealtimeMillis() = elapsed
    override fun uptimeMillis() = uptime
    fun advance(awakeMillis: Long, sleptMillis: Long = 0) {
        elapsed += awakeMillis + sleptMillis
        uptime += awakeMillis
    }
}

class FakeEventLog : EventLog {
    val lines = mutableListOf<String>()
    var rejectAppends = false
    var readFailure: String? = null
    private val version = MutableStateFlow(0)
    override val health = MutableStateFlow<LogHealth>(LogHealth.Healthy)
    override fun append(message: String): AppendResult {
        if (rejectAppends) return AppendResult.Rejected("disco lleno")
        lines += message
        version.value++
        return AppendResult.Accepted
    }
    override fun observeRecent(count: Int): Flow<LogRead> = version.map {
        readFailure?.let { LogRead.Unreadable(it) } ?: LogRead.Lines(lines.takeLast(count))
    }
    override fun copyTo(out: Writer): LogCopyResult {
        readFailure?.let { return LogCopyResult.Failed(it) }
        lines.forEach { out.write("$it\n") }
        return LogCopyResult.Copied
    }
}

/** Models the real adapter: a second start() while active is a no-op, and failures can be injected. */
class FakeLocationSource(
    override var hasPermission: Boolean = true,
    var startFailure: String? = null,
) : LocationSource {
    val flow = MutableSharedFlow<LocationEvent>(extraBufferCapacity = 16)
    var active = false
        private set
    var registrations = 0
        private set
    var stopCalls = 0
        private set
    override val events: Flow<LocationEvent> = flow
    override fun start(): StartResult = when {
        !hasPermission -> StartResult.PermissionDenied
        active -> StartResult.AlreadyActive
        startFailure != null -> StartResult.Failed(startFailure!!)
        else -> { active = true; registrations++; StartResult.Started(gpsProviderEnabled = true) }
    }
    override fun stop() { stopCalls++; active = false }
}

class FakeDiagnosticsSource(private val diagnostics: DeviceDiagnostics = sampleDiagnostics()) : DiagnosticsSource {
    override fun read() = diagnostics
}

class FakeReportStore(var result: ExportResult = ExportResult.Saved("/Download/report.txt")) : ReportStore {
    val saved = mutableListOf<Pair<String, String>>()
    override fun save(fileName: String, body: (Writer) -> Unit): ExportResult {
        val out = StringWriter()
        body(out)
        saved += fileName to out.toString()
        return result
    }
}

fun sampleDiagnostics() = DeviceDiagnostics(
    androidSdk = 27, androidRelease = "8.1.0", model = "M", manufacturer = "F", hardware = "H",
    supportedAbis = listOf("x86"), memTotalKb = 2_000_000, internalStorageTotalMb = 6_000,
    webView = null, playServices = PlayServices.NotInstalled, locationProviders = listOf("gps"),
)
