package app.guantera.system

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.guantera.presentation.port.DateTimeInfo
import app.guantera.presentation.port.DateTimeSettings
import java.util.Locale
import java.util.TimeZone

/** Reads Settings.Global (no permission needed) and checks the system screen exists. */
class AndroidDateTimeSettings(private val context: Context) : DateTimeSettings {
    override fun read(): DateTimeInfo = DateTimeInfo(
        autoTime = flag(Settings.Global.AUTO_TIME),
        autoTimeZone = flag(Settings.Global.AUTO_TIME_ZONE),
        timeZoneLabel = zoneLabel(),
        settingsScreenAvailable = screenAvailable(),
    )

    private fun flag(name: String): Boolean? = try {
        when (Settings.Global.getInt(context.contentResolver, name)) {
            0 -> false
            1 -> true
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun zoneLabel(): String? = try {
        val zone = TimeZone.getDefault()
        val offsetMinutes = zone.getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (offsetMinutes < 0) "-" else "+"
        val abs = kotlin.math.abs(offsetMinutes)
        "${zone.id} (GMT$sign${String.format(Locale.ROOT, "%02d:%02d", abs / 60, abs % 60)})"
    } catch (_: Exception) {
        null
    }

    private fun screenAvailable(): Boolean = try {
        Intent(Settings.ACTION_DATE_SETTINGS).resolveActivity(context.packageManager) != null
    } catch (_: Exception) {
        false
    }
}
