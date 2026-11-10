package app.guantera.sonda.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.guantera.sonda.R
import app.guantera.sonda.domain.model.GpsFix

class ServiceNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val name = context.getString(R.string.notification_channel_name)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW))
    }

    fun build(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        return builder
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    fun showFix(fix: GpsFix) {
        manager.notify(NOTIFICATION_ID, build(context.getString(R.string.notification_fix, fix.latitude.toString(), fix.longitude.toString())))
    }

    companion object {
        const val NOTIFICATION_ID = 74
        private const val CHANNEL_ID = "sonda"
    }
}
