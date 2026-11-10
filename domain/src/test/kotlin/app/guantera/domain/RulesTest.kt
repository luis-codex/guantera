package app.guantera.domain

import app.guantera.domain.backup.BackupData
import app.guantera.domain.backup.BackupRetention
import app.guantera.domain.backup.BackupValidator
import app.guantera.domain.backup.BackupVerdict
import app.guantera.domain.backup.StoredBackup
import app.guantera.domain.expense.CostPerKm
import app.guantera.domain.expense.CostRules
import app.guantera.domain.maintenance.DataOrigin
import app.guantera.domain.maintenance.ItemEvaluation
import app.guantera.domain.maintenance.MaintenanceItem
import app.guantera.domain.maintenance.MaintenanceOrdering
import app.guantera.domain.maintenance.MaintenanceRow
import app.guantera.domain.maintenance.MaintenanceRules
import app.guantera.domain.maintenance.MaintenanceStatus
import app.guantera.domain.maintenance.MaintenanceStatus.AL_DIA
import app.guantera.domain.maintenance.MaintenanceStatus.PROXIMO
import app.guantera.domain.maintenance.MaintenanceStatus.SIN_DATOS
import app.guantera.domain.maintenance.MaintenanceStatus.VENCIDO
import app.guantera.domain.maintenance.Remaining
import app.guantera.domain.motion.DrivingState
import app.guantera.domain.motion.DrivingStateRules
import app.guantera.domain.motion.GpsStatus
import app.guantera.domain.motion.ManualConfirmation
import app.guantera.domain.motion.TrackingHealth
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.odometer.OdometerRules
import app.guantera.domain.settings.Notice
import app.guantera.domain.settings.NoticeInputs
import app.guantera.domain.settings.NoticeRules
import app.guantera.domain.settings.Settings
import app.guantera.domain.time.CalendarDay
import app.guantera.domain.time.CalendarMonth
import app.guantera.domain.time.DayRange
import app.guantera.domain.time.FixTime
import app.guantera.domain.time.TrustedTime
import app.guantera.domain.time.TrustedTimeRules
import app.guantera.domain.trip.CleanFix
import app.guantera.domain.trip.GeoPoint
import app.guantera.domain.trip.TripPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val OK = TrackingHealth(hasPermission = true, gpsEnabled = true)

class DrivingStateRulesTest {
    private fun clean(speed: Double = 0.0, accuracy: Double = 5.0, mono: Long = 0) =
        CleanFix(GeoPoint(0.0, 0.0), speed, accuracy, BASE_UTC, mono)

    private fun parked(fix: CleanFix?, now: Long = 0, health: TrackingHealth = OK) =
        DrivingStateRules.evaluate(TripPhase.Parked, fix, now, health)

    @Test fun r6_anyPhaseButParkedIsMoving() {
        val phase = TripPhase.Starting(clean())
        assertEquals(DrivingState.EN_MOVIMIENTO, DrivingStateRules.evaluate(phase, null, 0, TrackingHealth(false, false)))
    }

    @Test fun r7_freshnessLimitIs30sInclusive() {
        assertEquals(DrivingState.DETENIDO, parked(clean(), now = 30_000))
        assertEquals(DrivingState.DESCONOCIDO, parked(clean(), now = 30_001))
    }

    @Test fun r7_speedMustBeBelowVStop() {
        assertEquals(DrivingState.DETENIDO, parked(clean(speed = 0.79)))
        assertEquals(DrivingState.DESCONOCIDO, parked(clean(speed = 0.8)))
    }

    @Test fun r7_accuracyLimit() {
        assertEquals(DrivingState.DETENIDO, parked(clean(accuracy = 25.0)))
        assertEquals(DrivingState.DESCONOCIDO, parked(clean(accuracy = 25.01)))
    }

    @Test fun r8_noFixOrNoHealthIsUnknown() {
        assertEquals(DrivingState.DESCONOCIDO, parked(null))
        assertEquals(DrivingState.DESCONOCIDO, parked(clean(), health = TrackingHealth(false, true)))
        assertEquals(DrivingState.DESCONOCIDO, parked(clean(), health = TrackingHealth(true, false)))
    }

