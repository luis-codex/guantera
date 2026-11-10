package app.guantera.sonda.domain.usecase

import app.guantera.sonda.domain.port.EventLog

/** Writes the journal lines for lifecycle moments and system broadcasts. */
class RecordEvent(private val eventLog: EventLog) {
    fun processStarted() = log("Arranque de proceso")
    fun appStarted() = log("Arranque de app")
    fun activityVisible() = log("Activity visible")
    fun startRequested() = log("Iniciar solicitado")
    fun stopRequested() = log("Detener solicitado")
    fun serviceCreated() = log("service create")
    fun serviceDestroyed() = log("service destroy")
    fun taskRemoved() = log("service onTaskRemoved")
    fun bootStartFailed(reason: String) = log("No se inició servicio tras arranque: $reason")
    fun unexpectedError(where: String, error: Throwable) = log("Error inesperado en $where: $error")

    fun broadcast(action: String, extras: Map<String, String>) {
        val details = extras.entries.joinToString { (key, value) -> "$key=$value" }
        log(if (details.isEmpty()) "Broadcast $action" else "Broadcast $action: $details")
    }

    private fun log(message: String) {
        eventLog.append(message)
    }
}
