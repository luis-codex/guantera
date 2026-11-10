package app.guantera.ui.screens

import android.content.Context
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import app.guantera.R
import app.guantera.presentation.onboarding.FirstUseDialog
import app.guantera.presentation.onboarding.FirstUseStep
import app.guantera.presentation.onboarding.FirstUseViewState
import app.guantera.presentation.onboarding.OnboardingPresenter
import app.guantera.presentation.port.GpsStatus
import app.guantera.ui.Screen
import app.guantera.ui.components.StepButton
import app.guantera.ui.components.StepPanelView
import app.guantera.ui.openSystemSettings

/** P0: permission, odometer and currency, one step per screen. */
class OnboardingScreen(
    parent: ViewGroup,
    private val presenter: OnboardingPresenter,
    private val onGrantLocation: () -> Unit,
    private val onKeyboardClosed: () -> Unit,
) : Screen() {
    private val context: Context = parent.context
    private val panel = StepPanelView(context)
    override val view = panel
    private var shownStep: FirstUseStep? = null
    private var locationSettingsFailed = false

    override suspend fun observe() = presenter.state.collect(::render)

    override fun onBack(): Boolean = presenter.back()

    private fun text(id: Int): CharSequence = context.getString(id)

    private fun render(state: FirstUseViewState) {
        if (state.dialog != null) {
            renderDialog(state.dialog)
            return
        }
        if (state.step != shownStep) {
            shownStep = state.step
            panel.hideInput()
        }
        when (state.step) {
            FirstUseStep.Location -> renderLocation(state)
            FirstUseStep.Odometer -> renderOdometer(state)
            FirstUseStep.Currency -> renderCurrency()
        }
    }

    private fun renderLocation(state: FirstUseViewState) {
        val granted = state.gps != GpsStatus.NoPermission
        val gpsOff = state.gps == GpsStatus.Off
        val message = buildString {
            append(context.getString(R.string.fu_location_message))
            if (granted) append("\n\n").append(context.getString(R.string.fu_location_granted))
        }
        panel.show(text(R.string.fu_kicker), text(R.string.fu_location_title), message)
        panel.hideInput()
        // The GPS warning only matters once the permission is there: without it there is nothing to switch on.
        panel.setError(if (granted && gpsOff) context.getString(R.string.fu_gps_off) else null)
        val buttons = mutableListOf<StepButton>()
        when {
            !granted -> {
                buttons += StepButton(text(R.string.fu_grant), primary = true) { onGrantLocation() }
                buttons += StepButton(text(R.string.fu_skip), primary = false) { presenter.continueFromLocation() }
            }
            gpsOff -> {
                buttons += StepButton(text(R.string.fu_open_location), primary = true) {
                    if (!context.openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) locationSettingsFailed = true
                }
                buttons += StepButton(text(R.string.fu_next), primary = false) { presenter.continueFromLocation() }
            }
            else -> buttons += StepButton(text(R.string.fu_next), primary = true) { presenter.continueFromLocation() }
        }
        buttons += StepButton(text(R.string.fu_import), primary = false) { presenter.importFromDrive() }
        panel.setButtons(buttons)
    }

    private fun renderOdometer(state: FirstUseViewState) {
        panel.show(text(R.string.fu_kicker), text(R.string.fu_odometer_title), text(R.string.fu_odometer_message))
        panel.showInput(InputType.TYPE_CLASS_NUMBER, "")
        panel.setError(if (state.odometerInvalid) context.getString(R.string.prompt_odometer_invalid) else null)
        panel.setButtons(
            listOf(
                StepButton(text(R.string.fu_save), primary = true) { presenter.saveOdometer(panel.inputText) },
                StepButton(text(R.string.fu_skip), primary = false) { presenter.skipOdometer(); onKeyboardClosed() },
                StepButton(text(R.string.fu_back), primary = false) { presenter.back() },
            ),
        )
    }

    private fun renderCurrency() {
        panel.show(text(R.string.fu_kicker), text(R.string.fu_currency_title), text(R.string.fu_currency_message), text(R.string.fu_footer))
        panel.showInput(InputType.TYPE_CLASS_TEXT, OnboardingPresenter.DEFAULT_CURRENCY)
        panel.setError(null)
        panel.setButtons(
            listOf(
                StepButton(text(R.string.fu_save), primary = true) { presenter.finish(panel.inputText); onKeyboardClosed() },
                StepButton(text(R.string.fu_back), primary = false) { presenter.back() },
            ),
        )
    }

    private fun renderDialog(dialog: FirstUseDialog) {
        panel.hideInput()
        panel.setError(null)
        val close = StepButton(text(R.string.rp_ok), primary = true) { presenter.dismissDialog() }
        when (dialog) {
            FirstUseDialog.AskStopped -> {
                panel.show(null, text(R.string.prompt_stopped_title), text(R.string.prompt_stopped_message_retry))
                panel.setButtons(
                    listOf(
                        StepButton(text(R.string.prompt_stopped_yes), primary = true) { presenter.answerStopped(true) },
                        StepButton(text(R.string.prompt_stopped_no), primary = false) { presenter.answerStopped(false) },
                    ),
                )
            }
            is FirstUseDialog.SaveFailed -> {
                panel.show(null, text(if (dialog.diskFull) R.string.settings_save_failed_full else R.string.er_save_failed), null)
                panel.setButtons(
                    listOf(
                        StepButton(text(R.string.rp_retry), primary = true) { presenter.retryWrite() },
                        StepButton(text(R.string.rp_close), primary = false) { presenter.dismissDialog() },
                    ),
                )
            }
            FirstUseDialog.CarMoving -> {
                panel.show(null, text(R.string.rp_car_moving), null)
                panel.setButtons(listOf(close))
            }
            FirstUseDialog.Importing -> {
                panel.show(null, text(R.string.rp_working), null)
                panel.setButtons(emptyList())
            }
            is FirstUseDialog.ImportFailed -> {
                panel.show(null, text(R.string.rp_import_title), context.importText(dialog.outcome))
                panel.setButtons(listOf(close))
            }
        }
    }
}
