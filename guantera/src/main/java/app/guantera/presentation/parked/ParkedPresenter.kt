package app.guantera.presentation.parked

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.ParkingDetail
import app.guantera.presentation.port.SaveSpotResult
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ParkedContent {
    data object Empty : ParkedContent
    data class Saved(
        val timeText: String,
        val ageMinutes: Long,
        val coordinatesText: String,
        val accuracyMeters: Int,
        val note: String?,
        val isLastSignalPoint: Boolean,
        val mapUrl: String,
    ) : ParkedContent
}

enum class ParkedError { NoFix, Moving }

/** What the user is doing on the screen. Kept here so driving mode can interrupt it and return. */
private data class Interaction(
    val editing: Boolean = false,
    val draft: String = "",
    val error: ParkedError? = null,
    val pending: Pending? = null,
)

private enum class Pending { SaveHere, EditNote }

data class ParkedViewState(
    val content: ParkedContent,
    val isEditingNote: Boolean,
    val draft: String,
    val error: ParkedError?,
    /** True while "¿El auto está estacionado?" waits for an answer (spec section 5). */
    val askIfStopped: Boolean,
)

class ParkedPresenter(
    private val data: GuanteraData,
    private val format: DisplayFormat,
    private val clock: Clock,
    ticks: Flow<Long>,
    scope: CoroutineScope,
) {
    private val interaction = MutableStateFlow(Interaction())
    private var confirmedUntil = 0L

    val state: StateFlow<ParkedViewState> = combine(data.parkingSpot, ticks, interaction) { spot, now, current ->
        reduce(spot, now, current)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        reduce(data.parkingSpot.value, clock.nowMillis(), Interaction()),
    )

    init {
        // R9: the manual confirmation ends as soon as the car moves.
        scope.launch {
            data.status.collect { if (it.motion == MotionStatus.Moving) confirmedUntil = 0L }
        }
    }

    fun saveHere() = whenStopped(Pending.SaveHere)

    fun editNote() = whenStopped(Pending.EditNote)

    fun confirmStopped() {
        confirmedUntil = clock.elapsedRealtimeMillis() + CONFIRMATION_MILLIS
        val pending = interaction.value.pending ?: return
        interaction.value = interaction.value.copy(pending = null)
        run(pending)
    }

    fun denyStopped() { interaction.value = interaction.value.copy(pending = null) }

    fun onDraftChanged(text: String) {
        if (interaction.value.editing) interaction.value = interaction.value.copy(draft = text)
    }

    fun saveNote() {
        data.saveSpotNote(interaction.value.draft)
        interaction.value = Interaction()
    }

    /** Returns true if a note was being edited, so that the back key only cancels the edit. */
    fun cancelNote(): Boolean {
        val wasEditing = interaction.value.editing
        interaction.value = Interaction()
        return wasEditing
    }

    private fun whenStopped(action: Pending) {
        if (needsConfirmation()) {
            interaction.value = interaction.value.copy(pending = action, error = null)
        } else {
            run(action)
        }
    }

    private fun needsConfirmation() =
        data.status.value.motion == MotionStatus.Unknown && clock.elapsedRealtimeMillis() >= confirmedUntil

    private fun run(action: Pending) = when (action) {
        Pending.SaveHere -> saveSpot()
        Pending.EditNote -> {
            interaction.value = Interaction(editing = true, draft = data.parkingSpot.value?.note.orEmpty())
        }
    }

    private fun saveSpot() {
        val error = when (data.saveSpotHere()) {
            SaveSpotResult.Saved -> null
            SaveSpotResult.NoValidFix -> ParkedError.NoFix
            SaveSpotResult.CarMoving -> ParkedError.Moving
        }
        interaction.value = Interaction(error = error)
    }

    private fun reduce(spot: ParkingDetail?, now: Long, current: Interaction) = ParkedViewState(
        content = spot?.let { toContent(it, now) } ?: ParkedContent.Empty,
        isEditingNote = current.editing,
        draft = current.draft,
        error = current.error,
        askIfStopped = current.pending != null,
    )

    private fun toContent(spot: ParkingDetail, now: Long): ParkedContent.Saved {
        val coordinates = format.coordinates(spot.location.latitude, spot.location.longitude)
        return ParkedContent.Saved(
            timeText = format.clock(spot.savedAtMillis),
            ageMinutes = ((now - spot.savedAtMillis) / MINUTE_MILLIS).coerceAtLeast(0),
            coordinatesText = coordinates,
            accuracyMeters = spot.accuracyMeters,
            note = spot.note,
            isLastSignalPoint = spot.isLastSignalPoint,
            mapUrl = MAP_URL + coordinates.replace(" ", ""),
        )
    }

    private companion object {
        /** Google's documented cross-platform Maps URL: opens the map app or the browser on any phone. */
        const val MAP_URL = "https://www.google.com/maps/search/?api=1&query="
        const val MINUTE_MILLIS = 60_000L
        const val CONFIRMATION_MILLIS = 10 * 60_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
