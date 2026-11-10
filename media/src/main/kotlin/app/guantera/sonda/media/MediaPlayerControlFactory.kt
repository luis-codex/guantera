package app.guantera.sonda.media

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.guantera.sonda.domain.media.MediaPlayerControl

/** Public entry point of `:media`. */
object MediaPlayerControlFactory {
    /** Builds the real adapter. Call [MediaPlayerControl.start] from the UI/service lifecycle. */
    fun create(context: Context): MediaPlayerControl {
        val app = context.applicationContext
        return CascadingMediaPlayerControl(
            sessions = AndroidSessionSource(app),
            keys = AudioKeyDispatcher(app),
            volume = AudioVolumeBackend(app),
            audio = AudioActivityProbe(app),
        )
    }

    /** Intent for the system screen where the user grants notification access; null-safe to start. */
    fun notificationAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
