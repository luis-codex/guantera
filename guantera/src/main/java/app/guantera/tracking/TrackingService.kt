package app.guantera.tracking

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.guantera.domain.trip.TripPhase
import app.guantera.domain.trip.activeTrip
import app.guantera.platform.AndroidAlertPublisher
import app.guantera.platform.AndroidLocationSource
import app.guantera.platform.AndroidTrackingHealthSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive, hosts the 1 Hz ticks, collects GPS fixes,
 * recovers trips across power/suspension events, and publishes silent notifications.
 */
class TrackingService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Excepción no capturada en corrutina de TrackingService", error)
        graphOrNull?.reportPlatformError(error, "TrackingService")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)
    /** Null while P12 is pending: the service then records nothing and says so. */
    private var graphOrNull: TrackingGraph? = null
    private val graph: TrackingGraph get() = checkNotNull(graphOrNull)
    private var coordinator: TrackingCoordinator? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action.orEmpty()
            Log.i(TAG, "Broadcast recibido: $action")

            // Actualizar estado de salud en caso de cambio de proveedor o despertar
            (graph.healthSource as? AndroidTrackingHealthSource)?.refresh()

            when (action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_POWER_CONNECTED,
                "com.qf.action.ACC_ON",
                "android.intent.action.ACC_ON" -> {
                    scope.launch {
                        coordinator?.onWakeBroadcastReceived()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TrackingService onCreate")

        val real = TrackingGraph.get()
        graphOrNull = real
        val alertPublisher = (real?.alertPublisher as? AndroidAlertPublisher) ?: AndroidAlertPublisher(this)
        alertPublisher.ensureChannel()
        if (TrackingStartPolicy.decide(real) == TrackingStartPolicy.Mode.WARN_AND_STOP) {
            // Data not usable yet (P12 pending): never open Room, never write. Visible warning, then stop.
            startForeground(AndroidAlertPublisher.NOTIFICATION_ID, alertPublisher.buildRecoveryNotification())
            // Detached, the warning outlives the service: stopping in foreground would take it away with it.
            stopForeground(STOP_FOREGROUND_DETACH)
            Log.w(TAG, "Datos sin revisar (P12 pendiente): no se registra y queda el aviso")
            stopSelf()
            return
        }
        startForeground(AndroidAlertPublisher.NOTIFICATION_ID, alertPublisher.buildNotification(null))

        val filter = IntentFilter().apply {
            WATCHED_ACTIONS.forEach(::addAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAction(Intent.ACTION_SHUTDOWN)
            }
        }
        registerReceiver(broadcastReceiver, filter)

        coordinator = TrackingCoordinator(
            graph = graph,
            scope = scope,
            onRearmNeeded = {
                (graph.locationSource as? AndroidLocationSource)?.rearm()
            },
        ).also { it.start() }

        // Registrar cambios de fase en logcat para depuración e integración
        scope.launch {
            var lastPhase: TripPhase? = null
            graph.engine.view.collect { view ->
                if (view.phase != lastPhase) {
                    Log.i(TAG, "Fase del motor cambió a: ${view.phase::class.simpleName}")
                    val active = view.phase.activeTrip
                    if (active != null) {
                        Log.i(TAG, "Viaje activo: ${active.distanceMeters.toInt()}m acumulados")
                    }
                    lastPhase = view.phase
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (graphOrNull == null) START_NOT_STICKY else START_STICKY

    override fun onDestroy() {
        Log.i(TAG, "TrackingService onDestroy")
        if (graphOrNull == null) {
            super.onDestroy()
            return
        }
        coordinator?.stop()
        coordinator = null
        try {
            unregisterReceiver(broadcastReceiver)
        } catch (_: Exception) {
            // Ya desregistrado
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "TrackingService"

        val WATCHED_ACTIONS = listOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            "com.qf.action.ACC_ON",
            "com.qf.action.ACC_OFF",
            "com.qf.action.READY_GO_SLEEP_PRE",
            "android.intent.action.ACC_ON",
            "android.intent.action.ACC_OFF",
            "android.location.PROVIDERS_CHANGED",
        )

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
