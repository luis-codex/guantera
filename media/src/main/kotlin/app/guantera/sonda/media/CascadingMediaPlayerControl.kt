package app.guantera.sonda.media

import app.guantera.sonda.domain.media.ControlChannel
import app.guantera.sonda.domain.media.ControlFailure
import app.guantera.sonda.domain.media.ControlResult
import app.guantera.sonda.domain.media.MediaCapabilities
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.MediaPlayerControl
import app.guantera.sonda.domain.media.MediaSnapshot
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.sonda.domain.media.VolumeCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Cascade: session transport controls (needs notification access) -> media key via AudioManager.
 * Nothing thrown by a backend escapes; it becomes a [ControlResult] or a degraded snapshot.
 */
internal class CascadingMediaPlayerControl(
    private val sessions: SessionSource,
    private val keys: KeyDispatcher,
    private val volume: VolumeBackend,
) : MediaPlayerControl {
    private val _state = MutableStateFlow(MediaSnapshot.Empty)
    override val state: StateFlow<MediaSnapshot> = _state

    @Volatile private var started = false

    override fun start() {
        if (started) return
        started = true
        safe { sessions.start(::refresh) }
        safe { volume.start(::refresh) }
        refresh()
    }

    override fun stop() {
        if (!started) return
        started = false
        safe { sessions.stop() }
        safe { volume.stop() }
    }

    override fun refresh() {
        _state.value = compute()
    }

    private fun readSessions(): Pair<Boolean, List<SessionHandle>> = try {
        when (val r = sessions.read()) {
            SessionRead.NoAccess -> false to emptyList()
            is SessionRead.Sessions -> true to r.list
        }
    } catch (e: Exception) {
        false to emptyList()
    }

    /** Prefers a playing session, otherwise the highest-priority one. */
    private fun pick(list: List<SessionHandle>): SessionHandle? =
        list.firstOrNull { runCatching { it.playback() }.getOrNull() == PlaybackState.Playing } ?: list.firstOrNull()

    private fun compute(): MediaSnapshot {
        val (granted, list) = readSessions()
        val chosen = pick(list)
        val playing = chosen?.let { runCatching { it.nowPlaying() }.getOrNull() }
        val playback = chosen?.let { runCatching { it.playback() }.getOrNull() } ?: PlaybackState.Unknown
        val vol = try { volume.read() } catch (e: Exception) { null }
        return MediaSnapshot(
            nowPlaying = playing,
            playback = playback,
            capabilities = MediaCapabilities(
                notificationAccessGranted = granted,
                hasActiveSession = chosen != null,
                canControlViaSession = chosen != null,
                canControlViaKeys = true,
            ),
            volume = vol,
        )
    }

    override fun send(command: MediaCommand): ControlResult {
        val (_, list) = readSessions()
        val chosen = pick(list)
        var degraded = false
        if (chosen != null) {
            try {
                when (command) {
                    MediaCommand.Next -> chosen.next()
                    MediaCommand.Previous -> chosen.previous()
                    MediaCommand.PlayPause ->
                        if (runCatching { chosen.playback() }.getOrNull() == PlaybackState.Playing) chosen.pause()
                        else chosen.play()
                }
                refresh()
                return ControlResult.Done(ControlChannel.Session)
            } catch (e: Exception) {
                degraded = true // foreign session misbehaved; fall back to keys
            }
        }
        val delivered = try { keys.dispatch(command) } catch (e: Exception) { false }
        refresh()
        return if (delivered) ControlResult.Done(ControlChannel.MediaKey, degraded)
        else ControlResult.Failed(ControlFailure.NoChannelAvailable)
    }

    override fun adjustVolume(command: VolumeCommand): ControlResult {
        val result = try {
            volume.adjust(command)
            ControlResult.Done(ControlChannel.Volume)
        } catch (e: UnsupportedOperationException) {
            ControlResult.Failed(ControlFailure.Unsupported)
        } catch (e: Exception) {
            ControlResult.Failed(ControlFailure.Rejected)
        }
        refresh()
        return result
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { /* observation is best effort */ }
    }
}
