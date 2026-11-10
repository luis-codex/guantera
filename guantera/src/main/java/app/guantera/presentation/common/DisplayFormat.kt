package app.guantera.presentation.common

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Argentine number and time formats. Used only from the main thread. */
class DisplayFormat(private val timeZone: TimeZone = TimeZone.getDefault()) {
    private val locale = Locale("es", "AR")
    private val symbols = DecimalFormatSymbols(locale)
    private val oneDecimal = DecimalFormat("#,##0.0", symbols)
    private val whole = DecimalFormat("#,##0", symbols)

    fun oneDecimal(value: Double): String = oneDecimal.format(value)

    fun whole(value: Int): String = whole.format(value.toLong())

    /** Always with a decimal point, the form a map understands. */
    fun coordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)

    /** "Lun 9 nov". Spelled out here because Android and the JVM abbreviate differently. */
    fun dayMonth(epochMillis: Long): String {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
        val day = DAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1]
        val month = MONTHS[calendar.get(Calendar.MONTH)]
        return "$day ${calendar.get(Calendar.DAY_OF_MONTH)} $month"
    }

    fun clock(epochMillis: Long): String =
        SimpleDateFormat("HH:mm", locale).apply { timeZone = this@DisplayFormat.timeZone }
            .format(Date(epochMillis))

    private companion object {
        val DAYS = listOf("Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb")
        val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    }
}
