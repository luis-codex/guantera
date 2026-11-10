package app.guantera.sonda.media

import app.guantera.sonda.domain.media.ControlRoute
import app.guantera.sonda.domain.media.NowPlaying
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.sonda.domain.media.VolumeCommand
import app.guantera.sonda.domain.media.VolumeState

/** One media session of some app. Every member may throw: it belongs to a foreign process. */
internal interface SessionHandle {
    fun nowPlaying(): NowPlaying
    fun playback(): PlaybackState

    /**
     * Changes whenever the session answers a command: track, position or the time of the last state update.
     * Null when it cannot be read.
     */
    fun signature(): String?
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun adjustVolume(command: VolumeCommand)
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
    /** Sends the media key of a `Key*` [route]. Returns false or throws when it could not be delivered. */
    fun dispatch(route: ControlRoute): Boolean
}

/** Whether the system hears music right now. Works without notification access. */
internal interface AudioActivity {
    fun isMusicActive(): Boolean
    fun start(onChange: () -> Unit)
    fun stop()
}

internal interface VolumeBackend {
    /** Null if unreadable. May throw. */
    fun read(): VolumeState?

    /** Delivers [command] by [route] (`StreamVolume`, `SuggestedVolume` or `KeyVolume`). May throw. */
    fun adjust(route: ControlRoute, command: VolumeCommand)
    fun start(onChange: () -> Unit)
    fun stop()
}
