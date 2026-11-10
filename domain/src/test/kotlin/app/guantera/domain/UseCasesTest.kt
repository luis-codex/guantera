package app.guantera.domain

import app.guantera.domain.backup.BackupData
import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.expense.CostPerKm
import app.guantera.domain.expense.Expense
import app.guantera.domain.expense.ExpenseCategory
import app.guantera.domain.maintenance.DataOrigin
import app.guantera.domain.maintenance.MaintenanceCatalog
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.maintenance.MaintenanceStatus
import app.guantera.domain.maintenance.ServiceRecord
import app.guantera.domain.motion.TrackingHealth
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.parking.ParkingOrigin
import app.guantera.domain.parking.ParkingSpot
import app.guantera.domain.port.BackupWriteResult
import app.guantera.domain.port.DataHealthReport
import app.guantera.domain.port.ExpenseRepository
import app.guantera.domain.port.ExternalExportResult
import app.guantera.domain.port.StorageException
import app.guantera.domain.port.StorageFailure
import app.guantera.domain.settings.Notice
import app.guantera.domain.settings.Settings
import app.guantera.domain.time.CalendarDay
import app.guantera.domain.time.CalendarMonth
import app.guantera.domain.time.DayRange
import app.guantera.domain.time.FixTime
import app.guantera.domain.trip.GeoPoint
import app.guantera.domain.trip.Trip
import app.guantera.domain.trip.TripClosure
import app.guantera.domain.usecase.AutomaticBackupResult
import app.guantera.domain.usecase.BackupSource
import app.guantera.domain.usecase.CalibrateOdometer
import app.guantera.domain.usecase.CheckDataHealthAndRecover
import app.guantera.domain.usecase.ConfirmParked
import app.guantera.domain.usecase.ConfirmParkedResult
import app.guantera.domain.usecase.DataHealthOutcome
import app.guantera.domain.usecase.DeleteExpense
import app.guantera.domain.usecase.DeleteMaintenanceItem
import app.guantera.domain.usecase.ExportBackup
import app.guantera.domain.usecase.ExportResult
import app.guantera.domain.usecase.FormBlock
import app.guantera.domain.usecase.FormError
import app.guantera.domain.usecase.FormResult
import app.guantera.domain.usecase.GetCostPerKm
import app.guantera.domain.usecase.GetDashboard
import app.guantera.domain.usecase.GetPendingAlerts
import app.guantera.domain.usecase.GetTripDetail
import app.guantera.domain.usecase.ImportBackup
import app.guantera.domain.usecase.ImportResult
import app.guantera.domain.usecase.ListExpenses
import app.guantera.domain.usecase.ListMaintenanceStatus
import app.guantera.domain.usecase.ListTrips
import app.guantera.domain.usecase.MaintenanceEvaluator
import app.guantera.domain.usecase.ManualParkResult
import app.guantera.domain.usecase.MarkParkedNow
import app.guantera.domain.usecase.PublishResult
import app.guantera.domain.usecase.ReadNotices
import app.guantera.domain.usecase.RecoveryReason
import app.guantera.domain.usecase.RegisterService
import app.guantera.domain.usecase.SaveExpense
import app.guantera.domain.usecase.SaveMaintenanceItem
import app.guantera.domain.usecase.SaveParkingNote
import app.guantera.domain.usecase.SaveSettings
import app.guantera.domain.usecase.SeedOrRestoreCatalog
import app.guantera.domain.usecase.ServiceRegistration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val DAY = CalendarDay(2025, 6, 15)
private const val DAY_MS = 86_400_000L
private val DAY_UTC = DAY.epochDay * DAY_MS

private fun trip(startUtc: Long, km: Double, id: Long = 0) = Trip(
    id, startUtc, startUtc + 60_000, km * 1000, GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0), TripClosure.AUTOMATIC,
)

private fun expense(minor: Long, date: CalendarDay = DAY, id: Long = 0, service: Long? = null) =
    Expense(id, ExpenseCategory.COMBUSTIBLE, minor, date, null, service)

private fun item(id: Long = 0, name: String = "Aceite", km: Int? = 10_000, months: Int? = null, fixed: CalendarDay? = null) =
    MaintenanceItem(id, name, km, months, fixed, null, null, DataOrigin.OWN)

private fun spot(instant: Long, id: Long = 0) =
    ParkingSpot(id, GeoPoint(1.0, 1.0), 5.0, instant, ParkingOrigin.TRIP_END, null, false)

private val EMPTY = BackupData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Settings())

class FormGateTest {
    private val app = TestApp()

    @Test fun r10_stoppedIsAccepted() = runTest {
        app.parkWithFix()
        assertNull(app.gate.check())
    }

