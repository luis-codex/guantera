package app.guantera.sonda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        container.guarded("receptor de arranque") {
            container.recordEvent.broadcast(intent.action.orEmpty(), emptyMap())
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && container.trackLocation.hasPermission) startService(context)
        }
    }

    private fun startService(context: Context) {
        try {
            ServiceLauncher.start(context)
        } catch (e: IllegalStateException) {
            context.container.recordEvent.bootStartFailed(e.toString())
        }
    }
}
