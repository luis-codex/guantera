package app.guantera

import android.content.Context
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
import app.guantera.presentation.real.RealData
import app.guantera.presentation.settings.SettingsPresenter
import app.guantera.presentation.shell.ShellPresenter
import app.guantera.presentation.trips.TripsPresenter
import app.guantera.platform.AndroidAlertPublisher
import app.guantera.platform.AndroidClock
import app.guantera.platform.AndroidLocationSource
import app.guantera.platform.AndroidTrackingHealthSource
import app.guantera.system.AndroidDateTimeSettings
import app.guantera.storage.StorageComponents
import app.guantera.presentation.onboarding.OnboardingPresenter
import app.guantera.presentation.real.backupActionsOf
import app.guantera.tracking.TrackingGraph
import app.guantera.sonda.media.MediaPlayerControlFactory
import app.guantera.sonda.domain.media.MediaPlayerControl
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Composition root: the only place that knows which implementations the app runs on. */
class AppContainer(context: Context, private val storage: StorageComponents) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: Clock = DeviceClock
    private val format = DisplayFormat()
    private val ticks = minuteTicks(clock)

    private val health = AndroidTrackingHealthSource(context.applicationContext)
    private val graph = TrackingGraph(
        clock = AndroidClock(context.applicationContext),
        locationSource = AndroidLocationSource(context.applicationContext) { provider, enabled -> health.onProviderStateChanged(provider, enabled) },
        healthSource = health,
        alertPublisher = AndroidAlertPublisher(context.applicationContext),
        tripRepo = storage.trips,
        parkingRepo = storage.parking,
        settingsRepo = storage.settings,
        odometerRepo = storage.odometer,
        maintenanceRepo = storage.maintenance,
        expenseRepo = storage.expenses,
        transactions = storage.transactions,
        backupStore = storage.backups,
    )
    private val backups = backupActionsOf(graph, storage.externalBackups, storage.backups)
    private val data = RealData(graph, scope, backups)

    /** Lets the screens tell the platform that the permission changed, so the status and banners follow. */
    fun onLocationPermissionChanged() = health.refresh()

    init { TrackingGraph.setInstance(graph) }

    // The real adapter from :media is the only player; nothing about the music is invented.
    private val player: MediaPlayerControl = MediaPlayerControlFactory.create(context)
    private val playback = PlaybackActions(player, scope)

    val onboarding = OnboardingPresenter(data, data, backups, scope)
    val shell = ShellPresenter(data, player, format, clock, ticks, scope, onboarding.visible)
    val home = HomePresenter(data, playback, format, clock, ticks, scope)
    val driving = DrivingPresenter(data, playback, format, scope)
    val music = MusicPresenter(playback, scope)
    val trips = TripsPresenter(data, format, clock, ticks, scope)
    val parked = ParkedPresenter(data, format, clock, ticks, scope)

    // Every list and form is fed by the domain use cases through the real adapter.
    private val ledgerFormat = LedgerFormat(format)
    private val formGate = FormGate(data.status, clock, scope, data::confirmStopped)
    val maintenance = MaintenancePresenter(data.maintenanceData, data.profile, formGate, clock, ledgerFormat, ticks, scope)
    val expenses = ExpensesPresenter(data.expenseData, data.profile, formGate, clock, ledgerFormat, ticks, scope)

    val settings = SettingsPresenter(data, data, player.diagnostics, AndroidDateTimeSettings(context), format, clock, scope)
}
