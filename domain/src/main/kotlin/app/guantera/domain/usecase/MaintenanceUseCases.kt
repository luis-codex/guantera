package app.guantera.domain.usecase

import app.guantera.domain.expense.Expense
import app.guantera.domain.expense.ExpenseCategory
import app.guantera.domain.maintenance.MaintenanceAlert
import app.guantera.domain.maintenance.MaintenanceCatalog
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.maintenance.MaintenanceOrdering
import app.guantera.domain.maintenance.MaintenanceRow
import app.guantera.domain.maintenance.MaintenanceRules
import app.guantera.domain.maintenance.ServiceRecord
import app.guantera.domain.motion.DrivingState
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.odometer.OdometerRules
import app.guantera.domain.port.AlertPublisher
import app.guantera.domain.port.ExpenseRepository
import app.guantera.domain.port.MaintenanceRepository
import app.guantera.domain.port.OdometerRepository
import app.guantera.domain.port.SettingsRepository
import app.guantera.domain.port.TransactionRunner
import app.guantera.domain.port.TripRepository
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.time.CalendarDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val METERS_PER_KM = 1000.0

/** R12 to R14: the estimated odometer, in one place. */
class OdometerReader(private val odometer: OdometerRepository, private val trips: TripRepository) {
    suspend fun gpsAccumulatedKm(): Double = trips.totalMeters(UtcWindow(null, null)) / METERS_PER_KM

    suspend fun currentKm(): Double? = OdometerRules.current(odometer.latest(), gpsAccumulatedKm())
}

/** CU-08. */
class CalibrateOdometer(
    private val odometer: OdometerRepository,
    private val reader: OdometerReader,
    private val time: ResolveTrustedTime,
    private val form: FormSubmission,
) {
    suspend operator fun invoke(realKm: Double): FormResult<Unit> {
        if (realKm < 0) return FormResult.Invalid(FormError.ODOMETER_NEGATIVE)
        return form.submit {
            odometer.add(OdometerCalibration(time.bestEffortUtcMs(), realKm, reader.gpsAccumulatedKm()))
        }
    }
}

/** Every item with its verdict for right now, shared by CU-05, CU-09 and CU-13. */
class MaintenanceEvaluator(
    private val maintenance: MaintenanceRepository,
    private val reader: OdometerReader,
    private val time: ResolveTrustedTime,
) {
    suspend fun rows(): List<MaintenanceRow> {
        val odometerKm = reader.currentKm()
        val today = time.today()
        return maintenance.items().map { MaintenanceRow(it, MaintenanceRules.evaluate(it, odometerKm, today)) }
    }
}

/** CU-09. */
class ListMaintenanceStatus(
    private val evaluator: MaintenanceEvaluator,
    private val maintenance: MaintenanceRepository,
    private val odometer: OdometerRepository,
    private val trips: TripRepository,
) {
    /** Emits whenever an item, the odometer or the kilometers change. */
    val changes: Flow<Unit> get() = combine(maintenance.changes, odometer.changes, trips.changes) { _, _, _ -> }

    suspend fun load(): List<MaintenanceRow> = MaintenanceOrdering.rows(evaluator.rows())

    fun observe(): Flow<List<MaintenanceRow>> = changes.map { load() }
}

/** CU-10. */
class SaveMaintenanceItem(private val maintenance: MaintenanceRepository, private val form: FormSubmission) {
    suspend operator fun invoke(item: MaintenanceItem): FormResult<Long> {
        if (item.name.isBlank()) return FormResult.Invalid(FormError.NAME_BLANK)
        if (!MaintenanceRules.hasValidRule(item)) return FormResult.Invalid(FormError.INVALID_RULE)
        if (item.id != 0L && maintenance.find(item.id) == null) return FormResult.Invalid(FormError.ITEM_NOT_FOUND)
        return form.submit { maintenance.save(item) }
    }
}

/** CU-11. */
class DeleteMaintenanceItem(private val maintenance: MaintenanceRepository, private val form: FormSubmission) {
    suspend operator fun invoke(id: Long): FormResult<Unit> = form.submit { maintenance.delete(id) }
}

