package app.guantera.ui.history

import android.content.Context
import app.guantera.R
import app.guantera.presentation.history.HistoryIcon
import app.guantera.presentation.history.HistoryUi
import app.guantera.presentation.history.HistoryUnit
import app.guantera.presentation.history.PeriodKind
import app.guantera.presentation.history.Periods
import app.guantera.presentation.history.RowText
import app.guantera.presentation.history.YearMonth
import app.guantera.presentation.input.CalendarDate
import app.guantera.ui.screens.categoryText
import app.guantera.ui.screens.durationText
import app.guantera.ui.screens.money

fun HistoryIcon.drawable(): Int = when (this) {
    HistoryIcon.Fuel -> R.drawable.ic_l_fuel
    HistoryIcon.Maintenance -> R.drawable.ic_l_wrench
    HistoryIcon.Insurance -> R.drawable.ic_l_shield
    HistoryIcon.Tolls -> R.drawable.ic_l_tolls
    HistoryIcon.Parking -> R.drawable.ic_l_parking
    HistoryIcon.Washing -> R.drawable.ic_l_washing
    HistoryIcon.Other -> R.drawable.ic_l_other
    HistoryIcon.Trip -> R.drawable.ic_l_trip
}

fun Context.monthName(month: YearMonth): String = resources.getStringArray(R.array.hs_months_full)[month.month - 1]

fun Context.monthShort(month: YearMonth): String = resources.getStringArray(R.array.hs_months_short)[month.month - 1]

fun Context.weekdayShort(index: Int): String = resources.getStringArray(R.array.hs_weekdays_short)[index]

fun Context.weekdayFull(date: CalendarDate): String = resources.getStringArray(R.array.hs_weekdays_full)[Periods.weekdayIndex(date)]

private fun Context.monthLower(month: Int): String = resources.getStringArray(R.array.hs_months_full)[month - 1].lowercase()

/** "Junio 2026", "8 – 14 jun 2026", "2026". For Range the two months are shown apart, so this is the whole span. */
fun Context.periodTitle(ui: HistoryUi): String {
    val from = ui.span.from
    val to = ui.span.to
    return when (ui.kind) {
        PeriodKind.Month -> getString(R.string.hs_title_month, monthName(YearMonth.of(from)), from.year)
        PeriodKind.Year -> from.year.toString()
        PeriodKind.Week -> {
            val fromShort = monthShort(YearMonth.of(from)).lowercase()
            val toShort = monthShort(YearMonth.of(to)).lowercase()
            if (from.month == to.month) {
                getString(R.string.hs_title_week_same_month, from.day, to.day, toShort, to.year)
            } else {
                getString(R.string.hs_title_week_cross_month, from.day, fromShort, to.day, toShort, to.year)
            }
        }
        PeriodKind.Range -> getString(R.string.hs_title_month, monthName(ui.rangeFrom), ui.rangeFrom.year)
    }
}

fun Context.monthTitle(month: YearMonth): String = getString(R.string.hs_title_month, monthName(month), month.year)

/** The period inside a sentence: "la semana del 8 de junio al 14 de junio", "junio de 2026". */
fun Context.periodInSentence(ui: HistoryUi): String {
    val from = ui.span.from
    val to = ui.span.to
    return when (ui.kind) {
        PeriodKind.Week -> if (from.month == to.month) {
            getString(R.string.hs_in_week, from.day, to.day, monthLower(to.month))
        } else {
            getString(R.string.hs_in_week_cross, from.day, monthLower(from.month), to.day, monthLower(to.month))
        }
        PeriodKind.Month -> getString(R.string.hs_in_month, monthLower(from.month), from.year)
        PeriodKind.Year -> getString(R.string.hs_in_year, from.year)
        PeriodKind.Range -> getString(
            R.string.hs_in_range, monthLower(ui.rangeFrom.month), ui.rangeFrom.year, monthLower(ui.rangeTo.month), ui.rangeTo.year,
        )
    }
}

/** "Lunes 9 de junio", with the year when it is not the current one, and "Hoy · " or "Ayer · " first. */
fun Context.dayHeader(date: CalendarDate, today: CalendarDate): String {
    val weekday = weekdayFull(date)
    val month = monthLower(date.month)
    val plain = if (date.year == today.year) {
        getString(R.string.hs_day_header, weekday, date.day, month)
    } else {
        getString(R.string.hs_day_header_year, weekday, date.day, month, date.year)
    }
    return when (date) {
        today -> getString(R.string.hs_day_header_today, plain)
        today.plusDays(-1) -> getString(R.string.hs_day_header_yesterday, plain)
        else -> plain
    }
}

/** A value with its currency or unit. */
fun Context.withUnit(unit: HistoryUnit, currency: String, text: String): String = when (unit) {
    HistoryUnit.Money -> money(currency, text)
    HistoryUnit.Kilometers -> getString(R.string.trips_km, text)
}

fun Context.rowTitle(title: RowText): String = when (title) {
    is RowText.Plain -> title.text
    is RowText.Category -> categoryText(title.category)
    is RowText.TripTimes -> getString(R.string.hs_duration_join, title.startText, durationText(title.minutes))
}
