package app.guantera.ui.screens

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import app.guantera.R
import app.guantera.databinding.ScreenDrivingBinding
import app.guantera.presentation.driving.DrivingPresenter
import app.guantera.presentation.driving.DrivingViewState
import app.guantera.ui.Screen

/** P2: replaces the whole app while the car moves. Only large controls, nothing to read twice. */
class DrivingScreen(parent: ViewGroup, private val presenter: DrivingPresenter) : Screen() {
    private val binding = ScreenDrivingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    private val context = parent.context
    override val view = binding.root

    init {
        binding.transport.setActions(presenter::previous, presenter::playPause, presenter::next)
        binding.volume.setActions(presenter::volumeDown, presenter::volumeUp)
        binding.parked.setOnClickListener { presenter.confirmParked() }
        binding.endSimulation.setOnClickListener { presenter.endSimulation() }
    }

    override suspend fun observe() = presenter.state.collect(::render)

    private fun render(state: DrivingViewState) {
        binding.km.setFigure(state.kmText)
        binding.minutes.setFigure(state.minutesText)
        binding.alert.text = state.alert?.let {
            context.getString(R.string.maintenance_line, it.itemName, context.levelText(it.level).lowercase())
        } ?: context.getString(R.string.drive_no_alert)
        binding.transport.setPlaying(state.playback.isPlaying)
        binding.transport.setPlayPrimary(!state.showParkedButton)
        binding.volume.render(state.playback)
        binding.parked.isVisible = state.showParkedButton
        binding.endSimulation.isVisible = state.showEndSimulation
        binding.actions.isVisible = state.showParkedButton || state.showEndSimulation
    }
}
