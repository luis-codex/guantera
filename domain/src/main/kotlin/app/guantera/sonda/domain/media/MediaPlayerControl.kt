package app.guantera.sonda.domain.media

import kotlinx.coroutines.flow.StateFlow

/**
 * Port: control and observe whatever is playing on the device. No method throws; every
 * failure is a value ([ControlResult]) so a foreign media app or the system can never crash the caller.
 * Calls are cheap and non-blocking enough for the main thread.
 */
interface MediaPlayerControl {
    /** Latest known picture. Always has a value; defaults to [MediaSnapshot.Empty]. */
    val state: StateFlow<MediaSnapshot>

    /** Begins observing sessions/volume. Idempotent. */
    fun start()

    /** Stops observing and releases callbacks. Idempotent. */
    fun stop()

    /** Re-reads sessions, capabilities and volume now (e.g. after returning from system settings). */
    fun refresh()

    /**
     * Sends a command and checks whether it had an effect, trying the next route when it did not.
     * Suspends for up to a second or two while it waits for the effect; safe to call from the main thread.
     */
    suspend fun send(command: MediaCommand): ControlResult
    suspend fun adjustVolume(command: VolumeCommand): ControlResult

    /** Per control, the last route tried and whether it had an effect. Always has every [ControlKind]. */
    val diagnostics: StateFlow<List<ControlDiagnostic>>
}

enum class MediaCommand { PlayPause, Next, Previous }

enum class VolumeCommand { Up, Down, ToggleMute }

/** What is playing. Any field may be unknown: sessions are not required to publish metadata. */
data class NowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    /** Package name of the source app, e.g. "com.spotify.music". */
    val sourcePackage: String?,
    /** Human label of the source app when resolvable, else null. */
    val sourceLabel: String?,
)

enum class PlaybackState { Playing, Paused, Stopped, Buffering, Unknown }

/** What the adapter can do right now. The UI hides or explains whatever is false. */
data class MediaCapabilities(
    /** True when the system granted notification access, which is needed to read [NowPlaying]. */
    val notificationAccessGranted: Boolean,
    /** True when at least one media session is active and readable. */
    val hasActiveSession: Boolean,
    /** Commands can be sent through a session's transport controls. */
    val canControlViaSession: Boolean,
    /** Commands can be sent as media keys (always attempted as fallback). */
    val canControlViaKeys: Boolean,
) {
    val canControl: Boolean get() = canControlViaSession || canControlViaKeys

    /** Controls work but we cannot tell what is playing because access is missing. */
    val needsNotificationAccess: Boolean get() = !notificationAccessGranted
}

data class VolumeState(val current: Int, val max: Int, val muted: Boolean) {
    init { require(max >= 0 && current >= 0) }
}

data class MediaSnapshot(
    /** Null when nothing is playing or it cannot be read (see [MediaCapabilities.notificationAccessGranted]). */
    val nowPlaying: NowPlaying?,
    val playback: PlaybackState,
    val capabilities: MediaCapabilities,
    /** Null when the volume is unknown: unreadable, or the system value stopped following the radio's real volume. */
    val volume: VolumeState?,
) {
    companion object {
        val Empty = MediaSnapshot(
            nowPlaying = null,
            playback = PlaybackState.Unknown,
            capabilities = MediaCapabilities(
                notificationAccessGranted = false,
                hasActiveSession = false,
                canControlViaSession = false,
                canControlViaKeys = true,
            ),
            volume = null,
        )
    }
}

/** Every control the UI offers, one row each in the diagnostics. */
enum class ControlKind { PlayPause, Previous, Next, VolumeDown, VolumeUp, Mute }

/** A way of delivering a command. The diagnostics shows which one the radio finally accepted. */
enum class ControlRoute {
    Transport, KeyPlayPause, KeyPlay, KeyPause, KeyHeadsetHook, KeyNext, KeyPrevious,
    StreamVolume, SuggestedVolume, SessionVolume, KeyVolume,
}

enum class Effect {
    /** The state was read again and it changed. */
    Confirmed,

    /** The command went out and the state did not change. */
    NoEffect,

    /** The command went out but there is nothing to compare against (e.g. no session to read the track from). */
    Unverifiable,
}

data class ControlDiagnostic(
    val control: ControlKind,
    /** Last route tried; null when this control was never used. */
    val route: ControlRoute?,
    val effect: Effect?,
    /** Routes tried by the last use, in order. */
    val tried: List<ControlRoute>,
) {
    companion object {
        val Initial: List<ControlDiagnostic> = ControlKind.entries.map { ControlDiagnostic(it, null, null, emptyList()) }
    }
}

enum class ControlFailure {
    /** Every route was tried and the state never changed. */
    NoEffect,

    /** No route could even deliver the command (all threw or were absent). */
    NoChannelAvailable,

    /** The system rejected the call (permission, do-not-disturb policy, ...). */
    Rejected,

    /** The audio service is not available on this device. */
    Unsupported,
}

sealed interface ControlResult {
    /** [effect] is [Effect.Confirmed] or [Effect.Unverifiable]; [route] is the one that worked or went out last. */
    data class Done(val control: ControlKind, val route: ControlRoute, val effect: Effect) : ControlResult
    data class Failed(val control: ControlKind, val reason: ControlFailure) : ControlResult
}
