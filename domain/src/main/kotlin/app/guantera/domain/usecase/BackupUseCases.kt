package app.guantera.domain.usecase

import app.guantera.domain.backup.BackupData
import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.backup.BackupRetention
import app.guantera.domain.backup.BackupValidator
import app.guantera.domain.backup.BackupVerdict
import app.guantera.domain.backup.StoredBackup
import app.guantera.domain.motion.DrivingState
import app.guantera.domain.port.BackupStore
import app.guantera.domain.port.BackupWriteResult
import app.guantera.domain.port.DataHealthChecker
import app.guantera.domain.port.ExpenseRepository
import app.guantera.domain.port.ExternalBackupTransfer
import app.guantera.domain.port.ExternalExportResult
import app.guantera.domain.port.MaintenanceRepository
import app.guantera.domain.port.OdometerRepository
import app.guantera.domain.port.ParkingRepository
import app.guantera.domain.port.SettingsRepository
import app.guantera.domain.port.StorageException
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.port.TransactionRunner
import app.guantera.domain.port.TripRepository
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.time.DayRange
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reads and replaces all the data at once. */
class BackupDataAccess(
    private val trips: TripRepository,
    private val odometer: OdometerRepository,
    private val maintenance: MaintenanceRepository,
    private val expenses: ExpenseRepository,
    private val parking: ParkingRepository,
    private val settings: SettingsRepository,
    private val transactions: TransactionRunner,
) {
    suspend fun read() = BackupData(
        trips = trips.closed(UtcWindow(null, null)),
        calibrations = odometer.all(),
        items = maintenance.items(),
        services = maintenance.services(),
        expenses = expenses.inRange(DayRange()),
        parkingSpots = parking.all(),
        settings = settings.get(),
    )

    suspend fun replace(data: BackupData) = transactions.run {
        trips.replaceAll(data.trips)
        odometer.replaceAll(data.calibrations)
        maintenance.replaceAll(data.items, data.services)
        expenses.replaceAll(data.expenses)
        parking.replaceAll(data.parkingSpots)
        settings.update { data.settings }
    }
}

sealed interface AutomaticBackupResult {
    data class Created(val backup: StoredBackup) : AutomaticBackupResult
    data object StorageFull : AutomaticBackupResult
    data class Failed(val reason: String) : AutomaticBackupResult
}

/** CU-25. Failing here never undoes what triggered it; P11 shows the date of the last good copy. */
class CreateAutomaticBackup(
    private val data: BackupDataAccess,
    private val store: BackupStore,
    private val time: ResolveTrustedTime,
) {
    private val mutex = Mutex()

    suspend fun run(): AutomaticBackupResult = mutex.withLock {
        val snapshot = try {
            data.read()
        } catch (e: StorageException) {
            return@withLock AutomaticBackupResult.Failed(e.failure.name)
        }
        val createdAt = time.bestEffortUtcMs()
        var result = store.save(snapshot, createdAt)
        if (result == BackupWriteResult.StorageFull) {
            rotate()
            result = store.save(snapshot, createdAt)
        }
        when (result) {
            is BackupWriteResult.Validated -> { rotate(); AutomaticBackupResult.Created(result.backup) }
            BackupWriteResult.StorageFull -> AutomaticBackupResult.StorageFull
            is BackupWriteResult.Failed -> AutomaticBackupResult.Failed(result.reason)
        }
    }

    /** R33, R34: only what retention already gave up on goes, so the newest valid copy always stays. */
    private suspend fun rotate() {
        val expired = BackupRetention.toDelete(store.list())
        if (expired.isNotEmpty()) store.delete(expired.map { it.id })
    }
}

sealed interface ExportResult {
    data class Exported(val location: String) : ExportResult
    data object CarMoving : ExportResult
    data object DriveMissing : ExportResult
    data object DriveFull : ExportResult
    data class Failed(val reason: String) : ExportResult
}

/** CU-26. Only marks the export as done once the drive confirmed it. */
class ExportBackup(
    private val data: BackupDataAccess,
    private val transfer: ExternalBackupTransfer,
    private val settings: SettingsRepository,
    private val driving: ObserveDrivingState,
    private val time: ResolveTrustedTime,
) {
    suspend operator fun invoke(): ExportResult {
        if (driving.current() == DrivingState.EN_MOVIMIENTO) return ExportResult.CarMoving
        return try {
            val createdAt = time.bestEffortUtcMs()
            when (val result = transfer.export(data.read(), createdAt)) {
                is ExternalExportResult.Exported -> {
                    settings.update { it.copy(lastExportUtcMs = createdAt) }
                    ExportResult.Exported(result.location)
                }
                ExternalExportResult.DriveMissing -> ExportResult.DriveMissing
                ExternalExportResult.DriveFull -> ExportResult.DriveFull
                is ExternalExportResult.Failed -> ExportResult.Failed(result.reason)
            }
        } catch (e: StorageException) {
            ExportResult.Failed(e.failure.name)
        }
    }
}

