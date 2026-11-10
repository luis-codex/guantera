package app.guantera.sonda.media

import app.guantera.sonda.domain.media.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private class FakeSession(
    var state: PlaybackState = PlaybackState.Paused,
    var title: String = "T",
    /** Routes that really change the session; the others are accepted and ignored. */
    var responds: Boolean = true,
    var throwOnControl: Boolean = false,
    var throwOnRead: Boolean = false,
) : SessionHandle {
    val calls = mutableListOf<String>()
    private var stamp = 0
    override fun nowPlaying(): NowPlaying {
        if (throwOnRead) throw IllegalStateException("dead")
        return NowPlaying(title, "A", null, "pkg", "App")
    }
    override fun playback(): PlaybackState { if (throwOnRead) throw IllegalStateException(); return state }
    override fun signature(): String? = if (throwOnRead) null else "$title|$stamp"
    private fun c(n: String, effect: () -> Unit) {
        if (throwOnControl) throw RuntimeException("boom")
        calls += n
        if (responds) { effect(); stamp++ }
    }
    override fun play() = c("play") { state = PlaybackState.Playing }
    override fun pause() = c("pause") { state = PlaybackState.Paused }
    override fun next() = c("next") { title = "N" }
    override fun previous() = c("prev") { title = "P" }
    override fun adjustVolume(command: VolumeCommand) { calls += "vol$command" }
}

private class FakeSource(var read: () -> SessionRead) : SessionSource {
    override fun read() = read.invoke()
    override fun start(onChange: () -> Unit) {}
    override fun stop() {}
}

private class FakeAudio(var active: Boolean = false) : AudioActivity {
    override fun isMusicActive() = active
    override fun start(onChange: () -> Unit) {}
    override fun stop() {}
}

/** [effective] are the routes whose key the radio obeys: they flip [audio]. */
private class FakeKeys(val audio: FakeAudio, var effective: Set<ControlRoute> = ControlRoute.entries.toSet(), var ok: Boolean = true, var throws: Boolean = false) : KeyDispatcher {
    val sent = mutableListOf<ControlRoute>()
    override fun dispatch(route: ControlRoute): Boolean {
        if (throws) throw SecurityException("no")
        sent += route
        if (ok && route in effective) {
            when (route) {
                ControlRoute.KeyPlay -> audio.active = true
                ControlRoute.KeyPause -> audio.active = false
                ControlRoute.KeyPlayPause, ControlRoute.KeyHeadsetHook -> audio.active = !audio.active
                else -> {}
            }
        }
        return ok
    }
}

private class FakeVolume(var movesOn: Set<ControlRoute> = setOf(ControlRoute.StreamVolume, ControlRoute.SuggestedVolume, ControlRoute.KeyVolume)) : VolumeBackend {
    var v = VolumeState(5, 15, false)
    val routes = mutableListOf<ControlRoute>()
    override fun read(): VolumeState? = v
    override fun adjust(route: ControlRoute, command: VolumeCommand) {
        routes += route
        if (route !in movesOn) return
        v = when (command) {
            VolumeCommand.Up -> v.copy(current = (v.current + 1).coerceAtMost(v.max))
            VolumeCommand.Down -> v.copy(current = (v.current - 1).coerceAtLeast(0))
            VolumeCommand.ToggleMute -> v.copy(muted = !v.muted)
        }
    }
    override fun start(onChange: () -> Unit) {}
    override fun stop() {}
}

class CascadeTest {
    private val audio = FakeAudio()
    private fun make(src: FakeSource, keys: FakeKeys = FakeKeys(audio), vol: FakeVolume = FakeVolume()) =
        CascadingMediaPlayerControl(src, keys, vol, audio)

    private fun CascadingMediaPlayerControl.diag(kind: ControlKind) = diagnostics.value.first { it.control == kind }

    @Test fun withoutNotificationAccess_playsByKeyAndConfirmsByAudio() = runTest {
        val keys = FakeKeys(audio)
        val c = make(FakeSource { SessionRead.NoAccess }, keys)
        c.refresh()
        assertTrue(c.state.value.capabilities.needsNotificationAccess)
        assertNull(c.state.value.nowPlaying)
        assertEquals(ControlResult.Done(ControlKind.PlayPause, ControlRoute.KeyPlay, Effect.Confirmed), c.send(MediaCommand.PlayPause))
        assertEquals(PlaybackState.Playing, c.state.value.playback)
        assertEquals(ControlResult.Done(ControlKind.PlayPause, ControlRoute.KeyPause, Effect.Confirmed), c.send(MediaCommand.PlayPause))
        assertEquals(PlaybackState.Stopped, c.state.value.playback)
        assertEquals(listOf(ControlRoute.KeyPlay, ControlRoute.KeyPause), keys.sent)
    }