    @Test fun gpsStatusOrder() {
        assertEquals(GpsStatus.NO_PERMISSION, DrivingStateRules.gpsStatus(TrackingHealth(false, false), clean(), 0))
        assertEquals(GpsStatus.OFF, DrivingStateRules.gpsStatus(TrackingHealth(true, false), clean(), 0))
        assertEquals(GpsStatus.GOOD, DrivingStateRules.gpsStatus(OK, clean(), 30_000))
        assertEquals(GpsStatus.SEARCHING, DrivingStateRules.gpsStatus(OK, clean(), 30_001))
    }

    @Test fun r9_confirmationLastsLessThanTenMinutes() {
        val confirmation = ManualConfirmation()
        assertFalse(confirmation.isValid(0))
        confirmation.grant(1_000)
        assertTrue(confirmation.isValid(1_000 + 599_999))
        assertFalse(confirmation.isValid(1_000 + 600_000))
    }
}

class OdometerRulesTest {
    @Test fun r12_appliesTheDeltaSinceTheLastCalibration() {
        assertEquals(50_300.0, OdometerRules.current(OdometerCalibration(0, 50_000.0, 1_000.0), 1_300.0)!!, 0.0)
    }

    @Test fun r14_withoutCalibrationItIsUnknown() {
        assertNull(OdometerRules.current(null, 1_300.0))
    }
}

class MaintenanceRulesTest {
    private val today = CalendarDay(2025, 6, 15)

    private fun item(
        km: Int? = null,
        months: Int? = null,
        fixed: CalendarDay? = null,
        lastKm: Double? = null,
        lastDate: CalendarDay? = null,
    ) = MaintenanceItem(1, "x", km, months, fixed, lastKm, lastDate, DataOrigin.OWN)

    private fun status(item: MaintenanceItem, odo: Double? = null, day: CalendarDay? = today) =
        MaintenanceRules.evaluate(item, odo, day).status

    @Test fun r15_needsAnAxisAndPositiveIntervals() {
        assertFalse(MaintenanceRules.hasValidRule(item()))
        assertFalse(MaintenanceRules.hasValidRule(item(km = 0)))
        assertFalse(MaintenanceRules.hasValidRule(item(months = -1, fixed = today)))
        assertTrue(MaintenanceRules.hasValidRule(item(km = 1)))
        assertTrue(MaintenanceRules.hasValidRule(item(months = 1)))
        assertTrue(MaintenanceRules.hasValidRule(item(fixed = today)))
    }

    @Test fun r16_dueKm() {
        assertEquals(15_000.0, MaintenanceRules.evaluate(item(km = 5_000, lastKm = 10_000.0), 11_000.0, today).dueKm!!, 0.0)
    }

    @Test fun r17_fixedDateWinsOverMonths() {
        val fixed = CalendarDay(2025, 7, 1)
        assertEquals(fixed, MaintenanceRules.evaluate(item(months = 12, fixed = fixed, lastDate = today), null, today).dueDate)
    }

    @Test fun r17_monthsClampToEndOfMonth() {
        val due = MaintenanceRules.evaluate(item(months = 1, lastDate = CalendarDay(2025, 1, 31)), null, today).dueDate
        assertEquals(CalendarDay(2025, 2, 28), due)
    }

    @Test fun r18_theWorstAxisDecides() {
        val eval = MaintenanceRules.evaluate(item(km = 10_000, months = 12, lastKm = 0.0, lastDate = CalendarDay(2024, 6, 1)), 500.0, today)
        assertEquals(VENCIDO, eval.status)
        assertTrue(eval.remaining is Remaining.Days)
    }

    @Test fun r19_kmSoonIsTenPercentWhenSmaller() {
        val i = item(km = 5_000, lastKm = 0.0)
        assertEquals(AL_DIA, status(i, 4_499.0))
        assertEquals(PROXIMO, status(i, 4_500.0))
    }

    @Test fun r19_kmSoonCapsAt1000() {
        val i = item(km = 50_000, lastKm = 0.0)
        assertEquals(AL_DIA, status(i, 48_999.0))
        assertEquals(PROXIMO, status(i, 49_000.0))
    }

    @Test fun overdueStartsPastTheDue() {
        val i = item(km = 5_000, lastKm = 0.0)
        assertEquals(PROXIMO, status(i, 5_000.0))
        assertEquals(VENCIDO, status(i, 5_000.5))
    }

