package app.guantera.ui.screens

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.guantera.R
import app.guantera.databinding.ItemTripBinding
import app.guantera.databinding.ScreenTripsBinding
import app.guantera.presentation.port.TripClosure
import app.guantera.presentation.trips.DayLabel
import app.guantera.presentation.trips.TripDetailUi
import app.guantera.presentation.trips.TripRowUi
import app.guantera.presentation.trips.TripsPresenter
import app.guantera.presentation.trips.TripsViewState
import app.guantera.ui.Screen

/** P3 answers "¿cuánto anduve?", and P4 shows one trip in place of the list. */
class TripsScreen(parent: ViewGroup, private val presenter: TripsPresenter) : Screen() {
    private val binding = ScreenTripsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    private val context = parent.context
    private val adapter = TripRowsAdapter(presenter::openTrip)
    override val view = binding.root

    init {
        val manager = LinearLayoutManager(context)
        binding.list.layoutManager = manager
        binding.list.adapter = adapter
        binding.list.itemAnimator = null
        val gap = context.resources.getDimensionPixelSize(R.dimen.touch_gap)
        binding.list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.bottom = gap
            }
        })
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_MARGIN) presenter.loadMore()
            }
        })
        binding.detail.back.setOnClickListener { presenter.closeTrip() }
    }

    override suspend fun observe() = presenter.state.collect(::render)

    override fun onBack(): Boolean = presenter.closeTrip()

    override fun onLeave() = presenter.reset()

    private fun render(state: TripsViewState) {
        binding.today.setFigure(context.getString(R.string.trips_km, state.todayKmText))
        binding.week.setFigure(context.getString(R.string.trips_km, state.weekKmText))
        binding.month.setFigure(context.getString(R.string.trips_km, state.monthKmText))
        adapter.submit(state.rows)
        binding.empty.isVisible = state.isEmpty
        binding.list.isVisible = !state.isEmpty
        binding.listGroup.isVisible = state.detail == null
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

    private companion object {
        const val LOAD_MORE_MARGIN = 5
    }
}

private fun Context.dayText(day: DayLabel): String = when (day) {
    DayLabel.Today -> getString(R.string.trips_day_today)
    DayLabel.Yesterday -> getString(R.string.trips_day_yesterday)
    is DayLabel.Date -> day.text
}

private class TripRowsAdapter(private val onOpen: (Long) -> Unit) : RecyclerView.Adapter<TripRowsAdapter.Holder>() {
    private var rows: List<TripRowUi> = emptyList()

    fun submit(newRows: List<TripRowUi>) {
        if (newRows == rows) return
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemTripBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    inner class Holder(private val binding: ItemTripBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: TripRowUi) = with(binding) {
            val context = root.context
            val dayText = context.dayText(row.day)
            day.text = dayText
            time.text = row.startTimeText
            duration.text = context.durationText(row.durationMinutes)
            estimated.isVisible = row.isEstimatedClosure
            km.text = context.getString(R.string.trips_km, row.kmText)
            root.contentDescription = context.getString(R.string.trips_row_description, dayText, row.startTimeText, row.kmText)
            root.setOnClickListener { onOpen(row.id) }
        }
    }
}
