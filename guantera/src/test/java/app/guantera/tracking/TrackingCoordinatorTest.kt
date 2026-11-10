package app.guantera.tracking

import app.guantera.domain.maintenance.DataOrigin
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.maintenance.MaintenanceStatus
import app.guantera.domain.motion.DrivingState
import app.guantera.domain.motion.TrackingHealth
import app.guantera.domain.port.AlertPublisher
import app.guantera.domain.port.Clock
import app.guantera.domain.port.LocationSource
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.port.TrackingHealthSource
import app.guantera.domain.port.UtcWindow
import app.guantera.domain.trip.GeoPoint
import app.guantera.domain.trip.LocationFix
import app.guantera.domain.trip.TripPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingCoordinatorTest {

    private class TestClock(
        var utc: Long = 1_700_000_000_000L,
        var mono: Long = 10_000L,
        var boot: Int? = 1,
    ) : Clock {
        override fun utcMs(): Long = utc
        override fun monoMs(): Long = mono
        override fun bootCount(): Int? = boot
        override fun utcOffsetMs(atUtcMs: Long): Int = 0

        fun advance(ms: Long) {
            utc += ms
            mono += ms
        }
    }

    private class TestLocationSource : LocationSource {
        val flow = MutableSharedFlow<LocationFix>(extraBufferCapacity = 64)
        override val fixes: Flow<LocationFix> = flow

        fun emit(fix: LocationFix) {
            flow.tryEmit(fix)
        }
    }

    private class TestHealthSource(
        initial: TrackingHealth = TrackingHealth(hasPermission = true, gpsEnabled = true),
    ) : TrackingHealthSource {
        override val health = MutableStateFlow(initial)

        fun update(hasPermission: Boolean, gpsEnabled: Boolean) {
            health.value = TrackingHealth(hasPermission, gpsEnabled)
        }
    }

    private class TestAlertPublisher : AlertPublisher {
        val published = mutableListOf<app.guantera.domain.maintenance.MaintenanceAlert?>()
        var throwOnPublish = false

        override suspend fun publish(alert: app.guantera.domain.maintenance.MaintenanceAlert?) {
            if (throwOnPublish) throw RuntimeException("Simulated publisher crash")
            published += alert
        }
    }

    private fun buildTestGraph(
        clock: TestClock,
        locationSource: LocationSource,
        healthSource: TrackingHealthSource,
        alertPublisher: AlertPublisher,
        inMemoryDb: InMemoryTrackingDatabase = InMemoryTrackingDatabase(),
    ): TrackingGraph = TrackingGraph(
        clock = clock,
        locationSource = locationSource,
        healthSource = healthSource,
        alertPublisher = alertPublisher,
        tripRepo = inMemoryDb.tripRepo,
        parkingRepo = inMemoryDb.parkingRepo,
        settingsRepo = inMemoryDb.settingsRepo,
        odometerRepo = inMemoryDb.odometerRepo,
        maintenanceRepo = inMemoryDb.maintenanceRepo,
        expenseRepo = inMemoryDb.expenseRepo,
        transactions = inMemoryDb.transactions,
        backupStore = inMemoryDb.backupStore,
    )

    private fun fixAt(clock: TestClock, lat: Double, lon: Double, speed: Double? = 10.0, accuracy: Double? = 5.0) =
        LocationFix(
            point = GeoPoint(lat, lon),
            speedMps = speed,
            accuracyMeters = accuracy,
            utcMs = clock.utcMs(),
            monoMs = clock.monoMs(),
        )

    @Test(timeout = 5000)
    fun tickEverySecondWithoutFixesAdvancesTimeAndFiresTimeouts() = runTest {
        val clock = TestClock()
        val location = TestLocationSource()
        val health = TestHealthSource()
        val publisher = TestAlertPublisher()
        val graph = buildTestGraph(clock, location, health, publisher)

        val coordinator = TrackingCoordinator(graph, this, autoTick = false)
        coordinator.start()
        runCurrent()

        try {
            // Estado inicial en reposo
            assertEquals(TripPhase.Parked, graph.engine.view.value.phase)

            // Llega un punto que arranca el vehículo hacia STARTING (velocidad 10 m/s >= 1.4 m/s)
            location.emit(fixAt(clock, 0.0, 0.0, speed = 10.0))
            runCurrent()
            assertTrue(graph.engine.view.value.phase is TripPhase.Starting)

            // Ahora avanzamos ticks manualmente sin fixes durante 16 segundos
            // START_TIMEOUT_MS es 15_000 ms.
            repeat(16) {
                clock.advance(1000L)
                coordinator.tick()
            }

            // El timeout de STARTING debe haber vencido sin ningún fix, volviendo a Parked
            assertEquals(TripPhase.Parked, graph.engine.view.value.phase)
        } finally {
            coordinator.stop()
        }
    }

    @Test(timeout = 5000)
    fun suspensionAndWakeRecoveryClosesTripAfterLongGap() = runTest {
        val clock = TestClock()
        val location = TestLocationSource()
        val health = TestHealthSource()
        val publisher = TestAlertPublisher()
        val db = InMemoryTrackingDatabase()
        val graph = buildTestGraph(clock, location, health, publisher, db)

        var rearmCalled = 0
        val coordinator = TrackingCoordinator(
            graph,
            this,
            tickIntervalMs = 1000L,
            suspensionThresholdMs = 5000L,
            autoTick = false,
            onRearmNeeded = { rearmCalled++ },
        )
        coordinator.start()
        runCurrent()

        try {
            // Iniciar un viaje real: fix 1 (Starting), fix 2 a > 10m (InTrip)
            location.emit(fixAt(clock, 0.0, 0.0, speed = 10.0))
            runCurrent()

            clock.advance(1000L)
            location.emit(fixAt(clock, 0.0003, 0.0, speed = 10.0))
            runCurrent()

            assertTrue(graph.engine.view.value.phase is TripPhase.InTrip)

            // Guardar heartbeat
            clock.advance(10_000L)
            location.emit(fixAt(clock, 0.003, 0.0, speed = 10.0))
            runCurrent()
            assertNotNull(db.tripRepo.activeTrip())

            // Simular suspensión por 200 segundos (mayor a STOP_TIMEOUT_MS = 180s)
            clock.advance(200_000L)

            // Al despertar
            coordinator.onWakeBroadcastReceived()
            runCurrent()

            // Se debió solicitar rearm del GPS
            assertEquals(1, rearmCalled)

            // El viaje activo debió cerrarse por haberse superado el timeout de parada
            assertNull(db.tripRepo.activeTrip())
            assertEquals(TripPhase.Parked, graph.engine.view.value.phase)

            // El viaje cerrado está persistido en el repositorio
            val closedTrips = db.tripRepo.closed(UtcWindow(null, null))
            assertEquals(1, closedTrips.size)
            assertTrue(closedTrips.first().distanceMeters > 200.0)
        } finally {
            coordinator.stop()
        }
    }

    @Test(timeout = 5000)
    fun gpsDisappearsAndReturns() = runTest {
        val clock = TestClock()
        val location = TestLocationSource()
        val health = TestHealthSource()
        val publisher = TestAlertPublisher()
        val db = InMemoryTrackingDatabase()
        val graph = buildTestGraph(clock, location, health, publisher, db)

        var rearmCalled = 0
        val coordinator = TrackingCoordinator(
            graph,
            this,
            tickIntervalMs = 1000L,
            autoTick = false,
            onRearmNeeded = { rearmCalled++ },
        )
        coordinator.start()
        runCurrent()

        try {
            // Vehículo detenido con fix válido
            location.emit(fixAt(clock, 0.0, 0.0, speed = 0.0))
            runCurrent()
            assertEquals(DrivingState.DETENIDO, graph.observeDrivingState.current())

            // GPS se apaga
            health.update(hasPermission = true, gpsEnabled = false)
            runCurrent()

            // El estado de conducción pasa inmediatamente a DESCONOCIDO
            assertEquals(DrivingState.DESCONOCIDO, graph.observeDrivingState.current())

            // Los ticks siguen corriendo sin problemas
            repeat(3) {
                clock.advance(1000L)
                coordinator.tick()
            }

            // GPS vuelve a encenderse
            health.update(hasPermission = true, gpsEnabled = true)
            coordinator.handleSuspensionWake()
            runCurrent()
            assertEquals(1, rearmCalled)

            // Llega un nuevo fix detenido
            clock.advance(1000L)
            location.emit(fixAt(clock, 0.0, 0.0, speed = 0.0))
            runCurrent()

            // Vuelve a DETENIDO
            assertEquals(DrivingState.DETENIDO, graph.observeDrivingState.current())
        } finally {
            coordinator.stop()
        }
    }

    @Test(timeout = 5000)
    fun retryAfterStorageFailure() = runTest {
        val clock = TestClock()
        val location = TestLocationSource()
        val health = TestHealthSource()
        val publisher = TestAlertPublisher()
        val db = InMemoryTrackingDatabase()
        val graph = buildTestGraph(clock, location, health, publisher, db)

        val coordinator = TrackingCoordinator(graph, this, autoTick = false)
        coordinator.start()
        runCurrent()

        try {
            // Simulamos fallo de disco
            db.simulatedFailure = StorageFailure.IO

            // Un fix que inicia viaje
            location.emit(fixAt(clock, 0.0, 0.0, speed = 10.0))
            clock.advance(1000L)
            location.emit(fixAt(clock, 0.0003, 0.0, speed = 10.0))
            runCurrent()

            // El motor siguió en memoria en InTrip
            assertTrue(graph.engine.view.value.phase is TripPhase.InTrip)
            assertEquals(StorageFailure.IO, graph.engine.view.value.storageFailure)

            // Se restaura el disco
            db.simulatedFailure = null

            // Próximo tick reintenta guardar
            clock.advance(1000L)
            coordinator.tick()
            runCurrent()

            // El fallo desaparece de la vista al tener éxito la persistencia
            assertNull(graph.engine.view.value.storageFailure)
        } finally {
            coordinator.stop()
        }
    }

    @Test(timeout = 5000)
    fun noExceptionEscapesTheService() = runTest {
        val clock = TestClock()
        val location = TestLocationSource()
        val health = TestHealthSource()
        val publisher = TestAlertPublisher()
        val db = InMemoryTrackingDatabase()
        val graph = buildTestGraph(clock, location, health, publisher, db)

        // Odómetro cargado para que el item vencido dispare publishUrgent
        db.odometerRepo.add(app.guantera.domain.odometer.OdometerCalibration(clock.utcMs(), 15_000.0, 0.0))

        val coordinator = TrackingCoordinator(graph, this, autoTick = false)
        coordinator.start()
        runCurrent()

        try {
            // Publisher lanza excepción
            publisher.throwOnPublish = true

            // Guardamos un ítem vencido que disparará publishUrgent
            graph.maintenanceRepo.save(
                MaintenanceItem(
                    id = 0,
                    name = "Aceite",
                    intervalKm = 10_000,
                    intervalMonths = null,
                    fixedDueDate = null,
                    lastServiceKm = 0.0,
                    lastServiceDate = null,
                    origin = DataOrigin.VERIFIED,
                ),
            )
            runCurrent()

            // La excepción fue capturada y registrada en lastPlatformError
            assertNotNull(graph.lastPlatformError.value)
            assertTrue(graph.lastPlatformError.value!!.contains("Simulated publisher crash"))

            // El coordinador sigue corriendo
            assertTrue(coordinator.isRunning)
        } finally {
            coordinator.stop()
        }
    }

    @Test(timeout = 5000)
    fun alertPublicationDeferredWhileMoving() = runTest {
        val clock = TestClock()
        val location = TestLocationSource()
        val health = TestHealthSource()
        val publisher = TestAlertPublisher()
        val db = InMemoryTrackingDatabase()
        val graph = buildTestGraph(clock, location, health, publisher, db)

        // Crear ítem vencido
        db.maintenanceRepo.save(
            MaintenanceItem(
                id = 1,
                name = "Aceite",
                intervalKm = 10_000,
                intervalMonths = null,
                fixedDueDate = null,
                lastServiceKm = 0.0,
                lastServiceDate = null,
                origin = DataOrigin.VERIFIED,
            ),
        )
        db.odometerRepo.add(app.guantera.domain.odometer.OdometerCalibration(clock.utcMs(), 15_000.0, 0.0))

        val coordinator = TrackingCoordinator(graph, this, autoTick = false)
        coordinator.start()
        runCurrent()

        try {
            // Inicialmente detenido, se publica el aviso
            assertEquals(1, publisher.published.size)
            assertEquals(MaintenanceStatus.VENCIDO, publisher.published.first()?.status)

            // Iniciar movimiento (EN_MOVIMIENTO)
            location.emit(fixAt(clock, 0.0, 0.0, speed = 10.0))
            clock.advance(1000L)
            location.emit(fixAt(clock, 0.0003, 0.0, speed = 10.0))
            runCurrent()
            assertEquals(DrivingState.EN_MOVIMIENTO, graph.observeDrivingState.current())

            // Limpiamos los avisos publicados para verificar
            publisher.published.clear()

            // En movimiento, publishUrgent devuelve DEFERRED
            val result = graph.getPendingAlerts.publishUrgent()
            assertEquals(app.guantera.domain.usecase.PublishResult.DEFERRED, result)
            assertTrue(publisher.published.isEmpty())

            // Al detenerse (velocidad 0)
            clock.advance(1000L)
            location.emit(fixAt(clock, 0.0003, 0.0, speed = 0.0))
            runCurrent()
        } finally {
            coordinator.stop()
        }
    }
}
