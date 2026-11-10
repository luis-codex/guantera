package app.guantera.ui.screens

import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private var lastState: MusicViewState? = null

    init {
        binding.previous.setOnClickListener { presenter.previous() }
        binding.playPause.setOnClickListener { presenter.playPause() }
        binding.next.setOnClickListener { presenter.next() }
        binding.volumeDown.setOnClickListener { presenter.volumeDown() }
        binding.volumeUp.setOnClickListener { presenter.volumeUp() }
        binding.mute.setOnClickListener { presenter.toggleMute() }
        binding.accessAction.setOnClickListener {
            if (!context.openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) {
                settingsUnavailable = true
                lastState?.let(::render)
            }
        }
    }

    override suspend fun observe() = presenter.state.collect(::render)

    private fun render(state: MusicViewState) {
        lastState = state
        val playback = state.playback
        val needsAccess = state.showAccessBanner && !playback.hasTrack
        val placeholder = playback.title == null
        binding.trackApp.text = playback.sourceApp.orEmpty()
        binding.trackTitle.text = when {
            playback.title != null -> playback.title
            playback.hasTrack -> context.getString(R.string.music_unknown_title)
            needsAccess -> context.getString(R.string.music_controls_active)
            playback.isPlaying -> context.getString(R.string.music_unknown_title)
            else -> context.getString(R.string.music_nothing_title)
        }
        binding.trackTitle.setTextColor(context.getColor(if (placeholder) R.color.ink_secondary else R.color.ink))
        binding.trackArtist.text = when {
            playback.artist != null -> playback.artist
            playback.hasTrack -> context.getString(R.string.music_unknown_artist)
            needsAccess -> context.getString(if (settingsUnavailable) R.string.notice_action_failed else R.string.music_access_hint)
            playback.isPlaying -> ""
            else -> context.getString(R.string.music_nothing_subtitle)
        }
        binding.accessAction.visibility = if (needsAccess) View.VISIBLE else View.INVISIBLE

        val failure = context.failureText(playback)
        binding.statusLine.text = failure ?: context.getString(statusText(playback.status))
        binding.statusLine.setTextColor(context.getColor(if (failure != null) R.color.signal else R.color.ink_secondary))

        binding.playPause.setImageResource(if (playback.isPlaying) R.drawable.ic_pause_lg else R.drawable.ic_play_lg)
        binding.playPause.contentDescription =
            context.getString(if (playback.isPlaying) R.string.transport_pause else R.string.transport_play)
        binding.volumeValue.text = context.getString(
            when {
                playback.muted -> R.string.volume_muted
                playback.volumePercent == null -> R.string.volume_unknown
                else -> R.string.volume_percent
            },
            playback.volumePercent ?: 0,
        )
        binding.muteIcon.setImageResource(if (playback.muted) R.drawable.ic_l_volume else R.drawable.ic_l_volume_off)
        binding.muteLabel.setText(if (playback.muted) R.string.volume_unmute else R.string.volume_mute)
        binding.mute.contentDescription = context.getString(if (playback.muted) R.string.volume_unmute else R.string.volume_mute)
    }
}
