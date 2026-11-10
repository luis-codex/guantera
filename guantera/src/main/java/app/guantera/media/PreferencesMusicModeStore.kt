package app.guantera.media

import android.content.Context

class PreferencesMusicModeStore(context: Context) : MusicModeStore {
    private val prefs = context.getSharedPreferences("guantera", Context.MODE_PRIVATE)
    override fun isReal(): Boolean = prefs.getBoolean(KEY, false)
    override fun save(real: Boolean) { prefs.edit().putBoolean(KEY, real).apply() }
    private companion object { const val KEY = "music_real" }
}