    @Test fun stateDoesNotChange_cascadeMovesToTheNextRoute() = runTest {
        val s = FakeSession(PlaybackState.Paused, responds = false)
        val keys = FakeKeys(audio, effective = emptySet())
        val c = make(FakeSource { SessionRead.Sessions(listOf(s)) }, keys)
        // Transport ignored, KeyPlay ignored, KeyPlayPause ignored -> headset hook makes the session play.
        keys.effective = setOf(ControlRoute.KeyHeadsetHook)
        val hook = object : KeyDispatcher {
            override fun dispatch(route: ControlRoute): Boolean {
                keys.dispatch(route)
                if (route == ControlRoute.KeyHeadsetHook) s.state = PlaybackState.Playing
                return true
            }
        }
        val cc = CascadingMediaPlayerControl(FakeSource { SessionRead.Sessions(listOf(s)) }, hook, FakeVolume(), audio)
        val r = cc.send(MediaCommand.PlayPause)
        assertEquals(ControlResult.Done(ControlKind.PlayPause, ControlRoute.KeyHeadsetHook, Effect.Confirmed), r)
        assertEquals(
            listOf(ControlRoute.Transport, ControlRoute.KeyPlay, ControlRoute.KeyPlayPause, ControlRoute.KeyHeadsetHook),
            cc.diag(ControlKind.PlayPause).tried,
        )
        assertEquals(listOf("play"), s.calls)
        assertEquals(PlaybackState.Playing, cc.state.value.playback)
    }

    @Test fun sessionTransportWorks_noKeyIsSent() = runTest {
        val s = FakeSession(PlaybackState.Playing)
        val keys = FakeKeys(audio)
        val c = make(FakeSource { SessionRead.Sessions(listOf(s)) }, keys)
        assertEquals(ControlResult.Done(ControlKind.PlayPause, ControlRoute.Transport, Effect.Confirmed), c.send(MediaCommand.PlayPause))
        assertEquals(PlaybackState.Paused, c.state.value.playback)
        c.send(MediaCommand.PlayPause)
        c.send(MediaCommand.Next); c.send(MediaCommand.Previous)
        assertEquals(listOf("pause", "play", "next", "prev"), s.calls)
        assertTrue(keys.sent.isEmpty())
        assertEquals("P", c.state.value.nowPlaying?.title)
    }

    @Test fun allRoutesFail_isReportedAsNoEffect_andDiagnosticsShowEveryRoute() = runTest {
        val s = FakeSession(PlaybackState.Paused, responds = false)
        val keys = FakeKeys(audio, effective = emptySet())
        val c = make(FakeSource { SessionRead.Sessions(listOf(s)) }, keys)
        assertEquals(ControlResult.Failed(ControlKind.PlayPause, ControlFailure.NoEffect), c.send(MediaCommand.PlayPause))
        val d = c.diag(ControlKind.PlayPause)
        assertEquals(Effect.NoEffect, d.effect)
        assertEquals(ControlRoute.KeyHeadsetHook, d.route)
        assertEquals(4, d.tried.size)
        assertNull(c.diag(ControlKind.Next).effect) // untouched controls stay "never used"
    }

    @Test fun noRouteCanDeliver_isNoChannel() = runTest {
        val c = make(FakeSource { SessionRead.NoAccess }, FakeKeys(audio, throws = true))
        assertEquals(ControlResult.Failed(ControlKind.Next, ControlFailure.NoChannelAvailable), c.send(MediaCommand.Next))
        val c2 = make(FakeSource { SessionRead.NoAccess }, FakeKeys(audio, ok = false))
        assertEquals(ControlResult.Failed(ControlKind.Next, ControlFailure.NoChannelAvailable), c2.send(MediaCommand.Next))
    }

    @Test fun throwingSession_fallsBackToKey() = runTest {
        val s = FakeSession(PlaybackState.Paused, throwOnControl = true)
        val keys = FakeKeys(audio)
        val c = make(FakeSource { SessionRead.Sessions(listOf(s)) }, keys)
        val r = c.send(MediaCommand.PlayPause)
        assertEquals(ControlRoute.KeyPlay, (r as ControlResult.Done).route)
    }

    @Test fun skipWithoutSession_cannotBeVerified_butIsNotAFailure() = runTest {
        val keys = FakeKeys(audio)
        val c = make(FakeSource { SessionRead.NoAccess }, keys)
        assertEquals(ControlResult.Done(ControlKind.Next, ControlRoute.KeyNext, Effect.Unverifiable), c.send(MediaCommand.Next))
        assertEquals(listOf(ControlRoute.KeyNext), keys.sent)
    }

