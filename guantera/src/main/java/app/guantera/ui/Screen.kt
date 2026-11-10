package app.guantera.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.view.View
import app.guantera.presentation.shell.Destination

/** One piece of content inside the shell. [observe] runs only while the screen is visible. */
abstract class Screen {
    abstract val view: View
    open suspend fun observe() = Unit

    /** Lets a screen unwind its own sub-view first. Returns true if it consumed the back action. */
    open fun onBack(): Boolean = false

    /** The user navigated away. Not called when driving mode interrupts, so that work in progress survives. */
    open fun onLeave() = Unit
}

fun interface Navigator {
    fun navigate(destination: Destination)
}

/** Opens a system settings page. Returns false if this device does not have it. */
fun Context.openSystemSettings(action: String, data: android.net.Uri? = null): Boolean = try {
    startActivity(Intent(action, data))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
