package app.guantera

import android.content.Context
import app.guantera.media.PreferencesMusicModeStore
import app.guantera.media.SwitchableMediaPlayerControl
import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.common.PlaybackActions
import app.guantera.presentation.common.minuteTicks
import app.guantera.presentation.driving.DrivingPresenter
import app.guantera.presentation.expenses.ExpensesPresenter
import app.guantera.presentation.home.HomePresenter
import app.guantera.presentation.input.FormGate
import app.guantera.presentation.input.LedgerFormat
import app.guantera.presentation.maintenance.MaintenancePresenter
import app.guantera.presentation.music.MusicPresenter
import app.guantera.presentation.parked.ParkedPresenter
import app.guantera.presentation.port.DemoControls
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.settings.SettingsPresenter
import app.guantera.presentation.shell.ShellPresenter
import app.guantera.presentation.trips.TripsPresenter
import app.guantera.sample.FakeMediaPlayerControl
import app.guantera.sample.SampleData
import app.guantera.sample.SampleExpenseData
import app.guantera.sample.SampleMaintenanceData
import app.guantera.sample.SampleSettings
import app.guantera.system.AndroidDateTimeSettings
import app.guantera.sonda.media.MediaPlayerControlFactory
import app.guantera.sonda.domain.media.MediaPlayerControl
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Composition root: the only place that knows which implementations the app runs on. */
class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock: Clock = DeviceClock
    private val format = DisplayFormat()
    private val ticks = minuteTicks(clock)

    private val sample = SampleData(clock, scope)
    private val data: GuanteraData = sample
    private val demo: DemoControls = sample

    // Ajustes chooses between the demo player and the real adapter from :media (persisted).
    private val switchablePlayer = SwitchableMediaPlayerControl(
        sample = FakeMediaPlayerControl(),
        real = MediaPlayerControlFactory.create(context),
        store = PreferencesMusicModeStore(context),
        scope = scope,
    )
    private val player: MediaPlayerControl = switchablePlayer
    private val playback = PlaybackActions(player)

    val shell = ShellPresenter(data, player, format, clock, ticks, scope)
    val home = HomePresenter(data, playback, format, clock, ticks, scope)
    val driving = DrivingPresenter(data, demo, playback, format, scope)
    val music = MusicPresenter(playback, scope)
    val trips = TripsPresenter(data, format, clock, ticks, scope)
    val parked = ParkedPresenter(data, format, clock, ticks, scope)

    // Mantenimiento y Gastos: muestras propias, y la confirmación de "auto estacionado" compartida por los formularios.
    private val ledgerFormat = LedgerFormat(format)
    private val formGate = FormGate(data.status, clock, scope)
    private val sampleExpenses = SampleExpenseData(clock)
    private val sampleMaintenance = SampleMaintenanceData(clock, sampleExpenses)
    val maintenance = MaintenancePresenter(sampleMaintenance, data.profile, formGate, clock, ledgerFormat, ticks, scope)
    val expenses = ExpensesPresenter(sampleExpenses, data.profile, formGate, clock, ledgerFormat, ticks, scope)

    val settings = SettingsPresenter(data, demo, SampleSettings(clock), switchablePlayer, AndroidDateTimeSettings(context), format, clock, scope)
}
