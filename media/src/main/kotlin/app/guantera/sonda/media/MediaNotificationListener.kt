package app.guantera.sonda.media

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose: only its being an enabled listener lets us call
 * MediaSessionManager.getActiveSessions. When it (dis)connects we ask the adapter to re-read.
 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { MediaBus.notifyChanged() }
    override fun onListenerDisconnected() { MediaBus.notifyChanged() }
}

/** Tiny in-process hook so the service can poke the adapter without holding a Context. */
internal object MediaBus {
    @Volatile var onChange: (() -> Unit)? = null
    fun notifyChanged() { try { onChange?.invoke() } catch (e: Exception) { } }
}
