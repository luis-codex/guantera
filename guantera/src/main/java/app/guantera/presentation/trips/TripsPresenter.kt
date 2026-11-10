package app.guantera.presentation.trips

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.history.HistoryActions
import app.guantera.presentation.history.HistoryBuilder
import app.guantera.presentation.history.HistoryController
import app.guantera.presentation.history.HistoryEntry
import app.guantera.presentation.history.HistoryIcon
import app.guantera.presentation.history.HistoryLayout
import app.guantera.presentation.history.HistoryRowUi
import app.guantera.presentation.history.HistoryState
import app.guantera.presentation.history.HistoryUi
import app.guantera.presentation.history.HistoryUnit
import app.guantera.presentation.history.KilometerNumbers
import app.guantera.presentation.history.PeriodKind
import app.guantera.presentation.history.RowText
import app.guantera.presentation.history.YearMonth
import app.guantera.presentation.input.CalendarDate
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
    /** The figures answer to the period shown in [history]. */
    val periodKmText: String,
    val periodTripsText: String,
    val history: HistoryUi,
    /** Non-null while one trip is open (P4). */
    val detail: TripDetailUi?,
)

class TripsPresenter(
    private val data: GuanteraData,
    private val format: DisplayFormat,
    private val clock: Clock,
    ticks: Flow<Long>,
    scope: CoroutineScope,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    private val selectedId = MutableStateFlow<Long?>(null)
    private val history = HistoryController { CalendarDate.fromMillis(clock.nowMillis(), zone) }
    private val numbers = KilometerNumbers(format)

    val state: StateFlow<TripsViewState> = combine(data.trips, ticks, history.state, selectedId) { trips, now, view, selected ->
        reduce(trips, now, view, selected)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(data.trips.value, clock.nowMillis(), history.state.value, null),
    )

    /** Period, layout and day moves. Opening a row opens its detail (P4). */
    val historyActions: HistoryActions = object : HistoryActions {
        override fun selectKind(kind: PeriodKind) = history.selectKind(kind)
        override fun selectLayout(layout: HistoryLayout) = history.selectLayout(layout)
        override fun previousPeriod() = history.previous()
        override fun nextPeriod() = history.next()
        override fun openDay(date: CalendarDate) = history.openDay(date)
        override fun closeDay() { history.closeDay() }
        override fun openMonth(month: YearMonth) = history.openMonth(month)
        override fun stepRangeFrom(delta: Int) = history.stepRangeFrom(delta)
        override fun stepRangeTo(delta: Int) = history.stepRangeTo(delta)
        override fun openRow(id: Long) = openTrip(id)
    }

    fun openTrip(id: Long) { selectedId.value = id }

    /** Returns true if something was open, so that the back key closes it instead of leaving the screen: first the trip, then the day. */
    fun onBack(): Boolean = closeTrip() || history.closeDay()

    /** Returns true if a trip was open. */
    fun closeTrip(): Boolean {
        val wasOpen = selectedId.value != null
        selectedId.value = null
        return wasOpen
    }

    /** Called when the user navigates away: the next visit starts from the current month. */
    fun reset() {
        selectedId.value = null
        history.reset()
    }

    private fun reduce(trips: List<TripRecord>, now: Long, view: HistoryState, selected: Long?): TripsViewState {
        val today = startOfDay(now)
        val yesterday = shiftDays(today, -1)
        val todayDate = CalendarDate.fromMillis(now, zone)
        val span = view.nav.span
        val inPeriod = data.tripsBetween(dayStart(span.from), dayStart(span.to.plusDays(1)))
        val entries = inPeriod.map { trip ->
            HistoryEntry(
                date = CalendarDate.fromMillis(trip.startMillis, zone),
                value = trip.distanceKm,
                row = HistoryRowUi(
                    id = trip.id,
                    icon = HistoryIcon.Trip,
                    title = RowText.TripTimes(format.clock(trip.startMillis), durationMinutes(trip)),
                    meta = null,
                    valueText = format.oneDecimal(trip.distanceKm),
                    estimated = trip.closure == TripClosure.Recovered,
                ),
            )
        }
        return TripsViewState(
            periodKmText = format.whole(inPeriod.sumOf { it.distanceKm }.roundToInt()),
            periodTripsText = format.whole(inPeriod.size),
            history = HistoryBuilder.build(view, todayDate, entries, HistoryUnit.Kilometers, numbers),
            detail = selected?.let { id -> trips.firstOrNull { it.id == id } }?.let { toDetail(it, today, yesterday) },
        )
    }

    /** Midnight at the start of [date], in the presenter's zone. */
    private fun dayStart(date: CalendarDate): Long = Calendar.getInstance(zone).apply {
        clear()
        set(date.year, date.month - 1, date.day, 0, 0, 0)
    }.timeInMillis

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

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 3_600_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
