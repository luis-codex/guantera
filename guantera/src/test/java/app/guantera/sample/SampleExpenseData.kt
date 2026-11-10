package app.guantera.sample

import app.guantera.presentation.expenses.Expense
import app.guantera.presentation.expenses.ExpenseCategory
import app.guantera.presentation.expenses.ExpenseData
import app.guantera.presentation.expenses.PeriodCost
import app.guantera.presentation.input.CalendarDate
import app.guantera.presentation.port.FormOutcome
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Invented expenses for the last 13 months, all relative to today so the period selector always
 * has something to show. Amounts are in pesos, stored as centavos.
 */
class SampleExpenseData(private val clock: Clock) : ExpenseData {
    private val today = CalendarDate.fromMillis(clock.nowMillis())
    private var nextId = 1L
    private val _expenses = MutableStateFlow(seed())

    override val expenses: StateFlow<List<Expense>> = _expenses

    /** What the next write answers: a test sets it to see how every form reacts. */
    var outcome: FormOutcome = FormOutcome.Saved
    var saves = 0

    override suspend fun save(expense: Expense): FormOutcome {
        saves += 1
        if (outcome != FormOutcome.Saved) return outcome
        val saved = if (expense.id == 0L) expense.copy(id = nextId++) else expense
        _expenses.update { list ->
            if (expense.id == 0L) list + saved else list.map { if (it.id == saved.id) saved else it }
        }
        return FormOutcome.Saved
    }

    override suspend fun delete(id: Long): FormOutcome {
        if (outcome != FormOutcome.Saved) return outcome
        _expenses.update { list -> list.filterNot { it.id == id } }
        return FormOutcome.Saved
    }

    /** A steady 36 km a day since the start of the sample, standing in for the closed trips (R23). */
    override suspend fun costPerKm(from: CalendarDate, to: CalendarDate): PeriodCost {
        val start = maxOf(from, sampleStart())
        val km = (start.daysUntil(to) + 1).coerceAtLeast(0) * KM_PER_DAY
        val total = _expenses.value.filter { it.date >= from && it.date <= to }.sumOf { it.amountMinor }
        return PeriodCost(total, km, if (km < 50.0) null else total / km)
    }

    private fun sampleStart(): CalendarDate = today.plusMonths(-13)

    private fun seed(): List<Expense> {
        val list = mutableListOf<Expense>()
        fun add(daysAgo: Int, category: ExpenseCategory, pesos: Long, note: String? = null, centavos: Long = 0) {
            list += Expense(nextId++, category, pesos * 100 + centavos, today.plusDays(-daysAgo), note, null)
        }
        // Fuel about every nine days, a little dearer over time.
        var day = 3
        var step = 0
        while (day < 395) {
            val liters = 31 + (step * 7) % 12
            val price = 1_210L + (395 - day) / 2
            add(day, ExpenseCategory.Fuel, liters * price, if (step % 4 == 0) "YPF Av. Libertador" else null)
            day += 8 + step % 3
            step += 1
        }
        for (month in 0..12) {
            add(month * 30 + 11, ExpenseCategory.Insurance, 71_400, "Cuota del seguro")
        }
        add(98, ExpenseCategory.Maintenance, 138_500, "Cambio de aceite y filtros", centavos = 50)
        add(172, ExpenseCategory.Maintenance, 64_000, "Rotación y balanceo")
        add(251, ExpenseCategory.Maintenance, 215_900, "Pastillas de freno delanteras")
        add(330, ExpenseCategory.Maintenance, 42_300, "Filtro de aire")
        for (i in 0..9) add(6 + i * 37, ExpenseCategory.Tolls, 3_100L + i * 90, "Peaje")
        for (i in 0..7) add(14 + i * 45, ExpenseCategory.Parking, 7_500L + i * 400, "Cochera")
        // Street parking in the city about twice a week, so that no week of the sample is empty.
        for (i in 0..125) add(1 + i * 3 + i % 2, ExpenseCategory.Parking, 1_800L + (i % 5) * 150, "Estacionamiento medido")
        for (i in 0..8) add(20 + i * 41, ExpenseCategory.Washing, 12_000L + i * 600)
        add(60, ExpenseCategory.Other, 9_800, "Perfume y alfombras")
        add(205, ExpenseCategory.Other, 27_500, "Patente: cuota")
        return list
    }

    private companion object {
        const val KM_PER_DAY = 36.0
    }
}
