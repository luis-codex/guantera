package app.guantera.presentation.music

import app.guantera.presentation.common.PlaybackActions
import app.guantera.presentation.common.PlaybackUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class MusicViewState(val playback: PlaybackUi, val showAccessBanner: Boolean)

class MusicPresenter(private val playback: PlaybackActions, scope: CoroutineScope) {
    val state: StateFlow<MusicViewState> = playback.state.map(::reduce).stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(playback.current),
    )

    fun previous() = playback.previous()
    fun playPause() = playback.playPause()
    fun next() = playback.next()
    fun volumeDown() = playback.volumeDown()
    fun volumeUp() = playback.volumeUp()
    fun toggleMute() = playback.toggleMute()

    private fun reduce(ui: PlaybackUi) = MusicViewState(ui, showAccessBanner = ui.needsNotificationAccess)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