    @Test fun r19_daysSoonIsTenPercentOfRealDays() {
        val last = CalendarDay(2025, 1, 1)
        val i = item(months = 6, lastDate = last) // Due 2025-07-01, 181 days, limit 18.1.
        assertEquals(AL_DIA, status(i, day = CalendarDay(2025, 7, 1).plusDays(-19)))
        assertEquals(PROXIMO, status(i, day = CalendarDay(2025, 7, 1).plusDays(-18)))
    }

    @Test fun r19_daysSoonCapsAt30() {
        val i = item(months = 24, lastDate = CalendarDay(2024, 1, 1)) // Due 2026-01-01.
        assertEquals(AL_DIA, status(i, day = CalendarDay(2026, 1, 1).plusDays(-31)))
        assertEquals(PROXIMO, status(i, day = CalendarDay(2026, 1, 1).plusDays(-30)))
    }

    @Test fun r19_fixedDateUses30Days() {
        val due = CalendarDay(2025, 12, 31)
        val i = item(fixed = due)
        assertEquals(AL_DIA, status(i, day = due.plusDays(-31)))
        assertEquals(PROXIMO, status(i, day = due.plusDays(-30)))
        assertEquals(PROXIMO, status(i, day = due))
        assertEquals(VENCIDO, status(i, day = due.plusDays(1)))
    }

    @Test fun r20_noComputableAxisIsNoData() {
        assertEquals(SIN_DATOS, status(item(km = 1_000, lastKm = 0.0), odo = null))
        assertEquals(SIN_DATOS, status(item(km = 1_000), odo = 500.0))
        assertEquals(SIN_DATOS, status(item(months = 12)))
    }

    @Test fun r29_withoutTrustedDateTheDateAxisIsLeftOut() {
        assertEquals(SIN_DATOS, status(item(fixed = CalendarDay(2020, 1, 1)), day = null))
        assertEquals(AL_DIA, status(item(km = 10_000, fixed = CalendarDay(2020, 1, 1), lastKm = 0.0), odo = 10.0, day = null))
    }

    @Test fun r30_ordersByStatusThenSmallestMargin() {
        fun row(name: String, status: MaintenanceStatus, margin: Double?) = MaintenanceRow(
            item(km = 1).copy(name = name),
            ItemEvaluation(status, null, margin, null, null),
        )
        val rows = listOf(
            row("none", SIN_DATOS, null), row("ok", AL_DIA, 0.9), row("soon-wide", PROXIMO, 0.09),
            row("soon-tight", PROXIMO, 0.01), row("late", VENCIDO, -0.2), row("very-late", VENCIDO, -0.9),
        )
        assertEquals(
            listOf("very-late", "late", "soon-tight", "soon-wide", "ok", "none"),
            MaintenanceOrdering.rows(rows).map { it.item.name },
        )
        assertEquals(listOf("very-late", "late", "soon-tight", "soon-wide"), MaintenanceOrdering.alerts(rows).map { it.itemName })
    }
}

class CostRulesTest {
    @Test fun r24_fiftyKmIsTheFirstComputableValue() {
        assertTrue(CostRules.costPerKm(1_000, 49.99) is CostPerKm.NotEnoughKm)
        assertEquals(20.0, (CostRules.costPerKm(1_000, 50.0) as CostPerKm.Available).minorPerKm, 0.0)
    }
}

class TrustedTimeRulesTest {
    private val day = TrustedTimeRules.MAX_DRIFT_MS
    private val sameBootFix = FixTime(BASE_UTC, 1_000, 3)

    private fun resolve(system: Long, mono: Long = 1_000, boot: Int? = 3, fix: FixTime? = sameBootFix) =
        TrustedTimeRules.resolve(system, mono, boot, fix)

    @Test fun r28_withoutAFixTheFloorDecides() {
        val floor = TrustedTimeRules.MIN_PLAUSIBLE_UTC_MS
        assertEquals(TrustedTime.Reliable(floor), resolve(floor, fix = null))
        assertEquals(TrustedTime.Unavailable, resolve(floor - 1, fix = null))
    }

    @Test fun r28_notBeforeLastFixIsInclusive() {
        assertEquals(TrustedTime.Reliable(BASE_UTC), resolve(BASE_UTC))
        assertEquals(TrustedTime.Estimated(BASE_UTC), resolve(BASE_UTC - 1))
    }

