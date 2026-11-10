package app.guantera.startup

import android.content.Context
import app.guantera.domain.maintenance.MaintenanceAlert
import app.guantera.domain.port.AlertPublisher
import app.guantera.domain.port.LocationSource
import app.guantera.domain.trip.LocationFix
import app.guantera.domain.usecase.CheckDataHealthAndRecover
import app.guantera.platform.AndroidClock
import app.guantera.platform.AndroidTrackingHealthSource
import app.guantera.presentation.real.RealRecovery
import app.guantera.presentation.real.backupActionsOf
import app.guantera.storage.StorageComponents
import app.guantera.tracking.TrackingGraph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What P12 needs and nothing else: the storage, a clock and the use cases. No location, no
 * notification, no service. It is not the graph of the app and never replaces it.
 */
object RecoveryWiring {
    fun create(context: Context, storage: StorageComponents): RealRecovery {
        val graph = TrackingGraph(
            clock = AndroidClock(context),
            locationSource = object : LocationSource { override val fixes: Flow<LocationFix> = emptyFlow() },
            healthSource = AndroidTrackingHealthSource(context),
            alertPublisher = object : AlertPublisher { override suspend fun publish(alert: MaintenanceAlert?) = Unit },
            tripRepo = storage.trips,
            parkingRepo = storage.parking,
            settingsRepo = storage.settings,
            odometerRepo = storage.odometer,
            maintenanceRepo = storage.maintenance,
            expenseRepo = storage.expenses,
            transactions = storage.transactions,
            backupStore = storage.backups,
        )
        val backups = backupActionsOf(graph, storage.externalBackups, storage.backups)
        return RealRecovery(
            check = CheckDataHealthAndRecover(storage.health, storage.backups, graph.backupData),
            importBackup = app.guantera.domain.usecase.ImportBackup(
                graph.backupData, storage.backups, storage.externalBackups, graph.automaticBackup, graph.observeDrivingState,
            ),
            store = storage.backups,
            backups = backups,
        )
    }
}
