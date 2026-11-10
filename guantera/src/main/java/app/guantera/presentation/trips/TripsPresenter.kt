package app.guantera.presentation.trips

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.port.GeoPoint
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.port.TripClosure
import app.guantera.presentation.port.TripRecord
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

sealed interface DayLabel {
    data object Today : DayLabel
    data object Yesterday : DayLabel
    data class Date(val text: String) : DayLabel
}

data class TripRowUi(
    val id: Long,
    val day: DayLabel,
    val startTimeText: String,
    val durationMinutes: Long,
    val kmText: String,
    val isEstimatedClosure: Boolean,
)

data class TripDetailUi(
    val day: DayLabel,
    val kmText: String,
    val durationMinutes: Long,
    /** Null for a trip too short to give a meaningful average. */
    val averageSpeedText: String?,
    val startTimeText: String,
    val startCoordinates: String,
    val endTimeText: String,
    val endCoordinates: String,
    val closure: TripClosure,
)

data class TripsViewState(
    val todayKmText: String,
    val weekKmText: String,
    val monthKmText: String,
    val rows: List<TripRowUi>,
    val hasMore: Boolean,
    val isEmpty: Boolean,
    /** Non-null while one trip is open (P4). */
    val detail: TripDetailUi?,
)

class TripsPresenter(
    private val data: GuanteraData,
    private val format: DisplayFormat,
    clock: Clock,
    ticks: Flow<Long>,
    scope: CoroutineScope,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    private val visibleCount = MutableStateFlow(PAGE_SIZE)
    private val selectedId = MutableStateFlow<Long?>(null)

    val state: StateFlow<TripsViewState> = combine(data.trips, ticks, visibleCount, selectedId) { trips, now, visible, selected ->
        reduce(trips, now, visible, selected)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(data.trips.value, clock.nowMillis(), PAGE_SIZE, null),
    )

    fun loadMore() {
        if (visibleCount.value < data.trips.value.size) visibleCount.value += PAGE_SIZE
    }

    fun openTrip(id: Long) { selectedId.value = id }

    /** Returns true if a trip was open, so that the back key closes it instead of leaving the screen. */
    fun closeTrip(): Boolean {
        val wasOpen = selectedId.value != null
        selectedId.value = null
        return wasOpen
    }

    /** Called when the user navigates away: the next visit starts from the top of the list. */
    fun reset() {
        selectedId.value = null
        visibleCount.value = PAGE_SIZE
    }

    private fun reduce(trips: List<TripRecord>, now: Long, visible: Int, selected: Long?): TripsViewState {
        val today = startOfDay(now)
        val yesterday = shiftDays(today, -1)
        return TripsViewState(
            todayKmText = format.oneDecimal(kmSince(trips, today)),
            weekKmText = format.whole(kmSince(trips, startOfWeek(now)).roundToInt()),
            monthKmText = format.whole(kmSince(trips, startOfMonth(now)).roundToInt()),
            rows = trips.take(visible).map { toRow(it, today, yesterday) },
            hasMore = visible < trips.size,
            isEmpty = trips.isEmpty(),
            detail = selected?.let { id -> trips.firstOrNull { it.id == id } }?.let { toDetail(it, today, yesterday) },
        )
    }

    private fun kmSince(trips: List<TripRecord>, from: Long) = trips.filter { it.startMillis >= from }.sumOf { it.distanceKm }

    private fun toRow(trip: TripRecord, today: Long, yesterday: Long) = TripRowUi(
        id = trip.id,
        day = dayLabel(trip.startMillis, today, yesterday),
        startTimeText = format.clock(trip.startMillis),
        durationMinutes = durationMinutes(trip),
        kmText = format.oneDecimal(trip.distanceKm),
        isEstimatedClosure = trip.closure == TripClosure.Recovered,
    )

    private fun toDetail(trip: TripRecord, today: Long, yesterday: Long) = TripDetailUi(
        day = dayLabel(trip.startMillis, today, yesterday),
        kmText = format.oneDecimal(trip.distanceKm),
        durationMinutes = durationMinutes(trip),
        averageSpeedText = averageSpeed(trip)?.let(format::whole),
        startTimeText = format.clock(trip.startMillis),
        startCoordinates = coordinates(trip.start),
        endTimeText = format.clock(trip.endMillis),
        endCoordinates = coordinates(trip.end),
        closure = trip.closure,
    )

    private fun coordinates(point: GeoPoint) = format.coordinates(point.latitude, point.longitude)

    private fun durationMinutes(trip: TripRecord) = (trip.endMillis - trip.startMillis).coerceAtLeast(0) / MINUTE_MILLIS

    private fun averageSpeed(trip: TripRecord): Int? {
        val millis = trip.endMillis - trip.startMillis
        if (millis < MINUTE_MILLIS) return null
        return (trip.distanceKm / (millis.toDouble() / HOUR_MILLIS)).roundToInt()
    }

    private fun dayLabel(millis: Long, today: Long, yesterday: Long): DayLabel = when {
        millis >= today -> DayLabel.Today
        millis >= yesterday -> DayLabel.Yesterday
        else -> DayLabel.Date(format.dayMonth(millis))
    }

    private fun calendarAt(millis: Long): Calendar = Calendar.getInstance(zone).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun startOfDay(millis: Long) = calendarAt(millis).timeInMillis

    private fun shiftDays(dayStart: Long, days: Int) = calendarAt(dayStart).apply { add(Calendar.DAY_OF_YEAR, days) }.timeInMillis

    /** Weeks start on Monday, as they do in Argentina. */
    private fun startOfWeek(millis: Long) = calendarAt(millis).apply {
        val sinceMonday = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + DAYS_PER_WEEK) % DAYS_PER_WEEK
        add(Calendar.DAY_OF_YEAR, -sinceMonday)
    }.timeInMillis

    private fun startOfMonth(millis: Long) = calendarAt(millis).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

    private companion object {
        const val PAGE_SIZE = 20
        const val DAYS_PER_WEEK = 7
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 3_600_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
