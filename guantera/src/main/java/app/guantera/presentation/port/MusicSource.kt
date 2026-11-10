package app.guantera.presentation.port

import kotlinx.coroutines.flow.StateFlow

/** Which music the app controls: invented (demo) or whatever really plays on the device. */
interface MusicSource {
    val isReal: StateFlow<Boolean>
    fun setReal(real: Boolean)
}
