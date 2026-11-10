package app.guantera.real

import app.guantera.domain.expense.Expense
import app.guantera.domain.expense.ExpenseCategory
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.time.CalendarDay
import app.guantera.domain.trip.GeoPoint
import app.guantera.domain.trip.Trip
import app.guantera.domain.trip.TripClosure
import app.guantera.presentation.input.CalendarDate
import app.guantera.presentation.port.AppNotice
import app.guantera.presentation.port.FormOutcome
import app.guantera.presentation.port.SaveSpotResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screens' ports over the real use cases, with an in-memory store instead of Room. */
@OptIn(ExperimentalCoroutinesApi::class)
class RealDataTest {
    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private val day = 86_400_000L

    private suspend fun RealRig.addTrip(startMs: Long, km: Double) {
        db.tripRepo.insertClosed(
            Trip(0, startMs, startMs + 600_000, km * 1000, GeoPoint(-34.6, -58.4), GeoPoint(-34.7, -58.5), TripClosure.AUTOMATIC),
        )
    }

    @Test fun `with the movement unknown a change asks first and goes through after the confirmation`() = test {
        val rig = RealRig(backgroundScope)
        // No fix yet, so the state is unknown (DESCONOCIDO) and the domain asks.
        assertEquals(FormOutcome.NeedsConfirmation, rig.data.setCurrency("US$"))
        assertEquals("$", rig.db.settingsRepo.get().currency)
        rig.data.confirmStopped()
        assertEquals(FormOutcome.Saved, rig.data.setCurrency("US$"))
        assertEquals("US$", rig.db.settingsRepo.get().currency)
    }

    @Test fun `a failing disk answers full or failed and writes nothing`() = test {
        val rig = RealRig(backgroundScope)
        rig.data.confirmStopped()
        rig.db.simulatedFailure = StorageFailure.FULL
        assertEquals(FormOutcome.StorageFull, rig.data.setOpenAtBoot(true))
        rig.db.simulatedFailure = StorageFailure.IO
        assertEquals(FormOutcome.StorageFailed, rig.data.setOpenAtBoot(true))
        rig.db.simulatedFailure = null
        assertEquals(false, rig.db.settingsRepo.get().openAtBoot)
        assertEquals(FormOutcome.Saved, rig.data.setOpenAtBoot(true))
    }

    // What a closed Room does: its cancelled scope throws this at a caller that was never cancelled.
    // Rethrown, it ended scope.launch in silence: forms without effect and "Trabajando…" forever.
    @Test fun `a cancellation coming out of the data layer is a failed save, not silence`() = test {
        val rig = RealRig(backgroundScope)
        rig.data.confirmStopped()
        rig.db.simulatedThrowable = kotlinx.coroutines.CancellationException("Job was cancelled")
        assertEquals(FormOutcome.StorageFailed, rig.data.setOpenAtBoot(true))
        rig.db.simulatedThrowable = null
        assertEquals(FormOutcome.Saved, rig.data.setOpenAtBoot(true))
    }

    @Test fun `a form really cancelled stays cancelled`() = test {
        val rig = RealRig(backgroundScope)
        rig.data.confirmStopped()
        rig.db.simulatedThrowable = kotlinx.coroutines.CancellationException("stop")
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            rig.data.setOpenAtBoot(true)
            error("the save answered after its coroutine was cancelled")
        }
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test fun `a note on a spot that does not exist is refused, and saving here without a fix says so`() = test {
        val rig = RealRig(backgroundScope)
        rig.data.confirmStopped()
        assertEquals(FormOutcome.Rejected, rig.data.saveSpotNote("piso 2"))
        assertEquals(SaveSpotResult.NoValidFix, rig.data.saveSpotHere())
    }

    @Test fun `cost per kilometer with 50 km or more is the spending over the km`() = test {
        val rig = RealRig(backgroundScope)
        val start = rig.clock.utc - 2 * day
        rig.addTrip(start, 30.0)
        rig.addTrip(start + day / 2, 50.0)
        val today = CalendarDay.fromEpochDay(Math.floorDiv(rig.clock.utc, day).toInt())
        rig.db.expenseRepo.save(Expense(0, ExpenseCategory.COMBUSTIBLE, 16_000_00, today.plusDays(-1), null, null))
        val from = CalendarDate(today.year, today.month, 1).let { if (today.day < 4) CalendarDate.fromMillis(start - 31 * day) else it }
        val cost = rig.data.expenseData.costPerKm(from, CalendarDate(today.year, today.month, today.day))
        assertEquals(80.0, cost.km, 0.001)
        assertEquals(16_000_00L, cost.totalMinor)
        assertEquals(16_000_00 / 80.0, cost.minorPerKm!!, 0.001)
    }

