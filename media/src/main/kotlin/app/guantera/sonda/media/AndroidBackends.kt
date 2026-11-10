package app.guantera.sonda.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as AndroidPlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.NowPlaying
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.sonda.domain.media.VolumeCommand
import app.guantera.sonda.domain.media.VolumeState

internal class ControllerHandle(private val context: Context, private val c: MediaController) : SessionHandle {
    override fun nowPlaying(): NowPlaying {
        val m = c.metadata
        val pkg = c.packageName
        val label = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { null }
        return NowPlaying(
            title = m?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            sourcePackage = pkg,
            sourceLabel = label,
        )
    }

    override fun playback(): PlaybackState = when (c.playbackState?.state) {
        AndroidPlaybackState.STATE_PLAYING -> PlaybackState.Playing
        AndroidPlaybackState.STATE_PAUSED -> PlaybackState.Paused
        AndroidPlaybackState.STATE_STOPPED, AndroidPlaybackState.STATE_NONE -> PlaybackState.Stopped
        AndroidPlaybackState.STATE_BUFFERING, AndroidPlaybackState.STATE_CONNECTING -> PlaybackState.Buffering
        null -> PlaybackState.Unknown
        else -> PlaybackState.Unknown
    }

    override fun play() = c.transportControls.play()
    override fun pause() = c.transportControls.pause()
    override fun next() = c.transportControls.skipToNext()
    override fun previous() = c.transportControls.skipToPrevious()
}

internal class AndroidSessionSource(private val context: Context) : SessionSource {
    private val listener = ComponentName(context, MediaNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var onChange: (() -> Unit)? = null
    private var msm: MediaSessionManager? = null
    private val registered = mutableListOf<MediaController>()
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: AndroidPlaybackState?) { onChange?.invoke() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { onChange?.invoke() }
        override fun onSessionDestroyed() { onChange?.invoke() }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        rewatch(list.orEmpty())
        onChange?.invoke()
    }

    private fun manager(): MediaSessionManager? =
        msm ?: (context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager).also { msm = it }

    override fun read(): SessionRead {
        val m = manager() ?: return SessionRead.NoAccess
        return try {
            SessionRead.Sessions(m.getActiveSessions(listener).map { ControllerHandle(context, it) })
        } catch (e: SecurityException) {
            SessionRead.NoAccess
        }
    }

    private fun rewatch(list: List<MediaController>) {
        registered.forEach { try { it.unregisterCallback(controllerCallback) } catch (e: Exception) { } }
        registered.clear()
        list.forEach {
            try { it.registerCallback(controllerCallback, handler); registered.add(it) } catch (e: Exception) { }
        }
    }

    override fun start(onChange: () -> Unit) {
        this.onChange = onChange
        MediaBus.onChange = {
            // Listener (dis)connected: access may have changed, so re-subscribe.
            tryWatch()
            onChange()
        }
        tryWatch()
    }

    private fun tryWatch() {
        val m = manager() ?: return
        try {
            m.removeOnActiveSessionsChangedListener(sessionsListener)
            m.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            rewatch(m.getActiveSessions(listener))
        } catch (e: SecurityException) {
            // No access yet; refresh() after the user grants it will retry.
        }
    }

    override fun stop() {
        MediaBus.onChange = null
        onChange = null
        try { manager()?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { }
        registered.forEach { try { it.unregisterCallback(controllerCallback) } catch (e: Exception) { } }
        registered.clear()
    }
}

internal class AudioKeyDispatcher(context: Context) : KeyDispatcher {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun dispatch(command: MediaCommand): Boolean {
        val am = audio ?: return false
        val code = when (command) {
            MediaCommand.PlayPause -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaCommand.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        return true
    }
}

internal class AudioVolumeBackend(private val context: Context) : VolumeBackend {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var receiver: BroadcastReceiver? = null

    override fun read(): VolumeState? {
        val am = audio ?: return null
        return VolumeState(
            current = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(0),
            max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(0),
            muted = am.isStreamMute(AudioManager.STREAM_MUSIC),
        )
    }

    override fun adjust(command: VolumeCommand) {
        val am = audio ?: throw UnsupportedOperationException("no AudioManager")
        val dir = when (command) {
            VolumeCommand.Up -> AudioManager.ADJUST_RAISE
            VolumeCommand.Down -> AudioManager.ADJUST_LOWER
            VolumeCommand.ToggleMute -> AudioManager.ADJUST_TOGGLE_MUTE
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
    }

    override fun start(onChange: () -> Unit) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { onChange() }
        }
        val f = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        }
        context.applicationContext.registerReceiver(r, f)
        receiver = r
    }

    override fun stop() {
        receiver?.let { try { context.applicationContext.unregisterReceiver(it) } catch (e: Exception) { } }
        receiver = null
    }
}
