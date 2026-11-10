package app.guantera.sample

import app.guantera.sonda.domain.media.ControlChannel
import app.guantera.sonda.domain.media.ControlResult
import app.guantera.sonda.domain.media.MediaCapabilities
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.MediaPlayerControl
import app.guantera.sonda.domain.media.MediaSnapshot
import app.guantera.sonda.domain.media.NowPlaying
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.sonda.domain.media.VolumeCommand
import app.guantera.sonda.domain.media.VolumeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Stands in for the real adapter until `:media` is wired in `AppContainer`. Plays nothing. */
class FakeMediaPlayerControl : MediaPlayerControl {
    private val tracks = listOf(
        "De música ligera" to "Soda Stereo",
        "Mariposa Technicolor" to "Fito Páez",
        "Demoliendo hoteles" to "Charly García",
    )
    private var trackIndex = 0

    private val _state = MutableStateFlow(
        MediaSnapshot(
            nowPlaying = nowPlaying(),
            playback = PlaybackState.Playing,
            capabilities = MediaCapabilities(
                notificationAccessGranted = true,
                hasActiveSession = true,
                canControlViaSession = true,
                canControlViaKeys = true,
            ),
            volume = VolumeState(current = 9, max = 15, muted = false),
        ),
    )
    override val state: StateFlow<MediaSnapshot> = _state

    override fun start() = Unit
    override fun stop() = Unit
    override fun refresh() = Unit

    override fun send(command: MediaCommand): ControlResult {
        when (command) {
            MediaCommand.PlayPause -> _state.update {
                val next = if (it.playback == PlaybackState.Playing) PlaybackState.Paused else PlaybackState.Playing
                it.copy(playback = next)
            }
            MediaCommand.Next -> moveBy(1)
            MediaCommand.Previous -> moveBy(tracks.size - 1)
        }
        return ControlResult.Done(ControlChannel.Session)
    }

    override fun adjustVolume(command: VolumeCommand): ControlResult {
        _state.update { snapshot ->
            val volume = snapshot.volume ?: return@update snapshot
            val adjusted = when (command) {
                VolumeCommand.Up -> volume.copy(current = (volume.current + 1).coerceAtMost(volume.max), muted = false)
                VolumeCommand.Down -> volume.copy(current = (volume.current - 1).coerceAtLeast(0), muted = false)
                VolumeCommand.ToggleMute -> volume.copy(muted = !volume.muted)
            }
            snapshot.copy(volume = adjusted)
        }
        return ControlResult.Done(ControlChannel.Volume)
    }

    private fun moveBy(step: Int) {
        trackIndex = (trackIndex + step) % tracks.size
        _state.update { it.copy(nowPlaying = nowPlaying()) }
    }

    private fun nowPlaying(): NowPlaying {
        val (title, artist) = tracks[trackIndex]
        return NowPlaying(title, artist, album = null, sourcePackage = "com.spotify.music", sourceLabel = "Spotify")
    }
}
