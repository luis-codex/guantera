package app.guantera

import app.guantera.presentation.common.DisplayFormat
import app.guantera.presentation.expenses.CostPerKm
import app.guantera.presentation.expenses.ExpenseCategory
import app.guantera.presentation.expenses.ExpensePeriod
import app.guantera.presentation.expenses.ExpensesPage
import app.guantera.presentation.expenses.ExpensesPresenter
import app.guantera.presentation.input.CalendarDate
import app.guantera.presentation.input.FormError
import app.guantera.presentation.input.FormField
import app.guantera.presentation.input.FormGate
import app.guantera.presentation.input.LedgerFormat
import app.guantera.presentation.input.PadEntry
import app.guantera.presentation.input.PadExtra
import app.guantera.presentation.input.PadKey
import app.guantera.presentation.input.PadSpec
import app.guantera.presentation.maintenance.ConfirmKind
import app.guantera.presentation.maintenance.DataOrigin
import app.guantera.presentation.maintenance.MaintenanceItem
import app.guantera.presentation.maintenance.MaintenancePage
import app.guantera.presentation.maintenance.MaintenancePresenter
import app.guantera.presentation.maintenance.MaintenanceRules
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.MaintenanceLevel
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.port.Remaining
import app.guantera.presentation.port.VehicleProfile
import app.guantera.presentation.port.VehicleStatus
import app.guantera.sample.SampleExpenseData
import app.guantera.sample.SampleMaintenanceData
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
import java.util.TimeZone

private val TODAY = CalendarDate(2026, 6, 15)

class CalendarDateTest {
    @Test fun `epoch day round trips across leap years and the epoch`() {
        assertEquals(0, CalendarDate(1970, 1, 1).epochDay)
        for (day in listOf(-1000, 0, 365, 11_016, 20_000, 30_000)) {
            assertEquals(day, CalendarDate.fromEpochDay(day).epochDay)
        }
        assertEquals(CalendarDate(2028, 2, 29), CalendarDate(2028, 2, 28).plusDays(1))
        assertEquals(CalendarDate(2027, 3, 1), CalendarDate(2027, 2, 28).plusDays(1))
    }

    @Test fun `adding months keeps the day or falls to the end of a shorter month`() {
        assertEquals(CalendarDate(2027, 2, 28), CalendarDate(2026, 8, 31).plusMonths(6))
        assertEquals(CalendarDate(2025, 12, 15), TODAY.plusMonths(-6))
        assertEquals(CalendarDate(2027, 6, 15), TODAY.plusMonths(12))
    }

    @Test fun `only real days are accepted`() {
        assertNull(CalendarDate.of(2026, 2, 30))
        assertNull(CalendarDate.of(2026, 13, 1))
        assertNull(CalendarDate.of(1980, 1, 1))
        assertNotNull(CalendarDate.of(2028, 2, 29))
    }
}

class PadEntryTest {
    private val km = PadSpec.Integer(7)
    private val money = PadSpec.Decimal(9, 2)

    private fun PadEntry.type(text: String): PadEntry = text.fold(this) { entry, c ->
        entry.press(if (c == ',') PadKey.Comma else PadKey.Digit(c.digitToInt()))
    }

    @Test fun `whole numbers ignore leading zeros and group thousands with a dot`() {
        val entry = PadEntry(km).type("0048250")
        assertEquals("48.250", entry.display())
        assertEquals(48_250, entry.intValue())
        assertEquals("0", PadEntry(km).type("000").typed)
    }

    @Test fun `whole numbers stop at the digit limit`() {
        assertEquals(7, PadEntry(km).type("123456789").typed.length)
    }

    @Test fun `backspace removes one digit and an emptied field has no value`() {
        val entry = PadEntry(km).type("12").press(PadKey.Backspace).press(PadKey.Backspace)
        assertTrue(entry.isEmpty)
        assertNull(entry.intValue())
        assertEquals("", entry.display())
    }

    @Test fun `money takes one comma and two decimals`() {
        val entry = PadEntry(money).type("18500,5")
        assertEquals("18.500,5", entry.display())
        assertEquals(1_850_050L, entry.minorValue())
        assertEquals("18500,50", PadEntry(money).type("18500,509").typed)
        assertEquals("0,", PadEntry(money).press(PadKey.Comma).typed)
        assertEquals("1,", PadEntry(money).type("1,").press(PadKey.Comma).typed)
    }

