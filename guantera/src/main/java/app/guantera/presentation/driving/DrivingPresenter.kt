package app.guantera.presentation.driving

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.common.MaintenanceAlertUi
import app.guantera.presentation.common.PlaybackActions
import app.guantera.presentation.common.PlaybackUi
import app.guantera.presentation.common.toUi
import app.guantera.presentation.port.DrivingData
import app.guantera.presentation.port.GuanteraData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DrivingViewState(
    val kmText: String,
    val minutesText: String,
    val alert: MaintenanceAlertUi?,
    val showParkedButton: Boolean,
    val playback: PlaybackUi,
)

class DrivingPresenter(
    private val data: GuanteraData,
    private val playback: PlaybackActions,
    private val format: DisplayFormat,
    scope: CoroutineScope,
) {
    val state: StateFlow<DrivingViewState> = combine(data.driving, playback.state, ::reduce).stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(data.driving.value, playback.current),
    )

    fun previous() = playback.previous()
    fun playPause() = playback.playPause()
    fun next() = playback.next()
    fun volumeDown() = playback.volumeDown()
    fun volumeUp() = playback.volumeUp()
    fun confirmParked() = data.confirmParked()

    /**
     * Distance and time are floored on purpose: the numbers may only change once per km and once
     * per minute, so that they do not pull the driver's eyes.
     */
    private fun reduce(driving: DrivingData?, media: PlaybackUi): DrivingViewState = DrivingViewState(
        kmText = format.whole(driving?.distanceKm?.toInt() ?: 0),
        minutesText = format.whole(((driving?.elapsedSeconds ?: 0) / SECONDS_PER_MINUTE).toInt()),
        alert = driving?.urgentAlert?.toUi(format),
        showParkedButton = (driving?.stoppingSeconds ?: 0) >= PARKED_BUTTON_AFTER_SECONDS,
        playback = media,
    )

    companion object {
        const val PARKED_BUTTON_AFTER_SECONDS = 20L
        private const val SECONDS_PER_MINUTE = 60L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
