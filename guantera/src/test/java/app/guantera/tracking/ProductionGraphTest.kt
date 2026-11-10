package app.guantera.tracking

import app.guantera.domain.maintenance.MaintenanceAlert
import app.guantera.domain.motion.TrackingHealth
import app.guantera.domain.port.AlertPublisher
import app.guantera.domain.port.Clock
import app.guantera.domain.port.LocationSource
import app.guantera.domain.port.TrackingHealthSource
import app.guantera.domain.trip.LocationFix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/** Arranque por boot: solo el grafo real, nunca uno en memoria. */
class ProductionGraphTest {
    private fun graph(): TrackingGraph {
        val db = InMemoryTrackingDatabase()
        return TrackingGraph(
            clock = object : Clock {
                override fun utcMs() = 1L
                override fun monoMs() = 1L
                override fun bootCount(): Int? = 1
                override fun utcOffsetMs(atUtcMs: Long) = 0
            },
            locationSource = object : LocationSource { override val fixes: Flow<LocationFix> = emptyFlow() },
            healthSource = object : TrackingHealthSource { override val health = MutableStateFlow(TrackingHealth(true, true)) },
            alertPublisher = object : AlertPublisher { override suspend fun publish(alert: MaintenanceAlert?) = Unit },
            tripRepo = db.tripRepo, parkingRepo = db.parkingRepo, settingsRepo = db.settingsRepo,
            odometerRepo = db.odometerRepo, maintenanceRepo = db.maintenanceRepo, expenseRepo = db.expenseRepo,
            transactions = db.transactions, backupStore = db.backupStore,
        )
    }

    @Test fun `without a real graph the service warns and stops, it does not record`() {
        assertEquals(TrackingStartPolicy.Mode.WARN_AND_STOP, TrackingStartPolicy.decide(null))
        assertEquals(TrackingStartPolicy.Mode.RECORD, TrackingStartPolicy.decide(graph()))
    }

    @Test fun `the process has one graph and no fallback creates another`() {
        val real = graph()
        TrackingGraph.setInstance(real)
        assertSame(real, TrackingGraph.get())
        // Whoever asks later (service started by boot, receiver, screens) gets that same one.
        assertSame(TrackingGraph.get(), TrackingGraph.get())
    }

    @Test fun `production code has no in-memory graph`() {
        val root = File("src/main/java")
        val offenders = root.walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readText().let { "InMemoryTrackingDatabase" in it || "getOrCreate" in it || "createInMemory" in it } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        assertFalse(File(root, "app/guantera/tracking/InMemoryTrackingDatabase.kt").exists())
    }
}
