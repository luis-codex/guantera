package app.guantera.domain.expense

import app.guantera.domain.time.CalendarDay
import app.guantera.domain.time.CalendarMonth

enum class ExpenseCategory { COMBUSTIBLE, MANTENIMIENTO, SEGURO, PEAJES, ESTACIONAMIENTO, LAVADO, OTROS }

/** R22: the amount is a positive integer in the minor unit of the single currency of the app. */
data class Expense(
    val id: Long,
    val category: ExpenseCategory,
    val amountMinor: Long,
    val date: CalendarDay,
    val note: String?,
    /** Set when a service created this expense (R25). */
    val serviceRecordId: Long?,
)

data class ExpenseTotal<K>(val key: K, val amountMinor: Long, val expenseCount: Int)

object ExpenseTotals {
    fun byDay(expenses: List<Expense>): List<ExpenseTotal<CalendarDay>> = group(expenses) { it.date }

    fun byMonth(expenses: List<Expense>): List<ExpenseTotal<CalendarMonth>> = group(expenses) { it.date.calendarMonth }

    private fun <K : Comparable<K>> group(expenses: List<Expense>, keyOf: (Expense) -> K): List<ExpenseTotal<K>> =
        expenses.groupBy(keyOf).toSortedMap().map { (key, group) ->
            ExpenseTotal(key, group.sumOf { it.amountMinor }, group.size)
        }
}

sealed interface CostPerKm {
    val totalMinor: Long
    val distanceKm: Double

    data class Available(
        override val totalMinor: Long,
        override val distanceKm: Double,
        val minorPerKm: Double,
    ) : CostPerKm

    /** R24: fewer than 50 km in the range. */
    data class NotEnoughKm(override val totalMinor: Long, override val distanceKm: Double) : CostPerKm
}

object CostRules {
    const val MIN_KM = 50.0

    /** R23 and R24. */
    fun costPerKm(totalMinor: Long, distanceKm: Double): CostPerKm =
        if (distanceKm < MIN_KM) {
            CostPerKm.NotEnoughKm(totalMinor, distanceKm)
        } else {
            CostPerKm.Available(totalMinor, distanceKm, totalMinor / distanceKm)
        }
}