    @Test fun `cost per kilometer with under 50 km is not calculated`() = test {
        val rig = RealRig(backgroundScope)
        val start = rig.clock.utc - day
        rig.addTrip(start, 20.0)
        val today = CalendarDay.fromEpochDay(Math.floorDiv(rig.clock.utc, day).toInt())
        rig.db.expenseRepo.save(Expense(0, ExpenseCategory.PEAJES, 3_000_00, today, null, null))
        val from = CalendarDate.fromMillis(start - 31 * day)
        val cost = rig.data.expenseData.costPerKm(from, CalendarDate(today.year, today.month, today.day))
        assertEquals(20.0, cost.km, 0.001)
        assertEquals(3_000_00L, cost.totalMinor)
        assertNull(cost.minorPerKm)
    }

    @Test fun `first use with the odometer loads it, the catalog and marks first use as done`() = test {
        val rig = RealRig(backgroundScope)
        val store = rig.data
        assertEquals(false, store.isComplete.value)
        store.confirmStopped()
        assertEquals(FormOutcome.Saved, store.saveOdometer(48_250))
        assertEquals(FormOutcome.Saved, store.saveCurrency("$"))
        assertEquals(FormOutcome.Saved, store.loadCatalog())
        assertEquals(FormOutcome.Saved, store.markComplete())
        assertEquals(true, store.isComplete.value)
        assertEquals(48_250, rig.data.profile.value.odometerKm)
        assertTrue(rig.db.maintenanceRepo.items().isNotEmpty())
        assertNotNull(rig.db.odometerRepo.latest())
        assertTrue(AppNotice.OdometerMissing !in rig.data.home.value.notices)
    }

    @Test fun `first use without an odometer works and the notice asks for it later`() = test {
        val rig = RealRig(backgroundScope)
        val store = rig.data
        store.confirmStopped()
        assertEquals(FormOutcome.Saved, store.loadCatalog())
        assertEquals(FormOutcome.Saved, store.markComplete())
        assertEquals(true, store.isComplete.value)
        assertNull(rig.data.profile.value.odometerKm)
        assertNull(rig.data.settings.value.odometerKm)
        assertNull(rig.db.odometerRepo.latest())
        assertTrue(AppNotice.OdometerMissing in rig.data.home.value.notices)
    }

    @Test fun `the recording gap closes when the banner is touched`() = test {
        val rig = RealRig(backgroundScope)
        rig.db.settingsRepo.update { it.copy(recordingGapPending = true) }
        // The in-memory store announces a change just before it applies it (Room does it after), so the
        // first announcement still shows the old value. A second one lets the banner read the new one.
        rig.db.settingsRepo.update { it }
        assertTrue(AppNotice.RecordingGap in rig.data.home.value.notices)
        rig.data.dismissRecordingGap()
        rig.db.settingsRepo.update { it }
        assertEquals(false, rig.db.settingsRepo.get().recordingGapPending)
        assertTrue(AppNotice.RecordingGap !in rig.data.home.value.notices)
    }

    @Test fun `the permission, GPS and odometer banners come from real data`() = test {
        val rig = RealRig(backgroundScope)
        rig.health.health.value = app.guantera.domain.motion.TrackingHealth(hasPermission = false, gpsEnabled = true)
        assertTrue(AppNotice.NoLocationPermission in rig.data.home.value.notices)
        rig.health.health.value = app.guantera.domain.motion.TrackingHealth(hasPermission = true, gpsEnabled = false)
        assertTrue(AppNotice.GpsOff in rig.data.home.value.notices)
        rig.health.health.value = app.guantera.domain.motion.TrackingHealth(hasPermission = true, gpsEnabled = true)
        assertTrue(AppNotice.SearchingGps in rig.data.home.value.notices)
        assertTrue(AppNotice.OdometerMissing in rig.data.home.value.notices)
    }
}