data class ServiceRegistration(
    val itemId: Long,
    val date: CalendarDay,
    val km: Double?,
    val costMinor: Long?,
    val note: String?,
    val newDueDate: CalendarDay?,
)

/** CU-12, R21. */
class RegisterService(
    private val maintenance: MaintenanceRepository,
    private val expenses: ExpenseRepository,
    private val transactions: TransactionRunner,
    private val form: FormSubmission,
) {
    suspend operator fun invoke(service: ServiceRegistration): FormResult<Long> {
        val item = maintenance.find(service.itemId) ?: return FormResult.Invalid(FormError.ITEM_NOT_FOUND)
        if (service.costMinor != null && service.costMinor <= 0) return FormResult.Invalid(FormError.AMOUNT_NOT_POSITIVE)
        if (service.newDueDate == null && isDateOnly(item)) return FormResult.Invalid(FormError.NEW_DUE_DATE_REQUIRED)
        return form.submit { transactions.run { record(item, service) } }
    }

    /** Without a new date this item would be left with no axis at all, breaking R15. */
    private fun isDateOnly(item: MaintenanceItem) =
        item.fixedDueDate != null && item.intervalKm == null && item.intervalMonths == null

    private suspend fun record(item: MaintenanceItem, service: ServiceRegistration): Long {
        val recordId = maintenance.addService(ServiceRecord(0, item.id, service.date, service.km, service.note))
        maintenance.save(
            item.copy(lastServiceKm = service.km, lastServiceDate = service.date, fixedDueDate = service.newDueDate),
        )
        service.costMinor?.let { cost ->
            val note = listOfNotNull(item.name, service.note).joinToString(" · ")
            expenses.save(Expense(0, ExpenseCategory.MANTENIMIENTO, cost, service.date, note, recordId))
        }
        return recordId
    }
}

enum class PublishResult { PUBLISHED, DEFERRED, UNCHANGED }

/** CU-13, R30, R31. */
class GetPendingAlerts(
    private val evaluator: MaintenanceEvaluator,
    private val publisher: AlertPublisher,
    private val driving: ObserveDrivingState,
) {
    private val mutex = Mutex()
    private var published: MaintenanceAlert? = null

    suspend fun load(): List<MaintenanceAlert> = MaintenanceOrdering.alerts(evaluator.rows())

    /** Shows the most urgent alert. An alert that did not change is not shown again. */
    suspend fun publishUrgent(): PublishResult = mutex.withLock {
        if (driving.current() == DrivingState.EN_MOVIMIENTO) return@withLock PublishResult.DEFERRED
        val urgent = load().firstOrNull()
        if (urgent?.itemId == published?.itemId && urgent?.status == published?.status) {
            return@withLock PublishResult.UNCHANGED
        }
        publisher.publish(urgent)
        published = urgent
        PublishResult.PUBLISHED
    }
}

/** CU-14. */
class SeedOrRestoreCatalog(
    private val maintenance: MaintenanceRepository,
    private val settings: SettingsRepository,
    private val time: ResolveTrustedTime,
    private val transactions: TransactionRunner,
    private val form: FormSubmission,
) {
    /** First use: loads the catalog only if there are no items at all. */
    suspend fun seed(): FormResult<Int> {
        if (maintenance.items().isNotEmpty()) return FormResult.Saved(0)
        return form.submit { transactions.run { addMissing() } }
    }

    /** Brings back the missing catalog items without touching the rest. Returns how many came back. */
    suspend fun restore(): FormResult<Int> = form.submit {
        val now = time.bestEffortUtcMs()
        transactions.run {
            val added = addMissing()
            settings.update { it.copy(catalogRestoredUtcMs = now) }
            added
        }
    }

    private suspend fun addMissing(): Int {
        val present = maintenance.items().map { it.name }.toSet()
        val missing = MaintenanceCatalog.entries.filter { it.name !in present }
        missing.forEach { maintenance.save(itemOf(it)) }
        return missing.size
    }

    private fun itemOf(entry: MaintenanceCatalog.Entry) = MaintenanceItem(
        id = 0, name = entry.name, intervalKm = entry.km, intervalMonths = entry.months,
        fixedDueDate = null, lastServiceKm = null, lastServiceDate = null,
        origin = entry.origin,
    )
}
