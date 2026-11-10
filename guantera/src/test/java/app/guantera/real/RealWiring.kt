package app.guantera.real

import app.guantera.domain.maintenance.MaintenanceAlert
import app.guantera.domain.motion.TrackingHealth
import app.guantera.domain.port.AlertPublisher
import app.guantera.domain.port.BackupStore
import app.guantera.domain.port.Clock
import app.guantera.domain.port.ExternalBackup
import app.guantera.domain.port.ExternalBackupTransfer
import app.guantera.domain.port.ExternalExportResult
import app.guantera.domain.port.LocationSource
import app.guantera.domain.port.TrackingHealthSource
import app.guantera.domain.backup.BackupData
import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.trip.LocationFix
import app.guantera.presentation.real.RealData
import app.guantera.presentation.real.backupActionsOf
import app.guantera.tracking.InMemoryTrackingDatabase
import app.guantera.tracking.TrackingGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

class TestDomainClock(var utc: Long = 1_700_000_000_000L, var mono: Long = 10_000L) : Clock {
    override fun utcMs() = utc
    override fun monoMs() = mono
    override fun bootCount(): Int? = 1
    override fun utcOffsetMs(atUtcMs: Long) = 0
}

class TestHealth(initial: TrackingHealth = TrackingHealth(hasPermission = true, gpsEnabled = true)) : TrackingHealthSource {
    override val health = MutableStateFlow(initial)
}

/** A pendrive that can be absent, empty or hold named files. */
class FakeTransfer : ExternalBackupTransfer {
    var present = true
    var full = false
    var failExport = false
    var files = linkedMapOf<String, BackupReadResult>()
    var exports = 0

    override suspend fun export(data: BackupData, createdUtcMs: Long): ExternalExportResult {
        if (!present) return ExternalExportResult.DriveMissing
        if (full) return ExternalExportResult.DriveFull
        if (failExport) return ExternalExportResult.Failed("write failed")
        exports += 1
        return ExternalExportResult.Exported("/pendrive/Guantera/respaldos/backup-$createdUtcMs-1.json")
    }

    override suspend fun available(): List<ExternalBackup> =
        if (present) files.keys.map { ExternalBackup(it, "$it.json") } else emptyList()

    override suspend fun read(reference: String): BackupReadResult = when {
        !present -> BackupReadResult.Unreadable("drive missing")
        else -> files[reference.removeSuffix(".json")] ?: BackupReadResult.Unreadable("missing")
    }
}

/** The real domain over memory: what the app runs, minus the platform. */
class RealRig(scope: CoroutineScope) {
    val db = InMemoryTrackingDatabase()
    val clock = TestDomainClock()
    val health = TestHealth()
    val transfer = FakeTransfer()
    val graph = TrackingGraph(
        clock = clock,
        locationSource = object : LocationSource { override val fixes: Flow<LocationFix> = emptyFlow() },
        healthSource = health,
        alertPublisher = object : AlertPublisher { override suspend fun publish(alert: MaintenanceAlert?) = Unit },
        tripRepo = db.tripRepo,
        parkingRepo = db.parkingRepo,
        settingsRepo = db.settingsRepo,
        odometerRepo = db.odometerRepo,
        maintenanceRepo = db.maintenanceRepo,
        expenseRepo = db.expenseRepo,
        transactions = db.transactions,
        backupStore = db.backupStore,
    )
    val backups = backupActionsOf(graph, transfer, db.backupStore as BackupStore)
    val data = RealData(graph, scope, backups)
}
