package app.guantera.presentation.common

import app.guantera.sonda.domain.media.ControlResult
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.MediaPlayerControl
import app.guantera.sonda.domain.media.MediaSnapshot
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.sonda.domain.media.VolumeCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

data class PlaybackUi(
    val title: String?,
    val artist: String?,
    val sourceApp: String?,
    val status: PlaybackState,
    val needsNotificationAccess: Boolean,
    /** Null when the volume could not be read. */
    val volumePercent: Int?,
    val muted: Boolean,
    /** The last command was rejected; cleared by the next one that works. */
    val commandFailed: Boolean,
) {
    val isPlaying: Boolean get() = status == PlaybackState.Playing || status == PlaybackState.Buffering
    val hasTrack: Boolean get() = title != null || artist != null
}

/** The transport actions shared by Inicio, Música and driving mode. */
class PlaybackActions(private val player: MediaPlayerControl) {
    private val commandFailed = MutableStateFlow(false)

    val state: Flow<PlaybackUi> = combine(player.state, commandFailed, ::toUi)
    val current: PlaybackUi get() = toUi(player.state.value, commandFailed.value)

    fun previous() = report(player.send(MediaCommand.Previous))
    fun playPause() = report(player.send(MediaCommand.PlayPause))
    fun next() = report(player.send(MediaCommand.Next))
    fun volumeDown() = report(player.adjustVolume(VolumeCommand.Down))
    fun volumeUp() = report(player.adjustVolume(VolumeCommand.Up))
    fun toggleMute() = report(player.adjustVolume(VolumeCommand.ToggleMute))

    private fun report(result: ControlResult) {
        commandFailed.value = result is ControlResult.Failed
    }

    private fun toUi(snapshot: MediaSnapshot, failed: Boolean): PlaybackUi {
        val volume = snapshot.volume
        return PlaybackUi(
            title = snapshot.nowPlaying?.title,
            artist = snapshot.nowPlaying?.artist,
            sourceApp = snapshot.nowPlaying?.run { sourceLabel ?: sourcePackage },
            status = snapshot.playback,
            needsNotificationAccess = snapshot.capabilities.needsNotificationAccess,
            volumePercent = volume?.takeIf { it.max > 0 }?.let { it.current * 100 / it.max },
            muted = volume?.muted == true,
            commandFailed = failed,
        )
    }
}
