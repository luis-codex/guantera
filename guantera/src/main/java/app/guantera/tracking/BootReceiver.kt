package app.guantera.tracking

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Broadcast receiver triggered on system boot.
 * Starts [TrackingService] in the foreground if location permissions were already granted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        Log.i(TAG, "BootReceiver recibió acción: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val hasPermission = try {
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                false
            }

            if (hasPermission) {
                try {
                    Log.i(TAG, "Permiso de ubicación concedido; iniciando TrackingService")
                    TrackingService.start(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error al iniciar TrackingService desde BootReceiver", e)
                    TrackingGraph.get()?.reportPlatformError(e, "BootReceiver.startService")
                }
            } else {
                Log.w(TAG, "Sin permiso ACCESS_FINE_LOCATION al arrancar; no se inicia el servicio")
            }
        }
    }

    companion object {
        const val TAG = "BootReceiver"
    }
}
