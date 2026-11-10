package app.guantera.presentation.real

import app.guantera.presentation.expenses.ExpenseData
import app.guantera.presentation.expenses.Expense as UiExpense
import app.guantera.presentation.expenses.ExpenseCategory as UiExpenseCategory
import app.guantera.presentation.input.CalendarDate
import app.guantera.presentation.maintenance.DataOrigin as UiDataOrigin
import app.guantera.presentation.expenses.PeriodCost
import app.guantera.presentation.maintenance.CatalogResult
import app.guantera.presentation.maintenance.MaintenanceData
import app.guantera.presentation.maintenance.MaintenanceItem as UiMaintenanceItem
import app.guantera.presentation.maintenance.ServiceRegistration as UiServiceRegistration
import app.guantera.presentation.port.AppNotice
import app.guantera.presentation.port.AppSettings
import app.guantera.presentation.port.BackupActions
import app.guantera.presentation.port.FirstUseStore
import app.guantera.presentation.port.FormOutcome
import app.guantera.presentation.port.DrivingData
import app.guantera.presentation.port.GeoPoint
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.port.HomeData
import app.guantera.presentation.port.MaintenanceAlert
import app.guantera.presentation.port.MaintenanceLevel
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.ParkedSpot
import app.guantera.presentation.port.ParkingDetail
import app.guantera.presentation.port.Remaining
import app.guantera.presentation.port.SaveSpotResult
import app.guantera.presentation.port.SettingsStore
import app.guantera.presentation.port.TodaySummary
import app.guantera.presentation.port.TripClosure
import app.guantera.presentation.port.TripRecord
import app.guantera.presentation.port.VehicleProfile
import app.guantera.presentation.port.VehicleStatus
import app.guantera.tracking.TrackingGraph
import app.guantera.domain.expense.Expense as DomainExpense
import app.guantera.domain.maintenance.DataOrigin
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.motion.DrivingState
import app.guantera.domain.parking.ParkingOrigin
import app.guantera.domain.time.CalendarDay
import app.guantera.domain.time.DayRange
import app.guantera.domain.trip.Trip
import app.guantera.domain.usecase.CalibrateOdometer
import app.guantera.domain.usecase.DeleteExpense
import app.guantera.domain.usecase.DeleteMaintenanceItem
import app.guantera.domain.usecase.ExportBackup
import app.guantera.domain.usecase.FormBlock
import app.guantera.domain.usecase.FormError
import app.guantera.domain.usecase.FormResult
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.expense.CostPerKm as DomainCostPerKm
import app.guantera.domain.usecase.GetDashboard
import app.guantera.domain.usecase.GetLastParkingSpot
import app.guantera.domain.usecase.GetPendingAlerts
import app.guantera.domain.usecase.GetTripDetail
import app.guantera.domain.usecase.GetCostPerKm
import app.guantera.domain.usecase.ImportBackup
import app.guantera.domain.usecase.ListExpenses
import app.guantera.domain.usecase.ListMaintenanceStatus
import app.guantera.domain.usecase.ListTrips
import app.guantera.domain.usecase.MarkParkedNow
import app.guantera.domain.usecase.ReadNotices
import app.guantera.domain.usecase.RegisterService
import app.guantera.domain.usecase.SaveExpense
import app.guantera.domain.usecase.SaveMaintenanceItem
import app.guantera.domain.usecase.SaveParkingNote
import app.guantera.domain.usecase.SaveSettings
import app.guantera.domain.usecase.SeedOrRestoreCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Presentation adapters over the real domain graph.  They deliberately expose only UI-shaped
 * values; every repository/use-case call remains in the background [scope].
 */
