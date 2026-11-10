package app.guantera.media

import app.guantera.presentation.port.MusicSource
import app.guantera.sonda.domain.media.ControlResult
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.MediaPlayerControl
import app.guantera.sonda.domain.media.MediaSnapshot
import app.guantera.sonda.domain.media.VolumeCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Remembers the choice between runs. */
interface MusicModeStore {
    fun isReal(): Boolean
    fun save(real: Boolean)
}

/** Delegates to the demo player or the real adapter, and lets Ajustes swap them live. */
class SwitchableMediaPlayerControl(
    private val sample: MediaPlayerControl,
    private val real: MediaPlayerControl,
    private val store: MusicModeStore,
    scope: CoroutineScope,
) : MediaPlayerControl, MusicSource {
    private val _isReal = MutableStateFlow(store.isReal())
    override val isReal: StateFlow<Boolean> = _isReal

    private var started = false
    private val active: MediaPlayerControl get() = if (_isReal.value) real else sample

    override val state: StateFlow<MediaSnapshot> =
        combine(_isReal, sample.state, real.state) { isReal, demo, actual -> if (isReal) actual else demo }
            .stateIn(scope, SharingStarted.Eagerly, if (_isReal.value) real.state.value else sample.state.value)

    override fun start() { started = true; active.start() }
    override fun stop() { started = false; active.stop() }
    override fun refresh() = active.refresh()
    override fun send(command: MediaCommand): ControlResult = active.send(command)
    override fun adjustVolume(command: VolumeCommand): ControlResult = active.adjustVolume(command)

    override fun setReal(real: Boolean) {
        if (real == _isReal.value) return
        if (started) active.stop()
        store.save(real)
        _isReal.value = real
        if (started) { active.start(); active.refresh() }
    }
}
