package app.guantera.domain.time

/**
 * A calendar day without time or zone. Hand-written because `java.time` needs API 26 and the app
 * supports API 24 without desugaring.
 */
data class CalendarDay(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDay> {
    init {
        require(month in 1..12 && day in 1..lengthOfMonth(year, month)) { "Not a real day: $year-$month-$day" }
    }

    /** Days since 1970-01-01. */
    val epochDay: Int get() = daysFromCivil(year, month, day)

    val calendarMonth: CalendarMonth get() = CalendarMonth(year, month)

    override fun compareTo(other: CalendarDay): Int = epochDay.compareTo(other.epochDay)

    fun plusDays(days: Int): CalendarDay = fromEpochDay(epochDay + days)

    /** Same day of the month, or the last day of the target month when it is shorter. */
    fun plusMonths(months: Int): CalendarDay {
        val target = calendarMonth.plusMonths(months)
        return CalendarDay(target.year, target.month, day.coerceAtMost(lengthOfMonth(target.year, target.month)))
    }

    /** Positive when [other] is later. */
    fun daysUntil(other: CalendarDay): Int = other.epochDay - epochDay

    /** The Monday of this day's week. 1970-01-01 was a Thursday. */
    fun mondayOfWeek(): CalendarDay = plusDays(-Math.floorMod(epochDay + THURSDAY_OFFSET, DAYS_PER_WEEK))

    companion object {
        private const val THURSDAY_OFFSET = 3
        private const val DAYS_PER_WEEK = 7

        /** Null when the combination is not a real day. */
        fun of(year: Int, month: Int, day: Int): CalendarDay? =
            if (month in 1..12 && day in 1..lengthOfMonth(year, month)) CalendarDay(year, month, day) else null

        fun fromEpochDay(epochDay: Int): CalendarDay {
            val z = epochDay + 719_468
            val era = Math.floorDiv(z, 146_097)
            val dayOfEra = z - era * 146_097
            val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val shifted = (5 * dayOfYear + 2) / 153
            val day = dayOfYear - (153 * shifted + 2) / 5 + 1
            val month = if (shifted < 10) shifted + 3 else shifted - 9
            val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
            return CalendarDay(year, month, day)
        }

        fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        private fun daysFromCivil(year: Int, month: Int, day: Int): Int {
            val y = if (month <= 2) year - 1 else year
            val era = Math.floorDiv(y, 400)
            val yearOfEra = y - era * 400
            val shifted = if (month > 2) month - 3 else month + 9
            val dayOfYear = (153 * shifted + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era * 146_097 + dayOfEra - 719_468
        }
    }
}

data class CalendarMonth(val year: Int, val month: Int) : Comparable<CalendarMonth> {
    init { require(month in 1..12) { "Not a month: $month" } }

    val firstDay: CalendarDay get() = CalendarDay(year, month, 1)

    override fun compareTo(other: CalendarMonth): Int = compareValuesBy(this, other, { it.year }, { it.month })

    fun plusMonths(months: Int): CalendarMonth {
        val total = year * 12 + (month - 1) + months
        return CalendarMonth(Math.floorDiv(total, 12), Math.floorMod(total, 12) + 1)
    }
}

/**
 * The half-open range of days `[from, toExclusive)` behind every list, calendar and total.
 * A null bound leaves that side open, so `DayRange()` means "everything".
 */
data class DayRange(val from: CalendarDay? = null, val toExclusive: CalendarDay? = null) {
    init {
        require(from == null || toExclusive == null || from <= toExclusive) { "Range ends before it starts" }
    }

    operator fun contains(day: CalendarDay): Boolean =
        (from == null || day >= from) && (toExclusive == null || day < toExclusive)

    companion object {
        fun day(day: CalendarDay) = DayRange(day, day.plusDays(1))

        /** Monday to Sunday, as P3 counts the week. */
        fun weekOf(day: CalendarDay): DayRange {
            val monday = day.mondayOfWeek()
            return DayRange(monday, monday.plusDays(7))
        }

        fun month(month: CalendarMonth) = DayRange(month.firstDay, month.plusMonths(1).firstDay)

        fun year(year: Int) = DayRange(CalendarDay(year, 1, 1), CalendarDay(year + 1, 1, 1))

        /** From the first day of [first] up to and including the last day of [last]. */
        fun months(first: CalendarMonth, last: CalendarMonth) = DayRange(first.firstDay, last.plusMonths(1).firstDay)

        /** The twelve months that end today, the default period of P8. */
        fun lastTwelveMonths(today: CalendarDay) = DayRange(today.plusMonths(-12).plusDays(1), today.plusDays(1))
    }
}
