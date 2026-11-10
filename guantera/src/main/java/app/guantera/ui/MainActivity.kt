package app.guantera.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.guantera.AppContainer
import app.guantera.GuanteraApp
import app.guantera.databinding.ActivityMainBinding
import app.guantera.presentation.shell.Destination
import app.guantera.presentation.shell.ShellViewState
import app.guantera.ui.screens.DrivingScreen
import app.guantera.ui.screens.ExpensesScreen
import app.guantera.ui.screens.HomeScreen
import app.guantera.ui.screens.MaintenanceScreen
import app.guantera.ui.screens.MusicScreen
import app.guantera.ui.screens.ParkedScreen
import app.guantera.ui.screens.SettingsScreen
import app.guantera.ui.screens.TripsScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The shell: top bar, rail and whichever screen the presenter says is current. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var container: AppContainer
    private var shownKey: Any? = null
    private var screenJob: Job? = null
    private var currentScreen: Screen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as GuanteraApp).container
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureWindow()

        binding.rail.setOnSelect(::onRailSelected)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentScreen?.onBack() == true || container.shell.onBack()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { container.shell.state.collect(::render) }
        }
    }

    override fun onStart() {
        super.onStart()
        container.shell.onVisible()
    }

    override fun onStop() {
        container.shell.onHidden()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // If the radio refuses to hide its bars, the insets keep the content clear of them.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Showing the keyboard clears the hide flags, and closing it brings no window event to restore them from. */
    private fun hideSystemBarsAfterKeyboard() {
        binding.root.postDelayed(::hideSystemBars, KEYBOARD_CLOSE_MILLIS)
    }

    private fun render(state: ShellViewState) {
        binding.topBar.render(state)
        binding.rail.isVisible = !state.isDriving
        binding.rail.select(state.destination)
        val key: Any = if (state.isDriving) DRIVING_KEY else state.destination
        if (key != shownKey) {
            shownKey = key
            if (!state.isDriving) currentScreen?.onLeave()
            show(if (state.isDriving) DrivingScreen(binding.content, container.driving) else screenFor(state.destination))
        }
    }

    private fun show(screen: Screen) {
        screenJob?.cancel()
        currentScreen = screen
        binding.content.removeAllViews()
        binding.content.addView(screen.view)
        screenJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { screen.observe() }
        }
    }

    /** Tapping the current destination again backs out of any sub-view, as the back key does. */
    private fun onRailSelected(target: Destination) {
        if (target == container.shell.state.value.destination) currentScreen?.onBack() else container.shell.navigate(target)
    }

    private fun screenFor(destination: Destination): Screen {
        val parent = binding.content
        val navigator = Navigator(container.shell::navigate)
        return when (destination) {
            Destination.Home -> HomeScreen(parent, container.home, navigator)
            Destination.Trips -> TripsScreen(parent, container.trips)
            Destination.Maintenance -> MaintenanceScreen(parent, container.maintenance, ::hideSystemBarsAfterKeyboard)
            Destination.Expenses -> ExpensesScreen(parent, container.expenses, ::hideSystemBarsAfterKeyboard)
            Destination.Music -> MusicScreen(parent, container.music)
            Destination.Parked -> ParkedScreen(parent, container.parked, ::hideSystemBarsAfterKeyboard)
            Destination.Settings -> SettingsScreen(parent, container.settings, ::hideSystemBarsAfterKeyboard)
        }
    }

    private companion object {
        val DRIVING_KEY = Any()
        const val KEYBOARD_CLOSE_MILLIS = 300L
    }
}