    @Test fun r10_unknownNeedsConfirmation() = runTest {
        assertEquals(FormBlock.NEEDS_CONFIRMATION, app.gate.check())
    }

    @Test fun cu04_confirmationOpensTheGateFromUnknownOnly() = runTest {
        val confirm = ConfirmParked(app.driving, app.confirmation, app.clock)
        assertEquals(ConfirmParkedResult.CONFIRMED, confirm())
        assertNull(app.gate.check())
        app.clock.mono += 600_000
        assertEquals(FormBlock.NEEDS_CONFIRMATION, app.gate.check())
    }

    @Test fun r9_confirmationNotApplicableWhenStopped() = runTest {
        app.parkWithFix()
        assertEquals(ConfirmParkedResult.NOT_APPLICABLE, ConfirmParked(app.driving, app.confirmation, app.clock)())
    }

    @Test fun r10_movingIsRejectedEvenWithConfirmation() = runTest {
        app.confirmation.grant(0)
        app.clock.mono = 1_000
        app.engine.onFix(fix(0.0, 1.0))
        assertEquals(FormBlock.CAR_MOVING, app.gate.check())
    }

    @Test fun storageFailureIsTyped() = runTest {
        app.parkWithFix()
        app.db.failure = StorageFailure.FULL
        val result = SaveExpense(app.db.expenseRepo, app.form)(expense(100))
        assertEquals(FormResult.StorageFailed(StorageFailure.FULL), result)
    }

    @Test fun savingAFormCreatesACopy() = runTest {
        app.parkWithFix()
        SaveExpense(app.db.expenseRepo, app.form)(expense(100))
        assertEquals(1, app.store.saves)
    }

    @Test fun aFailedCopyDoesNotFailTheForm() = runTest {
        app.parkWithFix()
        app.store.writeResults.addLast(BackupWriteResult.Failed("x"))
        assertTrue(SaveExpense(app.db.expenseRepo, app.form)(expense(100)) is FormResult.Saved)
    }
}

class OdometerUseCaseTest {
    private val app = TestApp()

    @Test fun r13_calibratingStoresTheGpsBaseAndLeavesTripsAlone() = runTest {
        app.parkWithFix()
        app.db.trips = listOf(trip(1_000, 100.0, id = 1))
        val calibrate = CalibrateOdometer(app.db.odometerRepo, app.reader, app.time, app.form)
        assertTrue(calibrate(50_000.0) is FormResult.Saved)
        assertEquals(100.0, app.db.calibrations.single().gpsAccumulatedKm, 0.0)
        assertEquals(50_000.0, app.reader.currentKm()!!, 0.0)
        assertEquals(1, app.db.trips.size)
        app.db.trips = app.db.trips + trip(2_000, 30.0, id = 2)
        assertEquals(50_030.0, app.reader.currentKm()!!, 0.0)
    }

    @Test fun calibrationsAreAddedNeverEdited() = runTest {
        app.parkWithFix()
        val calibrate = CalibrateOdometer(app.db.odometerRepo, app.reader, app.time, app.form)
        calibrate(10.0)
        app.clock.utc += 1_000
        app.db.lastFix = null
        calibrate(20.0)
        assertEquals(2, app.db.calibrations.size)
    }

    @Test fun negativeOdometerIsInvalid() = runTest {
        app.parkWithFix()
        val calibrate = CalibrateOdometer(app.db.odometerRepo, app.reader, app.time, app.form)
        assertEquals(FormResult.Invalid(FormError.ODOMETER_NEGATIVE), calibrate(-1.0))
    }
}

class MaintenanceUseCasesTest {
    private val app = TestApp()
    private val db = app.db
    private val register = RegisterService(db.maintenanceRepo, db.expenseRepo, db.transactions, app.form)

    private suspend fun stored(i: MaintenanceItem = item()) = db.maintenanceRepo.save(i)

    @Test fun cu10_validatesR15AndName() = runTest {
        app.parkWithFix()
        val save = SaveMaintenanceItem(db.maintenanceRepo, app.form)
        assertEquals(FormResult.Invalid(FormError.INVALID_RULE), save(item(km = null)))
        assertEquals(FormResult.Invalid(FormError.NAME_BLANK), save(item(name = " ")))
        assertEquals(FormResult.Invalid(FormError.ITEM_NOT_FOUND), save(item(id = 99)))
        assertTrue(save(item()) is FormResult.Saved)
    }

    @Test fun cu11_deletingAnItemKeepsItsExpenses() = runTest {
        app.parkWithFix()
        val id = stored()
        db.expenseRepo.save(expense(500, service = 1))
        DeleteMaintenanceItem(db.maintenanceRepo, app.form)(id)
        assertTrue(db.items.isEmpty())
        assertEquals(1, db.expenses.size)
    }

