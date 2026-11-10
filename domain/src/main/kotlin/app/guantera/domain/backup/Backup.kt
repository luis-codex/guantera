package app.guantera.domain.backup

import app.guantera.domain.expense.Expense
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.maintenance.ServiceRecord
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.parking.ParkingSpot
import app.guantera.domain.settings.Settings
import app.guantera.domain.trip.Trip

const val CURRENT_BACKUP_SCHEMA = 1

/** A logical copy of all the data (resiliencia.md). The trip in progress is not included. */
data class BackupData(
    val trips: List<Trip>,
    val calibrations: List<OdometerCalibration>,
    val items: List<MaintenanceItem>,
    val services: List<ServiceRecord>,
    val expenses: List<Expense>,
    val parkingSpots: List<ParkingSpot>,
    val settings: Settings,
) {
    val recordCount: Int
        get() = trips.size + calibrations.size + items.size + services.size + expenses.size + parkingSpots.size
}

/** A copy listed in the radio's storage. */
data class StoredBackup(val id: String, val createdUtcMs: Long, val recordCount: Int)

/** A file after being read back: [declaredChecksum] is from its header, [actualChecksum] from its bytes. */
data class BackupFile(
    val schemaVersion: Int,
    val createdUtcMs: Long,
    val declaredRecordCount: Int,
    val declaredChecksum: String,
    val actualChecksum: String,
    val data: BackupData,
)

sealed interface BackupReadResult {
    data class Read(val file: BackupFile) : BackupReadResult
    /** Missing, damaged or foreign file. */
    data class Unreadable(val reason: String) : BackupReadResult
}

sealed interface BackupVerdict {
    data class Valid(val data: BackupData) : BackupVerdict
    data object ChecksumMismatch : BackupVerdict
    data object RecordCountMismatch : BackupVerdict
    data class NewerSchema(val found: Int) : BackupVerdict
}

/** R32 and R36. */
object BackupValidator {
    fun validate(file: BackupFile, appSchema: Int = CURRENT_BACKUP_SCHEMA): BackupVerdict = when {
        file.schemaVersion > appSchema -> BackupVerdict.NewerSchema(file.schemaVersion)
        file.declaredChecksum != file.actualChecksum -> BackupVerdict.ChecksumMismatch
        file.declaredRecordCount != file.data.recordCount -> BackupVerdict.RecordCountMismatch
        else -> BackupVerdict.Valid(file.data)
    }
}

/**
 * R34: the last 7 copies, plus the newest of each of the calendar weeks of the last 4 weeks.
 * Weeks are fixed on the epoch so a copy never changes bucket and survives as it ages. The reference
 * is the newest copy, not the clock, so a wrong clock can never delete anything.
 */
object BackupRetention {
    const val KEEP_LATEST = 7
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val WEEKS = 4

    fun toDelete(copies: List<StoredBackup>): List<StoredBackup> {
        val newestFirst = copies.sortedByDescending { it.createdUtcMs }
        val reference = newestFirst.firstOrNull()?.createdUtcMs ?: return emptyList()
        val latest = newestFirst.take(KEEP_LATEST)
        val weekly = newestFirst
            .filter { reference - it.createdUtcMs < WEEKS * WEEK_MS }
            .distinctBy { Math.floorDiv(it.createdUtcMs, WEEK_MS) }
        val kept = (latest + weekly).toSet()
        return newestFirst.filterNot { it in kept }
    }
}
