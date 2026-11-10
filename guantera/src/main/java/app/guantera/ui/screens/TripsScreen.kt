package app.guantera.ui.screens

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import app.guantera.R
import app.guantera.databinding.ScreenTripsBinding
import app.guantera.presentation.port.TripClosure
import app.guantera.presentation.trips.DayLabel
import app.guantera.presentation.trips.TripDetailUi
import app.guantera.presentation.trips.TripsPresenter
import app.guantera.presentation.trips.TripsViewState
import app.guantera.ui.Screen

/** P3 answers "¿cuánto anduve?", and P4 shows one trip in place of the list. */
class TripsScreen(parent: ViewGroup, private val presenter: TripsPresenter) : Screen() {
    private val binding = ScreenTripsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    private val context = parent.context
    override val view = binding.root

    init {
        binding.controls.bind(presenter.historyActions)
        binding.body.bind(presenter.historyActions)
        binding.detail.back.setOnClickListener { presenter.closeTrip() }
    }

    override suspend fun observe() = presenter.state.collect(::render)

    override fun onBack(): Boolean = presenter.onBack()

    override fun onLeave() = presenter.reset()

    private fun render(state: TripsViewState) {
        binding.periodKm.setValue(context.getString(R.string.trips_km, state.periodKmText))
        binding.periodTrips.setValue(state.periodTripsText)
        binding.periodKm.emphasize()
        binding.controls.render(state.history)
        binding.body.render(state.history, "")
        binding.listGroup.isVisible = state.detail == null
        binding.detailHeader.isVisible = state.detail != null
        binding.detail.root.isVisible = state.detail != null
        state.detail?.let(::renderDetail)
    }

    private fun renderDetail(detail: TripDetailUi) = with(binding.detail) {
        date.text = context.dayText(detail.day)
        km.setFigure(context.getString(R.string.trips_km, detail.kmText))
        duration.setFigure(context.durationText(detail.durationMinutes))
        speed.setFigure(
            detail.averageSpeedText?.let { context.getString(R.string.trip_speed_value, it) }
                ?: context.getString(R.string.trip_no_data),
        )
        rowStart.setValue(context.getString(R.string.trip_point, detail.startTimeText, detail.startCoordinates))
        rowEnd.setValue(context.getString(R.string.trip_point, detail.endTimeText, detail.endCoordinates))
        rowClosure.setValue(
            context.getString(
                when (detail.closure) {
                    TripClosure.Automatic -> R.string.trip_closure_automatic
                    TripClosure.Manual -> R.string.trip_closure_manual
                    TripClosure.Recovered -> R.string.trips_estimated
                },
            ),
        )
        note.setText(if (detail.closure == TripClosure.Recovered) R.string.trip_estimated_explain else R.string.trip_gps_note)
    }
}

private fun Context.dayText(day: DayLabel): String = when (day) {
    DayLabel.Today -> getString(R.string.trips_day_today)
    DayLabel.Yesterday -> getString(R.string.trips_day_yesterday)
    is DayLabel.Date -> day.text
}
