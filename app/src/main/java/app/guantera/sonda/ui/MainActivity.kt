package app.guantera.sonda.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.guantera.sonda.R
import app.guantera.sonda.ServiceLauncher
import app.guantera.sonda.container
import app.guantera.sonda.domain.model.DashboardState
import app.guantera.sonda.domain.model.ExportResult
import app.guantera.sonda.domain.model.LogHealth
import app.guantera.sonda.domain.model.LogRead
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var messageView: TextView
    private val failureHandler = CoroutineExceptionHandler { _, error ->
        container.recordEvent.unexpectedError("pantalla", error)
        show(getString(R.string.message_unexpected_error, error.toString()))
    }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + failureHandler)
    private var observerJob: Job? = null
    private var exporting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container.recordEvent.appStarted()
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)
        statusView = findViewById(R.id.serviceStatus)
        messageView = findViewById(R.id.message)
        findViewById<TextView>(R.id.diagnostics).text = container.reportFormatter.diagnostics(container.dashboard.diagnostics())
        findViewById<Button>(R.id.start).setOnClickListener { startProbe() }
        findViewById<Button>(R.id.stop).setOnClickListener { stopProbe() }
        findViewById<Button>(R.id.export).setOnClickListener { export() }
    }

    override fun onStart() {
        super.onStart()
        container.recordEvent.activityVisible()
        observerJob = activityScope.launch { container.dashboard.observe().collect(::render) }
    }

    override fun onStop() {
        observerJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun render(state: DashboardState) {
        statusView.setText(if (state.serviceRunning) R.string.service_status_running else R.string.service_status_stopped)
        val events = when (val log = state.log) {
            is LogRead.Lines -> if (log.lines.isEmpty()) getString(R.string.log_empty) else log.lines.joinToString("\n")
            is LogRead.Unreadable -> getString(R.string.log_read_failed, log.reason)
        }
        val health = state.logHealth
        logView.text = if (health is LogHealth.WriteFailing) "${getString(R.string.log_write_failed, health.reason)}\n\n$events" else events
    }

    private fun startProbe() {
        if (!container.trackLocation.hasPermission) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
            return
        }
        try {
            ServiceLauncher.start(this)
            container.recordEvent.startRequested()
        } catch (e: IllegalStateException) {
            show(getString(R.string.message_start_failed, e.toString()))
        }
    }

    private fun stopProbe() {
        ServiceLauncher.stop(this)
        container.recordEvent.stopRequested()
        show(getString(R.string.message_stop_requested))
    }

    private fun export() {
        if (exporting) return
        exporting = true
        show(getString(R.string.message_export_running))
        activityScope.launch {
            try {
                handleExport(withContext(Dispatchers.IO) { container.exportReport() })
            } finally {
                exporting = false
            }
        }
    }

    private fun handleExport(result: ExportResult) {
        when (result) {
            is ExportResult.Saved -> show(getString(R.string.message_export_saved, result.location))
            is ExportResult.Failed -> show(getString(R.string.message_export_failed, result.reason))
            ExportResult.StoragePermissionRequired -> {
                show("")
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when {
            requestCode == REQUEST_LOCATION && granted -> startProbe()
            requestCode == REQUEST_LOCATION -> show(getString(R.string.message_location_permission_needed))
            requestCode == REQUEST_STORAGE && granted -> export()
            requestCode == REQUEST_STORAGE -> show(getString(R.string.message_storage_permission_needed))
        }
    }

    private fun show(text: String) {
        messageView.text = text
    }

    private companion object {
        const val REQUEST_LOCATION = 11
        const val REQUEST_STORAGE = 12
    }
}
