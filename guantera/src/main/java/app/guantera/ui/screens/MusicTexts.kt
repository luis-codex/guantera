package app.guantera.ui.screens

import android.content.Context
import app.guantera.R
import app.guantera.presentation.common.PlaybackUi
import app.guantera.sonda.domain.media.ControlDiagnostic
import app.guantera.sonda.domain.media.ControlKind
import app.guantera.sonda.domain.media.ControlRoute
import app.guantera.sonda.domain.media.Effect

/** Name of a control as the person knows it. [playing] tells whether play/pause currently offers "Pausa" or "Reproducir". */
fun Context.controlName(kind: ControlKind, playing: Boolean?): String = getString(
    when (kind) {
        ControlKind.PlayPause -> when (playing) {
            true -> R.string.control_pause
            false -> R.string.control_play
            null -> R.string.control_play_pause
        }
        ControlKind.Previous -> R.string.control_previous
        ControlKind.Next -> R.string.control_next
        ControlKind.VolumeDown -> R.string.control_volume_down
        ControlKind.VolumeUp -> R.string.control_volume_up
        ControlKind.Mute -> R.string.control_mute
    },
)

/** The one sentence shown when a control did nothing, or null when nothing failed. */
fun Context.failureText(playback: PlaybackUi): String? =
    playback.failedControl?.let { getString(R.string.music_no_response_to, controlName(it, playback.isPlaying)) }

fun Context.routeName(route: ControlRoute): String = getString(
    when (route) {
        ControlRoute.Transport -> R.string.route_transport
        ControlRoute.KeyPlayPause -> R.string.route_key_play_pause
        ControlRoute.KeyPlay -> R.string.route_key_play
        ControlRoute.KeyPause -> R.string.route_key_pause
        ControlRoute.KeyHeadsetHook -> R.string.route_key_headset
        ControlRoute.KeyNext -> R.string.route_key_next
        ControlRoute.KeyPrevious -> R.string.route_key_previous
        ControlRoute.StreamVolume -> R.string.route_stream_volume
        ControlRoute.SuggestedVolume -> R.string.route_suggested_volume
        ControlRoute.SessionVolume -> R.string.route_session_volume
        ControlRoute.KeyVolume -> R.string.route_key_volume
    },
)

/** "tecla Play · respondió", "tecla de auricular · sin efecto (probó 4 vías)" or "Sin probar". */
fun Context.diagnosticText(d: ControlDiagnostic): String {
    val route = d.route
    val effect = d.effect
    if (route == null || effect == null) return getString(R.string.settings_music_never)
    val effectText = getString(
        when (effect) {
            Effect.Confirmed -> R.string.effect_confirmed
            Effect.NoEffect -> R.string.effect_none
            Effect.Unverifiable -> R.string.effect_unverifiable
        },
    )
    return if (d.tried.size > 1) getString(R.string.settings_music_tried_many, routeName(route), effectText, d.tried.size)
    else getString(R.string.settings_music_tried, routeName(route), effectText)
}
