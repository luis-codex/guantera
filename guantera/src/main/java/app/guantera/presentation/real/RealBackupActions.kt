package app.guantera.presentation.real

import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.port.BackupStore
import app.guantera.domain.port.ExternalBackupTransfer
import app.guantera.domain.usecase.BackupSource
import app.guantera.domain.usecase.ExportBackup
import app.guantera.domain.usecase.ExportResult
import app.guantera.domain.usecase.ImportBackup
import app.guantera.domain.usecase.ImportResult
import app.guantera.presentation.port.BackupActions
import app.guantera.presentation.port.ExportOutcome
import app.guantera.presentation.port.ImportOutcome

/**
 * P11, P0 and P12 over CU-26 and CU-27. A damaged database fails reads with exceptions the domain
 * lets through (the ports say so), so each call here answers with an outcome instead of crashing.
 */
class RealBackupActions(
    private val exportBackup: ExportBackup,
    private val importBackup: ImportBackup,
    private val transfer: ExternalBackupTransfer,
    private val store: BackupStore,
) : BackupActions {

    override suspend fun export(): ExportOutcome = try {
        when (val result = exportBackup()) {
            is ExportResult.Exported -> ExportOutcome.Done(result.location)
            ExportResult.CarMoving -> ExportOutcome.CarMoving
            ExportResult.DriveMissing -> ExportOutcome.DriveMissing
            ExportResult.DriveFull -> ExportOutcome.DriveFull
            is ExportResult.Failed -> ExportOutcome.Failed
        }
    } catch (e: Exception) {
        reportUnlessCancelled("exportar", e)
        ExportOutcome.Failed
    }

    override suspend fun importFromDrive(): ImportOutcome = try {
        val newest = transfer.available().maxByOrNull { createdOf(it.displayName) }
        when {
            newest == null -> ImportOutcome.DriveMissing.takeIf { !driveIsThere() } ?: ImportOutcome.NoCopy
            else -> when (val result = importBackup(BackupSource.External(newest.reference))) {
                is ImportResult.Imported -> ImportOutcome.Done(result.recordCount)
                ImportResult.CarMoving -> ImportOutcome.CarMoving
                is ImportResult.Unreadable -> ImportOutcome.Unreadable
                ImportResult.Damaged -> ImportOutcome.Damaged
                is ImportResult.NewerSchema -> ImportOutcome.NewerVersion
                ImportResult.SafetyCopyFailed -> ImportOutcome.SafetyCopyFailed
                is ImportResult.StorageFailed -> ImportOutcome.StorageFailed
            }
        }
    } catch (e: Exception) {
        reportUnlessCancelled("importar", e)
        ImportOutcome.StorageFailed
    }

    override suspend fun lastCopyUtcMs(): Long? = try {
        store.list().maxOfOrNull { it.createdUtcMs }
    } catch (e: Exception) {
        reportUnlessCancelled("leer última copia", e)
        null
    }

    /** `available()` is empty both without a drive and with an empty one; reading a missing file tells them apart. */
    private suspend fun driveIsThere(): Boolean {
        val probe = transfer.read(PROBE)
        return !(probe is BackupReadResult.Unreadable && probe.reason == DRIVE_MISSING)
    }

    /** Files are named `backup-<utc ms>-<n>`: the newest sorts by its first number. */
    private fun createdOf(name: String): Long = NAME.find(name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private companion object {
        val NAME = Regex("""backup-(\d+)-""")
        const val PROBE = "backup-0-0.json"
        const val DRIVE_MISSING = "drive missing"
    }
}

/** The same copy actions for every graph: P11 and P0 use the app's graph, P12 a light one of its own. */
fun backupActionsOf(
    graph: app.guantera.tracking.TrackingGraph,
    transfer: ExternalBackupTransfer,
    store: BackupStore,
): RealBackupActions = RealBackupActions(
    exportBackup = ExportBackup(graph.backupData, transfer, graph.settingsRepo, graph.observeDrivingState, graph.time),
    importBackup = ImportBackup(graph.backupData, store, transfer, graph.automaticBackup, graph.observeDrivingState),
    transfer = transfer,
    store = store,
)
