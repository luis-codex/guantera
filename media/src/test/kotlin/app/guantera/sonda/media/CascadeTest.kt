package app.guantera.sonda.media

import app.guantera.sonda.domain.media.*
import org.junit.Assert.*
import org.junit.Test

private class FakeSession(
    var state: PlaybackState = PlaybackState.Paused,
    val title: String = "T",
    var throwOnControl: Boolean = false,
    var throwOnRead: Boolean = false,
) : SessionHandle {
    val calls = mutableListOf<String>()
    override fun nowPlaying(): NowPlaying {
        if (throwOnRead) throw IllegalStateException("dead")
        return NowPlaying(title, "A", null, "pkg", "App")
    }
    override fun playback(): PlaybackState { if (throwOnRead) throw IllegalStateException(); return state }
    private fun c(n: String) { if (throwOnControl) throw RuntimeException("boom"); calls += n }
    override fun play() = c("play")
    override fun pause() = c("pause")
    override fun next() = c("next")
    override fun previous() = c("prev")
}

private class FakeSource(var read: () -> SessionRead) : SessionSource {
    override fun read() = read.invoke()
    override fun start(onChange: () -> Unit) {}
    override fun stop() {}
}

private class FakeKeys(var ok: Boolean = true, var throws: Boolean = false) : KeyDispatcher {
    val sent = mutableListOf<MediaCommand>()
    override fun dispatch(command: MediaCommand): Boolean {
        if (throws) throw SecurityException("no")
        sent += command; return ok
    }
}

private class FakeVolume(var throws: Boolean = false) : VolumeBackend {
    var v = VolumeState(5, 15, false)
    override fun read(): VolumeState? = v
    override fun adjust(command: VolumeCommand) {
        if (throws) throw SecurityException("dnd")
        v = when (command) {
            VolumeCommand.Up -> v.copy(current = v.current + 1)
            VolumeCommand.Down -> v.copy(current = v.current - 1)
            VolumeCommand.ToggleMute -> v.copy(muted = !v.muted)
        }
    }
    override fun start(onChange: () -> Unit) {}
    override fun stop() {}
}

class CascadeTest {
    private fun make(src: FakeSource, keys: FakeKeys = FakeKeys(), vol: FakeVolume = FakeVolume()) =
        CascadingMediaPlayerControl(src, keys, vol)

    @Test fun withoutNotificationAccess_usesKeys_andSaysSo() {
        val keys = FakeKeys()
        val c = make(FakeSource { SessionRead.NoAccess }, keys)
        c.refresh()
        val caps = c.state.value.capabilities
        assertFalse(caps.notificationAccessGranted)
        assertTrue(caps.needsNotificationAccess)
        assertNull(c.state.value.nowPlaying)
        assertEquals(ControlResult.Done(ControlChannel.MediaKey), c.send(MediaCommand.Next))
        assertEquals(listOf(MediaCommand.Next), keys.sent)
    }

    @Test fun accessButNoSessions_usesKeys_withoutDegradation() {
        val keys = FakeKeys()
        val c = make(FakeSource { SessionRead.Sessions(emptyList()) }, keys)
        c.refresh()
        assertTrue(c.state.value.capabilities.notificationAccessGranted)
        assertFalse(c.state.value.capabilities.hasActiveSession)
        assertEquals(ControlResult.Done(ControlChannel.MediaKey), c.send(MediaCommand.PlayPause))
    }

    @Test fun sessionHandlesCommandsAndPlayPauseToggles() {
        val s = FakeSession(PlaybackState.Playing)
        val keys = FakeKeys()
        val c = make(FakeSource { SessionRead.Sessions(listOf(s)) }, keys)
        assertEquals(ControlResult.Done(ControlChannel.Session), c.send(MediaCommand.PlayPause))
        s.state = PlaybackState.Paused
        c.send(MediaCommand.PlayPause)
        c.send(MediaCommand.Next); c.send(MediaCommand.Previous)
        assertEquals(listOf("pause", "play", "next", "prev"), s.calls)
        assertTrue(keys.sent.isEmpty())
        assertEquals("T", c.state.value.nowPlaying?.title)
    }

    @Test fun throwingSession_fallsBackToKeys_degraded() {
        val s = FakeSession(throwOnControl = true)
        val keys = FakeKeys()
        val c = make(FakeSource { SessionRead.Sessions(listOf(s)) }, keys)
        assertEquals(ControlResult.Done(ControlChannel.MediaKey, degraded = true), c.send(MediaCommand.Next))
        assertEquals(listOf(MediaCommand.Next), keys.sent)
    }

    @Test fun sessionThatThrowsWhenRead_doesNotCrashSnapshot() {
        val c = make(FakeSource { SessionRead.Sessions(listOf(FakeSession(throwOnRead = true))) })
        c.refresh()
        assertNull(c.state.value.nowPlaying)
        assertEquals(PlaybackState.Unknown, c.state.value.playback)
    }

    @Test fun sourceThatThrows_isTreatedAsNoAccess() {
        val keys = FakeKeys()
        val c = make(FakeSource { throw SecurityException("denied") }, keys)
        c.refresh()
        assertFalse(c.state.value.capabilities.notificationAccessGranted)
        assertTrue(c.send(MediaCommand.Next) is ControlResult.Done)
    }

    @Test fun prefersPlayingSession() {
        val a = FakeSession(PlaybackState.Paused, "A")
        val b = FakeSession(PlaybackState.Playing, "B")
        val c = make(FakeSource { SessionRead.Sessions(listOf(a, b)) })
        c.refresh()
        assertEquals("B", c.state.value.nowPlaying?.title)
    }

    @Test fun allChannelsFail_returnsTypedFailure() {
        val c = make(FakeSource { SessionRead.NoAccess }, FakeKeys(throws = true))
        assertEquals(ControlResult.Failed(ControlFailure.NoChannelAvailable), c.send(MediaCommand.Next))
        val c2 = make(FakeSource { SessionRead.NoAccess }, FakeKeys(ok = false))
        assertEquals(ControlResult.Failed(ControlFailure.NoChannelAvailable), c2.send(MediaCommand.Next))
    }

    @Test fun volume_worksAndFailuresAreTyped() {
        val vol = FakeVolume()
        val c = make(FakeSource { SessionRead.NoAccess }, vol = vol)
        assertEquals(ControlResult.Done(ControlChannel.Volume), c.adjustVolume(VolumeCommand.Up))
        assertEquals(6, c.state.value.volume?.current)
        c.adjustVolume(VolumeCommand.ToggleMute)
        assertTrue(c.state.value.volume!!.muted)
        vol.throws = true
        assertEquals(ControlResult.Failed(ControlFailure.Rejected), c.adjustVolume(VolumeCommand.Down))
    }
}