    @Test fun r21_updatesItemAndCreatesLinkedExpense() = runTest {
        app.parkWithFix()
        val id = stored()
        val result = register(ServiceRegistration(id, DAY, 12_000.0, 3_000, "ok", null)) as FormResult.Saved
        val updated = db.items.single()
        assertEquals(12_000.0, updated.lastServiceKm!!, 0.0)
        assertEquals(DAY, updated.lastServiceDate)
        val created = db.expenses.single()
        assertEquals(ExpenseCategory.MANTENIMIENTO, created.category)
        assertEquals(result.value, created.serviceRecordId)
        assertEquals(DAY, created.date)
    }

    @Test fun r21_failureRollsEverythingBack() = runTest {
        app.parkWithFix()
        val id = stored()
        val failing = object : ExpenseRepository by db.expenseRepo {
            override suspend fun save(expense: Expense): Long {
                throw StorageException(StorageFailure.IO)
            }
        }
        val result = RegisterService(db.maintenanceRepo, failing, db.transactions, app.form)(
            ServiceRegistration(id, DAY, 1.0, 100, null, null),
        )
        assertEquals(FormResult.StorageFailed(StorageFailure.IO), result)
        assertTrue(db.services.isEmpty())
        assertNull(db.items.single().lastServiceDate)
    }

    @Test fun r21_withoutKmTheKmAxisLosesItsData() = runTest {
        app.parkWithFix()
        val id = stored(item().copy(lastServiceKm = 500.0))
        register(ServiceRegistration(id, DAY, null, null, null, null))
        assertNull(db.items.single().lastServiceKm)
    }

    @Test fun r21_replacesOrClearsFixedDate() = runTest {
        app.parkWithFix()
        val id = stored(item(km = 1_000, fixed = DAY))
        register(ServiceRegistration(id, DAY, null, null, null, null))
        assertNull(db.items.single().fixedDueDate)
        val dated = stored(item(km = null, fixed = DAY, name = "Seguro"))
        val next = DAY.plusMonths(12)
        register(ServiceRegistration(dated, DAY, null, null, null, next))
        assertEquals(next, db.items.first { it.id == dated }.fixedDueDate)
    }

    @Test fun r21_dateOnlyItemNeedsNewDate() = runTest {
        app.parkWithFix()
        val id = stored(item(km = null, fixed = DAY))
        assertEquals(FormResult.Invalid(FormError.NEW_DUE_DATE_REQUIRED), register(ServiceRegistration(id, DAY, null, null, null, null)))
    }

    @Test fun registerRejectsUnknownItemAndBadCost() = runTest {
        app.parkWithFix()
        assertEquals(FormResult.Invalid(FormError.ITEM_NOT_FOUND), register(ServiceRegistration(9, DAY, null, null, null, null)))
        val id = stored()
        assertEquals(FormResult.Invalid(FormError.AMOUNT_NOT_POSITIVE), register(ServiceRegistration(id, DAY, null, 0, null, null)))
    }

    @Test fun cu14_seedOnlyOnEmptyAndRestoreOnlyMissing() = runTest {
        app.parkWithFix()
        val catalog = SeedOrRestoreCatalog(db.maintenanceRepo, db.settingsRepo, app.time, db.transactions, app.form)
        assertEquals(FormResult.Saved(MaintenanceCatalog.entries.size), catalog.seed())
        assertNull(db.settings.catalogRestoredUtcMs)
        assertEquals(FormResult.Saved(0), catalog.seed())
        val aceite = db.items.first { it.name == "Aceite y filtro" }
        db.maintenanceRepo.delete(aceite.id)
        assertEquals(FormResult.Saved(1), catalog.restore())
        assertEquals(MaintenanceCatalog.entries.size, db.items.size)
        assertTrue(db.settings.catalogRestoredUtcMs != null)
    }

    private fun evaluator() = MaintenanceEvaluator(db.maintenanceRepo, app.reader, app.time)

    @Test fun cu09_listIsOrderedAndUsesTheOdometer() = runTest {
        db.odometerRepo.add(OdometerCalibration(0, 11_000.0, 0.0))
        stored(item(name = "ok", km = 50_000).copy(lastServiceKm = 10_000.0))
        stored(item(name = "late", km = 500).copy(lastServiceKm = 10_000.0))
        stored(item(name = "none", km = 500))
        val rows = ListMaintenanceStatus(evaluator(), db.maintenanceRepo, db.odometerRepo, db.tripRepo).load()
        assertEquals(listOf("late", "ok", "none"), rows.map { it.item.name })
        assertEquals(MaintenanceStatus.VENCIDO, rows[0].evaluation.status)
    }

    private fun overdue() = item(name = "late", km = 500).copy(lastServiceKm = 0.0)

