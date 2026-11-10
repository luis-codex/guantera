package app.guantera.tracking

import app.guantera.domain.usecase.BackupDataAccess
import app.guantera.domain.motion.ManualConfirmation
import app.guantera.domain.port.AlertPublisher
import app.guantera.domain.port.BackupStore
import app.guantera.domain.port.Clock
import app.guantera.domain.port.ExpenseRepository
import app.guantera.domain.port.LocationSource
import app.guantera.domain.port.MaintenanceRepository
import app.guantera.domain.port.OdometerRepository
import app.guantera.domain.port.ParkingRepository
import app.guantera.domain.port.SettingsRepository
import app.guantera.domain.port.TrackingHealthSource
import app.guantera.domain.port.TransactionRunner
import app.guantera.domain.port.TripRepository
import app.guantera.domain.usecase.CheckTrackingHealth
import app.guantera.domain.usecase.ConfirmParked
import app.guantera.domain.usecase.CreateAutomaticBackup
import app.guantera.domain.usecase.GetPendingAlerts
import app.guantera.domain.usecase.LocalCalendar
import app.guantera.domain.usecase.MaintenanceEvaluator
import app.guantera.domain.usecase.ObserveDrivingState
import app.guantera.domain.usecase.OdometerReader
import app.guantera.domain.usecase.ProcessLocationFix
import app.guantera.domain.usecase.ProcessTick
import app.guantera.domain.usecase.RecoverTripAfterWake
import app.guantera.domain.usecase.ResolveTrustedTime
import app.guantera.domain.usecase.TripEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Assembly graph for tracking, background service, and trip detection.
 * Ensures a single [TripEngine] per process and provides all necessary use cases.
 */
class TrackingGraph(
    val clock: Clock,
    val locationSource: LocationSource,
    val healthSource: TrackingHealthSource,
    val alertPublisher: AlertPublisher,
    val tripRepo: TripRepository,
    val parkingRepo: ParkingRepository,
    val settingsRepo: SettingsRepository,
    val odometerRepo: OdometerRepository,
    val maintenanceRepo: MaintenanceRepository,
    val expenseRepo: ExpenseRepository,
    val transactions: TransactionRunner,
    val backupStore: BackupStore,
    val confirmation: ManualConfirmation = ManualConfirmation(),
    val calendar: LocalCalendar = LocalCalendar(clock),
    val time: ResolveTrustedTime = ResolveTrustedTime(settingsRepo, clock, calendar),
    val backupData: BackupDataAccess = BackupDataAccess(
        tripRepo, odometerRepo, maintenanceRepo, expenseRepo, parkingRepo, settingsRepo, transactions,
    ),
    val automaticBackup: CreateAutomaticBackup = CreateAutomaticBackup(backupData, backupStore, time),
    val engine: TripEngine = TripEngine(
        tripRepo, parkingRepo, settingsRepo, transactions, clock, confirmation, automaticBackup,
    ),
    val processLocationFix: ProcessLocationFix = ProcessLocationFix(engine),
    val processTick: ProcessTick = ProcessTick(engine),
    val recoverTripAfterWake: RecoverTripAfterWake = RecoverTripAfterWake(engine),
    val observeDrivingState: ObserveDrivingState = ObserveDrivingState(engine, healthSource, clock),
    val odometerReader: OdometerReader = OdometerReader(odometerRepo, tripRepo),
    val maintenanceEvaluator: MaintenanceEvaluator = MaintenanceEvaluator(maintenanceRepo, odometerReader, time),
    val getPendingAlerts: GetPendingAlerts = GetPendingAlerts(maintenanceEvaluator, alertPublisher, observeDrivingState),
    val checkTrackingHealth: CheckTrackingHealth = CheckTrackingHealth(observeDrivingState),
    val confirmParked: ConfirmParked = ConfirmParked(observeDrivingState, confirmation, clock),
) {
    private val _lastPlatformError = MutableStateFlow<String?>(null)
    val lastPlatformError: StateFlow<String?> = _lastPlatformError.asStateFlow()

    fun reportPlatformError(error: Throwable, context: String = "") {
        val detail = error.message ?: error.toString()
        _lastPlatformError.value = if (context.isNotBlank()) "$context: $detail" else detail
    }

    fun clearPlatformError() {
        _lastPlatformError.value = null
    }

    companion object {
        @Volatile
        private var instance: TrackingGraph? = null

        /** Set once by [app.guantera.GuanteraApp] when the data is known to be usable. */
        @Synchronized
        fun setInstance(graph: TrackingGraph) {
            instance = graph
        }

        /**
         * The one graph of the process, or null while the data cannot be used (P12 pending). There is no
         * fallback: trips recorded in memory and lost without a word would be the worst failure of the app.
         */
        fun get(): TrackingGraph? = instance
    }
}
