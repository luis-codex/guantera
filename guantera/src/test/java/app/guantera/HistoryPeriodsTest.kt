package app.guantera

import app.guantera.presentation.history.DateSpan
import app.guantera.presentation.history.HistoryBody
import app.guantera.presentation.history.HistoryBuilder
import app.guantera.presentation.history.HistoryEntry
import app.guantera.presentation.history.HistoryIcon
import app.guantera.presentation.history.HistoryLayout
import app.guantera.presentation.history.HistoryNav
import app.guantera.presentation.history.HistoryNumbers
import app.guantera.presentation.history.HistoryRowUi
import app.guantera.presentation.history.HistoryState
import app.guantera.presentation.history.HistoryUnit
import app.guantera.presentation.history.HistoryController
import app.guantera.presentation.history.MoneyNumbers
import app.guantera.presentation.history.PeriodKind
import app.guantera.presentation.history.Periods
import app.guantera.presentation.history.RowText
import app.guantera.presentation.history.YearMonth
import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.input.CalendarDate
import app.guantera.presentation.input.LedgerFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The period arithmetic and the totals behind every layout of Gastos and Viajes. Pure JVM. */
class HistoryPeriodsTest {
    private fun date(y: Int, m: Int, d: Int) = CalendarDate(y, m, d)

    /** Wednesday 11 November 2026. */
    private val today = date(2026, 11, 11)
    private fun nav(kind: PeriodKind, anchor: CalendarDate = today) = HistoryNav.initial(anchor, kind).copy(anchor = anchor)

    // ---- Limits of each period --------------------------------------------------------------

    @Test fun `weekdays count from Monday`() {
        assertEquals(0, Periods.weekdayIndex(date(2026, 11, 9)))
        assertEquals(2, Periods.weekdayIndex(today))
        assertEquals(6, Periods.weekdayIndex(date(2026, 11, 15)))
        assertEquals(3, Periods.weekdayIndex(date(1970, 1, 1))) // a Thursday
    }

    @Test fun `a week runs Monday to Sunday and every day of it gives the same week`() {
        val expected = DateSpan(date(2026, 11, 9), date(2026, 11, 15))
        for (day in 9..15) assertEquals(expected, nav(PeriodKind.Week, date(2026, 11, day)).span)
        assertEquals(DateSpan(date(2026, 11, 2), date(2026, 11, 8)), nav(PeriodKind.Week, date(2026, 11, 8)).span)
    }

    @Test fun `a week can cross a month and a year`() {
        assertEquals(DateSpan(date(2026, 10, 26), date(2026, 11, 1)), nav(PeriodKind.Week, date(2026, 10, 28)).span)
        assertEquals(DateSpan(date(2025, 12, 29), date(2026, 1, 4)), nav(PeriodKind.Week, date(2026, 1, 1)).span)
    }

    @Test fun `a month runs from its first to its last day, whatever its length`() {
        assertEquals(DateSpan(date(2026, 11, 1), date(2026, 11, 30)), nav(PeriodKind.Month).span)
        assertEquals(DateSpan(date(2026, 12, 1), date(2026, 12, 31)), nav(PeriodKind.Month, date(2026, 12, 31)).span)
        assertEquals(31, nav(PeriodKind.Month, date(2026, 8, 17)).span.days)
        assertEquals(30, nav(PeriodKind.Month, date(2026, 4, 1)).span.days)
    }

    @Test fun `February has 28 days, or 29 in a leap year, and 1900 and 2100 are not leap`() {
        assertEquals(28, nav(PeriodKind.Month, date(2026, 2, 10)).span.days)
        assertEquals(29, nav(PeriodKind.Month, date(2028, 2, 10)).span.days)
        assertEquals(29, nav(PeriodKind.Month, date(2000, 2, 10)).span.days)
        assertEquals(28, nav(PeriodKind.Month, date(2100, 2, 10)).span.days)
        assertEquals(366, nav(PeriodKind.Year, date(2028, 5, 5)).span.days)
        assertEquals(365, nav(PeriodKind.Year, date(2026, 5, 5)).span.days)
    }

    @Test fun `a year runs from 1 January to 31 December and lists twelve months`() {
        assertEquals(DateSpan(date(2026, 1, 1), date(2026, 12, 31)), nav(PeriodKind.Year).span)
        assertEquals((1..12).map { YearMonth(2026, it) }, Periods.months(nav(PeriodKind.Year)))
    }

    // ---- Moving with the arrows -------------------------------------------------------------

