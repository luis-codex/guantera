package app.guantera.sonda.media

import app.guantera.sonda.domain.media.ControlDiagnostic
import app.guantera.sonda.domain.media.ControlFailure
import app.guantera.sonda.domain.media.ControlKind
import app.guantera.sonda.domain.media.ControlResult
import app.guantera.sonda.domain.media.ControlRoute
import app.guantera.sonda.domain.media.Effect
import app.guantera.sonda.domain.media.MediaCapabilities
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.MediaPlayerControl
import app.guantera.sonda.domain.media.MediaSnapshot
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.sonda.domain.media.VolumeCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends each command by one route, then reads the state again. If nothing changed it tries the next route:
 *  - play/pause: session transport, media key play or pause, media key play/pause, headset hook;
 *  - next/previous: session transport, media key;
 *  - volume: stream volume, suggested stream volume, the session's own volume, volume key.
 * Nothing thrown by a backend escapes; it becomes a [ControlResult] or a degraded snapshot.
 */
internal class CascadingMediaPlayerControl(
    private val sessions: SessionSource,
    private val keys: KeyDispatcher,
    private val volume: VolumeBackend,
    private val audio: AudioActivity,
) : MediaPlayerControl {
    private val _state = MutableStateFlow(MediaSnapshot.Empty)
    override val state: StateFlow<MediaSnapshot> = _state

    private val _diagnostics = MutableStateFlow(ControlDiagnostic.Initial)
    override val diagnostics: StateFlow<List<ControlDiagnostic>> = _diagnostics

    private val commandLock = Mutex()
    @Volatile private var started = false

    /** False once every volume route failed to move the system volume: it no longer follows the radio's. */
    @Volatile private var volumeTrusted = true

    override fun start() {
        if (started) return
        started = true
        safe { sessions.start(::refresh) }
        safe { volume.start(::refresh) }
        safe { audio.start(::refresh) }
        refresh()
    }

    override fun stop() {
        if (!started) return
        started = false
        safe { sessions.stop() }
        safe { volume.stop() }
        safe { audio.stop() }
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

    private fun musicActive(): Boolean = try { audio.isMusicActive() } catch (e: Exception) { false }

    private fun compute(): MediaSnapshot {
        val (granted, list) = readSessions()
        val chosen = pick(list)
        val playing = chosen?.let { runCatching { it.nowPlaying() }.getOrNull() }
        // Without a session the only thing we can hear is whether some audio is playing.
        val playback = if (chosen != null) {
            runCatching { chosen.playback() }.getOrNull() ?: PlaybackState.Unknown
        } else if (musicActive()) PlaybackState.Playing else PlaybackState.Stopped
        val vol = if (volumeTrusted) try { volume.read() } catch (e: Exception) { null } else null
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

    private class Step(val route: ControlRoute, val run: () -> Boolean)

    override suspend fun send(command: MediaCommand): ControlResult = commandLock.withLock {
        val chosen = pick(readSessions().second)
        when (command) {
            MediaCommand.PlayPause -> playPause(chosen)
            MediaCommand.Next -> skip(chosen, ControlKind.Next, ControlRoute.KeyNext) { next() }
            MediaCommand.Previous -> skip(chosen, ControlKind.Previous, ControlRoute.KeyPrevious) { previous() }
        }
    }

    private suspend fun playPause(chosen: SessionHandle?): ControlResult {
        fun sessionPlaying(): Boolean? =
            chosen?.playback()?.let { it == PlaybackState.Playing || it == PlaybackState.Buffering }

        val audioBefore = runCatching { audio.isMusicActive() }.getOrNull()
        val before = runCatching { sessionPlaying() }.getOrNull() ?: audioBefore
        val target = before != true
        val steps = buildList {
            if (chosen != null) add(Step(ControlRoute.Transport) { if (target) chosen.play() else chosen.pause(); true })
            add(Step(if (target) ControlRoute.KeyPlay else ControlRoute.KeyPause) { keys.dispatch(if (target) ControlRoute.KeyPlay else ControlRoute.KeyPause) })
            add(Step(ControlRoute.KeyPlayPause) { keys.dispatch(ControlRoute.KeyPlayPause) })
            add(Step(ControlRoute.KeyHeadsetHook) { keys.dispatch(ControlRoute.KeyHeadsetHook) })
        }
        // A session that answers or, when it does not, audio that starts or stops: either proves the command worked.
        return cascade(ControlKind.PlayPause, steps, verifiable = before != null, timeoutMs = PLAYBACK_WAIT_MS) {
            sessionPlaying() == target || audio.isMusicActive() != audioBefore
        }
    }

    private suspend fun skip(chosen: SessionHandle?, kind: ControlKind, key: ControlRoute, via: SessionHandle.() -> Unit): ControlResult {
        val before = chosen?.let { runCatching { it.signature() }.getOrNull() }
        val steps = buildList {
            if (chosen != null) add(Step(ControlRoute.Transport) { chosen.via(); true })
            add(Step(key) { keys.dispatch(key) })
        }
        return cascade(kind, steps, verifiable = before != null, timeoutMs = PLAYBACK_WAIT_MS) {
            chosen!!.signature() != before
        }
    }

    override suspend fun adjustVolume(command: VolumeCommand): ControlResult = commandLock.withLock {
        val kind = when (command) {
            VolumeCommand.Up -> ControlKind.VolumeUp
            VolumeCommand.Down -> ControlKind.VolumeDown
            VolumeCommand.ToggleMute -> ControlKind.Mute
        }
        val before = try { volume.read() } catch (e: Exception) { null }
        // Pressing "up" at the maximum changes nothing and proves nothing: do not count it as a failure.
        if ((command == VolumeCommand.Up && before != null && before.current >= before.max && !before.muted) ||
            (command == VolumeCommand.Down && before != null && before.current <= 0)
        ) {
            return@withLock ControlResult.Done(kind, ControlRoute.StreamVolume, Effect.Unverifiable)
        }
        val chosen = pick(readSessions().second)
        val steps = buildList {
            add(Step(ControlRoute.StreamVolume) { volume.adjust(ControlRoute.StreamVolume, command); true })
            add(Step(ControlRoute.SuggestedVolume) { volume.adjust(ControlRoute.SuggestedVolume, command); true })
            if (chosen != null) add(Step(ControlRoute.SessionVolume) { chosen.adjustVolume(command); true })
            add(Step(ControlRoute.KeyVolume) { volume.adjust(ControlRoute.KeyVolume, command); true })
        }
        val result = cascade(kind, steps, verifiable = before != null, timeoutMs = VOLUME_WAIT_MS, stepMs = VOLUME_STEP_MS) {
            volume.read() != before
        }
        when {
            result is ControlResult.Done && result.effect == Effect.Confirmed -> volumeTrusted = true
            result is ControlResult.Failed && result.reason == ControlFailure.NoEffect -> volumeTrusted = false
        }
        refresh()
        result
    }

    /** Runs [steps] in order until [changed] turns true. Records every attempt for the diagnostics. */
    private suspend fun cascade(
        kind: ControlKind,
        steps: List<Step>,
        verifiable: Boolean,
        timeoutMs: Long,
        stepMs: Long = PLAYBACK_STEP_MS,
        changed: () -> Boolean,
    ): ControlResult {
        val tried = mutableListOf<ControlRoute>()
        var delivered = false
        for (step in steps) {
            tried += step.route
            val sent = try { step.run() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (!sent) continue
            delivered = true
            if (!verifiable) return finish(kind, step.route, Effect.Unverifiable, tried)
            if (awaitChange(timeoutMs, stepMs, changed)) return finish(kind, step.route, Effect.Confirmed, tried)
        }
        record(kind, tried.lastOrNull(), Effect.NoEffect, tried)
        refresh()
        return ControlResult.Failed(kind, if (delivered) ControlFailure.NoEffect else ControlFailure.NoChannelAvailable)
    }

    private fun finish(kind: ControlKind, route: ControlRoute, effect: Effect, tried: List<ControlRoute>): ControlResult {
        record(kind, route, effect, tried)
        refresh()
        return ControlResult.Done(kind, route, effect)
    }

    private suspend fun awaitChange(timeoutMs: Long, stepMs: Long, changed: () -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            if (try { changed() } catch (e: Exception) { false }) return true
            if (waited >= timeoutMs) return false
            delay(stepMs)
            waited += stepMs
        }
    }

    private fun record(kind: ControlKind, route: ControlRoute?, effect: Effect, tried: List<ControlRoute>) {
        _diagnostics.value = _diagnostics.value.map { if (it.control == kind) ControlDiagnostic(kind, route, effect, tried.toList()) else it }
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { /* observation is best effort */ }
    }

    private companion object {
        const val PLAYBACK_WAIT_MS = 800L
        const val PLAYBACK_STEP_MS = 100L
        const val VOLUME_WAIT_MS = 300L
        const val VOLUME_STEP_MS = 50L
    }
}
