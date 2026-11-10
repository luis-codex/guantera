package app.guantera.presentation.history

import app.guantera.presentation.expenses.ExpenseCategory
import app.guantera.presentation.input.CalendarDate

/** One icon per kind of record. The screen maps each to a drawable; the presenter only names it. */
enum class HistoryIcon { Fuel, Maintenance, Insurance, Tolls, Parking, Washing, Other, Trip }

/** Text a row shows that needs the screen's resources. Presenters stay free of Android. */
sealed interface RowText {
    data class Plain(val text: String) : RowText
    data class Category(val category: ExpenseCategory) : RowText

    /** "08:15 · 24 min". */
    data class TripTimes(val startText: String, val minutes: Long) : RowText
}

data class HistoryRowUi(
    val id: Long,
    val icon: HistoryIcon,
    val title: RowText,
    val meta: String?,
    /** Without unit or currency: the screen adds them. */
    val valueText: String,
    /** A trip closed by estimation; shown with text, never only a color. */
    val estimated: Boolean = false,
)

/** What the figures and the cells measure, so the screen can add the currency or the unit. */
enum class HistoryUnit { Money, Kilometers }

/** One day of the timeline: date header with the day's total, then its records. */
data class DayGroupUi(val date: CalendarDate, val totalText: String, val rows: List<HistoryRowUi>)

data class DayCellUi(
    val date: CalendarDate,
    val isToday: Boolean,
    val count: Int,
    /** Compact total, or null when the day has no records. */
    val totalText: String?,
    /** Share of the largest day of the period, 0 to 1, for the bar of the week. */
    val fraction: Float = 0f,
)

data class MonthCellUi(
    val month: YearMonth,
    val isCurrent: Boolean,
    val count: Int,
    val totalText: String?,
    /** Share of the largest month of the period, 0 to 1, for the bar. */
    val fraction: Float,
)

sealed interface HistoryBody {
    /** List layout: every day of the period that has records, newest first. */
    data class Timeline(val groups: List<DayGroupUi>) : HistoryBody

    /** Calendar of one month: whole weeks, null cells before the 1st and after the last day. */
    data class MonthGrid(val cells: List<DayCellUi?>) : HistoryBody

    data class WeekStrip(val cells: List<DayCellUi>) : HistoryBody

    /** Year and Range: one cell per month. */
    data class MonthsGrid(val cells: List<MonthCellUi>, val columns: Int) : HistoryBody

    /** A day tapped in a Month or Week calendar. */
    data class OpenDay(val group: DayGroupUi) : HistoryBody
}

data class HistoryUi(
    val kind: PeriodKind,
    val layout: HistoryLayout,
    val span: DateSpan,
    val today: CalendarDate,
    val rangeFrom: YearMonth,
    val rangeTo: YearMonth,
    val unit: HistoryUnit,
    /** False on the current period: there is nothing newer, so the button is invisible, not dead. */
    val canGoNext: Boolean,
    val canStepRangeFromForward: Boolean,
    val canStepRangeToForward: Boolean,
    val isEmpty: Boolean,
    val body: HistoryBody,
)

/** One record before it is placed in a layout. */
data class HistoryEntry(val date: CalendarDate, val value: Double, val row: HistoryRowUi)

/** Turns numbers into texts. The currency or unit is added by the screen. */
interface HistoryNumbers {
    fun full(value: Double): String
    fun compact(value: Double): String
}

/** What the screen can ask of the history component. Both Gastos and Viajes implement it the same way. */
interface HistoryActions {
    fun selectKind(kind: PeriodKind)
    fun selectLayout(layout: HistoryLayout)
    fun previousPeriod()
    fun nextPeriod()
    fun openDay(date: CalendarDate)
    fun closeDay()
    fun openMonth(month: YearMonth)
    fun stepRangeFrom(delta: Int)
    fun stepRangeTo(delta: Int)
    fun openRow(id: Long)
}
