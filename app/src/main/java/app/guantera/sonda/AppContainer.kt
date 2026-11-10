package app.guantera.sonda

import android.content.Context
import app.guantera.sonda.data.AndroidDiagnosticsSource
import app.guantera.sonda.data.AndroidLocationSource
import app.guantera.sonda.data.DownloadsReportStore
import app.guantera.sonda.data.FileEventLog
import app.guantera.sonda.data.SystemClock
import app.guantera.sonda.domain.rule.IntervalSamplingPolicy
import app.guantera.sonda.domain.usecase.Dashboard
import app.guantera.sonda.domain.usecase.ExportReport
import app.guantera.sonda.domain.usecase.MonitorSuspension
import app.guantera.sonda.domain.usecase.RecordEvent
import app.guantera.sonda.domain.usecase.TrackLocation
import app.guantera.sonda.domain.usecase.TrackingStatus
import app.guantera.sonda.ui.ReportFormatter

/** Composition root: the only place that knows which adapter implements which port. */
class AppContainer(context: Context) {
    private val clock = SystemClock()
    // Device-protected storage is readable before the first unlock (Direct Boot), unlike filesDir.
    private val eventLog = FileEventLog(context.createDeviceProtectedStorageContext().filesDir, clock)
    private val diagnosticsSource = AndroidDiagnosticsSource(context)
    private val locationSource = AndroidLocationSource(context)
    private val formatter = ReportFormatter(context)

    val trackingStatus = TrackingStatus()
    val recordEvent = RecordEvent(eventLog)
    val trackLocation = TrackLocation(locationSource, eventLog, clock, IntervalSamplingPolicy())
    val monitorSuspension by lazy { MonitorSuspension(clock, eventLog, trackLocation) }
    val dashboard = Dashboard(diagnosticsSource, eventLog, trackingStatus)
    val exportReport = ExportReport(diagnosticsSource, eventLog, DownloadsReportStore(context), clock, formatter::header)
    val reportFormatter get() = formatter

    /** Entry-point safety net: an unexpected failure is written to the journal instead of closing the process. */
    fun guarded(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            recordEvent.unexpectedError(where, e)
        }
    }
}