    @Test fun r20_noDataNeverAlerts() = runTest {
        stored(item(km = 500))
        assertTrue(GetPendingAlerts(evaluator(), FakeAlertPublisher(), app.driving).load().isEmpty())
    }

    @Test fun r31_noNewAlertsWhileMoving_thenPublishedOnce() = runTest {
        db.odometerRepo.add(OdometerCalibration(0, 1_000.0, 0.0))
        stored(overdue())
        val publisher = FakeAlertPublisher()
        val alerts = GetPendingAlerts(evaluator(), publisher, app.driving)
        app.clock.mono = 1_000
        app.engine.onFix(fix(0.0, 1.0))
        assertEquals(PublishResult.DEFERRED, alerts.publishUrgent())
        assertTrue(publisher.published.isEmpty())
        app.clock.mono = 17_000
        app.engine.onTick()
        assertEquals(PublishResult.PUBLISHED, alerts.publishUrgent())
        assertEquals("late", publisher.published.single()!!.itemName)
        assertEquals(PublishResult.UNCHANGED, alerts.publishUrgent())
    }

    @Test fun r29_datesInPauseWithLostClockAndNoFix() = runTest {
        app.clock.utc = 1_000
        stored(item(name = "seguro", km = null, fixed = CalendarDay(2000, 1, 1)))
        assertEquals(MaintenanceStatus.SIN_DATOS, evaluator().rows().single().evaluation.status)
    }

    @Test fun r29_lostClockWithAFixUsesTheFixDate() = runTest {
        db.lastFix = FixTime(DAY_UTC, 0, 1)
        app.clock.utc = 1_000
        stored(item(name = "seguro", km = null, fixed = DAY.plusDays(10)))
        assertEquals(MaintenanceStatus.PROXIMO, evaluator().rows().single().evaluation.status)
    }
}

class LedgerUseCasesTest {
    private val app = TestApp()
    private val db = app.db
    private val trips = ListTrips(db.tripRepo, app.time, app.calendar)
    private val expenses = ListExpenses(db.expenseRepo)
    private val june = DayRange.month(CalendarMonth(2025, 6))

    @Test fun cu15_rejectsNonPositiveAmounts() = runTest {
        app.parkWithFix()
        val save = SaveExpense(db.expenseRepo, app.form)
        assertEquals(FormResult.Invalid(FormError.AMOUNT_NOT_POSITIVE), save(expense(0)))
        assertEquals(FormResult.Invalid(FormError.AMOUNT_NOT_POSITIVE), save(expense(-1)))
        assertTrue(save(expense(1)) is FormResult.Saved)
    }

    @Test fun r25_editingKeepsTheServiceLink() = runTest {
        app.parkWithFix()
        val id = db.expenseRepo.save(expense(100, service = 7))
        SaveExpense(db.expenseRepo, app.form)(expense(250, id = id))
        assertEquals(7L, db.expenses.single().serviceRecordId)
        assertEquals(250L, db.expenses.single().amountMinor)
    }

    @Test fun editingAMissingExpenseIsInvalid() = runTest {
        app.parkWithFix()
        assertEquals(FormResult.Invalid(FormError.EXPENSE_NOT_FOUND), SaveExpense(db.expenseRepo, app.form)(expense(1, id = 9)))
    }

    @Test fun r25_deletingAnExpenseKeepsTheService() = runTest {
        app.parkWithFix()
        db.services = listOf(ServiceRecord(7, 1, DAY, null, null))
        val id = db.expenseRepo.save(expense(100, service = 7))
        DeleteExpense(db.expenseRepo, app.form)(id)
        assertTrue(db.expenses.isEmpty())
        assertEquals(1, db.services.size)
    }

    @Test fun expensesInRangeAreHalfOpenAndPaged() = runTest {
        db.expenses = listOf(
            expense(1, CalendarDay(2025, 5, 31), id = 1), expense(2, CalendarDay(2025, 6, 1), id = 2),
            expense(3, CalendarDay(2025, 6, 30), id = 3), expense(4, CalendarDay(2025, 7, 1), id = 4),
        )
        assertEquals(listOf(3L, 2L), expenses.inRange(june).map { it.amountMinor })
    }

    @Test fun expenseTotalsByDayAndMonthAreSorted() = runTest {
        db.expenses = listOf(
            expense(10, CalendarDay(2025, 6, 2), id = 1), expense(5, CalendarDay(2025, 6, 2), id = 2),
            expense(7, CalendarDay(2025, 7, 1), id = 3),
        )
        val everything = DayRange()
        assertEquals(
            listOf(CalendarDay(2025, 6, 2) to 15L, CalendarDay(2025, 7, 1) to 7L),
            expenses.totalsByDay(everything).map { it.key to it.amountMinor },
        )
        val months = expenses.totalsByMonth(everything)
        assertEquals(listOf(CalendarMonth(2025, 6), CalendarMonth(2025, 7)), months.map { it.key })
        assertEquals(2, months[0].expenseCount)
    }

