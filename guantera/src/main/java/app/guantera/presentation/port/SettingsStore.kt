package app.guantera.presentation.port

import kotlinx.coroutines.flow.StateFlow

/** Everything P11 shows and edits. A null date means "never"; a null odometer means it was never entered (R14). */
data class AppSettings(
    val odometerKm: Int?,
    val odometerCalibratedAtMillis: Long?,
    val currency: String,
    val openAtBoot: Boolean,
    val catalogRestoredAtMillis: Long?,
    val lastBackupMillis: Long?,
    val lastExportMillis: Long?,
)

interface SettingsStore {
    val settings: StateFlow<AppSettings>
    val backups: BackupActions

    suspend fun correctOdometer(km: Int): FormOutcome
    suspend fun setCurrency(currency: String): FormOutcome
    suspend fun setOpenAtBoot(enabled: Boolean): FormOutcome
    suspend fun restoreCatalog(): FormOutcome

    /** Answers "¿El auto está estacionado?" with yes (CU-04). */
    fun confirmStopped()

    /** Re-reads what only the disk knows (the date of the last internal copy). */
    fun refreshBackupInfo()
}
