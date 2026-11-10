package app.guantera.sonda.domain.usecase

import app.guantera.sonda.domain.model.ClockSample
import app.guantera.sonda.domain.port.Clock
import app.guantera.sonda.domain.port.EventLog
import app.guantera.sonda.domain.rule.HEARTBEAT_INTERVAL_MILLIS
import app.guantera.sonda.domain.rule.SuspensionDetector
import kotlinx.coroutines.delay

/** Heartbeat that notices the head unit slept, re-arms GPS when it wakes and retries GPS on every beat if it is not active. */
class MonitorSuspension(
    private val clock: Clock,
    private val eventLog: EventLog,
    private val trackLocation: TrackLocation,
) {
    private var previous: ClockSample = sample()

    suspend fun run() {
        previous = sample()
        while (true) {
            delay(HEARTBEAT_INTERVAL_MILLIS)
            beat()
        }
    }

    fun beat() {
        val current = sample()
        SuspensionDetector.detect(previous, current)?.let { seconds ->
            eventLog.append("SUSPENSIÓN detectada de $seconds s")
            trackLocation.rearm()
        } ?: trackLocation.resume()
        previous = current
    }

    private fun sample() = ClockSample(clock.elapsedRealtimeMillis(), clock.uptimeMillis())
}
