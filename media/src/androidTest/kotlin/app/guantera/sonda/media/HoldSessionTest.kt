package app.guantera.sonda.media

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as PS
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/** Debug-only demo player: keeps a real session alive for `-e seconds N` so a UI can be driven against it. */
class HoldSessionTest {
    @Test fun hold() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val seconds = InstrumentationRegistry.getArguments().getString("seconds")?.toLongOrNull() ?: 60L
        val tracks = listOf("Cancion de prueba" to "Artista X", "Segunda cancion" to "Artista Y")
        var index = 0
        var state = PS.STATE_PLAYING
        lateinit var session: MediaSession
        fun publish() {
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, tracks[index].first)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, tracks[index].second).build())
            session.setPlaybackState(PS.Builder()
                .setActions(PS.ACTION_PLAY or PS.ACTION_PAUSE or PS.ACTION_PLAY_PAUSE or PS.ACTION_SKIP_TO_NEXT or PS.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, 0, 1f).build())
        }
        session = MediaSession(ctx, "guantera-demo")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { state = PS.STATE_PLAYING; publish() }
            override fun onPause() { state = PS.STATE_PAUSED; publish() }
            override fun onSkipToNext() { index = (index + 1) % tracks.size; publish() }
            override fun onSkipToPrevious() { index = (index + tracks.size - 1) % tracks.size; publish() }
        }, Handler(Looper.getMainLooper()))
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setMediaButtonReceiver(android.app.PendingIntent.getBroadcast(ctx, 0,
            android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).setPackage(ctx.packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        publish()
        session.isActive = true
        val track = AudioTrack(AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, 8820, AudioTrack.MODE_STREAM)
        track.play()
        val end = System.currentTimeMillis() + seconds * 1000
        val silence = ShortArray(2205)
        // A real player stops making sound when paused; the audio check of the adapter depends on that.
        var playing = false
        while (System.currentTimeMillis() < end) {
            if (state == PS.STATE_PLAYING) {
                if (!playing) { track.play(); playing = true }
                track.write(silence, 0, silence.size)
            } else {
                if (playing) { track.pause(); track.flush(); playing = false }
                Thread.sleep(50)
            }
        }
        track.release(); session.release()
    }
}
