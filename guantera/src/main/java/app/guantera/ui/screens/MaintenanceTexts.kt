package app.guantera.ui.screens

import android.content.Context
import app.guantera.R
import app.guantera.presentation.common.MaintenanceAlertUi
import app.guantera.presentation.port.MaintenanceLevel

fun Context.levelText(level: MaintenanceLevel): String = getString(
    when (level) {
        MaintenanceLevel.Overdue -> R.string.maintenance_overdue
        MaintenanceLevel.Soon -> R.string.maintenance_soon
        MaintenanceLevel.UpToDate -> R.string.maintenance_ok
        MaintenanceLevel.NoData -> R.string.maintenance_no_data
    },
)

/** "Vencido, pasado por 350 km". Falls back to the level alone when the distance is unknown. */
fun Context.alertDetail(alert: MaintenanceAlertUi): String {
    val overdue = alert.level == MaintenanceLevel.Overdue
    val remaining = when {
        alert.remainingKmText != null ->
            getString(if (overdue) R.string.remaining_overdue_km else R.string.remaining_km, alert.remainingKmText)
        alert.remainingDays != null ->
            getString(if (overdue) R.string.remaining_overdue_days else R.string.remaining_days, alert.remainingDays)
        else -> null
    }
    val level = levelText(alert.level)
    return remaining?.let { getString(R.string.maintenance_detail, level, it) } ?: level
}
