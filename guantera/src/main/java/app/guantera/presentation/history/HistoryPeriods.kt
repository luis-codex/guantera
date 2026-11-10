package app.guantera.presentation.history

import app.guantera.presentation.input.CalendarDate

/** The four ways of choosing which records to look at. */
enum class PeriodKind { Week, Month, Year, Range }

/** The same records as a timeline grouped by day, or as a calendar. */
enum class HistoryLayout { List, Calendar }

/** A month of a year, without a day. */
data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    val first: CalendarDate get() = CalendarDate(year, month, 1)
    val last: CalendarDate get() = CalendarDate(year, month, CalendarDate.lengthOfMonth(year, month))
    val length: Int get() = CalendarDate.lengthOfMonth(year, month)
    private val index: Int get() = year * 12 + (month - 1)

    fun plusMonths(months: Int): YearMonth {
        val total = index + months
        return YearMonth(Math.floorDiv(total, 12), Math.floorMod(total, 12) + 1)
    }

    /** Positive when [other] is later. */
    fun monthsUntil(other: YearMonth): Int = other.index - index

    override fun compareTo(other: YearMonth): Int = index.compareTo(other.index)

    companion object {
        fun of(date: CalendarDate) = YearMonth(date.year, date.month)
    }
}

/** A closed span of days: both ends belong to it. */
data class DateSpan(val from: CalendarDate, val to: CalendarDate) {
    operator fun contains(date: CalendarDate): Boolean = date >= from && date <= to
    val days: Int get() = from.daysUntil(to) + 1
}

/**
 * Where the user is looking: a kind of period, the day that anchors it (any day inside the period)
 * and, for [PeriodKind.Range], the first and last month. Immutable; every move returns a new one.
 */
data class HistoryNav(
    val kind: PeriodKind,
    val anchor: CalendarDate,
    val rangeFrom: YearMonth,
    val rangeTo: YearMonth,
) {
    /** The days the period covers. */
    val span: DateSpan get() = Periods.span(this)

    companion object {
        /** Starts in the current month, with a range that covers the last three. */
        fun initial(today: CalendarDate, kind: PeriodKind = PeriodKind.Month): HistoryNav {
            val current = YearMonth.of(today)
            return HistoryNav(kind, today, current.plusMonths(-2), current)
        }
    }
}

object Periods {
    const val DAYS_PER_WEEK = 7

    /** Monday is 0 and Sunday is 6, as weeks start on Monday in Argentina. */
    fun weekdayIndex(date: CalendarDate): Int {
        // 1970-01-01 was a Thursday, which is index 3.
        return Math.floorMod(date.epochDay + 3, DAYS_PER_WEEK)
    }

    fun weekStart(date: CalendarDate): CalendarDate = date.plusDays(-weekdayIndex(date))

    /** Two months in any order become the earlier one first. An inverted range is never an error. */
    fun orderedRange(a: YearMonth, b: YearMonth): Pair<YearMonth, YearMonth> = if (a <= b) a to b else b to a

    fun span(nav: HistoryNav): DateSpan = when (nav.kind) {
        PeriodKind.Week -> weekStart(nav.anchor).let { DateSpan(it, it.plusDays(DAYS_PER_WEEK - 1)) }
        PeriodKind.Month -> YearMonth.of(nav.anchor).let { DateSpan(it.first, it.last) }
        PeriodKind.Year -> DateSpan(CalendarDate(nav.anchor.year, 1, 1), CalendarDate(nav.anchor.year, 12, 31))
        PeriodKind.Range -> orderedRange(nav.rangeFrom, nav.rangeTo).let { (from, to) -> DateSpan(from.first, to.last) }
    }

    /** The months a Year or Range period lists, in order. Empty for the other kinds. */
    fun months(nav: HistoryNav): List<YearMonth> = when (nav.kind) {
        PeriodKind.Year -> (1..12).map { YearMonth(nav.anchor.year, it) }
        PeriodKind.Range -> orderedRange(nav.rangeFrom, nav.rangeTo).let { (from, to) ->
            (0..from.monthsUntil(to)).map { from.plusMonths(it) }
        }
        else -> emptyList()
    }

