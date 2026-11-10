package app.guantera.presentation.expenses

import app.guantera.presentation.input.CalendarDate
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

/** Boundary for P8 and P9 (CU-15 to CU-18). */
interface ExpenseData {
    val expenses: StateFlow<List<Expense>>

    /** Creates the expense when its id is 0, otherwise replaces it. Returns the id. */
    fun save(expense: Expense): Long

    fun delete(id: Long)

    /** Kilometers of closed trips in the period, for the cost per kilometer (R23). [from] null means from the start. */
    fun tripKilometers(from: CalendarDate?, to: CalendarDate): Double
}
