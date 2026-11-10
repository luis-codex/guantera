package app.guantera.presentation.maintenance

import app.guantera.presentation.input.CalendarDate
import app.guantera.presentation.port.MaintenanceAlert
import app.guantera.presentation.port.MaintenanceLevel
import app.guantera.presentation.port.Remaining

/** Where an interval came from, shown on every row (catalogo-mantenimiento.md). */
enum class DataOrigin { Verified, OwnCriterion, Own }

/** R15: at least one of [intervalKm], [intervalMonths] or [fixedDueDate], and intervals above zero. */
data class MaintenanceItem(
    val id: Long,
    val name: String,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    val fixedDueDate: CalendarDate?,
    val lastServiceKm: Int?,
    val lastServiceDate: CalendarDate?,
    val origin: DataOrigin,
) {
    val hasRule: Boolean
        get() = (intervalKm ?: 0) > 0 || (intervalMonths ?: 0) > 0 || fixedDueDate != null

    /** A date-only item must be told its next due date when a service is recorded (R21). */
    val needsNewDueDate: Boolean
        get() = fixedDueDate != null && intervalKm == null && intervalMonths == null
}

/** What P7 hands over; [costMinor] above zero creates an expense in the same step (R21). */
data class ServiceRegistration(
    val itemId: Long,
    val date: CalendarDate,
    val km: Int?,
    val costMinor: Long?,
    val note: String?,
    val newDueDate: CalendarDate?,
)

/** Which axis decides the status: distance or time. */
enum class Axis { Km, Days }

/**
 * The verdict for one item. [amount] is how far ahead (or behind, when [level] is Overdue) the
 * deciding axis is, always positive. [urgency] orders rows: smaller is more urgent.
 */
data class ItemStatus(
    val level: MaintenanceLevel,
    val axis: Axis?,
    val amount: Int,
    val urgency: Double,
) {
    fun toAlert(name: String): MaintenanceAlert = MaintenanceAlert(
        itemName = name,
        level = level,
        remaining = when (axis) {
            Axis.Km -> Remaining.Km(amount)
            Axis.Days -> Remaining.Days(amount)
            null -> null
        },
    )
}

/** Rules R16 to R20. */
object MaintenanceRules {
    private const val SOON_KM = 1_000
    private const val SOON_DAYS = 30
    private const val DAYS_PER_MONTH = 30
    private const val DEFAULT_FIXED_SPAN_DAYS = 365

    fun evaluate(item: MaintenanceItem, odometerKm: Int?, today: CalendarDate): ItemStatus {
        val axes = listOfNotNull(kmAxis(item, odometerKm), daysAxis(item, today))
        // R18: each axis with data is evaluated and the worst one decides.
        return axes.minWithOrNull(compareBy<ItemStatus> { it.level.ordinal }.thenBy { it.urgency })
            ?: ItemStatus(MaintenanceLevel.NoData, axis = null, amount = 0, urgency = Double.MAX_VALUE)
    }

    /** The most urgent item that needs attention, for Home and the notification (CU-13). Null if all is well. */
    fun urgent(items: List<MaintenanceItem>, odometerKm: Int?, today: CalendarDate): MaintenanceAlert? =
        items.map { it to evaluate(it, odometerKm, today) }
            .filter { it.second.level == MaintenanceLevel.Overdue || it.second.level == MaintenanceLevel.Soon }
            .minWithOrNull(compareBy({ it.second.level.ordinal }, { it.second.urgency }))
            ?.let { (item, status) -> status.toAlert(item.name) }

    /** R16, R19. */
    private fun kmAxis(item: MaintenanceItem, odometerKm: Int?): ItemStatus? {
        val interval = item.intervalKm?.takeIf { it > 0 } ?: return null
        val last = item.lastServiceKm ?: return null
        val odometer = odometerKm ?: return null
        val remaining = last + interval - odometer
        val level = when {
            remaining < 0 -> MaintenanceLevel.Overdue
            remaining <= minOf(SOON_KM, interval / 10) -> MaintenanceLevel.Soon
            else -> MaintenanceLevel.UpToDate
        }
        return ItemStatus(level, Axis.Km, Math.abs(remaining), remaining.toDouble() / interval)
    }

    /** R17, R19. A fixed date wins over the months interval. */
    private fun daysAxis(item: MaintenanceItem, today: CalendarDate): ItemStatus? {
        val fixed = item.fixedDueDate
        val months = item.intervalMonths?.takeIf { it > 0 }
        val last = item.lastServiceDate
        val due = fixed ?: if (months != null && last != null) last.plusMonths(months) else return null
        val span = when {
            fixed != null -> DEFAULT_FIXED_SPAN_DAYS
            else -> months!! * DAYS_PER_MONTH
        }
        val remaining = today.daysUntil(due)
        val soonLimit = if (fixed != null) SOON_DAYS else minOf(SOON_DAYS, span / 10)
        val level = when {
            remaining < 0 -> MaintenanceLevel.Overdue
            remaining <= soonLimit -> MaintenanceLevel.Soon
            else -> MaintenanceLevel.UpToDate
        }
        return ItemStatus(level, Axis.Days, Math.abs(remaining), remaining.toDouble() / span)
    }
}

/** The starting list from catalogo-mantenimiento.md. Editable values, not the word of any manual. */
object MaintenanceCatalog {
    data class Entry(val name: String, val km: Int?, val months: Int?, val origin: DataOrigin)

    val entries: List<Entry> = listOf(
        Entry("Aceite y filtro", 10_000, 12, DataOrigin.Verified),
        Entry("Rotación de cubiertas", 10_000, 6, DataOrigin.Verified),
        Entry("Alineación y balanceo", 20_000, null, DataOrigin.OwnCriterion),
        Entry("Revisión de desgaste de cubiertas y presión", null, 1, DataOrigin.OwnCriterion),
        Entry("Reemplazo de cubiertas por antigüedad", null, 120, DataOrigin.Verified),
        Entry("Filtro de aire de motor", 16_000, null, DataOrigin.Verified),
        Entry("Filtro de habitáculo", null, 12, DataOrigin.Verified),
        Entry("Líquido de frenos", null, 24, DataOrigin.Verified),
        Entry("Revisión de frenos (pastillas y discos)", 20_000, 12, DataOrigin.OwnCriterion),
        Entry("Refrigerante", null, 24, DataOrigin.Verified),
        Entry("Aceite de transmisión", 100_000, null, DataOrigin.Verified),
        Entry("Bujías", 50_000, null, DataOrigin.Verified),
        Entry("Correa de distribución", 100_000, null, DataOrigin.Verified),
        Entry("Batería (prueba)", null, 36, DataOrigin.Verified),
        Entry("Escobillas", null, 12, DataOrigin.OwnCriterion),
    )

    /** Insurance and the technical inspection have no default: their date comes from the paperwork. */
    val dateOnlyNames: List<String> = listOf("Seguro", "Revisión técnica o documentación")

    fun entry(name: String): Entry? = entries.firstOrNull { it.name == name }
}
