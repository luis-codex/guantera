package app.guantera.presentation.shell

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.MotionStatus
import app.guantera.sonda.domain.media.MediaPlayerControl
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class Destination { Home, Trips, Maintenance, Expenses, Music, Parked, Settings }

data class ShellViewState(
    val gps: GpsStatus,
    val motion: MotionStatus,
    val clockText: String,
    val showSampleLabel: Boolean,
    val destination: Destination,
    /** While true, driving mode replaces the rail and every screen. */
    val isDriving: Boolean,
)

class ShellPresenter(
    private val data: GuanteraData,
    private val player: MediaPlayerControl,
    private val format: DisplayFormat,
    clock: Clock,
    ticks: Flow<Long>,
    scope: CoroutineScope,
) {
    private val destination = MutableStateFlow(Destination.Home)

    val state: StateFlow<ShellViewState> = combine(data.status, data.driving, destination, ticks) { status, driving, dest, now ->
        ShellViewState(status.gps, status.motion, format.clock(now), data.isSample, dest, driving != null)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        ShellViewState(
            gps = data.status.value.gps,
            motion = data.status.value.motion,
            clockText = format.clock(clock.nowMillis()),
            showSampleLabel = data.isSample,
            destination = Destination.Home,
            isDriving = data.driving.value != null,
        ),
    )

    /** The destination is kept during driving mode, so the user returns to the same place afterwards. */
    fun navigate(target: Destination) {
        if (data.driving.value == null) destination.value = target
    }

    /** Returns true when the back press was consumed. */
    fun onBack(): Boolean {
        if (data.driving.value != null) return true
        if (destination.value == Destination.Home) return false
        destination.value = Destination.Home
        return true
    }

    fun onVisible() {
        player.start()
        player.refresh()
    }

    fun onHidden() = player.stop()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
