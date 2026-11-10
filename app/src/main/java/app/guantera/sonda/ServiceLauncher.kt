package app.guantera.sonda

import android.content.Context
import android.content.Intent
import android.os.Build

object ServiceLauncher {
    /** @throws IllegalStateException when the system forbids starting a service from the background. */
    fun start(context: Context) {
        val intent = Intent(context, ProbeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, ProbeService::class.java))
    }
}
