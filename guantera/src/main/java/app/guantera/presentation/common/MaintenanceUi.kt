package app.guantera.presentation.common

import app.guantera.presentation.port.MaintenanceAlert
import app.guantera.presentation.port.MaintenanceLevel
import app.guantera.presentation.port.Remaining

/** An alert ready to show; [remainingKmText] already carries the Argentine thousands separator. */
data class MaintenanceAlertUi(
    val itemName: String,
    val level: MaintenanceLevel,
    val remainingKmText: String?,
    val remainingDays: Int?,
)

fun MaintenanceAlert.toUi(format: DisplayFormat): MaintenanceAlertUi = MaintenanceAlertUi(
    itemName = itemName,
    level = level,
    remainingKmText = (remaining as? Remaining.Km)?.let { format.whole(it.value) },
    remainingDays = (remaining as? Remaining.Days)?.value,
)