    @Test fun `zero money has no value and whole money converts to centavos`() {
        assertNull(PadEntry(money).type("0").minorValue())
        assertEquals(250_000L, PadEntry(money).type("2500").minorValue())
        assertEquals("2.500,25", PadEntry.ofMinor(PadSpec.Decimal(9, 2), 250_025L).display())
    }

    @Test fun `the spare key depends on the field`() {
        assertEquals(PadExtra.None, PadEntry(km).extra)
        assertEquals(PadExtra.Comma, PadEntry(money).extra)
        assertEquals(PadExtra.Today, PadEntry.ofDate(null).extra)
    }

    @Test fun `dates show blanks until the eight digits are in and refuse days that do not exist`() {
        val partial = PadEntry.ofDate(null).type("1011")
        assertEquals("10/11/____", partial.display())
        assertTrue(partial.isIncompleteDate)
        assertEquals(CalendarDate(2026, 11, 10), partial.type("2026").dateValue())
        assertNull(PadEntry.ofDate(null).type("31022026").dateValue())
        assertEquals("__/__/____", PadEntry.ofDate(null).displayOrBlank())
    }

    @Test fun `the Hoy key fills the whole date`() {
        val entry = PadEntry.ofDate(null).press(PadKey.Today, TODAY)
        assertEquals(TODAY, entry.dateValue())
        assertEquals("15/06/2026", entry.display())
    }
}

class MaintenanceRulesTest {
    private fun item(
        km: Int? = null, months: Int? = null, fixed: CalendarDate? = null,
        lastKm: Int? = null, lastDate: CalendarDate? = null,
    ) = MaintenanceItem(1, "x", km, months, fixed, lastKm, lastDate, DataOrigin.Own)

    private fun level(item: MaintenanceItem, odo: Int? = 50_000) = MaintenanceRules.evaluate(item, odo, TODAY).level

    @Test fun `kilometers are overdue past the due km, next within a tenth and otherwise up to date`() {
        val oil = item(km = 10_000, lastKm = 40_000) // due at 50.000
        assertEquals(MaintenanceLevel.Overdue, level(oil, odo = 50_001))
        assertEquals(MaintenanceLevel.Soon, level(oil, odo = 50_000))
        assertEquals(MaintenanceLevel.Soon, level(oil, odo = 49_000))
        assertEquals(MaintenanceLevel.UpToDate, level(oil, odo = 48_999))
    }

    @Test fun `the next threshold is the smaller of 1000 km and a tenth of the interval`() {
        val spark = item(km = 5_000, lastKm = 45_000) // a tenth is 500
        assertEquals(MaintenanceLevel.UpToDate, level(spark, odo = 49_400))
        assertEquals(MaintenanceLevel.Soon, level(spark, odo = 49_600))
    }

    @Test fun `months count from the last service and the due day is not yet overdue`() {
        val filter = item(months = 12, lastDate = CalendarDate(2025, 6, 15))
        val status = MaintenanceRules.evaluate(filter, null, TODAY)
        assertEquals(MaintenanceLevel.Soon, status.level)
        assertEquals(0, status.amount)
        assertEquals(MaintenanceLevel.Overdue, MaintenanceRules.evaluate(filter, null, TODAY.plusDays(1)).level)
    }

    @Test fun `a fixed date is next within 30 days and wins over the months`() {
        assertEquals(MaintenanceLevel.Soon, level(item(fixed = TODAY.plusDays(30))))
        assertEquals(MaintenanceLevel.UpToDate, level(item(fixed = TODAY.plusDays(31))))
        val both = item(months = 12, lastDate = TODAY.plusMonths(-1), fixed = TODAY.plusDays(-3))
        assertEquals(MaintenanceLevel.Overdue, level(both))
    }

