package app.guantera.presentation.history

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.expenses.ExpenseCategory
import app.guantera.presentation.input.LedgerFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

fun ExpenseCategory.historyIcon(): HistoryIcon = when (this) {
    ExpenseCategory.Fuel -> HistoryIcon.Fuel
    ExpenseCategory.Maintenance -> HistoryIcon.Maintenance
    ExpenseCategory.Insurance -> HistoryIcon.Insurance
    ExpenseCategory.Tolls -> HistoryIcon.Tolls
    ExpenseCategory.Parking -> HistoryIcon.Parking
    ExpenseCategory.Washing -> HistoryIcon.Washing
    ExpenseCategory.Other -> HistoryIcon.Other
}

private val symbols = DecimalFormatSymbols(Locale("es", "AR"))

/** Money in centavos. Cells are narrow, so a day's total is "850", "4,5k" or "1,2M". */
class MoneyNumbers(private val format: LedgerFormat) : HistoryNumbers {
    private val oneDecimal = DecimalFormat("#0.#", symbols)
    private val whole = DecimalFormat("#0", symbols)

    override fun full(value: Double): String = format.money(Math.round(value))

    override fun compact(value: Double): String {
        val pesos = value / CENTAVOS
        return when {
            pesos < THOUSAND -> whole.format(Math.round(pesos))
            pesos < MILLION -> scaled(pesos / THOUSAND) + "k"
            else -> scaled(pesos / MILLION) + "M"
        }
    }

    private fun scaled(amount: Double) = if (amount < TEN) oneDecimal.format(amount) else whole.format(Math.round(amount))

    private companion object {
        const val CENTAVOS = 100.0
        const val THOUSAND = 1_000.0
        const val MILLION = 1_000_000.0
        const val TEN = 9.95
    }
}

/** Kilometers: the full form keeps one decimal, the cell form is whole. */
class KilometerNumbers(private val display: DisplayFormat) : HistoryNumbers {
    override fun full(value: Double): String = display.oneDecimal(value)

    override fun compact(value: Double): String = display.whole(Math.round(value).toInt())
}