    @Test fun r28_driftLimitIs24HoursInclusive() {
        assertEquals(TrustedTime.Reliable(BASE_UTC + day), resolve(BASE_UTC + day))
        assertEquals(TrustedTime.Estimated(BASE_UTC), resolve(BASE_UTC + day + 1))
    }

    @Test fun r28_parkedForDaysInTheSameBootIsTrusted() {
        val threeDays = 3 * day
        assertEquals(TrustedTime.Reliable(BASE_UTC + threeDays), resolve(BASE_UTC + threeDays, mono = 1_000 + threeDays))
    }

    @Test fun r28_afterARebootAnOldFixIsNotAgainstTheClock() {
        val threeDays = 3 * day
        assertEquals(TrustedTime.Reliable(BASE_UTC + threeDays), resolve(BASE_UTC + threeDays, mono = 5, boot = 4))
        assertEquals(TrustedTime.Reliable(BASE_UTC + threeDays), resolve(BASE_UTC + threeDays, mono = 5, boot = null))
    }

    @Test fun r29_lostClockUsesProjectedFixTimeInSameBoot() {
        assertEquals(TrustedTime.Estimated(BASE_UTC + 9_000), resolve(1_000, mono = 10_000))
    }

    @Test fun r29_lostClockAfterRebootUsesTheRawFixTime() {
        assertEquals(TrustedTime.Estimated(BASE_UTC), resolve(1_000, mono = 5, boot = 4))
    }
}

class CalendarTest {
    @Test fun rangeIsHalfOpen() {
        val range = DayRange(CalendarDay(2025, 1, 1), CalendarDay(2025, 2, 1))
        assertTrue(CalendarDay(2025, 1, 1) in range)
        assertTrue(CalendarDay(2025, 1, 31) in range)
        assertFalse(CalendarDay(2025, 2, 1) in range)
    }

    @Test fun openBoundsCoverEverything() {
        assertTrue(CalendarDay(1999, 1, 1) in DayRange())
        assertTrue(CalendarDay(1999, 1, 1) in DayRange(toExclusive = CalendarDay(2000, 1, 1)))
    }

    @Test fun rangeHelpers() {
        assertEquals(DayRange(CalendarDay(2025, 6, 9), CalendarDay(2025, 6, 16)), DayRange.weekOf(CalendarDay(2025, 6, 15)))
        assertEquals(DayRange(CalendarDay(2025, 6, 9), CalendarDay(2025, 6, 16)), DayRange.weekOf(CalendarDay(2025, 6, 9)))
        assertEquals(DayRange(CalendarDay(2024, 2, 1), CalendarDay(2024, 3, 1)), DayRange.month(CalendarMonth(2024, 2)))
        assertEquals(DayRange(CalendarDay(2025, 1, 1), CalendarDay(2026, 1, 1)), DayRange.year(2025))
        assertEquals(DayRange(CalendarDay(2024, 11, 1), CalendarDay(2025, 3, 1)), DayRange.months(CalendarMonth(2024, 11), CalendarMonth(2025, 2)))
        assertEquals(DayRange(CalendarDay(2024, 6, 16), CalendarDay(2025, 6, 16)), DayRange.lastTwelveMonths(CalendarDay(2025, 6, 15)))
    }

    @Test fun epochDayRoundTripsAcrossLeapYears() {
        var day = CalendarDay(1999, 12, 30)
        repeat(1000) {
            assertEquals(day, CalendarDay.fromEpochDay(day.epochDay))
            day = day.plusDays(1)
        }
        assertEquals(CalendarDay(2024, 2, 29), CalendarDay(2024, 1, 31).plusMonths(1))
        assertNull(CalendarDay.of(2025, 2, 29))
    }
}

class NoticeRulesTest {
    private val base = NoticeInputs(
        gps = GpsStatus.GOOD, driving = DrivingState.DETENIDO, hasTrustedTime = true, hasOdometer = true,
        recordingGapPending = false, storageFull = false, lastExportUtcMs = BASE_UTC, nowUtcMs = BASE_UTC,
    )

    @Test fun nothingWrongMeansNoNotices() { assertTrue(NoticeRules.notices(base).isEmpty()) }