    @Test fun `the arrows move one period and cross the year`() {
        val december = nav(PeriodKind.Month, date(2026, 12, 15))
        assertEquals(DateSpan(date(2027, 1, 1), date(2027, 1, 31)), Periods.shift(december, 1).span)
        val january = nav(PeriodKind.Month, date(2026, 1, 15))
        assertEquals(DateSpan(date(2025, 12, 1), date(2025, 12, 31)), Periods.shift(january, -1).span)
        assertEquals(DateSpan(date(2025, 1, 1), date(2025, 12, 31)), Periods.shift(nav(PeriodKind.Year), -1).span)
        assertEquals(DateSpan(date(2025, 12, 22), date(2025, 12, 28)), Periods.shift(nav(PeriodKind.Week, date(2026, 1, 1)), -1).span)
    }

    @Test fun `a month that is shorter does not lose the way when stepping from the 31st`() {
        val march31 = nav(PeriodKind.Month, date(2026, 3, 31))
        assertEquals(DateSpan(date(2026, 2, 1), date(2026, 2, 28)), Periods.shift(march31, -1).span)
        val back = Periods.shift(Periods.shift(march31, -1), 1)
        assertEquals(DateSpan(date(2026, 3, 1), date(2026, 3, 31)), back.span)
        // January 31 forward into a leap February.
        assertEquals(DateSpan(date(2028, 2, 1), date(2028, 2, 29)), Periods.shift(nav(PeriodKind.Month, date(2028, 1, 31)), 1).span)
    }

    @Test fun `a range slides as a whole, keeping its length`() {
        val range = HistoryNav(PeriodKind.Range, today, YearMonth(2026, 10), YearMonth(2026, 12))
        val back = Periods.shift(range, -1)
        assertEquals(DateSpan(date(2026, 7, 1), date(2026, 9, 30)), back.span)
        assertEquals(DateSpan(date(2027, 1, 1), date(2027, 3, 31)), Periods.shift(range, 1).span)
    }

    @Test fun `nothing newer than today to go to`() {
        assertTrue(Periods.isLatest(nav(PeriodKind.Month), today))
        assertTrue(Periods.isLatest(nav(PeriodKind.Week), today))
        assertTrue(Periods.isLatest(nav(PeriodKind.Year), today))
        assertFalse(Periods.isLatest(Periods.shift(nav(PeriodKind.Month), -1), today))
        assertFalse(Periods.isLatest(Periods.shift(nav(PeriodKind.Year), -1), today))
        val controller = HistoryController { today }
        controller.next()
        assertEquals(nav(PeriodKind.Month).span, controller.state.value.nav.span)
    }

    // ---- Range ------------------------------------------------------------------------------

    @Test fun `an inverted range is put in order, never an error`() {
        val inverted = HistoryNav(PeriodKind.Range, today, YearMonth(2026, 9), YearMonth(2026, 3))
        assertEquals(DateSpan(date(2026, 3, 1), date(2026, 9, 30)), inverted.span)
        assertEquals((3..9).map { YearMonth(2026, it) }, Periods.months(inverted))
    }

    @Test fun `a range of a single month is that month`() {
        val single = HistoryNav(PeriodKind.Range, today, YearMonth(2026, 2), YearMonth(2026, 2))
        assertEquals(DateSpan(date(2026, 2, 1), date(2026, 2, 28)), single.span)
        assertEquals(listOf(YearMonth(2026, 2)), Periods.months(single))
    }

    @Test fun `a range can span years`() {
        val span = HistoryNav(PeriodKind.Range, today, YearMonth(2025, 11), YearMonth(2026, 2))
        assertEquals(listOf(YearMonth(2025, 11), YearMonth(2025, 12), YearMonth(2026, 1), YearMonth(2026, 2)), Periods.months(span))
        assertEquals(DateSpan(date(2025, 11, 1), date(2026, 2, 28)), span.span)
    }

    @Test fun `moving one end past the other pulls the other along, and no end passes the current month`() {
        val range = HistoryNav(PeriodKind.Range, today, YearMonth(2026, 9), YearMonth(2026, 10))
        val pushed = Periods.stepRangeFrom(Periods.stepRangeFrom(range, 1, today), 1, today)
        assertEquals(YearMonth(2026, 11), pushed.rangeFrom)
        assertEquals(YearMonth(2026, 11), pushed.rangeTo)
        val pulled = Periods.stepRangeTo(range, -3, today)
        assertEquals(YearMonth(2026, 7), pulled.rangeFrom)
        assertEquals(YearMonth(2026, 7), pulled.rangeTo)
        val capped = Periods.stepRangeTo(range, 5, today)
        assertEquals(YearMonth(2026, 11), capped.rangeTo)
        assertEquals(YearMonth(2026, 9), capped.rangeFrom)
    }

