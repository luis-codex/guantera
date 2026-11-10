package app.guantera.ui.screens

import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import app.guantera.R
import app.guantera.databinding.ScreenMusicBinding
import app.guantera.presentation.music.MusicPresenter
import app.guantera.presentation.music.MusicViewState
import app.guantera.ui.Screen
import app.guantera.ui.openSystemSettings

/** P13: says what is playing and offers every control in plain sight. */
class MusicScreen(parent: ViewGroup, private val presenter: MusicPresenter) : Screen() {
    private val binding = ScreenMusicBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    private val context = parent.context
    override val view = binding.root
    private var settingsUnavailable = false

    init {
        binding.transport.setActions(presenter::previous, presenter::playPause, presenter::next)
        binding.volume.setActions(presenter::volumeDown, presenter::volumeUp, presenter::toggleMute)
    }

    override suspend fun observe() = presenter.state.collect(::render)

    private fun render(state: MusicViewState) {
        val playback = state.playback
        renderBanner(state)
        binding.trackTitle.text = when {
            playback.title != null -> playback.title
            playback.needsNotificationAccess -> context.getString(R.string.music_controls_active)
            playback.hasTrack -> context.getString(R.string.music_unknown_title)
            else -> context.getString(R.string.music_nothing_title)
        }
        binding.trackArtist.text = when {
            playback.artist != null -> playback.artist
            playback.hasTrack -> context.getString(R.string.music_unknown_artist)
            playback.needsNotificationAccess -> ""
            else -> context.getString(R.string.music_nothing_subtitle)
        }
        binding.rowApp.setValue(playback.sourceApp ?: context.getString(R.string.music_no_data))
        binding.rowStatus.setValue(context.getString(statusText(playback.status)))
        binding.commandError.isVisible = playback.commandFailed
        binding.transport.setPlaying(playback.isPlaying)
        binding.volume.render(playback)
    }

    private fun renderBanner(state: MusicViewState) {
        if (!state.showAccessBanner) {
            binding.banner.hide()
            return
        }
        val message = if (settingsUnavailable) R.string.notice_action_failed else R.string.music_access_needed
        binding.banner.show(context.getString(message), context.getString(R.string.music_access_action)) {
            if (!context.openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) {
                settingsUnavailable = true
                renderBanner(state)
            }
        }
    }
}
