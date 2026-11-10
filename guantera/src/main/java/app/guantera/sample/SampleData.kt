package app.guantera.sample

import app.guantera.presentation.port.AppNotice
import app.guantera.presentation.port.DemoControls
import app.guantera.presentation.port.DrivingData
import app.guantera.presentation.port.GeoPoint
import app.guantera.presentation.port.GuanteraData
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.HomeData
import app.guantera.presentation.port.MaintenanceAlert
import app.guantera.presentation.port.MaintenanceLevel
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.ParkedSpot
import app.guantera.presentation.port.ParkingDetail
import app.guantera.presentation.port.Remaining
import app.guantera.presentation.port.SaveSpotResult
import app.guantera.presentation.port.TodaySummary
import app.guantera.presentation.port.TripClosure
import app.guantera.presentation.port.TripRecord
import app.guantera.presentation.port.VehicleProfile
import app.guantera.presentation.port.VehicleStatus
import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone

/** An invented Argentine car, and the simulated trip that makes driving mode visible. */
class SampleData(
    private val clock: Clock,
    private val scope: CoroutineScope,
    zone: TimeZone = TimeZone.getDefault(),
) : GuanteraData, DemoControls {
    override val isSample = true

    private val stopped = VehicleStatus(GpsStatus.Good, MotionStatus.Stopped)
    private val urgent = MaintenanceAlert("Cambio de aceite", MaintenanceLevel.Overdue, Remaining.Km(350))

    private val _status = MutableStateFlow(stopped)
    private val _trips = MutableStateFlow(SampleTrips.build(clock.nowMillis(), zone))
    private val _parking = MutableStateFlow<ParkingDetail?>(
        ParkingDetail(
            savedAtMillis = clock.nowMillis() - SampleTrips.parkedAgoMillis(clock.nowMillis(), zone),
            location = SampleTrips.garage,
            accuracyMeters = 24,
            note = "Piso 2, columna B",
            isLastSignalPoint = true,
        ),
    )
    private val _home = MutableStateFlow(
        HomeData(
            today = TodaySummary(SampleTrips.TODAY_KM, SampleTrips.TODAY_TRIPS),
            urgentAlert = urgent,
            parked = _parking.value?.toSummary(),
            notices = listOf(AppNotice.ExportOverdue),
        ),
    )
    private val _profile = MutableStateFlow(VehicleProfile("Fiat Cronos 1.3", 48_250, "$"))
    private val _driving = MutableStateFlow<DrivingData?>(null)

    override val status: StateFlow<VehicleStatus> = _status
    override val home: StateFlow<HomeData> = _home
    override val profile: StateFlow<VehicleProfile> = _profile
    override val driving: StateFlow<DrivingData?> = _driving
    override val trips: StateFlow<List<TripRecord>> = _trips
    override val parkingSpot: StateFlow<ParkingDetail?> = _parking

    private var simulation: Job? = null

    override fun startSimulatedTrip() {
        simulation?.cancel()
        _status.value = stopped.copy(motion = MotionStatus.Moving)
        _driving.value = simulatedTrip(0)
        simulation = scope.launch {
            var tick = 0L
            while (true) {
                delay(TICK_MILLIS)
                tick += 1
                _driving.value = simulatedTrip(tick)
            }
        }
    }

    override fun stopSimulatedTrip() = finishSimulation()

    override fun confirmParked() {
        val trip = _driving.value ?: return
        val now = clock.nowMillis()
        val closed = TripRecord(
            id = (_trips.value.firstOrNull()?.id ?: 0) + 1,
            startMillis = now - trip.elapsedSeconds * 1_000,
            endMillis = now,
            distanceKm = trip.distanceKm,
            start = SampleTrips.garage,
            end = CURRENT_POSITION,
            closure = TripClosure.Manual,
        )
        _trips.update { listOf(closed) + it }
        _home.update { it.copy(today = TodaySummary(it.today.kilometers + trip.distanceKm, it.today.tripCount + 1)) }
        setSpot(ParkingDetail(now, CURRENT_POSITION, CURRENT_ACCURACY_METERS, note = null, isLastSignalPoint = false))
        finishSimulation()
    }

    override fun saveSpotHere(): SaveSpotResult {
        if (_driving.value != null) return SaveSpotResult.CarMoving
        if (_status.value.gps != GpsStatus.Good) return SaveSpotResult.NoValidFix
        setSpot(ParkingDetail(clock.nowMillis(), CURRENT_POSITION, CURRENT_ACCURACY_METERS, note = null, isLastSignalPoint = false))
        return SaveSpotResult.Saved
    }

    override fun saveSpotNote(note: String) {
        val current = _parking.value ?: return
        setSpot(current.copy(note = note.trim().ifBlank { null }))
    }

    private fun setSpot(spot: ParkingDetail) {
        _parking.value = spot
        _home.update { it.copy(parked = spot.toSummary()) }
    }

    private fun ParkingDetail.toSummary() = ParkedSpot(savedAtMillis, note)

    private fun finishSimulation() {
        simulation?.cancel()
        simulation = null
        _driving.value = null
        _status.value = stopped
    }

    private fun simulatedTrip(tick: Long): DrivingData {
        val movingTicks = tick.coerceAtMost(MOVING_TICKS)
        return DrivingData(
            distanceKm = START_KM + movingTicks / TICKS_PER_KM,
            elapsedSeconds = START_SECONDS + tick,
            stoppingSeconds = (tick - MOVING_TICKS).takeIf { it >= 0 },
            urgentAlert = urgent,
            isSimulated = true,
        )
    }

    private companion object {
        val CURRENT_POSITION = GeoPoint(-34.57340, -58.44410)
        const val CURRENT_ACCURACY_METERS = 6
        const val TICK_MILLIS = 1_000L
        const val MOVING_TICKS = 45L
        const val TICKS_PER_KM = 30.0
        const val START_KM = 12.0
        const val START_SECONDS = 18 * 60L
    }
}