sealed interface BackupSource {
    data class External(val reference: String) : BackupSource
    data class Internal(val id: String) : BackupSource
}

sealed interface ImportResult {
    data class Imported(val recordCount: Int) : ImportResult
    data object CarMoving : ImportResult
    data class Unreadable(val reason: String) : ImportResult
    data object Damaged : ImportResult
    data class NewerSchema(val found: Int) : ImportResult

    /** R35: without a copy of what is there now, nothing was replaced. */
    data object SafetyCopyFailed : ImportResult
    data class StorageFailed(val failure: StorageFailure) : ImportResult
}

/** CU-27, R35, R36. */
class ImportBackup(
    private val data: BackupDataAccess,
    private val store: BackupStore,
    private val transfer: ExternalBackupTransfer,
    private val backup: CreateAutomaticBackup,
    private val driving: ObserveDrivingState,
) {
    suspend operator fun invoke(source: BackupSource): ImportResult {
        if (driving.current() == DrivingState.EN_MOVIMIENTO) return ImportResult.CarMoving
        val read = when (source) {
            is BackupSource.External -> transfer.read(source.reference)
            is BackupSource.Internal -> store.read(source.id)
        }
        val file = when (read) {
            is BackupReadResult.Unreadable -> return ImportResult.Unreadable(read.reason)
            is BackupReadResult.Read -> read.file
        }
        val incoming = when (val verdict = BackupValidator.validate(file)) {
            is BackupVerdict.Valid -> verdict.data
            is BackupVerdict.NewerSchema -> return ImportResult.NewerSchema(verdict.found)
            BackupVerdict.ChecksumMismatch, BackupVerdict.RecordCountMismatch -> return ImportResult.Damaged
        }
        return replaceKeepingCopy(incoming)
    }

    private suspend fun replaceKeepingCopy(incoming: BackupData): ImportResult = try {
        if (data.read().recordCount > 0 && backup.run() !is AutomaticBackupResult.Created) {
            ImportResult.SafetyCopyFailed
        } else {
            data.replace(incoming)
            ImportResult.Imported(incoming.recordCount)
        }
    } catch (e: StorageException) {
        ImportResult.StorageFailed(e.failure)
    }
}

enum class RecoveryReason { NO_VALID_COPY, DATABASE_NOT_MOVED, MIGRATION_FAILED, REPEATED_CRASHES, RESTORE_FAILED }

sealed interface DataHealthOutcome {
    data object Healthy : DataHealthOutcome
    data class Restored(val backup: StoredBackup, val recordCount: Int) : DataHealthOutcome

    /** P12 opens: [copies] are the ones available, newest first. */
    data class NeedsUser(val reason: RecoveryReason, val copies: List<StoredBackup>) : DataHealthOutcome
}

/** CU-28, R37. */
class CheckDataHealthAndRecover(
    private val checker: DataHealthChecker,
    private val store: BackupStore,
    private val data: BackupDataAccess,
) {
    suspend operator fun invoke(): DataHealthOutcome {
        val report = checker.check()
        return when {
            !report.databaseIntact -> recoverDamagedDatabase()
            report.migrationFailed -> needsUser(RecoveryReason.MIGRATION_FAILED)
            report.consecutiveFailedOpens >= MAX_FAILED_OPENS -> needsUser(RecoveryReason.REPEATED_CRASHES)
            else -> DataHealthOutcome.Healthy
        }
    }

    /** "Empezar de cero": the old data is set aside, never deleted. False if it could not be moved. */
    suspend fun startFresh(): Boolean = checker.quarantineDatabase()

    private suspend fun recoverDamagedDatabase(): DataHealthOutcome {
        if (!checker.quarantineDatabase()) return needsUser(RecoveryReason.DATABASE_NOT_MOVED)
        for (copy in store.list().sortedByDescending { it.createdUtcMs }) {
            val incoming = validCopy(copy) ?: continue
            return try {
                data.replace(incoming)
                DataHealthOutcome.Restored(copy, incoming.recordCount)
            } catch (_: StorageException) {
                needsUser(RecoveryReason.RESTORE_FAILED)
            }
        }
        return needsUser(RecoveryReason.NO_VALID_COPY)
    }

    private suspend fun validCopy(copy: StoredBackup): BackupData? {
        val file = (store.read(copy.id) as? BackupReadResult.Read)?.file
        if (file == null) {
            store.quarantine(copy.id)
            return null
        }
        return when (val verdict = BackupValidator.validate(file)) {
            is BackupVerdict.Valid -> verdict.data
            is BackupVerdict.NewerSchema -> null // Fine for a newer app, so it is not damaged.
            BackupVerdict.ChecksumMismatch, BackupVerdict.RecordCountMismatch -> {
                store.quarantine(copy.id)
                null
            }
        }
    }

    private suspend fun needsUser(reason: RecoveryReason) =
        DataHealthOutcome.NeedsUser(reason, store.list().sortedByDescending { it.createdUtcMs })

    private companion object { const val MAX_FAILED_OPENS = 3 }
}