    @Test fun `changing the kind keeps the place, and a month opened from a range is that month`() {
        val month = Periods.shift(nav(PeriodKind.Month), -3)
        assertEquals(DateSpan(date(2026, 8, 1), date(2026, 8, 31)), Periods.withKind(month, PeriodKind.Month, today).span)
        assertEquals(DateSpan(date(2026, 1, 1), date(2026, 12, 31)), Periods.withKind(month, PeriodKind.Year, today).span)
        val range = HistoryNav(PeriodKind.Range, today, YearMonth(2026, 3), YearMonth(2026, 5))
        val opened = Periods.openMonth(range, YearMonth(2026, 4), today)
        assertEquals(PeriodKind.Month, opened.kind)
        assertEquals(DateSpan(date(2026, 4, 1), date(2026, 4, 30)), opened.span)
        // The current month opens on today, not on its first day.
        assertEquals(today, Periods.openMonth(range, YearMonth(2026, 11), today).anchor)
        // Leaving a range lands on its last month.
        assertEquals(DateSpan(date(2026, 5, 1), date(2026, 5, 31)), Periods.withKind(range, PeriodKind.Month, today).span)
    }

    // ---- Month grid -------------------------------------------------------------------------

    @Test fun `the month grid is whole weeks with empty cells around the days`() {
        // November 2026 starts on a Sunday and has 30 days: 6 + 30 + 6 = 42 cells.
        val november = Periods.monthGrid(YearMonth(2026, 11))
        assertEquals(42, november.size)
        assertEquals(6, november.takeWhile { it == null }.size)
        assertEquals(date(2026, 11, 1), november[6])
        assertEquals(date(2026, 11, 30), november.filterNotNull().last())
        // February 2027 starts on a Monday and has 28 days: exactly four weeks.
        val february = Periods.monthGrid(YearMonth(2027, 2))
        assertEquals(28, february.size)
        assertTrue(february.none { it == null })
        // A 31-day month starting on a Saturday needs six rows: August 2026.
        assertEquals(42, Periods.monthGrid(YearMonth(2026, 8)).size)
        assertEquals(35, Periods.monthGrid(YearMonth(2026, 9)).size)
    }

    // ---- Totals -----------------------------------------------------------------------------

    private fun entry(d: CalendarDate, value: Double, id: Long = 0) = HistoryEntry(
        d, value, HistoryRowUi(id, HistoryIcon.Fuel, RowText.Plain("x"), null, value.toString()),
    )

    private val plain = object : HistoryNumbers {
        override fun full(value: Double) = "F$value"
        override fun compact(value: Double) = "C$value"
    }

    @Test fun `totals add up by day and by month, and ignore what is outside the period`() {
        val span = DateSpan(date(2026, 10, 1), date(2026, 11, 30))
        val entries = listOf(
            entry(date(2026, 11, 11), 10.0), entry(date(2026, 11, 11), 5.0), entry(date(2026, 11, 2), 1.0),
            entry(date(2026, 10, 31), 100.0), entry(date(2026, 9, 30), 7777.0), entry(date(2026, 12, 1), 8888.0),
        )
        val totals = HistoryBuilder.totals(entries, span)
        assertEquals(116.0, totals.total, 0.0)
        assertEquals(4, totals.count)
        assertEquals(15.0, totals.byDay.getValue(date(2026, 11, 11)), 0.0)
        assertEquals(100.0, totals.byDay.getValue(date(2026, 10, 31)), 0.0)
        assertNull(totals.byDay[date(2026, 9, 30)])
        assertEquals(16.0, totals.byMonth.getValue(YearMonth(2026, 11)), 0.0)
        assertEquals(100.0, totals.byMonth.getValue(YearMonth(2026, 10)), 0.0)
    }

    @Test fun `the year calendar gives each month its total and a bar relative to the strongest`() {
        val entries = listOf(entry(date(2026, 3, 5), 50.0), entry(date(2026, 3, 20), 50.0), entry(date(2026, 4, 1), 25.0))
        val state = HistoryState(nav(PeriodKind.Year), HistoryLayout.Calendar)
        val ui = HistoryBuilder.build(state, today, entries, HistoryUnit.Money, plain)
        val body = ui.body as HistoryBody.MonthsGrid
        assertEquals(12, body.cells.size)
        assertEquals(4, body.columns)
        val march = body.cells[2]
        assertEquals("C100.0", march.totalText)
        assertEquals(1f, march.fraction, 0f)
        assertEquals(0.25f, body.cells[3].fraction, 0f)
        assertNull(body.cells[0].totalText)
        assertEquals(0f, body.cells[0].fraction, 0f)
        assertEquals(listOf(YearMonth(2026, 11)), body.cells.filter { it.isCurrent }.map { it.month })
        assertFalse(ui.isEmpty)
    }