    @Test fun tripsInRangeUseLocalDays_halfOpen() = runTest {
        app.clock.offset = (-3 * 3_600_000)
        val localMidnight = DAY_UTC + 3 * 3_600_000
        db.trips = listOf(
            trip(localMidnight - 1, 1.0, id = 1),
            trip(localMidnight, 2.0, id = 2),
            trip(localMidnight + DAY_MS - 1, 3.0, id = 3),
            trip(localMidnight + DAY_MS, 4.0, id = 4),
        )
        assertEquals(listOf(3L, 2L), trips.inRange(DayRange.day(DAY)).map { it.id })
    }

    @Test fun tripCountsForTheDayItStarted() = runTest {
        db.trips = listOf(trip(DAY_UTC + DAY_MS - 1_000, 5.0, id = 1))
        val totals = trips.totalsByDay(DayRange())
        assertEquals(DAY, totals.single().key)
        assertEquals(5.0, totals.single().distanceKm, 0.0)
    }

    @Test fun tripTotalsByMonth() = runTest {
        db.trips = listOf(
            trip(DAY_UTC, 5.0, id = 1), trip(DAY_UTC + 1_000, 2.0, id = 2),
            trip(DAY_UTC + 30 * DAY_MS, 1.0, id = 3),
        )
        val months = trips.totalsByMonth(DayRange())
        assertEquals(listOf(CalendarMonth(2025, 6), CalendarMonth(2025, 7)), months.map { it.key })
        assertEquals(7.0, months[0].distanceKm, 0.0)
        assertEquals(2, months[0].tripCount)
    }

    @Test fun headerCountsTodayWeekAndMonth() = runTest {
        app.clock.utc = DAY_UTC + 1_000
        db.trips = listOf(
            trip(DAY_UTC, 1.0, id = 1),                 // Today, a Sunday.
            trip(DAY_UTC - 6 * DAY_MS, 2.0, id = 2),    // Monday of the same week.
            trip(DAY_UTC - 7 * DAY_MS, 4.0, id = 3),    // Previous week, same month.
            trip(DAY_UTC - 30 * DAY_MS, 8.0, id = 4),   // Previous month.
        )
        val header = trips.header()
        assertEquals(1.0, header.todayKm, 0.0)
        assertEquals(3.0, header.weekKm, 0.0)
        assertEquals(7.0, header.monthKm, 0.0)
    }

    @Test fun cu07_tripDetail() = runTest {
        db.trips = listOf(trip(1, 1.0, id = 4))
        assertEquals(4L, GetTripDetail(db.tripRepo)(4)!!.id)
        assertNull(GetTripDetail(db.tripRepo)(5))
    }

    @Test fun r23_costPerKmForAnyRange() = runTest {
        db.trips = listOf(trip(DAY_UTC, 100.0, id = 1), trip(DAY_UTC + 40 * DAY_MS, 300.0, id = 2))
        db.expenses = listOf(expense(5_000, DAY, id = 1), expense(9_000, DAY.plusDays(40), id = 2))
        val cost = GetCostPerKm(db.expenseRepo, db.tripRepo, app.calendar)
        assertEquals(50.0, (cost(DayRange.month(CalendarMonth(2025, 6))) as CostPerKm.Available).minorPerKm, 0.0)
        assertEquals(35.0, (cost(DayRange()) as CostPerKm.Available).minorPerKm, 0.0)
    }

    @Test fun r24_tooFewKmInTheRange() = runTest {
        db.trips = listOf(trip(DAY_UTC, 49.0, id = 1))
        db.expenses = listOf(expense(5_000, DAY, id = 1))
        val cost = GetCostPerKm(db.expenseRepo, db.tripRepo, app.calendar)(june)
        assertTrue(cost is CostPerKm.NotEnoughKm)
        assertEquals(5_000L, cost.totalMinor)
    }
}

class ParkingAndDashboardTest {
    private val app = TestApp()
    private val db = app.db
    private val mark = MarkParkedNow(app.engine, db.parkingRepo, app.clock, app.form)

    @Test fun r27_savedWithAFreshFix() = runTest {
        app.parkWithFix()
        assertTrue(mark.saveHere() is FormResult.Saved)
        assertEquals(ParkingOrigin.MANUAL, db.spots.single().origin)
        assertEquals(BASE_UTC + 1_000, db.spots.single().instantUtcMs)
    }

