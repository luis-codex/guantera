package app.guantera.ui.screens

import android.content.Context
import app.guantera.R

private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 1_440L

fun Context.ageText(minutes: Long): String = when {
    minutes < 1 -> getString(R.string.age_now)
    minutes < MINUTES_PER_HOUR -> getString(R.string.age_minutes, minutes.toInt())
    minutes < MINUTES_PER_DAY ->
        getString(R.string.age_hours, (minutes / MINUTES_PER_HOUR).toInt(), (minutes % MINUTES_PER_HOUR).toInt())
    else -> (minutes / MINUTES_PER_DAY).toInt().let { resources.getQuantityString(R.plurals.age_days, it, it) }
}

/** "25 min" or "1 h 05". */
fun Context.durationText(minutes: Long): String = if (minutes < MINUTES_PER_HOUR) {
    getString(R.string.duration_minutes, minutes.toInt())
} else {
    getString(R.string.duration_hours, (minutes / MINUTES_PER_HOUR).toInt(), (minutes % MINUTES_PER_HOUR).toInt())
}
