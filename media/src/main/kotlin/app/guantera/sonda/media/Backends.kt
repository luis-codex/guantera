package app.guantera.sonda.media

import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.NowPlaying
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.sonda.domain.media.VolumeCommand
import app.guantera.sonda.domain.media.VolumeState

/** One media session of some app. Every member may throw: it belongs to a foreign process. */
internal interface SessionHandle {
    fun nowPlaying(): NowPlaying
    fun playback(): PlaybackState
    fun play()
    fun pause()
    fun next()
    fun previous()
}

internal sealed interface SessionRead {
    /** Notification access not granted (system threw SecurityException or listener not enabled). */
    data object NoAccess : SessionRead
    data class Sessions(val list: List<SessionHandle>) : SessionRead
}

internal interface SessionSource {
    /** May throw; the cascade converts that into a typed state. */
    fun read(): SessionRead
    fun start(onChange: () -> Unit)
    fun stop()
}

internal interface KeyDispatcher {
    /** Returns false or throws when the key could not be delivered. */
    fun dispatch(command: MediaCommand): Boolean
}

internal interface VolumeBackend {
    /** Null if unreadable. May throw. */
    fun read(): VolumeState?
    fun adjust(command: VolumeCommand)
    fun start(onChange: () -> Unit)
    fun stop()
}
