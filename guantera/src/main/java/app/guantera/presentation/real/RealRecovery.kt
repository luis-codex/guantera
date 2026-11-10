package app.guantera.presentation.real

import app.guantera.domain.backup.StoredBackup
import app.guantera.domain.port.BackupStore
import app.guantera.domain.usecase.BackupSource
import app.guantera.domain.usecase.CheckDataHealthAndRecover
import app.guantera.domain.usecase.DataHealthOutcome
import app.guantera.domain.usecase.ImportBackup
import app.guantera.domain.usecase.ImportResult
import app.guantera.domain.usecase.RecoveryReason
import app.guantera.presentation.port.BackupActions
import app.guantera.presentation.port.CopyInfo
import app.guantera.presentation.port.ExportOutcome
import app.guantera.presentation.port.ImportOutcome
import app.guantera.presentation.port.RecoveryActions
import app.guantera.presentation.port.RecoveryCause
import app.guantera.presentation.port.RestoreOutcome
import app.guantera.presentation.port.StartupResult

/**
 * P12 over CU-27 and CU-28. It runs before anything else, without the service, so every call
 * here turns a failure into an answer: a crash on this screen would cost the owner their data.
 */
class RealRecovery(
    private val check: CheckDataHealthAndRecover,
    private val importBackup: ImportBackup,
    private val store: BackupStore,
    private val backups: BackupActions,
) : RecoveryActions {
    /** True once the damaged database was set aside, so restoring a copy does not have to do it. */
    private var databaseMoved = false

    override suspend fun open(): StartupResult = try {
        when (val outcome = check()) {
            DataHealthOutcome.Healthy -> StartupResult.Healthy
            is DataHealthOutcome.Restored -> StartupResult.Restored(outcome.backup.toInfo(outcome.recordCount))
            is DataHealthOutcome.NeedsUser -> {
                databaseMoved = outcome.reason == RecoveryReason.NO_VALID_COPY || outcome.reason == RecoveryReason.RESTORE_FAILED
                StartupResult.NeedsUser(outcome.reason.toCause(), outcome.copies.map { it.toInfo() })
            }
        }
    } catch (e: Exception) {
        reportUnlessCancelled("revisar la base", e)
        StartupResult.NeedsUser(RecoveryCause.CheckFailed, emptyList())
    }

    override suspend fun restore(copyId: String): RestoreOutcome = try {
        // A database that cannot open is set aside first (R37); restoring then writes into a clean one.
        if (!databaseMoved && !check.startFresh()) {
            RestoreOutcome.Failed
        } else {
            databaseMoved = true
            when (importBackup(BackupSource.Internal(copyId))) {
                is ImportResult.Imported -> RestoreOutcome.Restored
                is ImportResult.Unreadable -> RestoreOutcome.Unreadable
                ImportResult.Damaged -> RestoreOutcome.Damaged
                is ImportResult.NewerSchema -> RestoreOutcome.NewerVersion
                ImportResult.CarMoving, ImportResult.SafetyCopyFailed, is ImportResult.StorageFailed -> RestoreOutcome.Failed
            }
        }
    } catch (e: Exception) {
        reportUnlessCancelled("restaurar copia", e)
        RestoreOutcome.Failed
    }

    override suspend fun importFromDrive(): ImportOutcome = backups.importFromDrive()

    override suspend fun export(): ExportOutcome = backups.export()

    override suspend fun startFresh(): Boolean = try {
        databaseMoved || check.startFresh().also { moved -> if (moved) databaseMoved = true }
    } catch (e: Exception) {
        reportUnlessCancelled("empezar de cero", e)
        false
    }

    private fun RecoveryReason.toCause() = when (this) {
        RecoveryReason.NO_VALID_COPY -> RecoveryCause.NoValidCopy
        RecoveryReason.DATABASE_NOT_MOVED -> RecoveryCause.DatabaseNotMoved
        RecoveryReason.MIGRATION_FAILED -> RecoveryCause.MigrationFailed
        RecoveryReason.REPEATED_CRASHES -> RecoveryCause.RepeatedCrashes
        RecoveryReason.RESTORE_FAILED -> RecoveryCause.RestoreFailed
    }

    private fun StoredBackup.toInfo(count: Int = recordCount) = CopyInfo(id, createdUtcMs, count)
}
