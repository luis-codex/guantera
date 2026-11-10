package app.guantera

import app.guantera.presentation.port.DemoControls
import app.guantera.presentation.port.DrivingData
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
import app.guantera.sonda.domain.media.ControlChannel
import app.guantera.sonda.domain.media.ControlFailure
import app.guantera.sonda.domain.media.ControlResult
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
    override val isSample = true
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
    override fun confirmParked() { parkedConfirmations += 1 }
    override fun saveSpotHere(): SaveSpotResult { savedSpots += 1; return saveSpotResult }
    override fun saveSpotNote(note: String) { savedNotes += note }
}

class FakeDemo : DemoControls {
    var started = 0
    var stopped = 0
    override fun startSimulatedTrip() { started += 1 }
    override fun stopSimulatedTrip() { stopped += 1 }
}

class FakePlayer(initial: MediaSnapshot = MediaSnapshot.Empty) : MediaPlayerControl {
    override val state = MutableStateFlow(initial)
    val commands = mutableListOf<MediaCommand>()
    val volumes = mutableListOf<VolumeCommand>()
    var failing = false
    var startCalls = 0
    var stopCalls = 0
    var refreshCalls = 0

    override fun start() { startCalls += 1 }
    override fun stop() { stopCalls += 1 }
    override fun refresh() { refreshCalls += 1 }

    override fun send(command: MediaCommand): ControlResult {
        commands += command
        return result(ControlChannel.Session)
    }

    override fun adjustVolume(command: VolumeCommand): ControlResult {
        volumes += command
        return result(ControlChannel.Volume)
    }

    private fun result(channel: ControlChannel): ControlResult =
        if (failing) ControlResult.Failed(ControlFailure.NoChannelAvailable) else ControlResult.Done(channel)
}