class RealData(
    private val graph: TrackingGraph,
    private val scope: CoroutineScope,
    override val backups: BackupActions,
) : GuanteraData, SettingsStore, FirstUseStore {

    private val notices = ReadNotices(graph.observeDrivingState, graph.time, graph.odometerReader, graph.settingsRepo, graph.engine)
    private val dashboard = GetDashboard(
        ListTrips(graph.tripRepo, graph.time, graph.calendar),
        graph.getPendingAlerts,
        ListMaintenanceStatus(graph.maintenanceEvaluator, graph.maintenanceRepo, graph.odometerRepo, graph.tripRepo),
        graph.parkingRepo, notices, graph.time, graph.calendar,
    )
    private val listTrips = ListTrips(graph.tripRepo, graph.time, graph.calendar)
    private val listExpenses = ListExpenses(graph.expenseRepo)
    private val costOfKm = GetCostPerKm(graph.expenseRepo, graph.tripRepo, graph.calendar)
    private val listMaintenance = ListMaintenanceStatus(graph.maintenanceEvaluator, graph.maintenanceRepo, graph.odometerRepo, graph.tripRepo)
    private val domainGate = app.guantera.domain.usecase.FormGate(graph.observeDrivingState, graph.confirmation, graph.clock)
    private val forms = app.guantera.domain.usecase.FormSubmission(domainGate, graph.automaticBackup)
    private val markParked = MarkParkedNow(graph.engine, graph.parkingRepo, graph.clock, forms)
    private val parkingNote = SaveParkingNote(graph.parkingRepo, forms)
    private val saveItem = SaveMaintenanceItem(graph.maintenanceRepo, forms)
    private val deleteItem = DeleteMaintenanceItem(graph.maintenanceRepo, forms)
    private val registerService = RegisterService(graph.maintenanceRepo, graph.expenseRepo, graph.transactions, forms)
    private val seedCatalog = SeedOrRestoreCatalog(graph.maintenanceRepo, graph.settingsRepo, graph.time, graph.transactions, forms)
    private val saveExpense = SaveExpense(graph.expenseRepo, forms)
    private val deleteExpense = DeleteExpense(graph.expenseRepo, forms)
    private val calibrate = CalibrateOdometer(graph.odometerRepo, graph.odometerReader, graph.time, forms)
    private val saveSettings = SaveSettings(graph.settingsRepo, forms)

    /** The date of the newest copy inside the radio: only the disk knows it, so screens ask for a refresh. */
    private val lastCopy = MutableStateFlow<Long?>(null)

    private fun <T> background(initial: T, source: kotlinx.coroutines.flow.Flow<T>): StateFlow<T> =
        source.stateIn(scope, SharingStarted.Eagerly, initial)

    override val status: StateFlow<VehicleStatus> = background(
        VehicleStatus(GpsStatus.Searching, MotionStatus.Unknown),
        graph.observeDrivingState.observe().map { VehicleStatus(it.gps.toUi(), it.state.toUi()) },
    )

    override val driving: StateFlow<DrivingData?> = background(null, graph.observeDrivingState.observe().map { snapshot ->
        snapshot.progress?.let { progress ->
            DrivingData(
                distanceKm = progress.distanceMeters / 1000.0,
                elapsedSeconds = progress.elapsedSeconds,
                stoppingSeconds = progress.stoppingSeconds,
                urgentAlert = null,
            )
        }
    })

    override val trips: StateFlow<List<TripRecord>> = background(emptyList(), listTrips.changes.map {
        listTrips.inRange(DayRange()).map { it.toUi() }
    })

    override val parkingSpot: StateFlow<ParkingDetail?> = background(null, GetLastParkingSpot(graph.parkingRepo).observe().map { it?.toUi() })

    override val home: StateFlow<HomeData> = background(
        HomeData(TodaySummary(0.0, 0), null, null, emptyList()),
        dashboard.observe().map { value ->
            HomeData(
                today = TodaySummary(value.today.km, value.today.tripCount),
                urgentAlert = value.urgentAlert?.toUi(),
                parked = value.parkingSpot?.let { ParkedSpot(it.instantUtcMs, it.note) },
                notices = value.notices.map { it.toUi() },
            )
        },
    )

    override val profile: StateFlow<VehicleProfile> = background(
        VehicleProfile(VEHICLE_NAME, null, "$"),
        combine(graph.settingsRepo.changes, graph.odometerRepo.changes, graph.tripRepo.changes) { _, _, _ ->
            val settings = graph.settingsRepo.get()
            VehicleProfile(VEHICLE_NAME, graph.odometerReader.currentKm()?.toInt(), settings.currency)
        },
    )

    private val items: StateFlow<List<UiMaintenanceItem>> = background(emptyList(), listMaintenance.observe().map { rows ->
        rows.map { it.item.toUi() }
    })

    private val expenses: StateFlow<List<UiExpense>> = background(emptyList(), listExpenses.changes.map {
        listExpenses.inRange(DayRange()).map { it.toUi() }
    })

    override val settings: StateFlow<AppSettings> = background(
        AppSettings(null, null, "$", false, null, null, null),
        combine(graph.settingsRepo.changes, graph.odometerRepo.changes, graph.tripRepo.changes, lastCopy) { _, _, _, copy ->
            val value = graph.settingsRepo.get()
            val calibration = graph.odometerRepo.latest()
            AppSettings(
                odometerKm = graph.odometerReader.currentKm()?.toInt(),
                odometerCalibratedAtMillis = calibration?.dateUtcMs,
                currency = value.currency,
                openAtBoot = value.openAtBoot,
                catalogRestoredAtMillis = value.catalogRestoredUtcMs,
                lastBackupMillis = copy,
                lastExportMillis = value.lastExportUtcMs,
            )
        },
    )

    override fun confirmParked() {
        scope.launch {
            try {
                markParked.finishTrip()
            } catch (e: Exception) {
                reportUnlessCancelled("Ya estacioné", e)
                // The engine keeps the closure in memory and retries on every tick; nothing else to tell the driver.
            }
        }
    }

    override fun confirmStopped() { graph.confirmParked() }

    override suspend fun saveSpotHere(): SaveSpotResult = guard { markParked.saveHere() }.let { result ->
        when (result) {
            is FormResult.Saved -> SaveSpotResult.Saved
            is FormResult.Blocked -> if (result.reason == FormBlock.CAR_MOVING) SaveSpotResult.CarMoving else SaveSpotResult.NeedsConfirmation
            is FormResult.Invalid -> if (result.error == FormError.NO_VALID_FIX) SaveSpotResult.NoValidFix else SaveSpotResult.Failed
            is FormResult.StorageFailed -> SaveSpotResult.Failed
        }
    }

    override suspend fun saveSpotNote(note: String): FormOutcome = outcome { parkingNote(note) }

    private suspend fun restoreMaintenanceCatalog(): CatalogResult {
        var restored = 0
        val outcome = outcome { seedCatalog.restore().also { if (it is FormResult.Saved) restored = it.value } }
        return CatalogResult(outcome, if (outcome == FormOutcome.Saved) restored else 0)
    }

    override suspend fun correctOdometer(km: Int): FormOutcome = outcome { calibrate(km.toDouble()) }
    override suspend fun setCurrency(currency: String): FormOutcome = outcome { saveSettings(currency = currency) }
    override suspend fun setOpenAtBoot(enabled: Boolean): FormOutcome = outcome { saveSettings(openAtBoot = enabled) }
    override suspend fun restoreCatalog(): FormOutcome = restoreMaintenanceCatalog().outcome

    override val isComplete: StateFlow<Boolean?> = background(null, graph.settingsRepo.changes.map { graph.settingsRepo.get().firstUseComplete })
    override suspend fun saveOdometer(km: Int): FormOutcome = correctOdometer(km)
    override suspend fun saveCurrency(currency: String): FormOutcome = setCurrency(currency)
    override suspend fun loadCatalog(): FormOutcome = outcome { seedCatalog.seed() }
    override suspend fun markComplete(): FormOutcome = outcome { saveSettings(firstUseComplete = true) }

    override fun refreshBackupInfo() { scope.launch { lastCopy.value = backups.lastCopyUtcMs() } }

    override fun dismissRecordingGap() { scope.launch { saveSettings.dismissRecordingGap() } }

    private suspend fun <T> guard(block: suspend () -> FormResult<T>): FormResult<T> = try {
        block()
    } catch (e: Exception) {
        reportUnlessCancelled("guardar formulario", e)
        FormResult.StorageFailed(StorageFailure.IO)
    }

    private suspend fun <T> outcome(block: suspend () -> FormResult<T>): FormOutcome = when (val result = guard(block)) {
        is FormResult.Saved -> FormOutcome.Saved
        is FormResult.Blocked -> if (result.reason == FormBlock.CAR_MOVING) FormOutcome.CarMoving else FormOutcome.NeedsConfirmation
        is FormResult.Invalid -> FormOutcome.Rejected
        is FormResult.StorageFailed -> if (result.failure == StorageFailure.FULL) FormOutcome.StorageFull else FormOutcome.StorageFailed
    }

    val maintenanceData: MaintenanceData = object : MaintenanceData {
        override val items: StateFlow<List<UiMaintenanceItem>> get() = this@RealData.items
        override suspend fun save(item: UiMaintenanceItem): FormOutcome = outcome { saveItem(item.toDomain()) }
        override suspend fun delete(id: Long): FormOutcome = outcome { deleteItem(id) }
        override suspend fun restoreCatalog(): CatalogResult = restoreMaintenanceCatalog()
        override suspend fun registerService(registration: UiServiceRegistration): FormOutcome =
            outcome { registerService(registration.toDomain()) }
    }

    val expenseData: ExpenseData = object : ExpenseData {
        override val expenses: StateFlow<List<UiExpense>> get() = this@RealData.expenses
        override suspend fun save(expense: UiExpense): FormOutcome = outcome { saveExpense(expense.toDomain()) }
        override suspend fun delete(id: Long): FormOutcome = outcome { deleteExpense(id) }
        override suspend fun costPerKm(from: CalendarDate, to: CalendarDate): PeriodCost {
            val range = DayRange(from.toDomain(), to.toDomain().plusDays(1))
            return when (val cost = costOfKm(range)) {
                is DomainCostPerKm.Available -> PeriodCost(cost.totalMinor, cost.distanceKm, cost.minorPerKm)
                is DomainCostPerKm.NotEnoughKm -> PeriodCost(cost.totalMinor, cost.distanceKm, null)
            }
        }
    }

    private fun Trip.toUi() = TripRecord(id, startUtcMs, endUtcMs, distanceKm, startPoint.toUi(), endPoint.toUi(), when (closure) {
        app.guantera.domain.trip.TripClosure.AUTOMATIC -> TripClosure.Automatic
        app.guantera.domain.trip.TripClosure.MANUAL -> TripClosure.Manual
        app.guantera.domain.trip.TripClosure.RECOVERED -> TripClosure.Recovered
    })
    private fun app.guantera.domain.parking.ParkingSpot.toUi() = ParkingDetail(instantUtcMs, point.toUi(), accuracyMeters.toInt(), note, isLastSignalPoint)
    private fun app.guantera.domain.trip.GeoPoint.toUi() = GeoPoint(latitude, longitude)
    private fun app.guantera.domain.motion.GpsStatus.toUi() = when (this) {
        app.guantera.domain.motion.GpsStatus.GOOD -> GpsStatus.Good
        app.guantera.domain.motion.GpsStatus.SEARCHING -> GpsStatus.Searching
        app.guantera.domain.motion.GpsStatus.OFF -> GpsStatus.Off
        app.guantera.domain.motion.GpsStatus.NO_PERMISSION -> GpsStatus.NoPermission
    }
    private fun DrivingState.toUi() = when (this) {
        DrivingState.DETENIDO -> MotionStatus.Stopped
        DrivingState.EN_MOVIMIENTO -> MotionStatus.Moving
        DrivingState.DESCONOCIDO -> MotionStatus.Unknown
    }
    private fun app.guantera.domain.maintenance.MaintenanceAlert.toUi() = MaintenanceAlert(itemName, when (status) {
        app.guantera.domain.maintenance.MaintenanceStatus.VENCIDO -> MaintenanceLevel.Overdue
        app.guantera.domain.maintenance.MaintenanceStatus.PROXIMO -> MaintenanceLevel.Soon
        app.guantera.domain.maintenance.MaintenanceStatus.AL_DIA -> MaintenanceLevel.UpToDate
        app.guantera.domain.maintenance.MaintenanceStatus.SIN_DATOS -> MaintenanceLevel.NoData
    }, when (val left = remaining) {
        is app.guantera.domain.maintenance.Remaining.Km -> Remaining.Km(kotlin.math.abs(left.value).toInt())
        is app.guantera.domain.maintenance.Remaining.Days -> Remaining.Days(kotlin.math.abs(left.value))
        null -> null
    })
    private fun app.guantera.domain.settings.Notice.toUi() = when (this) {
        app.guantera.domain.settings.Notice.NO_LOCATION_PERMISSION -> AppNotice.NoLocationPermission
        app.guantera.domain.settings.Notice.GPS_OFF -> AppNotice.GpsOff
        app.guantera.domain.settings.Notice.UNTRUSTED_CLOCK -> AppNotice.UntrustedClock
        app.guantera.domain.settings.Notice.SEARCHING_GPS -> AppNotice.SearchingGps
        app.guantera.domain.settings.Notice.ODOMETER_MISSING -> AppNotice.OdometerMissing
        app.guantera.domain.settings.Notice.RECORDING_GAP -> AppNotice.RecordingGap
        app.guantera.domain.settings.Notice.STORAGE_FULL -> AppNotice.StorageFull
        app.guantera.domain.settings.Notice.EXPORT_OVERDUE -> AppNotice.ExportOverdue
    }
    private fun MaintenanceItem.toUi() = UiMaintenanceItem(id, name, intervalKm, intervalMonths, fixedDueDate?.toUi(), lastServiceKm?.toInt(), lastServiceDate?.toUi(), when (origin) {
        DataOrigin.VERIFIED -> UiDataOrigin.Verified
        DataOrigin.OWN_CRITERION -> UiDataOrigin.OwnCriterion
        DataOrigin.OWN -> UiDataOrigin.Own
    })
    private fun UiMaintenanceItem.toDomain() = MaintenanceItem(id, name, intervalKm, intervalMonths, fixedDueDate?.toDomain(), lastServiceKm?.toDouble(), lastServiceDate?.toDomain(), when (origin) {
        UiDataOrigin.Verified -> DataOrigin.VERIFIED
        UiDataOrigin.OwnCriterion -> DataOrigin.OWN_CRITERION
        UiDataOrigin.Own -> DataOrigin.OWN
    })
    private fun UiServiceRegistration.toDomain() = app.guantera.domain.usecase.ServiceRegistration(itemId, date.toDomain(), km?.toDouble(), costMinor, note, newDueDate?.toDomain())
    private fun DomainExpense.toUi() = UiExpense(id, when (category) {
        app.guantera.domain.expense.ExpenseCategory.COMBUSTIBLE -> UiExpenseCategory.Fuel
        app.guantera.domain.expense.ExpenseCategory.MANTENIMIENTO -> UiExpenseCategory.Maintenance
        app.guantera.domain.expense.ExpenseCategory.SEGURO -> UiExpenseCategory.Insurance
        app.guantera.domain.expense.ExpenseCategory.PEAJES -> UiExpenseCategory.Tolls
        app.guantera.domain.expense.ExpenseCategory.ESTACIONAMIENTO -> UiExpenseCategory.Parking
        app.guantera.domain.expense.ExpenseCategory.LAVADO -> UiExpenseCategory.Washing
        app.guantera.domain.expense.ExpenseCategory.OTROS -> UiExpenseCategory.Other
    }, amountMinor, date.toUi(), note, serviceRecordId)
    private fun UiExpense.toDomain() = DomainExpense(id, when (category) {
        UiExpenseCategory.Fuel -> app.guantera.domain.expense.ExpenseCategory.COMBUSTIBLE
        UiExpenseCategory.Maintenance -> app.guantera.domain.expense.ExpenseCategory.MANTENIMIENTO
        UiExpenseCategory.Insurance -> app.guantera.domain.expense.ExpenseCategory.SEGURO
        UiExpenseCategory.Tolls -> app.guantera.domain.expense.ExpenseCategory.PEAJES
        UiExpenseCategory.Parking -> app.guantera.domain.expense.ExpenseCategory.ESTACIONAMIENTO
        UiExpenseCategory.Washing -> app.guantera.domain.expense.ExpenseCategory.LAVADO
        UiExpenseCategory.Other -> app.guantera.domain.expense.ExpenseCategory.OTROS
    }, amountMinor, date.toDomain(), note, serviceRecordId)
    private companion object { const val VEHICLE_NAME = "Mi auto" }

    private fun CalendarDay.toUi() = CalendarDate(year, month, day)
    private fun CalendarDate.toDomain() = CalendarDay(year, month, day)
}
