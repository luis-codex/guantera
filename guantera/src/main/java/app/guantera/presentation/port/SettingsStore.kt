package app.guantera.presentation.port

import kotlinx.coroutines.flow.StateFlow

/** Everything P11 shows and edits. A null date means "never". */
data class AppSettings(
    val odometerKm: Int,
    val odometerCalibratedAtMillis: Long?,
    val currency: String,
    val openAtBoot: Boolean,
    val catalogRestoredAtMillis: Long?,
    val lastBackupMillis: Long?,
    val lastExportMillis: Long?,
)

interface SettingsStore {
    val settings: StateFlow<AppSettings>
    fun correctOdometer(km: Int)
    fun setCurrency(currency: String)
    fun setOpenAtBoot(enabled: Boolean)
    fun restoreCatalog()
}