    /** Moves [steps] periods: negative goes back. A range slides as a whole, keeping its length in months. */
    fun shift(nav: HistoryNav, steps: Int): HistoryNav = when (nav.kind) {
        PeriodKind.Week -> nav.copy(anchor = nav.anchor.plusDays(steps * DAYS_PER_WEEK))
        PeriodKind.Month -> nav.copy(anchor = nav.anchor.plusMonths(steps))
        PeriodKind.Year -> nav.copy(anchor = nav.anchor.plusMonths(steps * 12))
        PeriodKind.Range -> orderedRange(nav.rangeFrom, nav.rangeTo).let { (from, to) ->
            nav.copy(rangeFrom = from.plusMonths(steps * rangeLength(from, to)), rangeTo = to.plusMonths(steps * rangeLength(from, to)))
        }
    }

    private fun rangeLength(from: YearMonth, to: YearMonth) = from.monthsUntil(to) + 1

    /** Switches the kind keeping the place the user was looking at, so the change never loses them. */
    fun withKind(nav: HistoryNav, kind: PeriodKind, today: CalendarDate): HistoryNav {
        if (kind == nav.kind) return nav
        val anchor = when (nav.kind) {
            // Coming from a range, the latest month of it is the closest thing to "where I was".
            PeriodKind.Range -> orderedRange(nav.rangeFrom, nav.rangeTo).second.let { clampDay(it, today) }
            else -> nav.anchor
        }
        return nav.copy(kind = kind, anchor = anchor)
    }

    /** A date inside [month], today when today is in it, otherwise its first day. */
    fun clampDay(month: YearMonth, today: CalendarDate): CalendarDate =
        if (YearMonth.of(today) == month) today else month.first

    /** Opens one month from a Year or Range calendar. */
    fun openMonth(nav: HistoryNav, month: YearMonth, today: CalendarDate): HistoryNav =
        nav.copy(kind = PeriodKind.Month, anchor = clampDay(month, today))

    /** True when the period reaches today or later, so there is nothing newer to go to. */
    fun isLatest(nav: HistoryNav, today: CalendarDate): Boolean = span(nav).to >= today

    /** Moves the first month of a range. The last month follows when they would cross, so the range never inverts. */
    fun stepRangeFrom(nav: HistoryNav, delta: Int, today: CalendarDate): HistoryNav {
        val (from, to) = orderedRange(nav.rangeFrom, nav.rangeTo)
        val limit = YearMonth.of(today)
        val newFrom = from.plusMonths(delta).coerceAtMost(limit)
        return nav.copy(rangeFrom = newFrom, rangeTo = maxOf(to, newFrom))
    }

    /** Moves the last month of a range. The first month follows when they would cross. */
    fun stepRangeTo(nav: HistoryNav, delta: Int, today: CalendarDate): HistoryNav {
        val (from, to) = orderedRange(nav.rangeFrom, nav.rangeTo)
        val limit = YearMonth.of(today)
        val newTo = to.plusMonths(delta).coerceAtMost(limit)
        return nav.copy(rangeFrom = minOf(from, newTo), rangeTo = newTo)
    }

    /** Cells of the month grid: leading and trailing nulls fill the first and last week. Always whole weeks. */
    fun monthGrid(month: YearMonth): List<CalendarDate?> {
        val lead = weekdayIndex(month.first)
        val cells = MutableList<CalendarDate?>(lead) { null }
        for (day in 1..month.length) cells += CalendarDate(month.year, month.month, day)
        while (cells.size % DAYS_PER_WEEK != 0) cells += null
        return cells
    }

    fun weekDays(nav: HistoryNav): List<CalendarDate> = weekStart(nav.anchor).let { start -> (0 until DAYS_PER_WEEK).map { start.plusDays(it) } }
}
