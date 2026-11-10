package app.guantera.sonda.ui

import android.content.Context
import app.guantera.sonda.R
import app.guantera.sonda.domain.model.DeviceDiagnostics
import app.guantera.sonda.domain.model.PlayServices
import java.text.DecimalFormat

/** Renders domain data as Spanish text using the app's string resources. */
class ReportFormatter(private val context: Context) {
    private val thousands = DecimalFormat("#,##0")

    fun diagnostics(d: DeviceDiagnostics): String = listOf(
        context.getString(R.string.diagnostics_title),
        context.getString(R.string.diagnostics_android, d.androidSdk, d.androidRelease),
        context.getString(R.string.diagnostics_model, d.model),
        context.getString(R.string.diagnostics_manufacturer, d.manufacturer),
        context.getString(R.string.diagnostics_hardware, d.hardware),
        context.getString(R.string.diagnostics_abis, d.supportedAbis.joinToString()),
        context.getString(R.string.diagnostics_memory, d.memTotalKb?.let { "${thousands.format(it)} kB" } ?: unavailable()),
        context.getString(R.string.diagnostics_storage, d.internalStorageTotalMb?.let { "${thousands.format(it)} MB" } ?: unavailable()),
        context.getString(R.string.diagnostics_webview, d.webView ?: unavailable()),
        context.getString(R.string.diagnostics_play_services, playServices(d.playServices)),
        context.getString(R.string.diagnostics_providers, d.locationProviders.joinToString().ifEmpty { unavailable() }),
    ).joinToString("\n")

    fun header(d: DeviceDiagnostics): String = "${diagnostics(d)}\n\n${context.getString(R.string.report_events_heading)}\n"

    private fun playServices(state: PlayServices) = when (state) {
        PlayServices.NotInstalled -> context.getString(R.string.diagnostics_not_installed)
        PlayServices.Unknown -> unavailable()
        is PlayServices.Installed -> state.version.ifEmpty { context.getString(R.string.diagnostics_installed) }
    }

    private fun unavailable() = context.getString(R.string.diagnostics_unavailable)
}