    @Test fun orderFollowsTheSpecification() {
        val all = base.copy(
            gps = GpsStatus.NO_PERMISSION, hasTrustedTime = false, hasOdometer = false,
            recordingGapPending = true, storageFull = true, lastExportUtcMs = null,
        )
        assertEquals(
            listOf(Notice.NO_LOCATION_PERMISSION, Notice.UNTRUSTED_CLOCK, Notice.ODOMETER_MISSING, Notice.RECORDING_GAP, Notice.STORAGE_FULL, Notice.EXPORT_OVERDUE),
            NoticeRules.notices(all),
        )
    }

    @Test fun exportOverdueAfterExactly30Days() {
        val limit = NoticeRules.EXPORT_OVERDUE_MS
        assertTrue(NoticeRules.notices(base.copy(nowUtcMs = BASE_UTC + limit)).isEmpty())
        assertEquals(listOf(Notice.EXPORT_OVERDUE), NoticeRules.notices(base.copy(nowUtcMs = BASE_UTC + limit + 1)))
    }

    @Test fun exportReminderOnlyWhenStopped() {
        assertTrue(NoticeRules.notices(base.copy(lastExportUtcMs = null, driving = DrivingState.DESCONOCIDO)).isEmpty())
        assertTrue(NoticeRules.notices(base.copy(lastExportUtcMs = null, driving = DrivingState.EN_MOVIMIENTO)).isEmpty())
    }
}

class BackupRulesTest {
    private val day = 24L * 60 * 60 * 1000
    private val week = 7 * day
    private val empty = BackupData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Settings())

    private fun copy(id: Int, created: Long) = StoredBackup("c$id", created, 0)

    @Test fun r32_checksumMustMatch() {
        assertEquals(BackupVerdict.ChecksumMismatch, BackupValidator.validate(backupFile(empty, declared = "a", actual = "b")))
        assertTrue(BackupValidator.validate(backupFile(empty)) is BackupVerdict.Valid)
    }

    @Test fun recordCountMustMatch() {
        assertEquals(BackupVerdict.RecordCountMismatch, BackupValidator.validate(backupFile(empty, declaredCount = 3)))
    }

    @Test fun r36_newerSchemaIsRejected() {
        assertEquals(BackupVerdict.NewerSchema(9), BackupValidator.validate(backupFile(empty, schema = 9), appSchema = 8))
        assertTrue(BackupValidator.validate(backupFile(empty, schema = 8), appSchema = 8) is BackupVerdict.Valid)
    }

    @Test fun r34_keepsTheLastSeven() {
        val sameWeekStart = 10 * week
        val inOneWeek = (0 until 9).map { copy(it, sameWeekStart + it) }
        assertEquals(listOf("c1", "c0"), BackupRetention.toDelete(inOneWeek).map { it.id })
    }

    @Test fun r34_keepsOnePerWeekForFourWeeks() {
        val base = 100 * week
        val daily = (0 until 42).map { copy(it, base + it * day) }
        val kept = daily - BackupRetention.toDelete(daily).toSet()
        val newest = daily.last().createdUtcMs
        val older = kept.filter { newest - it.createdUtcMs >= 7 * day }
        assertTrue(older.size in 3..4)
        assertTrue(older.all { newest - it.createdUtcMs < 28 * day })
        assertEquals(older.size, older.map { it.createdUtcMs / week }.toSet().size)
    }

    @Test fun r34_aWeeklyCopySurvivesAsItAges() {
        val base = 100 * week
        val daily = (0 until 21).map { copy(it, base + it * day) }
        val firstRound = daily - BackupRetention.toDelete(daily).toSet()
        val endOfFirstWeek = firstRound.first { it.createdUtcMs == base + 6 * day }
        val later = firstRound + (21 until 28).map { copy(it, base + it * day) }
        assertTrue(endOfFirstWeek in later - BackupRetention.toDelete(later).toSet())
    }

    @Test fun r33_theNewestCopyIsNeverDeleted() {
        val copies = (0 until 20).map { copy(it, it * week) }
        assertFalse("c19" in BackupRetention.toDelete(copies).map { it.id })
        assertTrue(BackupRetention.toDelete(emptyList()).isEmpty())
    }
}