    @Test fun r27_rejectedWhileMoving() = runTest {
        app.clock.mono = 1_000
        app.engine.onFix(fix(0.0, 1.0))
        assertEquals(FormResult.Blocked(FormBlock.CAR_MOVING), mark.saveHere())
    }

    @Test fun r27_rejectedWithAnOldFixEvenIfConfirmed() = runTest {
        app.parkWithFix()
        app.clock.mono = 1_000 + 30_001
        app.confirmation.grant(app.clock.mono)
        assertEquals(FormResult.Invalid(FormError.NO_VALID_FIX), mark.saveHere())
    }

    @Test fun unknownStateAsksForConfirmationFirst() = runTest {
        assertEquals(FormResult.Blocked(FormBlock.NEEDS_CONFIRMATION), mark.saveHere())
    }

    @Test fun cu21_noteIsTrimmedAndBlankRemovesIt() = runTest {
        app.parkWithFix()
        val note = SaveParkingNote(db.parkingRepo, app.form)
        assertEquals(FormResult.Invalid(FormError.NO_PARKING_SPOT), note("x"))
        db.parkingRepo.insert(spot(10))
        note("  piso 2 ")
        assertEquals("piso 2", db.spots.single().note)
        note("   ")
        assertNull(db.spots.single().note)
    }

    @Test fun r26_mostRecentInstantWins() = runTest {
        db.parkingRepo.insert(spot(50))
        db.parkingRepo.insert(spot(10))
        assertEquals(50L, db.parkingRepo.current()!!.instantUtcMs)
    }

    @Test fun cu24_settings() = runTest {
        app.parkWithFix()
        val save = SaveSettings(db.settingsRepo, app.form)
        assertEquals(FormResult.Invalid(FormError.CURRENCY_BLANK), save(currency = " "))
        save(currency = " U\$S ", openAtBoot = true)
        assertEquals("U\$S", db.settings.currency)
        assertTrue(db.settings.openAtBoot)
        assertFalse(db.settings.firstUseComplete)
    }

    @Test fun recordingGapIsDismissedEvenInMotion() = runTest {
        db.settings = db.settings.copy(recordingGapPending = true)
        assertTrue(SaveSettings(db.settingsRepo, app.form).dismissRecordingGap())
        assertFalse(db.settings.recordingGapPending)
    }

    private fun dashboard(): GetDashboard {
        val evaluator = MaintenanceEvaluator(db.maintenanceRepo, app.reader, app.time)
        return GetDashboard(
            ListTrips(db.tripRepo, app.time, app.calendar),
            GetPendingAlerts(evaluator, FakeAlertPublisher(), app.driving),
            ListMaintenanceStatus(evaluator, db.maintenanceRepo, db.odometerRepo, db.tripRepo),
            db.parkingRepo,
            ReadNotices(app.driving, app.time, app.reader, db.settingsRepo, app.engine),
            app.time,
            app.calendar,
        )
    }

    @Test fun cu05_summarizesTheDay() = runTest {
        app.clock.utc = DAY_UTC + 5_000
        db.trips = listOf(trip(DAY_UTC, 12.0, id = 1), trip(DAY_UTC + 1_000, 3.0, id = 2), trip(DAY_UTC - DAY_MS, 90.0, id = 3))
        db.odometerRepo.add(OdometerCalibration(0, 1_000.0, 105.0))
        db.maintenanceRepo.save(item(name = "late", km = 500).copy(lastServiceKm = 0.0))
        db.parkingRepo.insert(spot(77))
        db.settings = db.settings.copy(lastExportUtcMs = DAY_UTC)
        val dashboard = dashboard().load()
        assertEquals(15.0, dashboard.today.km, 0.0)
        assertEquals(2, dashboard.today.tripCount)
        assertEquals("late", dashboard.urgentAlert!!.itemName)
        assertEquals(77L, dashboard.parkingSpot!!.instantUtcMs)
        assertEquals(listOf(Notice.SEARCHING_GPS), dashboard.notices)
    }

    @Test fun cu05_observeEmits() = runTest {
        assertEquals(0, dashboard().observe().first().today.tripCount)
    }

    @Test fun cu22_cu03_snapshotShowsProgressAndGps() = runTest {
        app.health.health.value = TrackingHealth(hasPermission = false, gpsEnabled = true)
        assertEquals(listOf(Notice.NO_LOCATION_PERMISSION, Notice.ODOMETER_MISSING), dashboard().load().notices)
        app.health.health.value = TrackingHealth(hasPermission = true, gpsEnabled = true)
        app.clock.mono = 1_000
        app.engine.onFix(fix(0.0, 1.0))
        app.clock.mono = 3_000
        app.engine.onFix(fix(20.0, 3.0))
        val snapshot = app.driving.observe().first()
        assertEquals(20.0, snapshot.progress!!.distanceMeters, 0.5)
        assertEquals(2L, snapshot.progress!!.elapsedSeconds)
        assertNull(snapshot.progress!!.stoppingSeconds)
    }

