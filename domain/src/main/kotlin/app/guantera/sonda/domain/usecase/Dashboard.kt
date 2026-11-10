package app.guantera.sonda.domain.usecase

import app.guantera.sonda.domain.model.DashboardState
import app.guantera.sonda.domain.model.DeviceDiagnostics
import app.guantera.sonda.domain.port.DiagnosticsSource
import app.guantera.sonda.domain.port.EventLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

const val DASHBOARD_RECENT_EVENTS = 30

/** Whether the foreground service is up; written by the service, observed by the UI. */
class TrackingStatus {
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning

    fun markRunning(running: Boolean) {
        mutableRunning.value = running
    }
}

class Dashboard(
    private val diagnosticsSource: DiagnosticsSource,
    private val eventLog: EventLog,
    private val status: TrackingStatus,
) {
    fun diagnostics(): DeviceDiagnostics = diagnosticsSource.read()

    fun observe(): Flow<DashboardState> =
        combine(status.running, eventLog.observeRecent(DASHBOARD_RECENT_EVENTS), eventLog.health, ::DashboardState)
}
