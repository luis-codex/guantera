package app.guantera.presentation.history

import app.guantera.presentation.input.CalendarDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class HistoryState(
    val nav: HistoryNav,
    val layout: HistoryLayout = HistoryLayout.List,
    /** The day opened from a calendar, if any. */
    val openDay: CalendarDate? = null,
)

/**
 * The state of the history component, shared by Gastos and Viajes so both behave the same.
 * [today] is read on every move, so a screen left open across midnight keeps working.
 */
class HistoryController(private val today: () -> CalendarDate) {
    private val _state = MutableStateFlow(HistoryState(HistoryNav.initial(today())))
    val state: StateFlow<HistoryState> = _state

    fun selectKind(kind: PeriodKind) = _state.update { it.copy(nav = Periods.withKind(it.nav, kind, today()), openDay = null) }

    fun selectLayout(layout: HistoryLayout) = _state.update { it.copy(layout = layout, openDay = null) }

    fun previous() = _state.update { it.copy(nav = Periods.shift(it.nav, -1), openDay = null) }

    fun next() = _state.update {
        if (Periods.isLatest(it.nav, today())) it else it.copy(nav = Periods.shift(it.nav, 1), openDay = null)
    }

    fun openDay(date: CalendarDate) = _state.update { it.copy(openDay = date) }

    /** True when a day was open, so the back key closes it instead of leaving the screen. */
    fun closeDay(): Boolean {
        val wasOpen = _state.value.openDay != null
        _state.update { it.copy(openDay = null) }
        return wasOpen
    }

    fun openMonth(month: YearMonth) = _state.update { it.copy(nav = Periods.openMonth(it.nav, month, today()), openDay = null) }

    fun stepRangeFrom(delta: Int) = _state.update { it.copy(nav = Periods.stepRangeFrom(it.nav, delta, today())) }

    fun stepRangeTo(delta: Int) = _state.update { it.copy(nav = Periods.stepRangeTo(it.nav, delta, today())) }

    /** Back to the current month in the list, as when the app starts. */
    fun reset() = _state.update { HistoryState(HistoryNav.initial(today())) }
}
