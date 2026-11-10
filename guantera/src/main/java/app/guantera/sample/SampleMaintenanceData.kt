package app.guantera.sample

import app.guantera.presentation.expenses.Expense
import app.guantera.presentation.expenses.ExpenseCategory
import app.guantera.presentation.expenses.ExpenseData
import app.guantera.presentation.input.CalendarDate
import app.guantera.presentation.maintenance.DataOrigin
import app.guantera.presentation.maintenance.MaintenanceCatalog
import app.guantera.presentation.maintenance.MaintenanceData
import app.guantera.presentation.maintenance.MaintenanceItem
import app.guantera.presentation.maintenance.ServiceRegistration
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The catalog with invented last services for a car at [ODOMETER_KM], which is what SampleData reports.
 * Oil is overdue by 350 km, the same warning Home shows. Registering a service with a cost creates the expense.
 */
class SampleMaintenanceData(
    clock: Clock,
    private val expenses: ExpenseData,
) : MaintenanceData {
    private val today = CalendarDate.fromMillis(clock.nowMillis())
    private var nextId = 1L
    private var nextRecordId = 1L
    private val _items = MutableStateFlow(seed())

    override val items: StateFlow<List<MaintenanceItem>> = _items

    @Synchronized
    override fun save(item: MaintenanceItem) {
        _items.update { list ->
            if (item.id == 0L) list + item.copy(id = nextId++) else list.map { if (it.id == item.id) item else it }
        }
    }

    override fun delete(id: Long) = _items.update { list -> list.filterNot { it.id == id } }

    @Synchronized
    override fun restoreCatalog(): Int {
        val present = _items.value.map { it.name }.toSet()
        val missing = MaintenanceCatalog.entries.filter { it.name !in present }
        _items.update { list ->
            list + missing.map { MaintenanceItem(nextId++, it.name, it.km, it.months, null, null, null, it.origin) }
        }
        return missing.size
    }

    @Synchronized
    override fun registerService(registration: ServiceRegistration): Boolean {
        val item = _items.value.firstOrNull { it.id == registration.itemId } ?: return false
        val recordId = nextRecordId++
        _items.update { list ->
            list.map {
                if (it.id != item.id) it else it.copy(
                    lastServiceKm = registration.km ?: it.lastServiceKm,
                    lastServiceDate = registration.date,
                    fixedDueDate = registration.newDueDate,
                )
            }
        }
        registration.costMinor?.let { cost ->
            expenses.save(
                Expense(
                    id = 0L,
                    category = ExpenseCategory.Maintenance,
                    amountMinor = cost,
                    date = registration.date,
                    note = listOfNotNull(item.name, registration.note).joinToString(" · "),
                    serviceRecordId = recordId,
                ),
            )
        }
        return true
    }

    private fun seed(): List<MaintenanceItem> {
        fun item(
            name: String,
            lastKm: Int? = null,
            monthsAgo: Int? = null,
            extraDays: Int = 0,
            fixed: CalendarDate? = null,
            km: Int? = MaintenanceCatalog.entry(name)?.km,
            months: Int? = MaintenanceCatalog.entry(name)?.months,
            origin: DataOrigin = MaintenanceCatalog.entry(name)?.origin ?: DataOrigin.Own,
        ) = MaintenanceItem(
            id = nextId++,
            name = name,
            intervalKm = km,
            intervalMonths = months,
            fixedDueDate = fixed,
            lastServiceKm = lastKm,
            lastServiceDate = monthsAgo?.let { today.plusMonths(-it).plusDays(-extraDays) },
            origin = origin,
        )
        return listOf(
            item("Aceite y filtro", lastKm = 37_900, monthsAgo = 8),
            item("Rotación de cubiertas", lastKm = 42_100, monthsAgo = 3),
            item("Alineación y balanceo", lastKm = 35_000, monthsAgo = 9),
            item("Revisión de desgaste de cubiertas y presión", monthsAgo = 1, extraDays = 6),
            item("Reemplazo de cubiertas por antigüedad", monthsAgo = 40),
            item("Filtro de aire de motor", lastKm = 33_000, monthsAgo = 15),
            item("Filtro de habitáculo", monthsAgo = 11, extraDays = 10),
            item("Líquido de frenos", monthsAgo = 14),
            item("Revisión de frenos (pastillas y discos)", lastKm = 30_100, monthsAgo = 5),
            item("Refrigerante"),
            item("Aceite de transmisión"),
            item("Bujías", lastKm = 20_000, monthsAgo = 20),
            item("Correa de distribución"),
            item("Batería (prueba)", monthsAgo = 30),
            item("Escobillas", monthsAgo = 12, extraDays = 25),
            item("Seguro", fixed = today.plusDays(18), km = null, months = null, origin = DataOrigin.Own),
            item("Revisión técnica o documentación", fixed = today.plusMonths(7), km = null, months = null, origin = DataOrigin.Own),
        )
    }

    companion object {
        /** Must match the odometer in SampleData. */
        const val ODOMETER_KM = 48_250
    }
}
