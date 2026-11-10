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
import app.guantera.presentation.port.FormOutcome
import app.guantera.presentation.port.SaveSpotResult
import app.guantera.presentation.port.TripClosure
import app.guantera.presentation.port.TripRecord
import app.guantera.presentation.port.VehicleStatus
import app.guantera.presentation.history.HistoryBody
import app.guantera.presentation.history.HistoryIcon
import app.guantera.presentation.history.HistoryLayout
import app.guantera.presentation.history.PeriodKind
import app.guantera.presentation.history.RowText
import app.guantera.presentation.input.CalendarDate
import app.guantera.presentation.trips.TripsPresenter
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

    private fun TripsPresenter.groups() = (state.value.history.body as HistoryBody.Timeline).groups

    @Test fun `the figures follow the period, month by default, then week and year`() = test {
        data.trips.value = listOf(
            trip(1, at(11), km = 1.5),
            trip(2, at(9), km = 10.0), // Monday of this week
            trip(3, at(8), km = 100.0), // Sunday, last week
            trip(4, at(1), km = 1000.0), // this month
            trip(5, at(31, Calendar.OCTOBER), km = 5000.0),
        )
        val presenter = trips()
        assertEquals("1.112", presenter.state.value.periodKmText)
        assertEquals("4", presenter.state.value.periodTripsText)
        presenter.historyActions.selectKind(PeriodKind.Week)
        assertEquals("12", presenter.state.value.periodKmText)
        assertEquals("2", presenter.state.value.periodTripsText)
        presenter.historyActions.selectKind(PeriodKind.Year)
        assertEquals("6.112", presenter.state.value.periodKmText)
        assertEquals("5", presenter.state.value.periodTripsText)
    }

    @Test fun `the timeline groups trips by day, newest day first, with the day's total`() = test {
        data.trips.value = listOf(trip(1, at(11, hour = 14), km = 2.0), trip(2, at(11), km = 3.5), trip(3, at(9), km = 4.0))
        val groups = trips().groups()
        assertEquals(listOf(CalendarDate(2026, 11, 11), CalendarDate(2026, 11, 9)), groups.map { it.date })
        assertEquals(listOf("5,5", "4,0"), groups.map { it.totalText })
        assertEquals(listOf(1L, 2L), groups.first().rows.map { it.id })
        assertEquals(HistoryIcon.Trip, groups.first().rows.first().icon)
        assertEquals(RowText.TripTimes("14:00", 30), groups.first().rows.first().title)
    }

    @Test fun `only recovered trips are marked as estimated`() = test {
        data.trips.value = listOf(
            trip(1, at(11), closure = TripClosure.Recovered),
            trip(2, at(10), closure = TripClosure.Manual),
            trip(3, at(9), closure = TripClosure.Automatic),
        )
        assertEquals(listOf(true, false, false), trips().groups().flatMap { it.rows }.map { it.estimated })
    }

    @Test fun `no trips is reported as empty with zero totals`() = test {
        val state = trips().state.value
        assertTrue(state.history.isEmpty)
        assertEquals("0", state.periodKmText)
        assertEquals("0", state.periodTripsText)
    }

    @Test fun `a trip belongs to the day it started, and the period edges are exact`() = test {
        data.trips.value = listOf(
            trip(1, at(1, hour = 0)), // first instant of the month
            trip(2, at(30, Calendar.NOVEMBER, hour = 23)), // last evening of the month
            trip(3, at(1, Calendar.DECEMBER, hour = 0)), // next month
            trip(4, at(31, Calendar.OCTOBER, hour = 23)), // previous month
        )
        val presenter = trips()
        // The sample day is the 11th, so the 30th is still shown: the period is the whole month, not up to today.
        assertEquals(listOf(2L, 1L), presenter.groups().flatMap { it.rows }.map { it.id })
        presenter.historyActions.previousPeriod()
        assertEquals(listOf(4L), presenter.groups().flatMap { it.rows }.map { it.id })
    }

    @Test fun `the calendar of a month marks the days with trips and today`() = test {
        data.trips.value = listOf(trip(1, at(11), km = 20.0), trip(2, at(11, hour = 15), km = 16.4), trip(3, at(3), km = 7.0))
        val presenter = trips()
        presenter.historyActions.selectLayout(HistoryLayout.Calendar)
        val cells = (presenter.state.value.history.body as HistoryBody.MonthGrid).cells
        // 1 November 2026 is a Sunday: six empty cells first, 30 days, then six more to finish the sixth week.
        assertEquals(6, cells.takeWhile { it == null }.size)
        assertEquals(42, cells.size)
        val days = cells.filterNotNull()
        assertEquals(30, days.size)
        assertEquals(listOf(3, 11), days.filter { it.count > 0 }.map { it.date.day })
        assertEquals("36", days.single { it.date.day == 11 }.totalText)
        assertEquals(2, days.single { it.date.day == 11 }.count)
        assertEquals(listOf(11), days.filter { it.isToday }.map { it.date.day })
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

    @Test fun `back closes the trip first, then an open day, and leaving rewinds everything`() = test {
        data.trips.value = listOf(trip(1, at(11)))
        val presenter = trips()
        presenter.historyActions.selectLayout(HistoryLayout.Calendar)
        presenter.historyActions.openDay(CalendarDate(2026, 11, 11))
        presenter.openTrip(1)
        assertTrue(presenter.onBack())
        assertNull(presenter.state.value.detail)
        assertTrue(presenter.state.value.history.body is HistoryBody.OpenDay)
        assertTrue(presenter.onBack())
        assertTrue(presenter.state.value.history.body is HistoryBody.MonthGrid)
        assertFalse(presenter.onBack())
        presenter.historyActions.selectKind(PeriodKind.Year)
        presenter.openTrip(1)
        presenter.reset()
        assertNull(presenter.state.value.detail)
        assertEquals(PeriodKind.Month, presenter.state.value.history.kind)
        assertEquals(HistoryLayout.List, presenter.state.value.history.layout)
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

    @Test fun `saving here that needs the confirmation asks first and saves after yes`() = test {
        data.saveSpotResult = SaveSpotResult.NeedsConfirmation
        val presenter = parked()
        presenter.saveHere()
        assertTrue(presenter.state.value.askIfStopped)
        data.saveSpotResult = SaveSpotResult.Saved
        presenter.confirmStopped()
        assertEquals(1, data.stoppedConfirmations)
        assertFalse(presenter.state.value.askIfStopped)
        assertEquals(2, data.savedSpots)
    }

    @Test fun `saving here with the car moving or a failing disk says so`() = test {
        val presenter = parked()
        data.saveSpotResult = SaveSpotResult.CarMoving
        presenter.saveHere()
        assertEquals(ParkedError.Moving, presenter.state.value.error)
        data.saveSpotResult = SaveSpotResult.Failed
        presenter.saveHere()
        assertEquals(ParkedError.SaveFailed, presenter.state.value.error)
    }

    @Test fun `a note blocked by the domain keeps the draft, and goes through after yes`() = test {
        data.parkingSpot.value = spot("Piso 2")
        val presenter = parked()
        presenter.editNote()
        presenter.onDraftChanged("Piso 3")
        data.noteOutcome = FormOutcome.NeedsConfirmation
        presenter.saveNote()
        assertTrue(presenter.state.value.askIfStopped)
        assertTrue(presenter.state.value.isEditingNote)
        assertEquals("Piso 3", presenter.state.value.draft)
        data.noteOutcome = FormOutcome.Saved
        presenter.confirmStopped()
        assertEquals(listOf("Piso 3", "Piso 3"), data.savedNotes)
        assertFalse(presenter.state.value.isEditingNote)
    }

    @Test fun `a note that cannot be saved stays in the editor with the error`() = test {
        data.parkingSpot.value = spot("Piso 2")
        val presenter = parked()
        presenter.editNote()
        data.noteOutcome = FormOutcome.StorageFailed
        presenter.saveNote()
        assertTrue(presenter.state.value.isEditingNote)
        assertEquals(ParkedError.SaveFailed, presenter.state.value.error)
        data.noteOutcome = FormOutcome.CarMoving
        presenter.saveNote()
        assertEquals(ParkedError.Moving, presenter.state.value.error)
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

    private companion object {
        const val BONE_LUMINANCE: Byte = 0xC4.toByte()
    }
}
