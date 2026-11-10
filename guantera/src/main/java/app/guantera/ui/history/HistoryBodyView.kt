package app.guantera.ui.history

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.guantera.R
import app.guantera.databinding.CellDayBinding
import app.guantera.databinding.CellMonthBinding
import app.guantera.databinding.CellWeekBinding
import app.guantera.databinding.ItemHistoryHeaderBinding
import app.guantera.databinding.ItemHistoryRowBinding
import app.guantera.databinding.ViewHistoryBodyBinding
import app.guantera.presentation.history.DayCellUi
import app.guantera.presentation.history.DayGroupUi
import app.guantera.presentation.history.HistoryActions
import app.guantera.presentation.history.HistoryBody
import app.guantera.presentation.history.HistoryUi
import app.guantera.presentation.history.HistoryUnit
import app.guantera.presentation.history.MonthCellUi
import app.guantera.presentation.history.PeriodKind
import app.guantera.presentation.history.Periods
import app.guantera.presentation.input.CalendarDate

/**
 * Everything to the right of the controls: the period with its Anterior and Siguiente, and below it
 * the list or the calendar. Gastos and Viajes both use it, so the user learns it once.
 */
class HistoryBodyView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewHistoryBodyBinding.inflate(LayoutInflater.from(context), this)
    private val inflater = LayoutInflater.from(context)
    private val adapter = TimelineAdapter()
    private val gap = resources.getDimensionPixelSize(R.dimen.cell_gap)
    private var actions: HistoryActions? = null
    private var shown: Triple<HistoryBody, String, HistoryUnit>? = null
    private var scrollKey: Any? = null
    private var emptyText = ""
    private var emptyHint = ""

    init {
        orientation = VERTICAL
        context.withStyledAttributes(attrs, R.styleable.HistoryBodyView) {
            emptyText = getString(R.styleable.HistoryBodyView_historyEmpty).orEmpty()
            emptyHint = getString(R.styleable.HistoryBodyView_historyEmptyHint).orEmpty()
        }
        val manager = LinearLayoutManager(context)
        binding.list.layoutManager = manager
        binding.list.adapter = adapter
        binding.list.itemAnimator = null
        val rowGap = resources.getDimensionPixelSize(R.dimen.row_gap)
        val headerGap = resources.getDimensionPixelSize(R.dimen.s16)
        binding.list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                if (position == RecyclerView.NO_POSITION) return
                if (adapter.isHeader(position)) {
                    if (position > 0) outRect.top = headerGap
                } else {
                    outRect.bottom = rowGap
                }
            }
        })
    }

    fun bind(actions: HistoryActions) {
        this.actions = actions
        adapter.onOpen = actions::openRow
        binding.navRow.setActions(actions::previousPeriod, actions::nextPeriod)
        binding.rangeFromRow.setActions({ actions.stepRangeFrom(-1) }, { actions.stepRangeFrom(1) })
        binding.rangeToRow.setActions({ actions.stepRangeTo(-1) }, { actions.stepRangeTo(1) })
        binding.backToCalendar.setOnClickListener { actions.closeDay() }
    }

    fun render(ui: HistoryUi, currency: String) {
        val body = ui.body
        val isOpenDay = body is HistoryBody.OpenDay
        val isRange = ui.kind == PeriodKind.Range
        binding.navRow.isVisible = !isRange && !isOpenDay
        binding.rangeBlock.isVisible = isRange
        binding.backToCalendar.isVisible = isOpenDay
        if (!isRange) binding.navRow.render(context.periodTitle(ui), null, ui.canGoNext)
        if (isRange) {
            binding.rangeFromRow.render(context.monthTitle(ui.rangeFrom), context.getString(R.string.hs_from), ui.canStepRangeFromForward)
            binding.rangeToRow.render(context.monthTitle(ui.rangeTo), context.getString(R.string.hs_to), ui.canStepRangeToForward)
        }

        binding.emptyBlock.isVisible = ui.isEmpty
        binding.emptyText.text = String.format(emptyText, context.periodInSentence(ui))
        binding.emptyHint.text = emptyHint
        val showsMonths = body is HistoryBody.MonthsGrid
        val usesList = body is HistoryBody.Timeline || isOpenDay
        binding.list.isVisible = usesList && !ui.isEmpty
        binding.grid.isVisible = !usesList && !ui.isEmpty && !(showsMonths && isRange)
        binding.gridScroll.isVisible = !usesList && !ui.isEmpty && showsMonths && isRange

        val key = Triple(body, currency, ui.unit)
        if (key == shown) return
        shown = key
        when (body) {
            is HistoryBody.Timeline -> showList(body.groups, ui, currency, Triple(ui.kind, ui.span, ui.layout))
            is HistoryBody.OpenDay -> showList(listOf(body.group), ui, currency, body.group.date)
            is HistoryBody.MonthGrid -> showMonthGrid(body, ui)
            is HistoryBody.WeekStrip -> showWeek(body, ui)
            is HistoryBody.MonthsGrid -> showMonths(body, ui)
        }
    }

    // ---- List -----------------------------------------------------------------------------

    private fun showList(groups: List<DayGroupUi>, ui: HistoryUi, currency: String, key: Any) {
        adapter.submit(groups, ui, currency)
        // A different period or day starts at the top; a refresh of the same one keeps the place.
        if (key != scrollKey) {
            scrollKey = key
            binding.list.scrollToPosition(0)
        }
    }

    // ---- Calendars ------------------------------------------------------------------------

    private fun newRow(parent: ViewGroup, weight: Float?, topGap: Boolean): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.weightSum = 0f
        val params = if (weight == null) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.month_cell_height))
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight)
        }
        if (topGap) params.topMargin = gap
        parent.addView(row, params)
        return row
    }

    private fun spaced(view: View, indexInRow: Int) {
        (view.layoutParams as LinearLayout.LayoutParams).apply { if (indexInRow > 0) marginStart = gap }
    }

    private fun showMonthGrid(body: HistoryBody.MonthGrid, ui: HistoryUi) {
        val grid = binding.grid
        grid.removeAllViews()
        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        grid.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.weekday_height)))
        for (index in 0 until Periods.DAYS_PER_WEEK) {
            val label = inflater.inflate(R.layout.cell_weekday, header, false) as TextView
            label.text = context.weekdayShort(index)
            header.addView(label)
            spaced(label, index)
        }
        body.cells.chunked(Periods.DAYS_PER_WEEK).forEach { week ->
            val row = newRow(grid, 1f, topGap = true)
            week.forEachIndexed { index, cell ->
                val view = if (cell == null) {
                    View(context).also { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
                } else {
                    dayCell(row, cell, ui)
                }
                spaced(view, index)
            }
        }
    }

    private fun dayCell(row: LinearLayout, cell: DayCellUi, ui: HistoryUi): View {
        val item = CellDayBinding.inflate(inflater, row, false)
        val hasRecords = cell.count > 0
        item.number.text = cell.date.day.toString()
        item.total.text = cell.totalText.orEmpty()
        item.total.visibility = if (hasRecords) View.VISIBLE else View.INVISIBLE
        item.number.setTextColor(color(if (cell.isToday) R.color.signal else if (hasRecords) R.color.ink else R.color.ink_faint))
        item.root.isActivated = cell.isToday
        item.root.isSelected = hasRecords
        item.root.contentDescription = describe(cell.date, cell.isToday, cell.totalText, ui)
        // A day without records has nothing to open, so it is not a button.
        if (hasRecords) item.root.setOnClickListener { actions?.openDay(cell.date) } else item.root.isClickable = false
        row.addView(item.root)
        return item.root
    }

    private fun showWeek(body: HistoryBody.WeekStrip, ui: HistoryUi) {
        val grid = binding.grid
        grid.removeAllViews()
        val row = newRow(grid, 1f, topGap = false)
        body.cells.forEachIndexed { index, cell ->
            val item = CellWeekBinding.inflate(inflater, row, false)
            val hasRecords = cell.count > 0
            item.name.text = if (cell.isToday) context.getString(R.string.hs_today) else context.weekdayShort(Periods.weekdayIndex(cell.date))
            item.name.setTextColor(color(if (cell.isToday) R.color.signal else R.color.ink_faint))
            item.number.text = cell.date.day.toString()
            item.number.setTextColor(color(if (cell.isToday) R.color.signal else if (hasRecords) R.color.ink else R.color.ink_faint))
            item.total.text = cell.totalText.orEmpty()
            item.total.visibility = if (hasRecords) View.VISIBLE else View.INVISIBLE
            setWeights(item.barEmpty, 1f - cell.fraction)
            setWeights(item.barFill, cell.fraction)
            item.root.isActivated = cell.isToday
            item.root.isSelected = hasRecords
            item.root.contentDescription = describe(cell.date, cell.isToday, cell.totalText, ui)
            if (hasRecords) item.root.setOnClickListener { actions?.openDay(cell.date) } else item.root.isClickable = false
            row.addView(item.root)
            spaced(item.root, index)
        }
    }

    private fun showMonths(body: HistoryBody.MonthsGrid, ui: HistoryUi) {
        val isRange = ui.kind == PeriodKind.Range
        val host = if (isRange) binding.gridScrolled else binding.grid
        binding.grid.removeAllViews()
        binding.gridScrolled.removeAllViews()
        body.cells.chunked(body.columns).forEachIndexed { rowIndex, months ->
            // Year fills the height with equal rows; Range keeps a fixed height and scrolls.
            val row = newRow(host, if (isRange) null else 1f, topGap = rowIndex > 0)
            months.forEachIndexed { index, cell -> spaced(monthCell(row, cell, ui), index) }
            // Pad the last row so its cells keep the width of the others.
            repeat(body.columns - months.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = gap })
            }
        }
    }

    private fun monthCell(row: LinearLayout, cell: MonthCellUi, ui: HistoryUi): View {
        val item = CellMonthBinding.inflate(inflater, row, false)
        val hasRecords = cell.count > 0
        item.name.text = if (cell.isCurrent) context.getString(R.string.hs_this_month) else context.monthShort(cell.month)
        item.name.setTextColor(color(if (cell.isCurrent) R.color.signal else R.color.ink_secondary))
        item.total.text = cell.totalText ?: context.getString(R.string.lg_empty_value)
        item.total.setTextColor(color(if (hasRecords) R.color.ink else R.color.ink_faint))
        setWeights(item.barFill, cell.fraction)
        setWeights(item.barEmpty, 1f - cell.fraction)
        item.root.isActivated = cell.isCurrent
        item.root.isSelected = hasRecords
        item.root.contentDescription = describe(cell.month, cell.totalText, ui)
        item.root.setOnClickListener { actions?.openMonth(cell.month) }
        row.addView(item.root)
        return item.root
    }

    private fun setWeights(view: View, weight: Float) {
        (view.layoutParams as LinearLayout.LayoutParams).weight = weight
        view.isVisible = weight > 0f
    }

    private fun describe(date: CalendarDate, isToday: Boolean, total: String?, ui: HistoryUi): String {
        val day = context.dayHeader(date, ui.today)
        val amount = total?.let { context.withUnit(ui.unit, "", it).trim() } ?: context.getString(R.string.hs_cell_none)
        return context.getString(if (isToday) R.string.hs_cell_description_today else R.string.hs_cell_description, day, amount)
    }

    private fun describe(month: app.guantera.presentation.history.YearMonth, total: String?, ui: HistoryUi): String {
        val amount = total?.let { context.withUnit(ui.unit, "", it).trim() } ?: context.getString(R.string.hs_cell_none)
        return context.getString(R.string.hs_cell_description, context.monthTitle(month), amount)
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    // ---- Timeline adapter -----------------------------------------------------------------

    private sealed interface Item {
        data class Header(val group: DayGroupUi) : Item
        data class Row(val row: app.guantera.presentation.history.HistoryRowUi) : Item
    }

    private class TimelineAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Item> = emptyList()
        private var today = CalendarDate(2000, 1, 1)
        private var unit = HistoryUnit.Money
        private var currency = ""
        var onOpen: (Long) -> Unit = {}

        fun isHeader(position: Int) = items.getOrNull(position) is Item.Header

        fun submit(groups: List<DayGroupUi>, ui: HistoryUi, currency: String) {
            items = groups.flatMap { group -> listOf<Item>(Item.Header(group)) + group.rows.map { Item.Row(it) } }
            today = ui.today
            unit = ui.unit
            this.currency = currency
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) HEADER else ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == HEADER) {
                HeaderHolder(ItemHistoryHeaderBinding.inflate(inflater, parent, false))
            } else {
                RowHolder(ItemHistoryRowBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder as HeaderHolder).bind(item.group)
                is Item.Row -> (holder as RowHolder).bind(item.row)
            }
        }

        private inner class HeaderHolder(private val binding: ItemHistoryHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(group: DayGroupUi) {
                val context = binding.root.context
                binding.date.text = context.dayHeader(group.date, today)
                binding.date.setTextColor(ContextCompat.getColor(context, if (group.date == today) R.color.signal else R.color.ink_secondary))
                binding.total.text = context.withUnit(unit, currency, group.totalText)
            }
        }

        private inner class RowHolder(private val binding: ItemHistoryRowBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: app.guantera.presentation.history.HistoryRowUi) = with(binding) {
                val context = root.context
                icon.setImageResource(row.icon.drawable())
                title.text = context.rowTitle(row.title)
                meta.text = row.meta.orEmpty()
                meta.isVisible = row.meta != null
                estimated.isVisible = row.estimated
                (estimated.layoutParams as LinearLayout.LayoutParams).marginStart = if (row.meta != null) context.resources.getDimensionPixelSize(R.dimen.s16) else 0
                metaLine.isVisible = row.meta != null || row.estimated
                value.text = context.withUnit(unit, currency, row.valueText)
                val spoken = "${context.rowTitle(row.title)}, ${value.text}"
                root.contentDescription = if (row.estimated) context.getString(R.string.hs_row_estimated_description, spoken) else spoken
                root.setOnClickListener { onOpen(row.id) }
            }
        }

        private companion object {
            const val HEADER = 0
            const val ROW = 1
        }
    }
}
