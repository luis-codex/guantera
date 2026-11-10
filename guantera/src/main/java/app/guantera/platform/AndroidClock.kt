package app.guantera.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.guantera.domain.port.Clock
import java.util.TimeZone

class AndroidClock(private val context: Context) : Clock {
    override fun utcMs(): Long = System.currentTimeMillis()

    override fun monoMs(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Exception) {
        null
    }

    override fun utcOffsetMs(atUtcMs: Long): Int = TimeZone.getDefault().getOffset(atUtcMs)
}
