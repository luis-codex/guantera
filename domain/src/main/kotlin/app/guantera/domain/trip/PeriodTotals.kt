package app.guantera.domain.trip

import app.guantera.domain.time.CalendarDay
import app.guantera.domain.time.CalendarMonth

/** Kilometers and trip count of one calendar bucket, keyed by [CalendarDay] or [CalendarMonth]. */
data class TripTotal<K>(val key: K, val distanceMeters: Double, val tripCount: Int) {
    val distanceKm: Double get() = distanceMeters / 1000.0
}

/** A trip counts entirely for the local day it started on. */
object TripTotals {
    fun byDay(trips: List<Trip>, dayOf: (Long) -> CalendarDay): List<TripTotal<CalendarDay>> =
        group(trips) { dayOf(it.startUtcMs) }

    fun byMonth(trips: List<Trip>, dayOf: (Long) -> CalendarDay): List<TripTotal<CalendarMonth>> =
        group(trips) { dayOf(it.startUtcMs).calendarMonth }

    private fun <K : Comparable<K>> group(trips: List<Trip>, keyOf: (Trip) -> K): List<TripTotal<K>> =
        trips.groupBy(keyOf).toSortedMap().map { (key, group) ->
            TripTotal(key, group.sumOf { it.distanceMeters }, group.size)
        }
}