    @Test fun skipWithSessionThatIgnoresIt_triesTheKey() = runTest {
        val s = FakeSession(responds = false)
        val keys = FakeKeys(audio)
        val c = make(FakeSource { SessionRead.Sessions(listOf(s)) }, keys)
        val r = c.send(MediaCommand.Next)
        // the fake key does not touch the session either, so nothing ever changes
        assertEquals(ControlResult.Failed(ControlKind.Next, ControlFailure.NoEffect), r)
        assertEquals(listOf(ControlRoute.Transport, ControlRoute.KeyNext), c.diag(ControlKind.Next).tried)
    }

    @Test fun sessionThatThrowsWhenRead_doesNotCrashSnapshot() {
        val c = make(FakeSource { SessionRead.Sessions(listOf(FakeSession(throwOnRead = true))) })
        c.refresh()
        assertNull(c.state.value.nowPlaying)
        assertEquals(PlaybackState.Unknown, c.state.value.playback)
    }

    @Test fun sourceThatThrows_isTreatedAsNoAccess() {
        val c = make(FakeSource { throw SecurityException("denied") })
        c.refresh()
        assertFalse(c.state.value.capabilities.notificationAccessGranted)
    }

    @Test fun prefersPlayingSession() {
        val a = FakeSession(PlaybackState.Paused, "A")
        val b = FakeSession(PlaybackState.Playing, "B")
        val c = make(FakeSource { SessionRead.Sessions(listOf(a, b)) })
        c.refresh()
        assertEquals("B", c.state.value.nowPlaying?.title)
    }

    @Test fun volumeThatMoves_isConfirmedByTheFirstRoute() = runTest {
        val vol = FakeVolume()
        val c = make(FakeSource { SessionRead.NoAccess }, vol = vol)
        assertEquals(ControlResult.Done(ControlKind.VolumeUp, ControlRoute.StreamVolume, Effect.Confirmed), c.send2(VolumeCommand.Up))
        assertEquals(6, c.state.value.volume?.current)
        c.adjustVolume(VolumeCommand.ToggleMute)
        assertTrue(c.state.value.volume!!.muted)
    }

    @Test fun volumeThatNeverMoves_triesEveryRoute_andBecomesUnknown() = runTest {
        val vol = FakeVolume(movesOn = emptySet())
        val s = FakeSession(PlaybackState.Playing)
        val c = make(FakeSource { SessionRead.Sessions(listOf(s)) }, vol = vol)
        c.refresh()
        assertNotNull(c.state.value.volume)
        assertEquals(ControlResult.Failed(ControlKind.VolumeUp, ControlFailure.NoEffect), c.send2(VolumeCommand.Up))
        assertEquals(listOf(ControlRoute.StreamVolume, ControlRoute.SuggestedVolume, ControlRoute.KeyVolume), vol.routes)
        assertEquals(
            listOf(ControlRoute.StreamVolume, ControlRoute.SuggestedVolume, ControlRoute.SessionVolume, ControlRoute.KeyVolume),
            c.diag(ControlKind.VolumeUp).tried,
        )
        assertNull("no invented percentage", c.state.value.volume)
        // a route starts working: the number comes back
        vol.movesOn = setOf(ControlRoute.SuggestedVolume)
        assertEquals(ControlRoute.SuggestedVolume, (c.send2(VolumeCommand.Up) as ControlResult.Done).route)
        assertEquals(6, c.state.value.volume?.current)
    }

    @Test fun volumeFallsThroughToTheRouteThatWorks() = runTest {
        val vol = FakeVolume(movesOn = setOf(ControlRoute.KeyVolume))
        val c = make(FakeSource { SessionRead.NoAccess }, vol = vol)
        val r = c.send2(VolumeCommand.Down) as ControlResult.Done
        assertEquals(ControlRoute.KeyVolume, r.route)
        assertEquals(4, c.state.value.volume?.current)
    }

    @Test fun volumeAtTheLimit_isNotAFailure() = runTest {
        val vol = FakeVolume(movesOn = emptySet()).apply { v = VolumeState(15, 15, false) }
        val c = make(FakeSource { SessionRead.NoAccess }, vol = vol)
        c.refresh()
        assertTrue(c.send2(VolumeCommand.Up) is ControlResult.Done)
        assertTrue(vol.routes.isEmpty())
        assertNotNull(c.state.value.volume)
    }

    private suspend fun CascadingMediaPlayerControl.send2(c: VolumeCommand) = adjustVolume(c)
}
