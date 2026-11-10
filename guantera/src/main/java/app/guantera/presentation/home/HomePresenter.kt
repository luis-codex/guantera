package app.guantera.presentation.home

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.common.MaintenanceAlertUi
import app.guantera.presentation.common.PlaybackActions
import app.guantera.presentation.common.PlaybackUi
import app.guantera.presentation.common.toUi
import app.guantera.presentation.port.AppNotice
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.port.HomeData
import app.guantera.presentation.port.MaintenanceLevel
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class HomeVerdict { AllGood, ServiceOverdue, ServiceSoon, NeedsAttention }

enum class NoticeAction { None, OpenSettings, OpenLocationSettings, GrantLocation }

data class NoticeUi(val notice: AppNotice, val action: NoticeAction)

sealed interface ParkedUi {
    data object Empty : ParkedUi
    data class Saved(val timeText: String, val ageMinutes: Long, val note: String?) : ParkedUi
}

data class HomeViewState(
    val verdict: HomeVerdict,
    val notice: NoticeUi?,
    val todayKmText: String,
    val todayTrips: Int,
    /** Null means everything is up to date. */
    val maintenance: MaintenanceAlertUi?,
    val parked: ParkedUi,
    val playback: PlaybackUi,
)

class HomePresenter(
    private val data: GuanteraData,
    private val playback: PlaybackActions,
    private val format: DisplayFormat,
    clock: Clock,
    ticks: Flow<Long>,
    scope: CoroutineScope,
) {
    val state: StateFlow<HomeViewState> = combine(data.home, playback.state, ticks) { home, media, now ->
        reduce(home, media, now)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(data.home.value, playback.current, clock.nowMillis()),
    )

    /** The banner was touched. "Registro interrumpido" closes when touched (especificación, sección 6). */
    fun noticeTouched(notice: AppNotice) {
        if (notice == AppNotice.RecordingGap) data.dismissRecordingGap()
    }

    fun previous() = playback.previous()
    fun playPause() = playback.playPause()
    fun next() = playback.next()

    private fun reduce(home: HomeData, media: PlaybackUi, now: Long): HomeViewState {
        // AppNotice is declared by priority, and only one banner is shown at a time.
        val notice = home.notices.minByOrNull { it.ordinal }
        return HomeViewState(
            verdict = verdictOf(home, notice),
            notice = notice?.let { NoticeUi(it, actionOf(it)) },
            todayKmText = format.oneDecimal(home.today.kilometers),
            todayTrips = home.today.tripCount,
            maintenance = home.urgentAlert?.toUi(format),
            parked = home.parked?.let {
                ParkedUi.Saved(
                    timeText = format.clock(it.savedAtMillis),
                    ageMinutes = ((now - it.savedAtMillis) / MINUTE_MILLIS).coerceAtLeast(0),
                    note = it.note,
                )
            } ?: ParkedUi.Empty,
            playback = media,
        )
    }

    private fun verdictOf(home: HomeData, notice: AppNotice?): HomeVerdict = when {
        home.urgentAlert?.level == MaintenanceLevel.Overdue -> HomeVerdict.ServiceOverdue
        notice != null -> HomeVerdict.NeedsAttention
        home.urgentAlert?.level == MaintenanceLevel.Soon -> HomeVerdict.ServiceSoon
        else -> HomeVerdict.AllGood
    }

    private fun actionOf(notice: AppNotice): NoticeAction = when (notice) {
        AppNotice.GpsOff -> NoticeAction.OpenLocationSettings
        AppNotice.NoLocationPermission -> NoticeAction.GrantLocation
        AppNotice.OdometerMissing, AppNotice.RecordingGap, AppNotice.ExportOverdue -> NoticeAction.OpenSettings
        AppNotice.UntrustedClock, AppNotice.SearchingGps, AppNotice.StorageFull -> NoticeAction.None
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MINUTE_MILLIS = 60_000L
    }
}
