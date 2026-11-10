package app.guantera.domain.time

import kotlin.math.abs

/** The time a fix carried, with the monotonic reading and boot it belongs to. The reference for R28. */
data class FixTime(val utcMs: Long, val monoMs: Long, val bootCount: Int?)

sealed interface TrustedTime {
    /** The system clock passed R28. */
    data class Reliable(val utcMs: Long) : TrustedTime

    /** The system clock failed R28, so the time comes from the last fix (R29). */
    data class Estimated(val utcMs: Long) : TrustedTime

    /** The system clock failed R28 and no fix was ever saved: date axes give no alerts (R29). */
    data object Unavailable : TrustedTime
}

/** R28 and R29. */
object TrustedTimeRules {
    const val MAX_DRIFT_MS = 24L * 60 * 60 * 1000

    /** 2025-01-01 UTC. Before any fix, a clock older than this is a radio that lost its time. */
    const val MIN_PLAUSIBLE_UTC_MS = 1_735_689_600_000L

    fun resolve(systemUtcMs: Long, nowMonoMs: Long, bootCount: Int?, lastFix: FixTime?): TrustedTime {
        val projected = lastFix?.let { projectedUtc(it, nowMonoMs, bootCount) }
        val notBeforeReference = systemUtcMs >= (lastFix?.utcMs ?: MIN_PLAUSIBLE_UTC_MS)
        val closeToProjection = projected == null || abs(systemUtcMs - projected) <= MAX_DRIFT_MS
        return when {
            notBeforeReference && closeToProjection -> TrustedTime.Reliable(systemUtcMs)
            lastFix != null -> TrustedTime.Estimated(projected ?: lastFix.utcMs)
            else -> TrustedTime.Unavailable
        }
    }

    /** Null across a reboot, because monotonic readings of different boots cannot be compared. */
    private fun projectedUtc(fix: FixTime, nowMonoMs: Long, bootCount: Int?): Long? =
        if (bootCount != null && fix.bootCount == bootCount) fix.utcMs + (nowMonoMs - fix.monoMs) else null
}
