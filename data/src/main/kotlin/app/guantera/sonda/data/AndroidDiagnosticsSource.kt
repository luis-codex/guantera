package app.guantera.sonda.data

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.StatFs
import android.webkit.WebView
import app.guantera.sonda.domain.model.DeviceDiagnostics
import app.guantera.sonda.domain.model.PlayServices
import app.guantera.sonda.domain.port.DiagnosticsSource
import java.io.File
import java.io.IOException

/** Every probe is isolated: on a stripped-down ROM one missing service must not hide the others or close the app. */
class AndroidDiagnosticsSource(private val context: Context) : DiagnosticsSource {
    override fun read() = DeviceDiagnostics(
        androidSdk = Build.VERSION.SDK_INT,
        androidRelease = Build.VERSION.RELEASE,
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        hardware = Build.HARDWARE,
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        memTotalKb = readMemTotalKb(),
        internalStorageTotalMb = readStorageTotalMb(),
        webView = readWebView(),
        playServices = readPlayServices(),
        locationProviders = readLocationProviders(),
    )

    private fun readMemTotalKb(): Long? = try {
        File(MEMINFO_PATH).useLines { lines ->
            lines.firstOrNull { it.startsWith(MEM_TOTAL_PREFIX) }
                ?.removePrefix(MEM_TOTAL_PREFIX)?.trim()?.substringBefore(' ')?.toLongOrNull()
        }
    } catch (e: IOException) {
        null
    }

    private fun readStorageTotalMb(): Long? = try {
        StatFs(context.filesDir.absolutePath).totalBytes / BYTES_PER_MB
    } catch (e: RuntimeException) {
        null
    }

    private fun readWebView(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun readPlayServices(): PlayServices = try {
        PlayServices.Installed(context.packageManager.getPackageInfo(PLAY_SERVICES_PACKAGE, 0).versionName.orEmpty())
    } catch (e: PackageManager.NameNotFoundException) {
        PlayServices.NotInstalled
    } catch (e: RuntimeException) {
        PlayServices.Unknown
    }

    private fun readLocationProviders(): List<String> = try {
        (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).allProviders
    } catch (e: RuntimeException) {
        emptyList()
    }

    private companion object {
        const val MEMINFO_PATH = "/proc/meminfo"
        const val MEM_TOTAL_PREFIX = "MemTotal:"
        const val PLAY_SERVICES_PACKAGE = "com.google.android.gms"
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
