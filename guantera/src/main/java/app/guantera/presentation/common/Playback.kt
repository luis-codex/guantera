package app.guantera.presentation.common

import app.guantera.sonda.domain.media.ControlKind
import app.guantera.sonda.domain.media.ControlResult
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.MediaPlayerControl
import app.guantera.sonda.domain.media.MediaSnapshot
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.sonda.domain.media.VolumeCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PlaybackUi(
    val title: String?,
    val artist: String?,
    val sourceApp: String?,
    val status: PlaybackState,
    val needsNotificationAccess: Boolean,
    /** Null when the volume could not be read. */
    val volumePercent: Int?,
    val muted: Boolean,
    /** The control whose last command had no effect; cleared by the next command that works. */
    val failedControl: ControlKind?,
) {
    val isPlaying: Boolean get() = status == PlaybackState.Playing || status == PlaybackState.Buffering
    val hasTrack: Boolean get() = title != null || artist != null
}

/** The transport actions shared by Inicio, Música and driving mode. Commands wait for their effect off the caller's way. */
class PlaybackActions(private val player: MediaPlayerControl, private val scope: CoroutineScope) {
    private val failedControl = MutableStateFlow<ControlKind?>(null)

    val state: Flow<PlaybackUi> = combine(player.state, failedControl, ::toUi)
    val current: PlaybackUi get() = toUi(player.state.value, failedControl.value)

    fun previous() = run { player.send(MediaCommand.Previous) }
    fun playPause() = run { player.send(MediaCommand.PlayPause) }
    fun next() = run { player.send(MediaCommand.Next) }
    fun volumeDown() = run { player.adjustVolume(VolumeCommand.Down) }
    fun volumeUp() = run { player.adjustVolume(VolumeCommand.Up) }
    fun toggleMute() = run { player.adjustVolume(VolumeCommand.ToggleMute) }

    private fun run(command: suspend () -> ControlResult) {
        scope.launch { report(command()) }
    }

    private fun report(result: ControlResult) {
        failedControl.value = (result as? ControlResult.Failed)?.control
    }

    private fun toUi(snapshot: MediaSnapshot, failed: ControlKind?): PlaybackUi {
        val volume = snapshot.volume
        return PlaybackUi(
            title = snapshot.nowPlaying?.title,
            artist = snapshot.nowPlaying?.artist,
            sourceApp = snapshot.nowPlaying?.run { sourceLabel ?: sourcePackage },
            status = snapshot.playback,
            needsNotificationAccess = snapshot.capabilities.needsNotificationAccess,
            volumePercent = volume?.takeIf { it.max > 0 }?.let { it.current * 100 / it.max },
            muted = volume?.muted == true,
            failedControl = failed,
        )
    }
}
