package app.guantera.presentation.onboarding

import app.guantera.presentation.port.BackupActions
import app.guantera.presentation.port.FirstUseStore
import app.guantera.presentation.port.FormOutcome
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.port.ImportOutcome
import app.guantera.presentation.real.reportUnlessCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** P0: one step per screen (especificación, P0). */
enum class FirstUseStep { Location, Odometer, Currency }

sealed interface FirstUseDialog {
    /** "¿El auto está estacionado?" after a step came back needing it. */
    data object AskStopped : FirstUseDialog
    data class SaveFailed(val diskFull: Boolean) : FirstUseDialog
    data object CarMoving : FirstUseDialog
    data object Importing : FirstUseDialog
    data class ImportFailed(val outcome: ImportOutcome) : FirstUseDialog
}

data class FirstUseViewState(
    val step: FirstUseStep,
    val gps: GpsStatus,
    val odometerInvalid: Boolean,
    val dialog: FirstUseDialog?,
)

class OnboardingPresenter(
    data: GuanteraData,
    private val store: FirstUseStore,
    private val backups: BackupActions,
    private val scope: CoroutineScope,
) {
    private data class Ui(
        val step: FirstUseStep = FirstUseStep.Location,
        val odometerInvalid: Boolean = false,
        val dialog: FirstUseDialog? = null,
    )

    private val ui = MutableStateFlow(Ui())
    private var retry: (() -> Unit)? = null

    /** True while P0 has to be shown: only when first use was never completed. */
    val visible: StateFlow<Boolean> = store.isComplete.map { it == false }
        .stateIn(scope, SharingStarted.Eagerly, store.isComplete.value == false)

    val state: StateFlow<FirstUseViewState> = combine(ui, data.status) { current, status ->
        FirstUseViewState(current.step, status.gps, current.odometerInvalid, current.dialog)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        FirstUseViewState(FirstUseStep.Location, data.status.value.gps, false, null),
    )

    /** "Saltear" and "Seguir" on the location step: tracking just stays off until the permission is granted. */
    fun continueFromLocation() = ui.update { it.copy(step = FirstUseStep.Odometer) }

    /** "Saltear" on the odometer: items by km stay without data and trips work the same (R14). */
    fun skipOdometer() = ui.update { it.copy(step = FirstUseStep.Currency, odometerInvalid = false) }

    fun saveOdometer(input: String) {
        val km = parseKm(input)
        if (km == null) {
            ui.update { it.copy(odometerInvalid = true) }
            return
        }
        ui.update { it.copy(odometerInvalid = false) }
        write({ ui.update { it.copy(step = FirstUseStep.Currency) } }) { store.saveOdometer(km) }
    }

    /** "Guardar" on the last step: the currency, the catalog and the mark that first use is over. */
    fun finish(currency: String) {
        val chosen = currency.trim().ifEmpty { DEFAULT_CURRENCY }
        write({}) {
            val steps = listOf<suspend () -> FormOutcome>(
                { store.saveCurrency(chosen) },
                { store.loadCatalog() },
                { store.markComplete() },
            )
            var outcome: FormOutcome = FormOutcome.Saved
            for (step in steps) {
                outcome = step()
                if (outcome != FormOutcome.Saved) break
            }
            outcome
        }
    }

    /** "Importar desde pendrive": a radio that was reset can bring its data back (resiliencia.md). */
    fun importFromDrive() {
        ui.update { it.copy(dialog = FirstUseDialog.Importing) }
        scope.launch {
            when (val result = backups.importFromDrive()) {
                is ImportOutcome.Done -> write({ ui.update { it.copy(dialog = null) } }) { store.markComplete() }
                else -> ui.update { it.copy(dialog = FirstUseDialog.ImportFailed(result)) }
            }
        }
    }

    fun answerStopped(parked: Boolean) {
        val waiting = retry
        retry = null
        ui.update { it.copy(dialog = null) }
        if (parked) {
            store.confirmStopped()
            waiting?.invoke()
        }
    }

    fun retryWrite() {
        val waiting = retry
        retry = null
        ui.update { it.copy(dialog = null) }
        waiting?.invoke()
    }

    fun dismissDialog() {
        retry = null
        ui.update { it.copy(dialog = null) }
    }

    fun back(): Boolean {
        val previous = when (ui.value.step) {
            FirstUseStep.Location -> return false
            FirstUseStep.Odometer -> FirstUseStep.Location
            FirstUseStep.Currency -> FirstUseStep.Odometer
        }
        ui.update { it.copy(step = previous, dialog = null) }
        return true
    }

    private fun write(onSaved: () -> Unit, change: suspend () -> FormOutcome) {
        scope.launch {
            val outcome = try {
                change()
            } catch (e: Exception) {
                reportUnlessCancelled("primer uso", e)
                FormOutcome.StorageFailed
            }
            when (outcome) {
                FormOutcome.Saved -> onSaved()
                FormOutcome.NeedsConfirmation -> asking(FirstUseDialog.AskStopped) { write(onSaved, change) }
                FormOutcome.CarMoving -> asking(FirstUseDialog.CarMoving) { }
                FormOutcome.StorageFull -> asking(FirstUseDialog.SaveFailed(diskFull = true)) { write(onSaved, change) }
                FormOutcome.Rejected, FormOutcome.StorageFailed ->
                    asking(FirstUseDialog.SaveFailed(diskFull = false)) { write(onSaved, change) }
            }
        }
    }

    private fun asking(dialog: FirstUseDialog, again: () -> Unit) {
        retry = again
        ui.update { it.copy(dialog = dialog) }
    }

    private fun parseKm(input: String): Int? {
        val km = input.trim().replace(".", "").replace(" ", "").toIntOrNull() ?: return null
        return km.takeIf { it in 0..MAX_ODOMETER }
    }

    private fun <T> MutableStateFlow<T>.update(change: (T) -> T) { value = change(value) }

    companion object {
        const val DEFAULT_CURRENCY = "$"
        const val MAX_ODOMETER = 9_999_999
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
