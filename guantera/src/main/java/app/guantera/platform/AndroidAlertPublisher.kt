package app.guantera.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.guantera.R
import app.guantera.domain.maintenance.MaintenanceAlert
import app.guantera.domain.maintenance.MaintenanceStatus
import app.guantera.domain.maintenance.Remaining
import app.guantera.domain.port.AlertPublisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

class AndroidAlertPublisher(
    private val context: Context,
    private val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager,
) : AlertPublisher {

    private val _currentAlert = MutableStateFlow<MaintenanceAlert?>(null)
    val currentAlert: StateFlow<MaintenanceAlert?> = _currentAlert.asStateFlow()

    override suspend fun publish(alert: MaintenanceAlert?) {
        _currentAlert.value = alert
        try {
            ensureChannel()
            val notification = buildNotification(alert)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Never throws across the port
        }
    }

    fun buildNotification(alert: MaintenanceAlert? = _currentAlert.value): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }

        val contentText = if (alert != null) {
            formatAlertText(alert)
        } else {
            context.getString(R.string.tracking_notification_active_text)
        }

        return builder
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Shown instead of the tracking one while the data waits for the owner in P12: nothing is being recorded. */
    fun buildRecoveryNotification(): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        return builder
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.tracking_notification_recovery_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun formatAlertText(alert: MaintenanceAlert): String {
        val statusText = when (alert.status) {
            MaintenanceStatus.VENCIDO -> context.getString(R.string.tracking_status_overdue)
            MaintenanceStatus.PROXIMO -> context.getString(R.string.tracking_status_soon)
            MaintenanceStatus.AL_DIA -> "Al día"
            MaintenanceStatus.SIN_DATOS -> "Sin datos"
        }
        val remainingText = when (val rem = alert.remaining) {
            is Remaining.Km -> if (rem.value < 0) "hace ${(-rem.value).roundToInt()} km" else "en ${rem.value.roundToInt()} km"
            is Remaining.Days -> if (rem.value < 0) "hace ${-rem.value} días" else "en ${rem.value} días"
            null -> ""
        }
        return if (remainingText.isNotBlank()) {
            "${alert.itemName}: $statusText ($remainingText)"
        } else {
            "${alert.itemName}: $statusText"
        }
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.tracking_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.tracking_notification_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "guantera_tracking"
    }
}
