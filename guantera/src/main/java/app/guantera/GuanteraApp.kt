package app.guantera

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.guantera.presentation.port.StartupResult
import app.guantera.presentation.real.RealRecovery
import app.guantera.startup.RecoveryWiring
import app.guantera.storage.StorageComponents
import app.guantera.storage.StorageFactory
import app.guantera.tracking.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class GuanteraApp : Application() {
    /** Building the storage opens nothing: the database is opened by the first query. */
    private lateinit var storage: StorageComponents

    /** P12's way to the data. It never depends on [container]. */
    lateinit var recovery: RealRecovery
        private set

    /** What opening the app found (CU-28). Anything but Healthy sends the user to P12 first. */
    var startup: StartupResult = StartupResult.Healthy
        private set

    private var built: AppContainer? = null

    /** Only exists once the data was checked and, if needed, recovered. */
    val container: AppContainer get() = checkNotNull(built) { "The container exists only after the data check passed" }

    /** True when the data may be used. A restored copy still shows its notice, but nothing is missing. */
    val isReady: Boolean get() = built != null

    /** True while there is something P12 has to say or ask before the app opens. */
    val needsRecoveryScreen: Boolean get() = startup !is StartupResult.Healthy

    override fun onCreate() {
        super.onCreate()
        storage = StorageFactory.create(this)
        recovery = RecoveryWiring.create(this, storage)
        // Before anything else, and before any service: the check decides whether Room may open at all.
        startup = runBlocking(Dispatchers.IO) { recovery.open() }
        android.util.Log.i("Guantera", "Revisión de datos al abrir: $startup")
        // TrackingService may be started by BOOT_COMPLETED before an activity exists. Creating the
        // root here guarantees that it receives the persistent graph, never the fallback memory DB.
        if (startup !is StartupResult.NeedsUser) buildContainer()
    }

    /** P12 is done: the data is usable, so the rest of the app can start. */
    fun recoveryFinished() {
        startup = StartupResult.Healthy
        if (built == null) buildContainer()
    }

    private fun buildContainer() {
        built = AppContainer(this, storage)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            TrackingService.start(this)
        }
    }
}
