package app.guantera.tracking

import app.guantera.domain.motion.DrivingState
import app.guantera.platform.AndroidLocationSource
import app.guantera.presentation.real.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates background tracking: 1 Hz ticks, location fix processing, wake recovery,
 * GPS rearming after suspension, and silent maintenance alert publishing.
 */
class TrackingCoordinator(
    val graph: TrackingGraph,
    private val scope: CoroutineScope,
    val tickIntervalMs: Long = 1000L,
    val suspensionThresholdMs: Long = 5000L,
    private val autoTick: Boolean = true,
    private val onRearmNeeded: (() -> Unit)? = null,
) {
    @Volatile
    var isRunning: Boolean = false
        private set

    private var coordinatorJob: Job? = null
    private var lastTickMonoMs: Long? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        val job = SupervisorJob(scope.coroutineContext[Job])
        coordinatorJob = job
        val coordinatorScope = CoroutineScope(scope.coroutineContext + job)

        coordinatorScope.launch {
            safeExecute("recuperación inicial") {
                graph.recoverTripAfterWake()
            }
        }

        // 1. Colectar fixes del GPS a 1 Hz
        coordinatorScope.launch {
            graph.locationSource.fixes
                .catch { error ->
                    graph.reportPlatformError(error, "LocationSource.fixes")
                }
                .collect { fix ->
                    safeExecute("processLocationFix") {
                        graph.processLocationFix(fix)
                    }
                }
        }

        // 2. Loop de ticks cada 1 s siempre, con o sin fixes (activo por defecto en el servicio)
        if (autoTick) {
            coordinatorScope.launch {
                lastTickMonoMs = graph.clock.monoMs()
                while (isActive && isRunning) {
                    delay(tickIntervalMs)
                    tick()
                }
            }
        }

        // 3. Publicación de avisos urgentes
        coordinatorScope.launch {
            safeExecute("publishUrgent inicial") {
                graph.getPendingAlerts.publishUrgent()
            }

            // Al salir de EN_MOVIMIENTO
            launch {
                var previousState: DrivingState? = null
                graph.observeDrivingState.observe().collect { snapshot ->
                    val prev = previousState
                    previousState = snapshot.state
                    if (prev == DrivingState.EN_MOVIMIENTO && snapshot.state != DrivingState.EN_MOVIMIENTO) {
                        safeExecute("publishUrgent al salir de EN_MOVIMIENTO") {
                            graph.getPendingAlerts.publishUrgent()
                        }
                    }
                }
            }

            // Al cambiar datos
            launch {
                graph.maintenanceRepo.changes.collect {
                    safeExecute("publishUrgent por cambio en mantenimiento") {
                        graph.getPendingAlerts.publishUrgent()
                    }
                }
            }
            launch {
                graph.odometerRepo.changes.collect {
                    safeExecute("publishUrgent por cambio en odómetro") {
                        graph.getPendingAlerts.publishUrgent()
                    }
                }
            }
            launch {
                graph.settingsRepo.changes.collect {
                    safeExecute("publishUrgent por cambio en ajustes") {
                        graph.getPendingAlerts.publishUrgent()
                    }
                }
            }
        }
    }

    /**
     * Executes a single 1 Hz tick step.
     * Evaluates clock jump / suspension and advances engine timeouts.
     */
    suspend fun tick() {
        val nowMono = graph.clock.monoMs()
        val prevMono = lastTickMonoMs
        lastTickMonoMs = nowMono

        if (prevMono != null && (nowMono - prevMono) >= (tickIntervalMs + suspensionThresholdMs)) {
            safeExecute("recuperación tras suspensión detectada en tick") {
                handleSuspensionWake()
            }
        }

        safeExecute("processTick") {
            graph.processTick()
        }
    }

    suspend fun onWakeBroadcastReceived() {
        safeExecute("wake broadcast") {
            handleSuspensionWake()
        }
    }

    suspend fun handleSuspensionWake() {
        safeExecute("recoverTripAfterWake al despertar") {
            graph.recoverTripAfterWake()
        }
        safeExecute("rearm GPS al despertar") {
            onRearmNeeded?.invoke()
            (graph.locationSource as? AndroidLocationSource)?.rearm()
        }
    }

    fun stop() {
        isRunning = false
        coordinatorJob?.cancel()
        coordinatorJob = null
    }

    private suspend fun safeExecute(context: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            // Only a real stop of this coroutine ends it; a cancellation leaking from below is a failure.
            currentCoroutineContext().ensureActive()
            logFailure(context, e)
            graph.reportPlatformError(e, context)
        }
    }
}
