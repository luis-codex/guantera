package app.guantera.presentation.expenses

import app.guantera.presentation.input.CalendarDate
import app.guantera.presentation.port.FormOutcome
import kotlinx.coroutines.flow.StateFlow

enum class ExpenseCategory { Fuel, Maintenance, Insurance, Tolls, Parking, Washing, Other }

/** R22: [amountMinor] above zero, in the single currency of the app (centavos), without conversion. */
data class Expense(
    val id: Long,
    val category: ExpenseCategory,
    val amountMinor: Long,
    val date: CalendarDate,
    val note: String?,
    /** Set when a service created this expense. Deleting the expense never deletes the service (R25). */
    val serviceRecordId: Long?,
)

/** R23 and R24. [minorPerKm] is null when there are fewer than 50 km in the period. */
data class PeriodCost(val totalMinor: Long, val km: Double, val minorPerKm: Double?)

/** Boundary for P8 and P9 (CU-15 to CU-18). */
interface ExpenseData {
    val expenses: StateFlow<List<Expense>>

    /**
     * The expenses dated from [from] to [to], both included. The history screen asks for the period
     * it shows; a real source can answer from an indexed query instead of filtering everything.
     */
    fun expensesBetween(from: CalendarDate, to: CalendarDate): List<Expense> =
        expenses.value.filter { it.date >= from && it.date <= to }

    /** Creates the expense when its id is 0, otherwise replaces it. */
    suspend fun save(expense: Expense): FormOutcome

    suspend fun delete(id: Long): FormOutcome

    /** CU-18 for the days [from] to [to], both included: the total, the km of closed trips and the cost of one km. */
    suspend fun costPerKm(from: CalendarDate, to: CalendarDate): PeriodCost
}
