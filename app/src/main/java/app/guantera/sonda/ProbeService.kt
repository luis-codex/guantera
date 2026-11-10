package app.guantera.sonda

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import app.guantera.sonda.ui.ServiceNotifications
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Thin Android entry point: wires system events to use cases and keeps the process in the foreground. */
class ProbeService : Service() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error -> container.recordEvent.unexpectedError("servicio", error) },
    )
    private val notifications by lazy { ServiceNotifications(this) }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            container.guarded("receptor del servicio") {
                val action = intent.action.orEmpty()
                container.recordEvent.broadcast(action, extrasOf(intent))
                if (action == Intent.ACTION_SCREEN_ON) container.trackLocation.rearm()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        container.trackingStatus.markRunning(true)
        container.recordEvent.serviceCreated()
        notifications.createChannel()
        startForeground(ServiceNotifications.NOTIFICATION_ID, notifications.build(getString(R.string.notification_active)))
        registerReceiver(broadcastReceiver, IntentFilter().apply { watchedActions().forEach(::addAction) })
        scope.launch { container.trackLocation.collectEvents() }
        scope.launch { container.trackLocation.latestFix.filterNotNull().collect(notifications::showFix) }
        scope.launch { container.monitorSuspension.run() }
        container.trackLocation.resume()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        container.recordEvent.taskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterReceiver(broadcastReceiver)
        container.trackLocation.stop()
        container.trackingStatus.markRunning(false)
        container.recordEvent.serviceDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Foreign senders may attach classes this app does not have; unparcelling them throws. */
    @Suppress("DEPRECATION")
    private fun extrasOf(intent: Intent): Map<String, String> = try {
        val extras = intent.extras
        extras?.keySet()?.associateWith { key -> extras.get(key).toString() } ?: emptyMap()
    } catch (e: RuntimeException) {
        mapOf("extras" to "ilegibles")
    }

    // Since API 28 the system delivers ACTION_SHUTDOWN only to receivers registered in code.
    private fun watchedActions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) WATCHED_ACTIONS + Intent.ACTION_SHUTDOWN else WATCHED_ACTIONS

    private companion object {
        val WATCHED_ACTIONS = listOf(
            "com.qf.action.ACC_ON",
            "com.qf.action.ACC_OFF",
            "com.qf.action.READY_GO_SLEEP_PRE",
            "android.intent.action.ACC_ON",
            "android.intent.action.ACC_OFF",
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
        )
    }
}
