package app.guantera.sonda.media

import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as PS
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import app.guantera.sonda.domain.media.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** Debug-only: owns a real MediaSession and drives the real adapter. -e access granted|denied */
class RealAdapterTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val granted = InstrumentationRegistry.getArguments().getString("access") == "granted"
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var session: MediaSession
    private lateinit var control: MediaPlayerControl
    private var track: android.media.AudioTrack? = null
    @Volatile private var feeding = true

    /** API 26+ routes media keys to the last app that actually played audio, so play silence. */
    private fun startSilence() {
        val t = android.media.AudioTrack(AudioManager.STREAM_MUSIC, 44100, android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT, 8820, android.media.AudioTrack.MODE_STREAM)
        t.play(); track = t
        Thread { val buf = ShortArray(2205); while (feeding) { try { t.write(buf, 0, buf.size) } catch (e: Exception) { break } } }.start()
    }

    @Volatile private var current = PS.STATE_PLAYING

    private fun setState(s: Int) = session.setPlaybackState(
        PS.Builder().setActions(PS.ACTION_PLAY or PS.ACTION_PAUSE or PS.ACTION_PLAY_PAUSE or PS.ACTION_SKIP_TO_NEXT or PS.ACTION_SKIP_TO_PREVIOUS)
            .setState(s.also { current = it }, 0, 1f).build()
    )

    @Before fun setUp() {
        val main = Handler(Looper.getMainLooper())
        session = MediaSession(ctx, "guantera-test")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { calls += "play"; setState(PS.STATE_PLAYING) }
            override fun onPause() { calls += "pause"; setState(PS.STATE_PAUSED) }
            override fun onSkipToNext() { calls += "next"; setState(current) }
            override fun onSkipToPrevious() { calls += "prev"; setState(current) }
        }, main)
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Cancion de prueba")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artista X").build())
        setState(PS.STATE_PLAYING)
        // Real players declare a media button receiver; on API 26+ keys only reach such a session.
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setMediaButtonReceiver(android.app.PendingIntent.getBroadcast(ctx, 0,
            android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).setPackage(ctx.packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        session.isActive = true
        startSilence()
        Thread.sleep(500)
        control = MediaPlayerControlFactory.create(ctx)
        control.start()
    }

    @After fun tearDown() { feeding = false; control.stop(); session.release(); track?.release() }

    private fun await(msg: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(50) }
        fail("timeout: $msg; calls=$calls state=${control.state.value}")
    }

    @Test fun controls() = runBlocking {
        control.refresh()
        val caps = control.state.value.capabilities
        assertEquals(granted, caps.notificationAccessGranted)
        val first = control.send(MediaCommand.PlayPause)
        if (granted) {
            assertEquals("Cancion de prueba", control.state.value.nowPlaying?.title)
            assertEquals("Artista X", control.state.value.nowPlaying?.artist)
            assertEquals(ControlResult.Done(ControlKind.PlayPause, ControlRoute.Transport, Effect.Confirmed), first)
            assertEquals(PlaybackState.Paused, control.state.value.playback)
        } else {
            assertNull(control.state.value.nowPlaying)
            // Without access the key still reaches the session, and the diagnostics must say which route worked.
            assertTrue("first=$first", first is ControlResult.Done)
        }
        await("pause") { calls.contains("pause") }
        control.send(MediaCommand.PlayPause)
        await("play") { calls.contains("play") }
        control.send(MediaCommand.Next); await("next") { calls.contains("next") }
        control.send(MediaCommand.Previous); await("prev") { calls.contains("prev") }
        Unit
    }

    @Test fun volume() = runBlocking {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        control.refresh()
        assertEquals(5, control.state.value.volume?.current)
        assertTrue(control.adjustVolume(VolumeCommand.Up) is ControlResult.Done)
        assertEquals(6, control.state.value.volume?.current)
        control.adjustVolume(VolumeCommand.Down); control.adjustVolume(VolumeCommand.Down)
        assertEquals(4, control.state.value.volume?.current)
        control.adjustVolume(VolumeCommand.ToggleMute)
        assertTrue(control.state.value.volume!!.muted)
        control.adjustVolume(VolumeCommand.ToggleMute)
        assertFalse(control.state.value.volume!!.muted)
        Unit
    }
}
