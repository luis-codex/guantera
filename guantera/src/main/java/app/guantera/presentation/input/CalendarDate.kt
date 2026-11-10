package app.guantera.presentation.input

import java.util.Calendar
import java.util.TimeZone

/** A calendar day without time or zone. Immutable and pure, so every rule on dates can be tested on the JVM. */
data class CalendarDate(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDate> {
    /** Days since 1970-01-01. */
    val epochDay: Int get() = daysFromCivil(year, month, day)

    override fun compareTo(other: CalendarDate): Int = epochDay.compareTo(other.epochDay)

    fun plusDays(days: Int): CalendarDate = fromEpochDay(epochDay + days)

    /** Same day of the month, or the last day of the target month when it is shorter. */
    fun plusMonths(months: Int): CalendarDate {
        val total = year * 12 + (month - 1) + months
        val newYear = Math.floorDiv(total, 12)
        val newMonth = Math.floorMod(total, 12) + 1
        return CalendarDate(newYear, newMonth, day.coerceAtMost(lengthOfMonth(newYear, newMonth)))
    }

    /** Positive when [other] is later. */
    fun daysUntil(other: CalendarDate): Int = other.epochDay - epochDay

    companion object {
        /** Null when the combination is not a real day or the year is outside 1990..2100. */
        fun of(year: Int, month: Int, day: Int): CalendarDate? {
            if (year !in MIN_YEAR..MAX_YEAR || month !in 1..12) return null
            if (day !in 1..lengthOfMonth(year, month)) return null
            return CalendarDate(year, month, day)
        }

        fun fromEpochDay(epochDay: Int): CalendarDate {
            val z = epochDay + 719_468
            val era = Math.floorDiv(z, 146_097)
            val dayOfEra = z - era * 146_097
            val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val shifted = (5 * dayOfYear + 2) / 153
            val day = dayOfYear - (153 * shifted + 2) / 5 + 1
            val month = if (shifted < 10) shifted + 3 else shifted - 9
            val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
            return CalendarDate(year, month, day)
        }

        fun fromMillis(epochMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): CalendarDate {
            val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
            return CalendarDate(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
            )
        }

        fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (isLeap(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        private fun isLeap(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

        private fun daysFromCivil(year: Int, month: Int, day: Int): Int {
            val y = if (month <= 2) year - 1 else year
            val era = Math.floorDiv(y, 400)
            val yearOfEra = y - era * 400
            val shifted = if (month > 2) month - 3 else month + 9
            val dayOfYear = (153 * shifted + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era * 146_097 + dayOfEra - 719_468
        }

        const val MIN_YEAR = 1990
        const val MAX_YEAR = 2100
    }
}
