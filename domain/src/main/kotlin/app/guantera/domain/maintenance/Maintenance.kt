package app.guantera.domain.maintenance

import app.guantera.domain.time.CalendarDay

/** Where an interval came from, shown on every row of P5. */
enum class DataOrigin { VERIFIED, OWN_CRITERION, OWN }

data class MaintenanceItem(
    val id: Long,
    val name: String,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    val fixedDueDate: CalendarDay?,
    val lastServiceKm: Double?,
    val lastServiceDate: CalendarDay?,
    val origin: DataOrigin,
)

data class ServiceRecord(
    val id: Long,
    val itemId: Long,
    val date: CalendarDay,
    val km: Double?,
    val note: String?,
)

enum class MaintenanceStatus { VENCIDO, PROXIMO, AL_DIA, SIN_DATOS }

sealed interface Remaining {
    /** Negative when already overdue. */
    data class Km(val value: Double) : Remaining
    data class Days(val value: Int) : Remaining
}

/**
 * The verdict for one item. [margin] is what is left as a fraction of the interval, so that
 * kilometers and days can be compared (R30). Smaller is more urgent; null for SIN_DATOS.
 */
data class ItemEvaluation(
    val status: MaintenanceStatus,
    val remaining: Remaining?,
    val margin: Double?,
    val dueKm: Double?,
    val dueDate: CalendarDay?,
)

/** R16 to R20. */
object MaintenanceRules {
    private const val SOON_KM = 1000.0
    private const val SOON_DAYS = 30.0
    private const val SOON_FRACTION_DIVISOR = 10.0
    private const val FIXED_DATE_SPAN_DAYS = 365.0

    private val NO_DATA = ItemEvaluation(MaintenanceStatus.SIN_DATOS, null, null, null, null)

    /** A null [today] means there is no trustworthy date, so the date axis is left out (R29). */
    fun evaluate(item: MaintenanceItem, odometerKm: Double?, today: CalendarDay?): ItemEvaluation {
        val axes = listOfNotNull(kmAxis(item, odometerKm), dateAxis(item, today))
        val worst = axes.minWithOrNull(compareBy({ it.status.ordinal }, { it.margin })) ?: return NO_DATA
        return worst.copy(dueKm = axes.firstNotNullOfOrNull { it.dueKm }, dueDate = axes.firstNotNullOfOrNull { it.dueDate })
    }

    /** Validates R15. */
    fun hasValidRule(item: MaintenanceItem): Boolean {
        val intervalsArePositive = (item.intervalKm ?: 1) > 0 && (item.intervalMonths ?: 1) > 0
        val hasAxis = item.intervalKm != null || item.intervalMonths != null || item.fixedDueDate != null
        return intervalsArePositive && hasAxis
    }

    private fun kmAxis(item: MaintenanceItem, odometerKm: Double?): ItemEvaluation? {
        val interval = item.intervalKm?.toDouble() ?: return null
        val last = item.lastServiceKm ?: return null
        val odometer = odometerKm ?: return null
        val due = last + interval
        val left = due - odometer
        val status = statusFor(left, minOf(SOON_KM, interval / SOON_FRACTION_DIVISOR))
        return ItemEvaluation(status, Remaining.Km(left), left / interval, due, null)
    }

    private fun dateAxis(item: MaintenanceItem, today: CalendarDay?): ItemEvaluation? {
        val now = today ?: return null
        item.fixedDueDate?.let { due ->
            val left = now.daysUntil(due)
            return ItemEvaluation(statusFor(left.toDouble(), SOON_DAYS), Remaining.Days(left), left / FIXED_DATE_SPAN_DAYS, null, due)
        }
        val months = item.intervalMonths ?: return null
        val last = item.lastServiceDate ?: return null
        val due = last.plusMonths(months)
        val span = last.daysUntil(due).toDouble()
        val left = now.daysUntil(due)
        val status = statusFor(left.toDouble(), minOf(SOON_DAYS, span / SOON_FRACTION_DIVISOR))
        return ItemEvaluation(status, Remaining.Days(left), left / span, null, due)
    }

    private fun statusFor(left: Double, soonLimit: Double): MaintenanceStatus = when {
        left < 0 -> MaintenanceStatus.VENCIDO
        left <= soonLimit -> MaintenanceStatus.PROXIMO
        else -> MaintenanceStatus.AL_DIA
    }
}

data class MaintenanceAlert(val itemId: Long, val itemName: String, val status: MaintenanceStatus, val remaining: Remaining?)

data class MaintenanceRow(val item: MaintenanceItem, val evaluation: ItemEvaluation)

/** R20, R30 and the row order of P5. */
object MaintenanceOrdering {
    private val byUrgency = compareBy<MaintenanceRow>(
        { it.evaluation.status.ordinal },
        { it.evaluation.margin ?: Double.MAX_VALUE },
    )

    fun rows(rows: List<MaintenanceRow>): List<MaintenanceRow> = rows.sortedWith(byUrgency)

    fun alerts(rows: List<MaintenanceRow>): List<MaintenanceAlert> = rows(rows)
        .filter { it.evaluation.status == MaintenanceStatus.VENCIDO || it.evaluation.status == MaintenanceStatus.PROXIMO }
        .map { MaintenanceAlert(it.item.id, it.item.name, it.evaluation.status, it.evaluation.remaining) }
}

/** The starting list of catalogo-mantenimiento.md. Editable values, not the word of any manual. */
object MaintenanceCatalog {
    data class Entry(val name: String, val km: Int?, val months: Int?, val origin: DataOrigin)

    val entries: List<Entry> = listOf(
        Entry("Aceite y filtro", 10_000, 12, DataOrigin.VERIFIED),
        Entry("Rotación de cubiertas", 10_000, 6, DataOrigin.VERIFIED),
        Entry("Alineación y balanceo", 20_000, null, DataOrigin.OWN_CRITERION),
        Entry("Revisión de desgaste de cubiertas y presión", null, 1, DataOrigin.OWN_CRITERION),
        Entry("Reemplazo de cubiertas por antigüedad", null, 120, DataOrigin.VERIFIED),
        Entry("Filtro de aire de motor", 16_000, null, DataOrigin.VERIFIED),
        Entry("Filtro de habitáculo", null, 12, DataOrigin.VERIFIED),
        Entry("Líquido de frenos", null, 24, DataOrigin.VERIFIED),
        Entry("Revisión de frenos (pastillas y discos)", 20_000, 12, DataOrigin.OWN_CRITERION),
        Entry("Refrigerante", null, 24, DataOrigin.VERIFIED),
        Entry("Aceite de transmisión", 100_000, null, DataOrigin.VERIFIED),
        Entry("Bujías", 50_000, null, DataOrigin.VERIFIED),
        Entry("Correa de distribución", 100_000, null, DataOrigin.VERIFIED),
        Entry("Batería (prueba)", null, 36, DataOrigin.VERIFIED),
        Entry("Escobillas", null, 12, DataOrigin.OWN_CRITERION),
    )

    /** Insurance and technical inspection have no default: R15 needs the date from the paperwork. */
    val dateOnlyNames: List<String> = listOf("Seguro", "Revisión técnica o documentación")
}
