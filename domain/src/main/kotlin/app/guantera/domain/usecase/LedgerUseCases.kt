package app.guantera.domain.usecase

import app.guantera.domain.expense.CostPerKm
import app.guantera.domain.expense.CostRules
import app.guantera.domain.expense.Expense
import app.guantera.domain.expense.ExpenseTotal
import app.guantera.domain.expense.ExpenseTotals
import app.guantera.domain.port.ExpenseRepository
import app.guantera.domain.port.PageRequest
import app.guantera.domain.port.TripRepository
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.time.CalendarDay
import app.guantera.domain.time.CalendarMonth
import app.guantera.domain.time.DayRange
import app.guantera.domain.trip.Trip
import app.guantera.domain.trip.TripTotal
import app.guantera.domain.trip.TripTotals
import kotlinx.coroutines.flow.Flow

data class TripsHeader(val todayKm: Double, val weekKm: Double, val monthKm: Double)

/** CU-06: lists, calendar totals and the header of P3. */
class ListTrips(
    private val trips: TripRepository,
    private val time: ResolveTrustedTime,
    private val calendar: LocalCalendar,
) {
    val changes: Flow<Unit> get() = trips.changes

    suspend fun page(page: PageRequest): List<Trip> = trips.closed(UtcWindow(null, null), page)

    suspend fun inRange(range: DayRange, page: PageRequest? = null): List<Trip> =
        trips.closed(calendar.window(range), page)

    suspend fun totalsByDay(range: DayRange): List<TripTotal<CalendarDay>> =
        TripTotals.byDay(inRange(range), calendar::dayOf)

    suspend fun totalsByMonth(range: DayRange): List<TripTotal<CalendarMonth>> =
        TripTotals.byMonth(inRange(range), calendar::dayOf)

    suspend fun header(): TripsHeader {
        val today = calendar.dayOf(time.bestEffortUtcMs())
        return TripsHeader(
            todayKm = kmIn(DayRange.day(today)),
            weekKm = kmIn(DayRange.weekOf(today)),
            monthKm = kmIn(DayRange.month(today.calendarMonth)),
        )
    }

    private suspend fun kmIn(range: DayRange) = trips.totalMeters(calendar.window(range)) / METERS_PER_KM

    private companion object { const val METERS_PER_KM = 1000.0 }
}

/** CU-07. Null when the trip does not exist. */
class GetTripDetail(private val trips: TripRepository) {
    suspend operator fun invoke(id: Long): Trip? = trips.find(id)
}

/** CU-15, R22, R25. */
class SaveExpense(private val expenses: ExpenseRepository, private val form: FormSubmission) {
    suspend operator fun invoke(expense: Expense): FormResult<Long> {
        if (expense.amountMinor <= 0) return FormResult.Invalid(FormError.AMOUNT_NOT_POSITIVE)
        if (expense.id == 0L) return form.submit { expenses.save(expense) }
        val existing = expenses.find(expense.id) ?: return FormResult.Invalid(FormError.EXPENSE_NOT_FOUND)
        // Editing an expense never changes which service it came from.
        return form.submit { expenses.save(expense.copy(serviceRecordId = existing.serviceRecordId)) }
    }
}

/** CU-16, R25: the service record stays. */
class DeleteExpense(private val expenses: ExpenseRepository, private val form: FormSubmission) {
    suspend operator fun invoke(id: Long): FormResult<Unit> = form.submit { expenses.delete(id) }
}

/** CU-17: lists and calendar totals of P8. */
class ListExpenses(private val expenses: ExpenseRepository) {
    val changes: Flow<Unit> get() = expenses.changes

    suspend fun find(id: Long): Expense? = expenses.find(id)

    suspend fun inRange(range: DayRange, page: PageRequest? = null): List<Expense> = expenses.inRange(range, page)

    suspend fun totalsByDay(range: DayRange): List<ExpenseTotal<CalendarDay>> =
        ExpenseTotals.byDay(expenses.inRange(range))

    suspend fun totalsByMonth(range: DayRange): List<ExpenseTotal<CalendarMonth>> =
        ExpenseTotals.byMonth(expenses.inRange(range))
}

/** CU-18, R23, R24, for any range. */
class GetCostPerKm(
    private val expenses: ExpenseRepository,
    private val trips: TripRepository,
    private val calendar: LocalCalendar,
) {
    suspend operator fun invoke(range: DayRange): CostPerKm {
        val km = trips.totalMeters(calendar.window(range)) / METERS_PER_KM
        return CostRules.costPerKm(expenses.totalMinor(range), km)
    }

    private companion object { const val METERS_PER_KM = 1000.0 }
}