    @Test fun untrustedClockNoticeOnlyWithoutAnyTimeReference() = runTest {
        app.clock.utc = 1_000
        assertTrue(Notice.UNTRUSTED_CLOCK in dashboard().load().notices)
        db.lastFix = FixTime(BASE_UTC, 0, 1)
        assertFalse(Notice.UNTRUSTED_CLOCK in dashboard().load().notices)
    }

    @Test fun storageFullShowsAsNotice() = runTest {
        db.failure = StorageFailure.FULL
        app.clock.mono = 1_000
        app.engine.onFix(fix(0.0, 1.0))
        app.clock.mono = 3_000
        app.engine.onFix(fix(20.0, 3.0))
        db.failure = null
        assertTrue(Notice.STORAGE_FULL in dashboard().load().notices)
    }

    @Test fun cu20_yaEstacioneDelegatesToTheEngine() = runTest {
        assertEquals(ManualParkResult.NotStopping, mark.finishTrip())
    }
}

class BackupUseCasesTest {
    private val app = TestApp()
    private val db = app.db
    private val store = app.store
    private val transfer = FakeTransfer()
    private val checker = FakeHealthChecker()
    private val full = EMPTY.copy(expenses = listOf(expense(5, id = 1)))
    private val import = ImportBackup(app.data, store, transfer, app.backup, app.driving)
    private val export = ExportBackup(app.data, transfer, db.settingsRepo, app.driving, app.time)
    private val recover = CheckDataHealthAndRecover(checker, store, app.data)

    @Test fun cu25_createsAndRotates() = runTest {
        repeat(9) { store.add(10_000L + it, BackupReadResult.Unreadable("x")) }
        assertTrue(app.backup.run() is AutomaticBackupResult.Created)
        assertEquals(1, store.saves)
        assertTrue(store.deleted.isNotEmpty())
    }

