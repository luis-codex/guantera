package app.guantera.sample

import app.guantera.presentation.port.GeoPoint
import app.guantera.presentation.port.TripClosure
import app.guantera.presentation.port.TripRecord
import java.util.Calendar
import java.util.TimeZone

/** A believable two-month history around Buenos Aires. Deterministic, so that it looks the same every run. */
internal object SampleTrips {
    val garage = GeoPoint(-34.56270, -58.45650)
    private val places = listOf(
        garage,
        GeoPoint(-34.60370, -58.38160),
        GeoPoint(-34.58410, -58.43060),
        GeoPoint(-34.54520, -58.48830),
        GeoPoint(-34.61750, -58.43340),
    )
    private val distances = listOf(11.4, 6.2, 23.7, 3.8, 15.1, 41.6, 8.9, 18.3)

    /** Today's kilometers and trips, which [build] guarantees whatever the time of day. */
    const val TODAY_KM = 42.8
    const val TODAY_TRIPS = 3

    private const val HOUR_MILLIS = 3_600_000L
    private const val MINUTE_MILLIS = 60_000L
    private const val PAST_DAYS = 60

    /** Time since the last trip ended, the moment the car was left in the garage. */
    fun parkedAgoMillis(now: Long, zone: TimeZone): Long = (window(now, zone) * 0.28125).toLong()

    fun build(now: Long, zone: TimeZone): List<TripRecord> = (today(now, zone) + past(now, zone))
        .sortedByDescending { it.startMillis }
        .mapIndexed { index, trip -> trip.copy(id = (10_000 - index).toLong()) }

    /** Today's trips are spread over the part of the day that has already happened. */
    private fun window(now: Long, zone: TimeZone): Long = (now - startOfDay(now, zone, 0)).coerceAtMost(8 * HOUR_MILLIS)

    private fun today(now: Long, zone: TimeZone): List<TripRecord> {
        val window = window(now, zone)
        val scale = window.toDouble() / (8 * HOUR_MILLIS)
        fun minutes(value: Int) = (value * MINUTE_MILLIS * scale).toLong()
        val lastEnd = now - parkedAgoMillis(now, zone)
        val lastStart = lastEnd - minutes(26)
        val middleEnd = lastStart - (window * 0.2).toLong()
        val middleStart = middleEnd - minutes(11)
        val firstEnd = middleStart - (window * 0.2).toLong()
        val firstStart = firstEnd - minutes(28)
        return listOf(
            trip(firstStart, firstEnd, 18.4, places[0], places[1], TripClosure.Automatic),
            trip(middleStart, middleEnd, 6.1, places[1], places[2], TripClosure.Recovered),
            trip(lastStart, lastEnd, 18.3, places[2], garage, TripClosure.Automatic),
        )
    }

    private fun past(now: Long, zone: TimeZone): List<TripRecord> = (1..PAST_DAYS).flatMap { day ->
        val midnight = startOfDay(now, zone, day)
        (0 until day % 3 + 1).map { slot ->
            val seed = day * 3 + slot
            val distance = distances[seed % distances.size]
            val start = midnight + (8 + slot * 5 + seed % 3) * HOUR_MILLIS + (seed * 7 % 50) * MINUTE_MILLIS
            val duration = ((distance * 2.1).toLong() + seed % 9 + 3) * MINUTE_MILLIS
            val closure = when {
                seed % 13 == 0 -> TripClosure.Recovered
                seed % 7 == 0 -> TripClosure.Manual
                else -> TripClosure.Automatic
            }
            trip(start, start + duration, distance, places[seed % places.size], places[(seed + 1) % places.size], closure)
        }
    }

    private fun startOfDay(now: Long, zone: TimeZone, daysAgo: Int): Long = Calendar.getInstance(zone).run {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun trip(start: Long, end: Long, km: Double, from: GeoPoint, to: GeoPoint, closure: TripClosure) =
        TripRecord(id = 0, startMillis = start, endMillis = end, distanceKm = km, start = from, end = to, closure = closure)
}
