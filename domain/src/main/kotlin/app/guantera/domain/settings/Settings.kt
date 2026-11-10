package app.guantera.domain.settings

import app.guantera.domain.motion.GpsStatus
import app.guantera.domain.motion.DrivingState

data class Settings(
    val currency: String = "$",
    val openAtBoot: Boolean = false,
    val firstUseComplete: Boolean = false,
    /** "Registro interrumpido": set when a trip is recovered, cleared when the user touches the banner. */
    val recordingGapPending: Boolean = false,
    val lastExportUtcMs: Long? = null,
    val catalogRestoredUtcMs: Long? = null,
)

/** Specification section 6, declared from highest to lowest priority. */
enum class Notice {
    NO_LOCATION_PERMISSION,
    GPS_OFF,
    UNTRUSTED_CLOCK,
    SEARCHING_GPS,
    ODOMETER_MISSING,
    RECORDING_GAP,
    STORAGE_FULL,
    EXPORT_OVERDUE,
}

data class NoticeInputs(
    val gps: GpsStatus,
    val driving: DrivingState,
    val hasTrustedTime: Boolean,
    val hasOdometer: Boolean,
    val recordingGapPending: Boolean,
    val storageFull: Boolean,
    val lastExportUtcMs: Long?,
    val nowUtcMs: Long,
)

object NoticeRules {
    const val EXPORT_OVERDUE_MS = 30L * 24 * 60 * 60 * 1000

    fun notices(input: NoticeInputs): List<Notice> = buildList {
        if (input.gps == GpsStatus.NO_PERMISSION) add(Notice.NO_LOCATION_PERMISSION)
        if (input.gps == GpsStatus.OFF) add(Notice.GPS_OFF)
        if (!input.hasTrustedTime) add(Notice.UNTRUSTED_CLOCK)
        if (input.gps == GpsStatus.SEARCHING) add(Notice.SEARCHING_GPS)
        if (!input.hasOdometer) add(Notice.ODOMETER_MISSING)
        if (input.recordingGapPending) add(Notice.RECORDING_GAP)
        if (input.storageFull) add(Notice.STORAGE_FULL)
        if (input.driving == DrivingState.DETENIDO && exportIsOverdue(input)) add(Notice.EXPORT_OVERDUE)
    }

    private fun exportIsOverdue(input: NoticeInputs): Boolean =
        input.lastExportUtcMs == null || input.nowUtcMs - input.lastExportUtcMs > EXPORT_OVERDUE_MS
}
