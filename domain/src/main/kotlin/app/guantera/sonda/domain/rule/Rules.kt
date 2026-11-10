package app.guantera.sonda.domain.rule

import app.guantera.sonda.domain.model.ClockSample

const val HEARTBEAT_INTERVAL_MILLIS = 10_000L
const val SUSPENSION_THRESHOLD_SECONDS = 5L
const val FIX_SAMPLING_INTERVAL_MILLIS = 30_000L

private const val MILLIS_PER_SECOND = 1000L

/** Time the device slept between two samples: elapsedRealtime advances during sleep, uptime does not. */
object SuspensionDetector {
    fun suspendedSeconds(previous: ClockSample, current: ClockSample): Long {
        val elapsed = current.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
        val awake = current.uptimeMillis - previous.uptimeMillis
        return (elapsed - awake).coerceAtLeast(0L) / MILLIS_PER_SECOND
    }

    fun detect(previous: ClockSample, current: ClockSample): Long? =
        suspendedSeconds(previous, current).takeIf { it >= SUSPENSION_THRESHOLD_SECONDS }
}

/** Strategy deciding which GPS fixes are worth writing to the journal. */
fun interface FixSamplingPolicy {
    fun shouldRecord(lastRecordedAtMillis: Long?, nowMillis: Long): Boolean
}

class IntervalSamplingPolicy(
    private val intervalMillis: Long = FIX_SAMPLING_INTERVAL_MILLIS,
) : FixSamplingPolicy {
    override fun shouldRecord(lastRecordedAtMillis: Long?, nowMillis: Long): Boolean =
        lastRecordedAtMillis == null || nowMillis - lastRecordedAtMillis >= intervalMillis
}
