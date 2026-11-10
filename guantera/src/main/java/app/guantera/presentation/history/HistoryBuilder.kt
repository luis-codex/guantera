package app.guantera.presentation.history

import app.guantera.presentation.input.CalendarDate

/** Totals of what a period holds, calculated once so the figures and the layouts never disagree. */
data class PeriodTotals(
    val total: Double,
    val count: Int,
    val byDay: Map<CalendarDate, Double>,
    val byMonth: Map<YearMonth, Double>,
)

object HistoryBuilder {
    /** Entries outside [span] are ignored, so a source that returns a little too much cannot skew anything. */
    fun totals(entries: List<HistoryEntry>, span: DateSpan): PeriodTotals {
        val inside = entries.filter { it.date in span }
        return PeriodTotals(
            total = inside.sumOf { it.value },
            count = inside.size,
            byDay = inside.groupBy { it.date }.mapValues { (_, list) -> list.sumOf { it.value } },
            byMonth = inside.groupBy { YearMonth.of(it.date) }.mapValues { (_, list) -> list.sumOf { it.value } },
        )
    }

    fun build(
        state: HistoryState,
        today: CalendarDate,
        entries: List<HistoryEntry>,
        unit: HistoryUnit,
        numbers: HistoryNumbers,
    ): HistoryUi {
        val nav = state.nav
        val span = nav.span
        val inside = entries.filter { it.date in span }
        val totals = totals(inside, span)
        val currentMonth = YearMonth.of(today)
        val (rangeFrom, rangeTo) = Periods.orderedRange(nav.rangeFrom, nav.rangeTo)

        fun group(date: CalendarDate) = DayGroupUi(
            date = date,
            totalText = numbers.full(totals.byDay[date] ?: 0.0),
            rows = inside.filter { it.date == date }.map { it.row },
        )

        val largestDay = totals.byDay.values.maxOrNull() ?: 0.0

        fun dayCell(date: CalendarDate): DayCellUi {
            val total = totals.byDay[date]
            return DayCellUi(
                date = date,
                isToday = date == today,
                count = inside.count { it.date == date },
                totalText = total?.let(numbers::compact),
                fraction = if (total == null || largestDay <= 0.0) 0f else (total / largestDay).toFloat(),
            )
        }

        val body: HistoryBody = when {
            state.layout == HistoryLayout.List ->
                HistoryBody.Timeline(totals.byDay.keys.sortedDescending().map(::group))

            state.openDay != null && nav.kind in DAY_KINDS && state.openDay in span ->
                HistoryBody.OpenDay(group(state.openDay))

            nav.kind == PeriodKind.Week -> HistoryBody.WeekStrip(Periods.weekDays(nav).map(::dayCell))

            nav.kind == PeriodKind.Month ->
                HistoryBody.MonthGrid(Periods.monthGrid(YearMonth.of(nav.anchor)).map { it?.let(::dayCell) })

            else -> {
                val months = Periods.months(nav)
                val largest = months.maxOfOrNull { totals.byMonth[it] ?: 0.0 } ?: 0.0
                HistoryBody.MonthsGrid(
                    cells = months.map { month ->
                        val total = totals.byMonth[month]
                        MonthCellUi(
                            month = month,
                            isCurrent = month == currentMonth,
                            count = inside.count { YearMonth.of(it.date) == month },
                            totalText = total?.let(numbers::compact),
                            fraction = if (total == null || largest <= 0.0) 0f else (total / largest).toFloat(),
                        )
                    },
                    columns = if (nav.kind == PeriodKind.Year) YEAR_COLUMNS else RANGE_COLUMNS,
                )
            }
        }
        return HistoryUi(
            kind = nav.kind,
            layout = state.layout,
            span = span,
            today = today,
            rangeFrom = rangeFrom,
            rangeTo = rangeTo,
            unit = unit,
            canGoNext = !Periods.isLatest(nav, today),
            canStepRangeFromForward = rangeFrom < currentMonth,
            canStepRangeToForward = rangeTo < currentMonth,
            isEmpty = inside.isEmpty(),
            body = body,
        )
    }

    private val DAY_KINDS = setOf(PeriodKind.Week, PeriodKind.Month)
    const val YEAR_COLUMNS = 4
    const val RANGE_COLUMNS = 4
}