    @Test fun `a range calendar lists only the chosen months`() {
        val entries = listOf(entry(date(2026, 4, 1), 25.0), entry(date(2026, 8, 1), 10.0))
        val range = HistoryNav(PeriodKind.Range, today, YearMonth(2026, 4), YearMonth(2026, 6))
        val ui = HistoryBuilder.build(HistoryState(range, HistoryLayout.Calendar), today, entries, HistoryUnit.Money, plain)
        val body = ui.body as HistoryBody.MonthsGrid
        assertEquals(listOf(4, 5, 6), body.cells.map { it.month.month })
        assertEquals(listOf("C25.0", null, null), body.cells.map { it.totalText })
    }

    @Test fun `the week calendar has seven days, the empty ones without total`() {
        val entries = listOf(entry(date(2026, 11, 10), 30.0), entry(date(2026, 11, 11), 60.0))
        val ui = HistoryBuilder.build(HistoryState(nav(PeriodKind.Week), HistoryLayout.Calendar), today, entries, HistoryUnit.Kilometers, plain)
        val cells = (ui.body as HistoryBody.WeekStrip).cells
        assertEquals((9..15).map { date(2026, 11, it) }, cells.map { it.date })
        assertEquals(listOf(null, "C30.0", "C60.0", null, null, null, null), cells.map { it.totalText })
        assertEquals(0.5f, cells[1].fraction, 0f)
        assertEquals(listOf(false, false, true, false, false, false, false), cells.map { it.isToday })
    }

    @Test fun `the list is grouped by day, newest first, and says when it is empty`() {
        val entries = listOf(entry(date(2026, 11, 2), 1.0, id = 1), entry(date(2026, 11, 9), 2.0, id = 2), entry(date(2026, 11, 9), 3.0, id = 3))
        val ui = HistoryBuilder.build(HistoryState(nav(PeriodKind.Month)), today, entries, HistoryUnit.Money, plain)
        val groups = (ui.body as HistoryBody.Timeline).groups
        assertEquals(listOf(date(2026, 11, 9), date(2026, 11, 2)), groups.map { it.date })
        assertEquals(listOf("F5.0", "F1.0"), groups.map { it.totalText })
        assertEquals(listOf(listOf(2L, 3L), listOf(1L)), groups.map { group -> group.rows.map { it.id } })
        val empty = HistoryBuilder.build(HistoryState(nav(PeriodKind.Month)), today, emptyList(), HistoryUnit.Money, plain)
        assertTrue(empty.isEmpty)
        assertTrue((empty.body as HistoryBody.Timeline).groups.isEmpty())
    }

    @Test fun `an open day only applies inside its calendar period`() {
        val entries = listOf(entry(date(2026, 11, 9), 2.0, id = 1))
        val open = HistoryState(nav(PeriodKind.Month), HistoryLayout.Calendar, openDay = date(2026, 11, 9))
        val opened = HistoryBuilder.build(open, today, entries, HistoryUnit.Money, plain).body
        assertEquals(1, (opened as HistoryBody.OpenDay).group.rows.size)
        // A stale day from another month is ignored: the calendar shows.
        val stale = open.copy(openDay = date(2026, 10, 9))
        assertTrue(HistoryBuilder.build(stale, today, entries, HistoryUnit.Money, plain).body is HistoryBody.MonthGrid)
    }

    // ---- Controller -------------------------------------------------------------------------

    @Test fun `going back through January lands in December of the year before and back again`() {
        var now = date(2026, 1, 20)
        val controller = HistoryController { now }
        controller.previous()
        assertEquals(DateSpan(date(2025, 12, 1), date(2025, 12, 31)), controller.state.value.nav.span)
        controller.next()
        assertEquals(DateSpan(date(2026, 1, 1), date(2026, 1, 31)), controller.state.value.nav.span)
        now = date(2026, 2, 1)
        controller.next()
        assertEquals(DateSpan(date(2026, 2, 1), date(2026, 2, 28)), controller.state.value.nav.span)
    }

    @Test fun `choosing a kind or a layout closes the open day`() {
        val controller = HistoryController { today }
        controller.selectLayout(HistoryLayout.Calendar)
        controller.openDay(today)
        assertEquals(today, controller.state.value.openDay)
        controller.selectKind(PeriodKind.Week)
        assertNull(controller.state.value.openDay)
        controller.openDay(today)
        assertTrue(controller.closeDay())
        assertFalse(controller.closeDay())
    }

    // ---- Numbers ----------------------------------------------------------------------------

    @Test fun `money in a cell is short, from hundreds to millions`() {
        val money = MoneyNumbers(LedgerFormat(DisplayFormat(TimeZone.getTimeZone("UTC"))))
        assertEquals("850", money.compact(85_000.0))
        assertEquals("4,5k", money.compact(450_000.0))
        assertEquals("45k", money.compact(4_500_000.0))
        assertEquals("999k", money.compact(99_900_000.0))
        assertEquals("1,2M", money.compact(120_000_000.0))
        assertEquals("18.500", money.full(1_850_000.0))
    }
}