    @Test fun `the worst axis decides`() {
        val both = item(km = 10_000, lastKm = 45_000, months = 6, lastDate = TODAY.plusMonths(-8))
        val status = MaintenanceRules.evaluate(both, 50_000, TODAY)
        assertEquals(MaintenanceLevel.Overdue, status.level)
        assertEquals(app.guantera.presentation.maintenance.Axis.Days, status.axis)
    }

    @Test fun `without data there is no verdict and no alert`() {
        val noLast = item(km = 10_000, months = 12)
        assertEquals(MaintenanceLevel.NoData, level(noLast))
        assertEquals(MaintenanceLevel.NoData, level(item(km = 10_000, lastKm = 40_000), odo = null))
        assertNull(MaintenanceRules.urgent(listOf(noLast), 50_000, TODAY))
    }

    @Test fun `the urgent alert is the most overdue item`() {
        val a = item(km = 10_000, lastKm = 39_000).copy(id = 1, name = "A") // 1.000 over
        val b = item(km = 10_000, lastKm = 30_000).copy(id = 2, name = "B") // 10.000 over
        val alert = MaintenanceRules.urgent(listOf(a, b), 50_000, TODAY)!!
        assertEquals("B", alert.itemName)
        assertEquals(Remaining.Km(10_000), alert.remaining)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerPresentersTest {
    private val clock = FakeClock(TODAY.epochDay * 86_400_000L + 12 * 3_600_000L)
    private val format = LedgerFormat(DisplayFormat(TimeZone.getTimeZone("UTC")))
    private val utc = TimeZone.getTimeZone("UTC")
    private val profile = MutableStateFlow(VehicleProfile("Auto", SampleMaintenanceData.ODOMETER_KM, "$"))
    private val status = MutableStateFlow(VehicleStatus(GpsStatus.Good, MotionStatus.Stopped))
    private val ticks = MutableStateFlow(0L)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private class Rig(
        val expenseData: SampleExpenseData,
        val maintenanceData: SampleMaintenanceData,
        val maintenance: MaintenancePresenter,
        val expenses: ExpensesPresenter,
    )

    private fun TestScope.rig(): Rig {
        val gate = FormGate(status, clock, backgroundScope)
        val expenseData = SampleExpenseData(clock)
        val maintenanceData = SampleMaintenanceData(clock, expenseData)
        val maintenance = MaintenancePresenter(maintenanceData, profile, gate, clock, format, ticks, backgroundScope, utc)
        val expenses = ExpensesPresenter(expenseData, profile, gate, clock, format, ticks, backgroundScope, utc)
        backgroundScope.launch { maintenance.state.collect {} }
        backgroundScope.launch { expenses.state.collect {} }
        return Rig(expenseData, maintenanceData, maintenance, expenses)
    }

    private fun MaintenancePresenter.page() = state.value.page
    private fun MaintenancePresenter.overview() = (page() as MaintenancePage.Overview).ui
    private fun ExpensesPresenter.page() = state.value.page
    private fun ExpensesPresenter.overview() = (page() as ExpensesPage.Overview).ui

    private fun MaintenancePresenter.type(text: String) = text.forEach { press(PadKey.Digit(it.digitToInt())) }
    private fun ExpensesPresenter.type(text: String) =
        text.forEach { press(if (it == ',') PadKey.Comma else PadKey.Digit(it.digitToInt())) }

    // ---- Maintenance ----------------------------------------------------------------------

    @Test fun `the list puts overdue first and pages of three`() = test {
        val r = rig()
        val ui = r.maintenance.overview()
        assertEquals(3, ui.rows.size)
        assertEquals(MaintenanceLevel.Overdue, ui.rows.first().level)
        assertEquals("Aceite y filtro", ui.rows.first { it.level == MaintenanceLevel.Overdue && it.amountText == "350" }.name)
        assertEquals(17, ui.totalCount)
        assertEquals(6, ui.pageCount)
        assertTrue(ui.overdueCount >= 3)
        // Rows never go from a calmer level back to a more urgent one.
        val levels = (0 until ui.pageCount).flatMap { page ->
            while (r.maintenance.overview().pageIndex < page) r.maintenance.nextPage()
            r.maintenance.overview().rows.map { it.level.ordinal }
        }
        assertEquals(levels.sorted(), levels)
    }

    @Test fun `registering a service with a cost updates the item and creates the expense`() = test {
        val r = rig()
        val oil = r.maintenanceData.items.value.first { it.name == "Aceite y filtro" }
        val before = r.expenseData.expenses.value.size
        r.maintenance.markDone(oil.id)
        val form = r.maintenance.page() as MaintenancePage.ServiceForm
        assertEquals("48.250", form.fields.first { it.field == FormField.ServiceKm }.valueText)
        r.maintenance.type("95000")
        r.maintenance.save()
        val updated = r.maintenanceData.items.value.first { it.id == oil.id }
        assertEquals(48_250, updated.lastServiceKm)
        assertEquals(TODAY, updated.lastServiceDate)
        val created = r.expenseData.expenses.value.last()
        assertEquals(before + 1, r.expenseData.expenses.value.size)
        assertEquals(ExpenseCategory.Maintenance, created.category)
        assertEquals(9_500_000L, created.amountMinor)
        assertNotNull(created.serviceRecordId)
        assertTrue(r.maintenance.page() is MaintenancePage.Overview)
    }

    @Test fun `a service without a cost creates no expense`() = test {
        val r = rig()
        val before = r.expenseData.expenses.value.size
        r.maintenance.markDone(r.maintenanceData.items.value.first().id)
        r.maintenance.save()
        assertEquals(before, r.expenseData.expenses.value.size)
    }

    @Test fun `a service for an item with a fixed date asks for the new due date`() = test {
        val r = rig()
        val insurance = r.maintenanceData.items.value.first { it.name == "Seguro" }
        r.maintenance.markDone(insurance.id)
        r.maintenance.save()
        assertEquals(FormError.NewDueRequired, (r.maintenance.page() as MaintenancePage.ServiceForm).error)
        r.maintenance.selectField(FormField.NewDue)
        r.maintenance.press(PadKey.Today)
        r.maintenance.save()
        assertEquals(FormError.NewDueTooEarly, (r.maintenance.page() as MaintenancePage.ServiceForm).error)
        // 15/06/2027, one year later.
        repeat(8) { r.maintenance.press(PadKey.Backspace) }
        r.maintenance.type("15062027")
        r.maintenance.save()
        assertEquals(TODAY.plusMonths(12), r.maintenanceData.items.value.first { it.id == insurance.id }.fixedDueDate)
    }

    @Test fun `a service for an item measured in km needs the km`() = test {
        val r = rig()
        profile.value = profile.value.copy(odometerKm = 0)
        r.maintenance.markDone(r.maintenanceData.items.value.first { it.name == "Aceite y filtro" }.id)
        r.maintenance.save()
        assertEquals(FormError.KmRequired, (r.maintenance.page() as MaintenancePage.ServiceForm).error)
        profile.value = profile.value.copy(odometerKm = SampleMaintenanceData.ODOMETER_KM)
    }

    @Test fun `a new item needs a name and at least one rule and then appears in the list`() = test {
        val r = rig()
        r.maintenance.addItem()
        r.maintenance.save()
        assertEquals(FormError.NameRequired, (r.maintenance.page() as MaintenancePage.ItemForm).error)
        r.maintenance.setText(FormField.Name, "Cadena de moto")
        r.maintenance.save()
        assertEquals(FormError.NeedsRule, (r.maintenance.page() as MaintenancePage.ItemForm).error)
        r.maintenance.selectField(FormField.IntervalKm)
        r.maintenance.type("3000")
        r.maintenance.save()
        val saved = r.maintenanceData.items.value.last()
        assertEquals("Cadena de moto", saved.name)
        assertEquals(3_000, saved.intervalKm)
        assertEquals(DataOrigin.Own, saved.origin)
        assertEquals(18, r.maintenance.overview().totalCount)
    }

    @Test fun `a date that does not exist or lies in the future is refused`() = test {
        val r = rig()
        r.maintenance.addItem()
        r.maintenance.setText(FormField.Name, "Algo")
        r.maintenance.selectField(FormField.IntervalKm)
        r.maintenance.type("1000")
        r.maintenance.selectField(FormField.LastDate)
        r.maintenance.type("31022026")
        r.maintenance.save()
        assertEquals(FormError.InvalidDate, (r.maintenance.page() as MaintenancePage.ItemForm).error)
        repeat(8) { r.maintenance.press(PadKey.Backspace) }
        r.maintenance.type("01012030")
        r.maintenance.save()
        assertEquals(FormError.FutureDate, (r.maintenance.page() as MaintenancePage.ItemForm).error)
    }

    @Test fun `editing the interval of a catalog item marks it as the driver's own data`() = test {
        val r = rig()
        val oil = r.maintenanceData.items.value.first { it.name == "Aceite y filtro" }
        assertEquals(DataOrigin.Verified, oil.origin)
        r.maintenance.openItem(oil.id)
        repeat(7) { r.maintenance.press(PadKey.Backspace) }
        r.maintenance.type("7500")
        r.maintenance.save()
        assertEquals(DataOrigin.Own, r.maintenanceData.items.value.first { it.id == oil.id }.origin)
    }

    @Test fun `deleting an item asks first and No keeps it`() = test {
        val r = rig()
        val item = r.maintenanceData.items.value.first()
        r.maintenance.openItem(item.id)
        r.maintenance.askDelete()
        assertEquals(ConfirmKind.DeleteItem, r.maintenance.state.value.confirm?.kind)
        r.maintenance.confirmNo()
        assertEquals(17, r.maintenanceData.items.value.size)
        r.maintenance.askDelete()
        r.maintenance.confirmYes()
        assertEquals(16, r.maintenanceData.items.value.size)
        assertTrue(r.maintenance.page() is MaintenancePage.Overview)
    }

    @Test fun `restoring the catalog brings back only what was deleted`() = test {
        val r = rig()
        r.maintenanceData.delete(r.maintenanceData.items.value.first { it.name == "Bujías" }.id)
        r.maintenance.restoreCatalog()
        assertEquals(17, r.maintenanceData.items.value.size)
        assertNull(r.maintenanceData.items.value.last().lastServiceKm)
        r.maintenance.restoreCatalog()
        assertEquals(17, r.maintenanceData.items.value.size)
    }

    @Test fun `with an unknown driving state a form opens only after the driver confirms`() = test {
        val r = rig()
        status.value = VehicleStatus(GpsStatus.Searching, MotionStatus.Unknown)
        r.maintenance.addItem()
        assertEquals(ConfirmKind.ParkedCheck, r.maintenance.state.value.confirm?.kind)
        assertTrue(r.maintenance.page() is MaintenancePage.Overview)
        r.maintenance.confirmNo()
        assertTrue(r.maintenance.page() is MaintenancePage.Overview)
        r.maintenance.addItem()
        r.maintenance.confirmYes()
        assertTrue(r.maintenance.page() is MaintenancePage.ItemForm)
        // The confirmation also covers the expense form for ten minutes.
        r.maintenance.cancel()
        r.expenses.newExpense()
        assertNull(r.expenses.state.value.confirm)
        clock.now += 10 * 60_000L
        r.expenses.cancel()
        r.expenses.newExpense()
        assertEquals(ConfirmKind.ParkedCheck, r.expenses.state.value.confirm?.kind)
        status.value = VehicleStatus(GpsStatus.Good, MotionStatus.Stopped)
    }

    @Test fun `an open draft survives the screen being rebuilt and back leaves the form`() = test {
        val r = rig()
        r.maintenance.addItem()
        r.maintenance.setText(FormField.Name, "Pastillas")
        r.maintenance.type("20000")
        // A new screen reads the same presenter state.
        val form = r.maintenance.page() as MaintenancePage.ItemForm
        assertEquals("Pastillas", form.name)
        assertEquals("20.000", form.fields.first { it.field == FormField.IntervalKm }.valueText)
        assertTrue(r.maintenance.onBack())
        assertTrue(r.maintenance.page() is MaintenancePage.Overview)
        assertFalse(r.maintenance.onBack())
    }

    // ---- Expenses -------------------------------------------------------------------------

    @Test fun `cost per kilometer divides the period's spending by the period's km`() = test {
        val r = rig()
        val ui = r.expenses.overview()
        val inPeriod = r.expenseData.expenses.value.filter { it.date > TODAY.plusMonths(-12) && it.date <= TODAY }
        val km = r.expenseData.tripKilometers(TODAY.plusMonths(-12).plusDays(1), TODAY)
        val expected = inPeriod.sumOf { it.amountMinor } / 100.0 / km
        assertEquals(format.moneyExact(expected), (ui.costPerKm as CostPerKm.Value).text)
        assertEquals(ExpensePeriod.TwelveMonths, ui.period)
    }

    @Test fun `under 50 km the cost per kilometer is not calculated`() = test {
        val r = rig()
        // Sample trips run 36 km a day, so the first day of a month has too little.
        val first = FakeClock(CalendarDate(2026, 6, 1).epochDay * 86_400_000L + 12 * 3_600_000L)
        val data = SampleExpenseData(first)
        val gate = FormGate(status, first, backgroundScope)
        val presenter = ExpensesPresenter(data, profile, gate, first, format, ticks, backgroundScope, utc)
        backgroundScope.launch { presenter.state.collect {} }
        presenter.selectPeriod(ExpensePeriod.ThisMonth)
        assertEquals(CostPerKm.TooFewKm, presenter.overview().costPerKm)
        presenter.selectPeriod(ExpensePeriod.All)
        assertTrue(presenter.overview().costPerKm is CostPerKm.Value)
    }

    @Test fun `the period selector narrows the list`() = test {
        val r = rig()
        r.expenses.selectPeriod(ExpensePeriod.All)
        val all = r.expenses.overview()
        r.expenses.selectPeriod(ExpensePeriod.ThisMonth)
        val month = r.expenses.overview()
        assertTrue(month.rows.size <= 3)
        assertTrue(all.pageCount > month.pageCount)
        assertTrue(all.totalText != month.totalText)
    }

    @Test fun `a new expense needs category and amount, then shows first in the list`() = test {
        val r = rig()
        r.expenses.newExpense()
        r.expenses.save()
        assertEquals(FormError.CategoryRequired, (r.expenses.page() as ExpensesPage.Form).error)
        r.expenses.selectCategory(ExpenseCategory.Tolls)
        r.expenses.save()
        assertEquals(FormError.AmountRequired, (r.expenses.page() as ExpensesPage.Form).error)
        r.expenses.type("4500,5")
        r.expenses.save()
        val row = r.expenses.overview().rows.first()
        assertEquals(ExpenseCategory.Tolls, row.category)
        assertEquals("4.500,50", row.amountText)
        assertEquals("15/06/2026", row.dateText)
        assertEquals(TODAY, r.expenseData.expenses.value.last().date)
    }

    @Test fun `editing an expense keeps its link to the service and deleting asks first`() = test {
        val r = rig()
        val oil = r.maintenanceData.items.value.first { it.name == "Aceite y filtro" }
        r.maintenance.markDone(oil.id)
        r.maintenance.type("1000")
        r.maintenance.save()
        val linked = r.expenseData.expenses.value.last()
        r.expenses.openExpense(linked.id)
        assertTrue((r.expenses.page() as ExpensesPage.Form).linkedToService)
        r.expenses.type("5")
        r.expenses.save()
        assertEquals(linked.serviceRecordId, r.expenseData.expenses.value.first { it.id == linked.id }.serviceRecordId)
        r.expenses.openExpense(linked.id)
        r.expenses.askDelete()
        assertEquals(ConfirmKind.DeleteExpense, r.expenses.state.value.confirm?.kind)
        r.expenses.confirmNo()
        assertTrue(r.expenseData.expenses.value.any { it.id == linked.id })
        r.expenses.askDelete()
        r.expenses.confirmYes()
        assertFalse(r.expenseData.expenses.value.any { it.id == linked.id })
        // The service stays: the item keeps the last service the form recorded.
        assertEquals(TODAY, r.maintenanceData.items.value.first { it.id == oil.id }.lastServiceDate)
    }

    @Test fun `an empty period says so`() = test {
        val r = rig()
        r.expenseData.expenses.value.forEach { r.expenseData.delete(it.id) }
        val ui = r.expenses.overview()
        assertTrue(ui.isEmpty)
        assertEquals(1, ui.pageCount)
        assertEquals("0", ui.totalText)
    }
}