    @Test fun r33_failedWriteDeletesNothing() = runTest {
        repeat(9) { store.add(10_000L + it, BackupReadResult.Unreadable("x")) }
        store.writeResults.addLast(BackupWriteResult.Failed("io"))
        assertTrue(app.backup.run() is AutomaticBackupResult.Failed)
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun storageFullRotatesAndRetriesOnce() = runTest {
        store.writeResults.addLast(BackupWriteResult.StorageFull)
        assertTrue(app.backup.run() is AutomaticBackupResult.Created)
        assertEquals(2, store.saves)
    }

    @Test fun storageFullTwiceIsReported() = runTest {
        store.writeResults.addLast(BackupWriteResult.StorageFull)
        store.writeResults.addLast(BackupWriteResult.StorageFull)
        assertEquals(AutomaticBackupResult.StorageFull, app.backup.run())
    }

    @Test fun cu26_marksExportOnlyOnSuccess() = runTest {
        transfer.exportResult = ExternalExportResult.DriveMissing
        assertEquals(ExportResult.DriveMissing, export())
        assertNull(db.settings.lastExportUtcMs)
        transfer.exportResult = ExternalExportResult.Exported("/usb/a")
        assertEquals(ExportResult.Exported("/usb/a"), export())
        assertEquals(BASE_UTC, db.settings.lastExportUtcMs)
    }

    @Test fun cu26_cu27_rejectedWhileMoving() = runTest {
        app.clock.mono = 1_000
        app.engine.onFix(fix(0.0, 1.0))
        assertEquals(ExportResult.CarMoving, export())
        assertEquals(ImportResult.CarMoving, import(BackupSource.External("r")))
    }

    @Test fun r35_damagedFileTouchesNothing() = runTest {
        db.expenses = listOf(expense(1, id = 1))
        transfer.readResult = BackupReadResult.Read(backupFile(full, declared = "a", actual = "b"))
        assertEquals(ImportResult.Damaged, import(BackupSource.External("r")))
        assertEquals(1L, db.expenses.single().amountMinor)
        assertEquals(0, store.saves)
    }

    @Test fun r36_newerSchemaIsRejectedWithTheVersion() = runTest {
        transfer.readResult = BackupReadResult.Read(backupFile(full, schema = 99))
        assertEquals(ImportResult.NewerSchema(99), import(BackupSource.External("r")))
    }

    @Test fun unreadableFileIsReported() = runTest {
        assertEquals(ImportResult.Unreadable("none"), import(BackupSource.External("r")))
    }

    @Test fun r35_copiesCurrentBeforeReplacing() = runTest {
        db.expenses = listOf(expense(1, id = 1))
        transfer.readResult = BackupReadResult.Read(backupFile(full))
        assertEquals(ImportResult.Imported(1), import(BackupSource.External("r")))
        assertEquals(5L, db.expenses.single().amountMinor)
        assertEquals(1, store.saves)
        val safety = store.copies.values.single().second as BackupReadResult.Read
        assertEquals(1L, safety.file.data.expenses.single().amountMinor)
    }

    @Test fun r35_withoutSafetyCopyNothingIsReplaced() = runTest {
        db.expenses = listOf(expense(1, id = 1))
        transfer.readResult = BackupReadResult.Read(backupFile(full))
        store.writeResults.addLast(BackupWriteResult.Failed("io"))
        assertEquals(ImportResult.SafetyCopyFailed, import(BackupSource.External("r")))
        assertEquals(1L, db.expenses.single().amountMinor)
    }

    @Test fun importOnEmptyDataNeedsNoSafetyCopy() = runTest {
        transfer.readResult = BackupReadResult.Read(backupFile(full))
        assertEquals(ImportResult.Imported(1), import(BackupSource.External("r")))
        assertEquals(0, store.saves)
    }

    @Test fun importReplaceFailureRollsBackAndIsTyped() = runTest {
        transfer.readResult = BackupReadResult.Read(backupFile(full))
        db.failure = StorageFailure.IO
        assertEquals(ImportResult.StorageFailed(StorageFailure.IO), import(BackupSource.External("r")))
    }

    @Test fun importsFromAnInternalCopy() = runTest {
        val copy = store.add(100, BackupReadResult.Read(backupFile(full)))
        assertEquals(ImportResult.Imported(1), import(BackupSource.Internal(copy.id)))
    }

    @Test fun cu28_healthyDoesNothing() = runTest {
        assertEquals(DataHealthOutcome.Healthy, recover())
        assertEquals(0, checker.quarantines)
    }

    @Test fun r37_damagedDatabaseIsMovedAndNewestValidCopyRestored() = runTest {
        checker.report = DataHealthReport(false, false, 0)
        val older = store.add(100, BackupReadResult.Read(backupFile(EMPTY)))
        val newer = store.add(200, BackupReadResult.Read(backupFile(full)))
        val outcome = recover() as DataHealthOutcome.Restored
        assertEquals(newer, outcome.backup)
        assertEquals(1, outcome.recordCount)
        assertEquals(1, checker.quarantines)
        assertTrue(older.id in store.copies)
        assertEquals(5L, db.expenses.single().amountMinor)
    }

    @Test fun r37_damagedCopiesAreSetAsideNotDeleted() = runTest {
        checker.report = DataHealthReport(false, false, 0)
        val good = store.add(100, BackupReadResult.Read(backupFile(full)))
        val bad = store.add(200, BackupReadResult.Read(backupFile(full, declared = "a", actual = "b")))
        val unreadable = store.add(300, BackupReadResult.Unreadable("x"))
        assertEquals(good, (recover() as DataHealthOutcome.Restored).backup)
        assertEquals(listOf(unreadable.id, bad.id), store.quarantined)
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun newerCopyIsNotTreatedAsDamaged() = runTest {
        checker.report = DataHealthReport(false, false, 0)
        store.add(200, BackupReadResult.Read(backupFile(full, schema = 99)))
        val outcome = recover() as DataHealthOutcome.NeedsUser
        assertEquals(RecoveryReason.NO_VALID_COPY, outcome.reason)
        assertTrue(store.quarantined.isEmpty())
        assertEquals(1, outcome.copies.size)
    }

    @Test fun cannotMoveDatabaseNeverTriesToRestore() = runTest {
        checker.report = DataHealthReport(false, false, 0)
        checker.canQuarantine = false
        store.add(200, BackupReadResult.Read(backupFile(full)))
        assertEquals(RecoveryReason.DATABASE_NOT_MOVED, (recover() as DataHealthOutcome.NeedsUser).reason)
        assertTrue(db.expenses.isEmpty())
    }

    @Test fun migrationFailureAndCrashLoopGoToTheUser() = runTest {
        checker.report = DataHealthReport(true, true, 0)
        assertEquals(RecoveryReason.MIGRATION_FAILED, (recover() as DataHealthOutcome.NeedsUser).reason)
        checker.report = DataHealthReport(true, false, 3)
        assertEquals(RecoveryReason.REPEATED_CRASHES, (recover() as DataHealthOutcome.NeedsUser).reason)
        checker.report = DataHealthReport(true, false, 2)
        assertEquals(DataHealthOutcome.Healthy, recover())
    }

    @Test fun startFreshSetsTheDatabaseAside() = runTest {
        assertTrue(recover.startFresh())
        assertEquals(1, checker.quarantines)
    }
}
