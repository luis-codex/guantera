package app.guantera.presentation.input

import java.util.Locale

/** What a field accepts from the on-screen pad. */
sealed interface PadSpec {
    /** Whole numbers, such as kilometers. */
    data class Integer(val maxDigits: Int) : PadSpec

    /** Money: whole part, a comma and up to [decimals] decimals. */
    data class Decimal(val maxIntDigits: Int, val decimals: Int = 2) : PadSpec

    /** dd/mm/aaaa typed as eight digits. */
    data object Date : PadSpec
}

sealed interface PadKey {
    data class Digit(val value: Int) : PadKey
    data object Backspace : PadKey
    data object Comma : PadKey
    data object Today : PadKey
}

/** The spare key next to the zero: a comma for money, "Hoy" for dates, nothing for whole numbers. */
enum class PadExtra { None, Comma, Today }

/**
 * Digits typed on the pad for one field. Immutable: pressing a key returns the next entry.
 * [typed] holds only digits and, for money, one comma.
 */
data class PadEntry(val spec: PadSpec, val typed: String = "") {
    val isEmpty: Boolean get() = typed.isEmpty()

    val extra: PadExtra
        get() = when (spec) {
            is PadSpec.Integer -> PadExtra.None
            is PadSpec.Decimal -> if (spec.decimals > 0) PadExtra.Comma else PadExtra.None
            PadSpec.Date -> PadExtra.Today
        }

    /** [today] is only needed for [PadKey.Today]. */
    fun press(key: PadKey, today: CalendarDate? = null): PadEntry = when (key) {
        is PadKey.Digit -> pressDigit(key.value)
        PadKey.Backspace -> copy(typed = typed.dropLast(1))
        PadKey.Comma -> pressComma()
        PadKey.Today -> if (spec == PadSpec.Date && today != null) ofDate(today) else this
    }

    private fun pressDigit(digit: Int): PadEntry {
        require(digit in 0..9)
        return when (spec) {
            is PadSpec.Integer -> copy(typed = appendWhole(typed, digit, spec.maxDigits))
            is PadSpec.Decimal -> {
                val comma = typed.indexOf(',')
                if (comma < 0) {
                    copy(typed = appendWhole(typed, digit, spec.maxIntDigits))
                } else if (typed.length - comma - 1 < spec.decimals) {
                    copy(typed = typed + digit)
                } else {
                    this
                }
            }
            PadSpec.Date -> if (typed.length < DATE_DIGITS) copy(typed = typed + digit) else this
        }
    }

    private fun pressComma(): PadEntry {
        if (spec !is PadSpec.Decimal || spec.decimals == 0 || typed.contains(',')) return this
        return copy(typed = (if (typed.isEmpty()) "0" else typed) + ",")
    }

    /** Leading zeros are ignored, so "0007" can never appear. */
    private fun appendWhole(current: String, digit: Int, max: Int): String = when {
        current == "0" -> if (digit == 0) current else digit.toString()
        current.length >= max -> current
        else -> current + digit
    }

    /** Whole number for [PadSpec.Integer]; null while empty. */
    fun intValue(): Int? = if (spec is PadSpec.Integer) typed.toIntOrNull() else null

    /** Money in minor units (centavos); null while empty or zero. */
    fun minorValue(): Long? {
        if (spec !is PadSpec.Decimal) return null
        val whole = typed.substringBefore(',').toLongOrNull() ?: return null
        val decimals = typed.substringAfter(',', "").padEnd(spec.decimals, '0')
        val minor = whole * pow10(spec.decimals) + (decimals.toLongOrNull() ?: 0L)
        return minor.takeIf { it > 0 }
    }

    /** Null while the eight digits are not a real day. */
    fun dateValue(): CalendarDate? {
        if (spec != PadSpec.Date || typed.length != DATE_DIGITS) return null
        return CalendarDate.of(typed.substring(4, 8).toInt(), typed.substring(2, 4).toInt(), typed.substring(0, 2).toInt())
    }

    /** True when something was typed but it is not (yet) a real day. */
    val isIncompleteDate: Boolean get() = spec == PadSpec.Date && typed.isNotEmpty() && dateValue() == null

    /**
     * The text to show. Numbers use the Argentine thousands dot and decimal comma; dates show
     * underscores for the digits still missing. Empty entries give "".
     */
    fun display(): String = when (spec) {
        is PadSpec.Integer, is PadSpec.Decimal -> displayNumber()
        PadSpec.Date -> if (typed.isEmpty()) "" else displayDate()
    }

    /** Date placeholder shown when a date field is being edited but still empty. */
    fun displayOrBlank(): String = if (spec == PadSpec.Date && typed.isEmpty()) "__/__/____" else display()

    private fun displayNumber(): String {
        if (typed.isEmpty()) return ""
        val whole = typed.substringBefore(',')
        val grouped = whole.reversed().chunked(3).joinToString(".").reversed()
        return if (typed.contains(',')) grouped + "," + typed.substringAfter(',') else grouped
    }

    private fun displayDate(): String {
        val padded = typed.padEnd(DATE_DIGITS, '_')
        return padded.substring(0, 2) + "/" + padded.substring(2, 4) + "/" + padded.substring(4, 8)
    }

    companion object {
        const val DATE_DIGITS = 8

        fun ofInt(spec: PadSpec.Integer, value: Int?) = PadEntry(spec, value?.toString().orEmpty())

        fun ofMinor(spec: PadSpec.Decimal, minor: Long?): PadEntry {
            if (minor == null) return PadEntry(spec)
            val unit = pow10(spec.decimals)
            val whole = minor / unit
            val fraction = (minor % unit).toString().padStart(spec.decimals, '0')
            return PadEntry(spec, if (fraction.all { it == '0' }) whole.toString() else "$whole,$fraction")
        }

        fun ofDate(date: CalendarDate?): PadEntry = PadEntry(
            PadSpec.Date,
            date?.let { String.format(Locale.ROOT, "%02d%02d%04d", it.day, it.month, it.year) }.orEmpty(),
        )

        private fun pow10(n: Int): Long {
            var result = 1L
            repeat(n) { result *= 10 }
            return result
        }
    }
}
