package app.guantera.sonda.domain.model

data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val speedMetersPerSecond: Float?,
    val accuracyMeters: Float?,
    val usedSatellites: Int?,
)

data class ClockSample(val elapsedRealtimeMillis: Long, val uptimeMillis: Long)

data class DeviceDiagnostics(
    val androidSdk: Int,
    val androidRelease: String,
    val model: String,
    val manufacturer: String,
    val hardware: String,
    val supportedAbis: List<String>,
    val memTotalKb: Long?,
    val internalStorageTotalMb: Long?,
    val webView: String?,
    val playServices: PlayServices,
    val locationProviders: List<String>,
)

sealed interface PlayServices {
    data class Installed(val version: String) : PlayServices
    data object NotInstalled : PlayServices
    data object Unknown : PlayServices
}

data class DashboardState(val serviceRunning: Boolean, val log: LogRead, val logHealth: LogHealth)

sealed interface AppendResult {
    data object Accepted : AppendResult
    data class Rejected(val reason: String) : AppendResult
}

/** Outcome of the asynchronous disk writes behind [AppendResult.Accepted]. */
sealed interface LogHealth {
    data object Healthy : LogHealth
    data class WriteFailing(val reason: String) : LogHealth
}

sealed interface LogRead {
    data class Lines(val lines: List<String>) : LogRead
    data class Unreadable(val reason: String) : LogRead
}

sealed interface LogCopyResult {
    data object Copied : LogCopyResult
    data class Failed(val reason: String) : LogCopyResult
}

sealed interface StartResult {
    data class Started(val gpsProviderEnabled: Boolean) : StartResult
    data object AlreadyActive : StartResult
    data object PermissionDenied : StartResult
    data class Failed(val reason: String) : StartResult
}

sealed interface LocationEvent {
    data class Fix(val fix: GpsFix) : LocationEvent
    data class ProviderStateChanged(val provider: String, val enabled: Boolean) : LocationEvent
}

sealed interface ExportResult {
    data class Saved(val location: String) : ExportResult
    data object StoragePermissionRequired : ExportResult
    data class Failed(val reason: String) : ExportResult
}
