package app.guantera.ui.screens

import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import app.guantera.R
import app.guantera.databinding.ScreenHomeBinding
import app.guantera.presentation.common.PlaybackUi
import app.guantera.presentation.home.HomePresenter
import app.guantera.presentation.home.HomeVerdict
import app.guantera.presentation.home.HomeViewState
import app.guantera.presentation.home.NoticeAction
import app.guantera.presentation.home.NoticeUi
import app.guantera.presentation.home.ParkedUi
import app.guantera.presentation.port.AppNotice
import app.guantera.presentation.port.MaintenanceLevel
import app.guantera.presentation.shell.Destination
import app.guantera.sonda.domain.media.PlaybackState
import app.guantera.ui.Navigator
import app.guantera.ui.Screen
import app.guantera.ui.openSystemSettings

/** P1: answers "¿está todo bien?" without opening anything else. */
class HomeScreen(
    parent: ViewGroup,
    private val presenter: HomePresenter,
    private val navigator: Navigator,
) : Screen() {
    private val binding = ScreenHomeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    private val context = parent.context
    override val view = binding.root
    private var settingsUnavailable = false

    init {
        binding.cardToday.label.setText(R.string.home_today)
        binding.cardToday.cta.setText(R.string.home_see_trips)
        binding.cardToday.root.setOnClickListener { navigator.navigate(Destination.Trips) }
        binding.cardMaintenance.label.setText(R.string.home_maintenance)
        binding.cardMaintenance.cta.setText(R.string.home_see_maintenance)
        binding.cardMaintenance.root.setOnClickListener { navigator.navigate(Destination.Maintenance) }
        binding.cardParked.label.setText(R.string.home_parked)
        binding.cardParked.cta.setText(R.string.home_see_parked)
        binding.cardParked.root.setOnClickListener { navigator.navigate(Destination.Parked) }
        binding.transport.setActions(presenter::previous, presenter::playPause, presenter::next)
    }

    override suspend fun observe() = presenter.state.collect(::render)

    private fun render(state: HomeViewState) {
        binding.verdict.setText(
            when (state.verdict) {
                HomeVerdict.AllGood -> R.string.home_verdict_ok
                HomeVerdict.ServiceOverdue -> R.string.home_verdict_overdue
                HomeVerdict.ServiceSoon -> R.string.home_verdict_soon
                HomeVerdict.NeedsAttention -> R.string.home_verdict_notice
            },
        )
        renderNotice(state.notice)
        renderToday(state)
        renderMaintenance(state)
        renderParked(state.parked)
        renderPlayback(state.playback)
    }

    private fun renderNotice(notice: NoticeUi?) {
        if (notice == null) {
            binding.banner.hide()
            return
        }
        val message = if (settingsUnavailable) context.getString(R.string.notice_action_failed) else context.getString(textOf(notice.notice))
        when (notice.action) {
            NoticeAction.None -> binding.banner.show(message, null, null)
            NoticeAction.OpenSettings ->
                binding.banner.show(message, context.getString(R.string.notice_action_settings)) {
                    navigator.navigate(Destination.Settings)
                }
            NoticeAction.OpenLocationSettings ->
                binding.banner.show(message, context.getString(R.string.notice_action_location)) {
                    if (!context.openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) {
                        settingsUnavailable = true
                        renderNotice(notice)
                    }
                }
        }
    }

    private fun textOf(notice: AppNotice): Int = when (notice) {
        AppNotice.NoLocationPermission -> R.string.notice_no_permission
        AppNotice.GpsOff -> R.string.notice_gps_off
        AppNotice.UntrustedClock -> R.string.notice_clock
        AppNotice.SearchingGps -> R.string.notice_searching
        AppNotice.OdometerMissing -> R.string.notice_odometer
        AppNotice.RecordingGap -> R.string.notice_gap
        AppNotice.StorageFull -> R.string.notice_storage
        AppNotice.ExportOverdue -> R.string.notice_export
    }

    private fun renderToday(state: HomeViewState) = with(binding.cardToday) {
        figure.text = context.getString(R.string.home_km_value, state.todayKmText)
        detail.text = context.resources.getQuantityString(R.plurals.home_trips, state.todayTrips, state.todayTrips)
    }

    private fun renderMaintenance(state: HomeViewState) = with(binding.cardMaintenance) {
        figure.isVisible = false
        headline.isVisible = true
        val alert = state.maintenance
        headline.text = alert?.itemName ?: context.getString(R.string.home_all_up_to_date)
        detail.text = alert?.let(context::alertDetail) ?: context.getString(R.string.home_no_pending)
        val urgent = alert?.level == MaintenanceLevel.Overdue
        detail.setTextColor(ContextCompat.getColor(context, if (urgent) R.color.signal else R.color.ink_secondary))
    }

    private fun renderParked(parked: ParkedUi) = with(binding.cardParked) {
        when (parked) {
            ParkedUi.Empty -> {
                figure.isVisible = false
                headline.isVisible = true
                headline.setText(R.string.home_parked_empty)
                detail.isVisible = false
            }
            is ParkedUi.Saved -> {
                headline.isVisible = false
                figure.isVisible = true
                figure.text = parked.timeText
                detail.isVisible = true
                detail.text = context.ageText(parked.ageMinutes)
            }
        }
    }

    private fun renderPlayback(playback: PlaybackUi) {
        binding.playbackStatus.setText(statusText(playback.status))
        binding.transport.setPlaying(playback.isPlaying)
        when {
            playback.commandFailed -> {
                binding.playbackTitle.setText(R.string.music_no_response)
                binding.playbackSubtitle.setText(R.string.music_nothing_subtitle)
            }
            playback.needsNotificationAccess -> {
                binding.playbackTitle.setText(R.string.music_controls_active)
                binding.playbackSubtitle.setText(R.string.music_open_to_see)
            }
            playback.hasTrack -> {
                binding.playbackTitle.text = playback.title ?: context.getString(R.string.music_unknown_title)
                binding.playbackSubtitle.text = listOfNotNull(playback.artist, playback.sourceApp).joinToString(" · ")
            }
            else -> {
                binding.playbackTitle.setText(R.string.music_nothing_title)
                binding.playbackSubtitle.setText(R.string.music_nothing_subtitle)
            }
        }
    }

}

fun statusText(status: PlaybackState): Int = when (status) {
    PlaybackState.Playing -> R.string.status_playing
    PlaybackState.Paused -> R.string.status_paused
    PlaybackState.Stopped -> R.string.status_stopped
    PlaybackState.Buffering -> R.string.status_buffering
    PlaybackState.Unknown -> R.string.status_unknown
}
