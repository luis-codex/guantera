package app.guantera.ui.history

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import app.guantera.databinding.ViewHistoryControlsBinding
import app.guantera.presentation.history.HistoryActions
import app.guantera.presentation.history.HistoryLayout
import app.guantera.presentation.history.HistoryUi
import app.guantera.presentation.history.PeriodKind

/** Semana, Mes, Año, Rango, and below them Lista or Calendario. The same for Gastos and Viajes. */
class HistoryControlsView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewHistoryControlsBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = VERTICAL
    }

    fun bind(actions: HistoryActions) {
        binding.kindWeek.setOnClickListener { actions.selectKind(PeriodKind.Week) }
        binding.kindMonth.setOnClickListener { actions.selectKind(PeriodKind.Month) }
        binding.kindYear.setOnClickListener { actions.selectKind(PeriodKind.Year) }
        binding.kindRange.setOnClickListener { actions.selectKind(PeriodKind.Range) }
        binding.layoutList.setOnClickListener { actions.selectLayout(HistoryLayout.List) }
        binding.layoutCalendar.setOnClickListener { actions.selectLayout(HistoryLayout.Calendar) }
    }

    fun render(ui: HistoryUi) {
        binding.kindWeek.isSelected = ui.kind == PeriodKind.Week
        binding.kindMonth.isSelected = ui.kind == PeriodKind.Month
        binding.kindYear.isSelected = ui.kind == PeriodKind.Year
        binding.kindRange.isSelected = ui.kind == PeriodKind.Range
        binding.layoutList.isSelected = ui.layout == HistoryLayout.List
        binding.layoutCalendar.isSelected = ui.layout == HistoryLayout.Calendar
    }
}
