package app.guantera

import app.guantera.presentation.port.DrivingData
import app.guantera.presentation.port.FormOutcome
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.port.HomeData
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.ParkingDetail
import app.guantera.presentation.port.SaveSpotResult
import app.guantera.presentation.port.TodaySummary
import app.guantera.presentation.port.TripRecord
import app.guantera.presentation.port.VehicleProfile
import app.guantera.presentation.port.VehicleStatus
import app.guantera.sonda.domain.media.ControlDiagnostic
import app.guantera.sonda.domain.media.ControlFailure
import app.guantera.sonda.domain.media.ControlKind
import app.guantera.sonda.domain.media.ControlResult
import app.guantera.sonda.domain.media.ControlRoute
import app.guantera.sonda.domain.media.Effect
import app.guantera.sonda.domain.media.MediaCommand
import app.guantera.sonda.domain.media.MediaPlayerControl
import app.guantera.sonda.domain.media.MediaSnapshot
import app.guantera.sonda.domain.media.VolumeCommand
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.flow.MutableStateFlow

class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis() = now
    override fun elapsedRealtimeMillis() = now
    override fun uptimeMillis() = now
}

class FakeData : GuanteraData {
    override val status = MutableStateFlow(VehicleStatus(GpsStatus.Good, MotionStatus.Stopped))
    override val home = MutableStateFlow(HomeData(TodaySummary(0.0, 0), null, null, emptyList()))
    override val profile = MutableStateFlow(VehicleProfile("Auto", 1_000, "$"))
    override val driving = MutableStateFlow<DrivingData?>(null)
    override val trips = MutableStateFlow<List<TripRecord>>(emptyList())
    override val parkingSpot = MutableStateFlow<ParkingDetail?>(null)
    var parkedConfirmations = 0
    var saveSpotResult = SaveSpotResult.Saved
    var savedSpots = 0
    val savedNotes = mutableListOf<String>()
    var stoppedConfirmations = 0
    var gapDismissals = 0
    var noteOutcome: FormOutcome = FormOutcome.Saved
    override fun confirmParked() { parkedConfirmations += 1 }
    override fun confirmStopped() { stoppedConfirmations += 1 }
    override fun dismissRecordingGap() { gapDismissals += 1 }
    override suspend fun saveSpotHere(): SaveSpotResult { savedSpots += 1; return saveSpotResult }
    override suspend fun saveSpotNote(note: String): FormOutcome { savedNotes += note; return noteOutcome }
}

/** Test-only player. Records commands; with [failing] every command reports that it had no effect. */
class FakePlayer(initial: MediaSnapshot = MediaSnapshot.Empty) : MediaPlayerControl {
    override val state = MutableStateFlow(initial)
    override val diagnostics = MutableStateFlow(ControlDiagnostic.Initial)
    val commands = mutableListOf<MediaCommand>()
    val volumes = mutableListOf<VolumeCommand>()
    var failing = false
    var startCalls = 0
    var stopCalls = 0
    var refreshCalls = 0

    override fun start() { startCalls += 1 }
    override fun stop() { stopCalls += 1 }
    override fun refresh() { refreshCalls += 1 }

    override suspend fun send(command: MediaCommand): ControlResult {
        commands += command
        return result(
            when (command) {
                MediaCommand.PlayPause -> ControlKind.PlayPause
                MediaCommand.Next -> ControlKind.Next
                MediaCommand.Previous -> ControlKind.Previous
            },
        )
    }

    override suspend fun adjustVolume(command: VolumeCommand): ControlResult {
        volumes += command
        return result(
            when (command) {
                VolumeCommand.Up -> ControlKind.VolumeUp
                VolumeCommand.Down -> ControlKind.VolumeDown
                VolumeCommand.ToggleMute -> ControlKind.Mute
            },
        )
    }

    private fun result(kind: ControlKind): ControlResult =
        if (failing) ControlResult.Failed(kind, ControlFailure.NoEffect)
        else ControlResult.Done(kind, ControlRoute.Transport, Effect.Confirmed)
}
