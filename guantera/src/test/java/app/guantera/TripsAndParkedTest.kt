package app.guantera

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.parked.ParkedContent
import app.guantera.presentation.parked.ParkedError
import app.guantera.presentation.parked.ParkedPresenter
import app.guantera.presentation.parked.QrEncoder
import app.guantera.presentation.port.GeoPoint
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.ParkingDetail
import app.guantera.presentation.port.SaveSpotResult
import app.guantera.presentation.port.TripClosure
import app.guantera.presentation.port.TripRecord
import app.guantera.presentation.port.VehicleStatus
import app.guantera.presentation.trips.DayLabel
import app.guantera.presentation.trips.TripsPresenter
import app.guantera.sample.SampleData
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class TripsAndParkedTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val format = DisplayFormat(utc)

    /** Wednesday 11 November 2026, 12:00 UTC. */
    private val noon = Calendar.getInstance(utc).apply {
        clear()
        set(2026, Calendar.NOVEMBER, 11, 12, 0, 0)
    }.timeInMillis
    private val clock = FakeClock(noon)
    private val ticks = MutableStateFlow(noon)
    private val data = FakeData()
    private val here = GeoPoint(-34.5627, -58.4565)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private fun TestScope.trips() = TripsPresenter(data, format, clock, ticks, backgroundScope, utc).also { p ->
        backgroundScope.launch { p.state.collect {} }
    }

    private fun TestScope.parked() = ParkedPresenter(data, format, clock, ticks, backgroundScope).also { p ->
        backgroundScope.launch { p.state.collect {} }
    }

    private fun at(day: Int, month: Int = Calendar.NOVEMBER, hour: Int = 9) = Calendar.getInstance(utc).apply {
        clear()
        set(2026, month, day, hour, 0, 0)
    }.timeInMillis

    private fun trip(
        id: Long,
        start: Long,
        minutes: Long = 30,
        km: Double = 10.0,
        closure: TripClosure = TripClosure.Automatic,
    ) = TripRecord(id, start, start + minutes * 60_000, km, here, here, closure)

    private fun spot(note: String? = "Piso 2") = ParkingDetail(noon - 135 * 60_000, here, 6, note, false)

    @Test fun `totals split by today, Monday-based week and calendar month`() = test {
        data.trips.value = listOf(
            trip(1, at(11), km = 1.5),
            trip(2, at(9), km = 10.0), // Monday of this week
            trip(3, at(8), km = 100.0), // Sunday, last week
            trip(4, at(1), km = 1000.0), // this month
            trip(5, at(31, Calendar.OCTOBER), km = 5000.0),
        )
        val state = trips().state.value
        assertEquals("1,5", state.todayKmText)
        assertEquals("12", state.weekKmText)
        assertEquals("1.112", state.monthKmText)
    }

    @Test fun `rows say today, yesterday or the date`() = test {
        data.trips.value = listOf(trip(1, at(11)), trip(2, at(10)), trip(3, at(9)))
        val rows = trips().state.value.rows
        assertEquals(DayLabel.Today, rows[0].day)
        assertEquals(DayLabel.Yesterday, rows[1].day)
        assertEquals(DayLabel.Date("Lun 9 nov"), rows[2].day)
    }

    @Test fun `only recovered trips are marked as estimated`() = test {
        data.trips.value = listOf(
            trip(1, at(11), closure = TripClosure.Recovered),
            trip(2, at(10), closure = TripClosure.Manual),
            trip(3, at(9), closure = TripClosure.Automatic),
        )
        assertEquals(listOf(true, false, false), trips().state.value.rows.map { it.isEstimatedClosure })
    }

    @Test fun `no trips is reported as empty with zero totals`() = test {
        val state = trips().state.value
        assertTrue(state.isEmpty)
        assertEquals("0,0", state.todayKmText)
        assertFalse(state.hasMore)
    }

    @Test fun `the list grows one page at a time until it is complete`() = test {
        data.trips.value = (1L..45L).map { trip(it, at(1) + it) }
        val presenter = trips()
        assertEquals(20, presenter.state.value.rows.size)
        assertTrue(presenter.state.value.hasMore)
        presenter.loadMore()
        assertEquals(40, presenter.state.value.rows.size)
        presenter.loadMore()
        assertEquals(45, presenter.state.value.rows.size)
        assertFalse(presenter.state.value.hasMore)
        presenter.loadMore()
        assertEquals(45, presenter.state.value.rows.size)
    }

    @Test fun `opening a trip gives its detail and the back action closes it`() = test {
        data.trips.value = listOf(trip(7, at(11), minutes = 30, km = 20.0, closure = TripClosure.Recovered))
        val presenter = trips()
        presenter.openTrip(7)
        val detail = presenter.state.value.detail!!
        assertEquals("20,0", detail.kmText)
        assertEquals(30, detail.durationMinutes)
        assertEquals("40", detail.averageSpeedText)
        assertEquals("09:00", detail.startTimeText)
        assertEquals("09:30", detail.endTimeText)
        assertEquals("-34.56270, -58.45650", detail.startCoordinates)
        assertEquals(TripClosure.Recovered, detail.closure)
        assertTrue(presenter.closeTrip())
        assertNull(presenter.state.value.detail)
        assertFalse(presenter.closeTrip())
    }

    @Test fun `a trip under a minute has no average speed`() = test {
        data.trips.value = listOf(trip(1, at(11), minutes = 0, km = 0.1))
        val presenter = trips()
        presenter.openTrip(1)
        assertNull(presenter.state.value.detail!!.averageSpeedText)
    }

    @Test fun `leaving the screen closes the trip and rewinds the list`() = test {
        data.trips.value = (1L..45L).map { trip(it, at(1) + it) }
        val presenter = trips()
        presenter.loadMore()
        presenter.openTrip(1)
        presenter.reset()
        assertNull(presenter.state.value.detail)
        assertEquals(20, presenter.state.value.rows.size)
    }

    @Test fun `a saved spot shows age, accuracy and the map link`() = test {
        data.parkingSpot.value = spot()
        val content = parked().state.value.content as ParkedContent.Saved
        assertEquals(135, content.ageMinutes)
        assertEquals(6, content.accuracyMeters)
        assertEquals("Piso 2", content.note)
        assertEquals("https://www.google.com/maps/search/?api=1&query=-34.56270,-58.45650", content.mapUrl)
    }

    @Test fun `no spot is reported as empty`() = test {
        assertEquals(ParkedContent.Empty, parked().state.value.content)
    }

    @Test fun `save here reports a missing GPS position`() = test {
        data.saveSpotResult = SaveSpotResult.NoValidFix
        val presenter = parked()
        presenter.saveHere()
        assertEquals(ParkedError.NoFix, presenter.state.value.error)
    }

    @Test fun `a later successful save clears the error`() = test {
        data.saveSpotResult = SaveSpotResult.NoValidFix
        val presenter = parked()
        presenter.saveHere()
        data.saveSpotResult = SaveSpotResult.Saved
        presenter.saveHere()
        assertNull(presenter.state.value.error)
        assertEquals(2, data.savedSpots)
    }

    @Test fun `editing starts from the current note and saves the draft`() = test {
        data.parkingSpot.value = spot("Piso 2")
        val presenter = parked()
        presenter.editNote()
        assertTrue(presenter.state.value.isEditingNote)
        assertEquals("Piso 2", presenter.state.value.draft)
        presenter.onDraftChanged("Piso 3")
        presenter.saveNote()
        assertEquals(listOf("Piso 3"), data.savedNotes)
        assertFalse(presenter.state.value.isEditingNote)
    }

    @Test fun `cancelling keeps the note and tells the back key it did something`() = test {
        data.parkingSpot.value = spot("Piso 2")
        val presenter = parked()
        presenter.editNote()
        presenter.onDraftChanged("Otra")
        assertTrue(presenter.cancelNote())
        assertTrue(data.savedNotes.isEmpty())
        assertFalse(presenter.cancelNote())
    }

    @Test fun `the draft survives while the screen is not observed`() = test {
        data.parkingSpot.value = spot("Piso 2")
        val presenter = ParkedPresenter(data, format, clock, ticks, backgroundScope)
        val first = backgroundScope.launch { presenter.state.collect {} }
        presenter.editNote()
        presenter.onDraftChanged("A medio escribir")
        first.cancel()
        backgroundScope.launch { presenter.state.collect {} }
        assertEquals("A medio escribir", presenter.state.value.draft)
        assertTrue(presenter.state.value.isEditingNote)
    }

    @Test fun `stopped needs no confirmation`() = test {
        val presenter = parked()
        presenter.saveHere()
        assertFalse(presenter.state.value.askIfStopped)
        assertEquals(1, data.savedSpots)
    }

    @Test fun `unknown motion asks first and only saves after yes`() = test {
        data.status.value = VehicleStatus(GpsStatus.Good, MotionStatus.Unknown)
        val presenter = parked()
        presenter.saveHere()
        assertTrue(presenter.state.value.askIfStopped)
        assertEquals(0, data.savedSpots)
        presenter.confirmStopped()
        assertFalse(presenter.state.value.askIfStopped)
        assertEquals(1, data.savedSpots)
    }

    @Test fun `answering no cancels the action`() = test {
        data.status.value = VehicleStatus(GpsStatus.Good, MotionStatus.Unknown)
        val presenter = parked()
        presenter.saveHere()
        presenter.denyStopped()
        assertFalse(presenter.state.value.askIfStopped)
        assertEquals(0, data.savedSpots)
    }

    @Test fun `the confirmation lasts ten minutes`() = test {
        data.status.value = VehicleStatus(GpsStatus.Good, MotionStatus.Unknown)
        val presenter = parked()
        presenter.saveHere()
        presenter.confirmStopped()
        clock.now += 9 * 60_000
        presenter.saveHere()
        assertFalse(presenter.state.value.askIfStopped)
        clock.now += 60_000
        presenter.saveHere()
        assertTrue(presenter.state.value.askIfStopped)
    }

    @Test fun `movement ends the confirmation`() = test {
        data.status.value = VehicleStatus(GpsStatus.Good, MotionStatus.Unknown)
        val presenter = parked()
        presenter.saveHere()
        presenter.confirmStopped()
        data.status.value = VehicleStatus(GpsStatus.Good, MotionStatus.Moving)
        data.status.value = VehicleStatus(GpsStatus.Good, MotionStatus.Unknown)
        presenter.saveHere()
        assertTrue(presenter.state.value.askIfStopped)
    }

    @Test fun `the QR decodes back to the map link with an independent reader`() {
        val url = "https://www.google.com/maps/search/?api=1&query=-34.56270,-58.45650"
        assertEquals(url, decode(url))
    }

    @Test fun `extreme coordinates still produce a readable QR`() {
        val url = "https://www.google.com/maps/search/?api=1&query=-179.99999,-179.99999"
        assertEquals(url, decode(url))
    }

    /** Renders with the colors the screen uses, bone light modules and black dark ones. */
    private fun decode(text: String, modulePx: Int = 8, quiet: Int = 4): String {
        val qr = QrEncoder.encode(text)
        val side = (qr.size + quiet * 2) * modulePx
        val pixels = ByteArray(side * side) { BONE_LUMINANCE }
        for (py in 0 until side) {
            for (px in 0 until side) {
                val x = px / modulePx - quiet
                val y = py / modulePx - quiet
                if (x in 0 until qr.size && y in 0 until qr.size && qr.isDark(x, y)) pixels[py * side + px] = 0
            }
        }
        val source = PlanarYUVLuminanceSource(pixels, side, side, 0, 0, side, side, false)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test fun `sample today adds up to what Inicio shows, at any hour`() = test {
        val zone = utc
        listOf(0, 1, 7, 13, 23).forEach { hour ->
            val now = at(11, hour = hour) + 30 * 60_000
            val sample = SampleData(FakeClock(now), backgroundScope, zone)
            val midnight = at(11, hour = 0)
            val today = sample.trips.value.filter { it.startMillis >= midnight }
            assertEquals("hora $hour", sample.home.value.today.tripCount, today.size)
            assertEquals("hora $hour", sample.home.value.today.kilometers, today.sumOf { it.distanceKm }, 0.001)
            assertTrue(today.all { it.endMillis <= now })
        }
    }

    @Test fun `sample ids are unique and the list is newest first`() = test {
        val trips = SampleData(FakeClock(noon), backgroundScope, utc).trips.value
        assertEquals(trips.size, trips.map { it.id }.toSet().size)
        assertEquals(trips.sortedByDescending { it.startMillis }, trips)
        assertTrue(trips.size > 40)
        assertTrue(trips.any { it.closure == TripClosure.Recovered })
    }

    @Test fun `sample saving here replaces the spot and updates Inicio`() = test {
        val sample = SampleData(clock, backgroundScope, utc)
        assertEquals(SaveSpotResult.Saved, sample.saveSpotHere())
        assertEquals(noon, sample.parkingSpot.value!!.savedAtMillis)
        assertEquals(noon, sample.home.value.parked!!.savedAtMillis)
        assertNull(sample.parkingSpot.value!!.note)
    }

    @Test fun `sample cannot save while moving`() = test {
        val sample = SampleData(clock, backgroundScope, utc)
        sample.startSimulatedTrip()
        assertEquals(SaveSpotResult.CarMoving, sample.saveSpotHere())
    }

    @Test fun `sample trims notes and treats blank as no note`() = test {
        val sample = SampleData(clock, backgroundScope, utc)
        sample.saveSpotNote("  Piso 5 ")
        assertEquals("Piso 5", sample.parkingSpot.value!!.note)
        assertEquals("Piso 5", sample.home.value.parked!!.note)
        sample.saveSpotNote("   ")
        assertNull(sample.parkingSpot.value!!.note)
    }

    @Test fun `ya estacione adds a trip at the top of the list`() = test {
        val sample = SampleData(clock, backgroundScope, utc)
        val before = sample.trips.value.size
        sample.startSimulatedTrip()
        sample.confirmParked()
        assertEquals(before + 1, sample.trips.value.size)
        assertEquals(TripClosure.Manual, sample.trips.value.first().closure)
        assertNotNull(sample.parkingSpot.value)
    }

    private companion object {
        const val BONE_LUMINANCE: Byte = 0xC4.toByte()
    }
}
