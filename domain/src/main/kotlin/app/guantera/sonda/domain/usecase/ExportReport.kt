package app.guantera.sonda.domain.usecase

import app.guantera.sonda.domain.model.DeviceDiagnostics
import app.guantera.sonda.domain.model.ExportResult
import app.guantera.sonda.domain.model.LogCopyResult
import app.guantera.sonda.domain.port.Clock
import app.guantera.sonda.domain.port.DiagnosticsSource
import app.guantera.sonda.domain.port.EventLog
import app.guantera.sonda.domain.port.ReportStore

/** Writes diagnostics and then streams the journal; an unreadable journal does not stop the diagnostics export. */
class ExportReport(
    private val diagnostics: DiagnosticsSource,
    private val eventLog: EventLog,
    private val store: ReportStore,
    private val clock: Clock,
    private val renderHeader: (DeviceDiagnostics) -> String,
) {
    operator fun invoke(): ExportResult {
        val fileName = "guantera-sonda-${clock.nowMillis()}.txt"
        val header = renderHeader(diagnostics.read())
        return store.save(fileName) { out ->
            out.write(header)
            val copy = eventLog.copyTo(out)
            if (copy is LogCopyResult.Failed) out.write("\n[registro no disponible: ${copy.reason}]\n")
        }
    }
}
