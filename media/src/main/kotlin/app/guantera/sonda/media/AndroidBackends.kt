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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import app.guantera.sonda.domain.media.ControlRoute
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

    override fun signature(): String? {
        val ps = c.playbackState ?: return null
        val m = c.metadata
        return listOf(m?.getString(MediaMetadata.METADATA_KEY_TITLE), m?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            ps.state, ps.position, ps.lastPositionUpdateTime).joinToString("|")
    }

    override fun adjustVolume(command: VolumeCommand) = c.adjustVolume(
        when (command) {
            VolumeCommand.Up -> AudioManager.ADJUST_RAISE
            VolumeCommand.Down -> AudioManager.ADJUST_LOWER
            VolumeCommand.ToggleMute -> AudioManager.ADJUST_TOGGLE_MUTE
        },
        0,
    )

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

private fun AudioManager.pressKey(code: Int) {
    val t = SystemClock.uptimeMillis()
    dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
    dispatchMediaKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
}

internal class AudioKeyDispatcher(context: Context) : KeyDispatcher {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun dispatch(route: ControlRoute): Boolean {
        val am = audio ?: return false
        am.pressKey(
            when (route) {
                ControlRoute.KeyPlayPause -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                ControlRoute.KeyPlay -> KeyEvent.KEYCODE_MEDIA_PLAY
                ControlRoute.KeyPause -> KeyEvent.KEYCODE_MEDIA_PAUSE
                ControlRoute.KeyHeadsetHook -> KeyEvent.KEYCODE_HEADSETHOOK
                ControlRoute.KeyNext -> KeyEvent.KEYCODE_MEDIA_NEXT
                ControlRoute.KeyPrevious -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> throw IllegalArgumentException("not a media key route: $route")
            },
        )
        return true
    }
}

internal class AudioActivityProbe(private val context: Context) : AudioActivity {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var callback: Any? = null

    override fun isMusicActive(): Boolean = audio?.isMusicActive == true

    override fun start(onChange: () -> Unit) {
        if (Build.VERSION.SDK_INT < 26 || callback != null) return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) { onChange() }
        }
        audio?.registerAudioPlaybackCallback(cb, Handler(Looper.getMainLooper()))
        callback = cb
    }

    override fun stop() {
        if (Build.VERSION.SDK_INT >= 26) (callback as? AudioManager.AudioPlaybackCallback)?.let { audio?.unregisterAudioPlaybackCallback(it) }
        callback = null
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

    override fun adjust(route: ControlRoute, command: VolumeCommand) {
        val am = audio ?: throw UnsupportedOperationException("no AudioManager")
        val dir = when (command) {
            VolumeCommand.Up -> AudioManager.ADJUST_RAISE
            VolumeCommand.Down -> AudioManager.ADJUST_LOWER
            VolumeCommand.ToggleMute -> AudioManager.ADJUST_TOGGLE_MUTE
        }
        when (route) {
            ControlRoute.StreamVolume -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
            ControlRoute.SuggestedVolume -> am.adjustSuggestedStreamVolume(dir, AudioManager.STREAM_MUSIC, 0)
            ControlRoute.KeyVolume -> am.pressKey(
                when (command) {
                    VolumeCommand.Up -> KeyEvent.KEYCODE_VOLUME_UP
                    VolumeCommand.Down -> KeyEvent.KEYCODE_VOLUME_DOWN
                    VolumeCommand.ToggleMute -> KeyEvent.KEYCODE_VOLUME_MUTE
                },
            )
            else -> throw IllegalArgumentException("not a volume route: $route")
        }
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
