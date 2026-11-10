package app.guantera.sample

import app.guantera.presentation.port.AppSettings
import app.guantera.presentation.port.SettingsStore
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory settings for the demonstration build. */
class SampleSettings(private val clock: Clock) : SettingsStore {
    private val _settings = MutableStateFlow(
        AppSettings(
            odometerKm = 48_250,
            odometerCalibratedAtMillis = clock.nowMillis() - 12 * DAY,
            currency = "$",
            openAtBoot = false,
            catalogRestoredAtMillis = null,
            lastBackupMillis = clock.nowMillis() - 6 * HOUR,
            lastExportMillis = clock.nowMillis() - 45 * DAY,
        ),
    )
    override val settings: StateFlow<AppSettings> = _settings

    override fun correctOdometer(km: Int) =
        _settings.update { it.copy(odometerKm = km, odometerCalibratedAtMillis = clock.nowMillis()) }

    override fun setCurrency(currency: String) = _settings.update { it.copy(currency = currency) }
    override fun setOpenAtBoot(enabled: Boolean) = _settings.update { it.copy(openAtBoot = enabled) }
    override fun restoreCatalog() = _settings.update { it.copy(catalogRestoredAtMillis = clock.nowMillis()) }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
