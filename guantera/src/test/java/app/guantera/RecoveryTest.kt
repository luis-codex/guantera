package app.guantera

import app.guantera.domain.backup.BackupData
import app.guantera.domain.expense.Expense
import app.guantera.domain.expense.ExpenseCategory
import app.guantera.domain.port.DataHealthChecker
import app.guantera.domain.port.DataHealthReport
import app.guantera.domain.settings.Settings
import app.guantera.domain.time.CalendarDay
import app.guantera.domain.usecase.CheckDataHealthAndRecover
import app.guantera.domain.usecase.ImportBackup
import app.guantera.presentation.port.CopyInfo
import app.guantera.presentation.port.ExportOutcome
import app.guantera.presentation.port.ImportOutcome
import app.guantera.presentation.port.RecoveryActions
import app.guantera.presentation.port.RecoveryCause
import app.guantera.presentation.port.RestoreOutcome
import app.guantera.presentation.port.StartupResult
import app.guantera.presentation.real.RealRecovery
import app.guantera.presentation.recovery.RecoveryDialog
import app.guantera.presentation.recovery.RecoveryPresenter
import app.guantera.real.RealRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeHealthChecker(var report: DataHealthReport, var canMove: Boolean = true) : DataHealthChecker {
    var moved = 0
    override suspend fun check() = report
    override suspend fun quarantineDatabase(): Boolean { moved += 1; return canMove }
}

private class FakeRecoveryActions(var start: StartupResult) : RecoveryActions {
    var restoreResult = RestoreOutcome.Restored
    var importResult: ImportOutcome = ImportOutcome.Done(5)
    var exportResult: ExportOutcome = ExportOutcome.DriveMissing
    var freshResult = true
    val restored = mutableListOf<String>()
    var fresh = 0
    override suspend fun open() = start
    override suspend fun restore(copyId: String): RestoreOutcome { restored += copyId; return restoreResult }
    override suspend fun importFromDrive() = importResult
    override suspend fun export() = exportResult
    override suspend fun startFresh(): Boolean { fresh += 1; return freshResult }
}

private val COPY = CopyInfo("copy0", 1_700_000_000_000L, 40)

/** P12 as the owner meets it: the screen's logic, then the real use cases under it. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryTest {
    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    // ---- The screen's logic ---------------------------------------------------------------

    @Test fun `starting from zero needs the confirmation, and asking alone changes nothing`() = test {
        val actions = FakeRecoveryActions(StartupResult.NeedsUser(RecoveryCause.NoValidCopy, emptyList()))
        val p = RecoveryPresenter(actions, actions.start, backgroundScope)
        p.confirmStartFresh() // nobody asked yet
        assertEquals(0, actions.fresh)
        p.askStartFresh()
        assertEquals(RecoveryDialog.ConfirmFresh, p.state.value.dialog)
        assertEquals(0, actions.fresh)
        p.dismissDialog() // "Cancelar"
        p.confirmStartFresh()
        assertEquals(0, actions.fresh)
        assertFalse(p.state.value.finished)
        p.askStartFresh()
        p.confirmStartFresh()
        assertEquals(1, actions.fresh)
        assertTrue(p.state.value.finished)
    }

    @Test fun `if the old data cannot be set aside nothing is started`() = test {
        val actions = FakeRecoveryActions(StartupResult.NeedsUser(RecoveryCause.NoValidCopy, emptyList()))
        actions.freshResult = false
        val p = RecoveryPresenter(actions, actions.start, backgroundScope)
        p.askStartFresh()
        p.confirmStartFresh()
        assertEquals(RecoveryDialog.FreshFailed, p.state.value.dialog)
        assertFalse(p.state.value.finished)
    }

    @Test fun `restoring a copy finishes, and a copy that fails reports it and stays`() = test {
        val actions = FakeRecoveryActions(StartupResult.NeedsUser(RecoveryCause.MigrationFailed, listOf(COPY)))
        val p = RecoveryPresenter(actions, actions.start, backgroundScope)
        assertEquals(listOf(COPY), p.state.value.copies)
        assertEquals(RecoveryCause.MigrationFailed, p.state.value.cause)
        actions.restoreResult = RestoreOutcome.Damaged
        p.restore("copy0")
        assertEquals(RecoveryDialog.RestoreFailed(RestoreOutcome.Damaged), p.state.value.dialog)
        assertFalse(p.state.value.finished)
        p.dismissDialog()
        actions.restoreResult = RestoreOutcome.Restored
        p.restore("copy0")
        assertTrue(p.state.value.finished)
    }

    @Test fun `import and export report their results without leaving the screen`() = test {
        val actions = FakeRecoveryActions(StartupResult.NeedsUser(RecoveryCause.NoValidCopy, emptyList()))
        val p = RecoveryPresenter(actions, actions.start, backgroundScope)
        actions.importResult = ImportOutcome.DriveMissing
        p.importFromDrive()
        assertEquals(RecoveryDialog.ImportFailed(ImportOutcome.DriveMissing), p.state.value.dialog)
        p.dismissDialog()
        p.export()
        assertEquals(RecoveryDialog.ExportReport(ExportOutcome.DriveMissing), p.state.value.dialog)
        assertFalse(p.state.value.finished)
        p.dismissDialog()
        actions.importResult = ImportOutcome.Done(12)
        p.importFromDrive()
        assertTrue(p.state.value.finished)
    }

    @Test fun `a restored copy is announced first and the app opens after the acknowledgement`() = test {
        val actions = FakeRecoveryActions(StartupResult.Restored(COPY))
        val p = RecoveryPresenter(actions, actions.start, backgroundScope)
        assertEquals(COPY, p.state.value.restored)
        assertFalse(p.state.value.finished)
        p.acknowledgeRestored()
        assertTrue(p.state.value.finished)
    }

    // ---- The real use cases ---------------------------------------------------------------

    private class Rig(scope: kotlinx.coroutines.CoroutineScope, report: DataHealthReport, canMove: Boolean = true) {
        val rig = RealRig(scope)
        val checker = FakeHealthChecker(report, canMove)
        val recovery = RealRecovery(
            check = CheckDataHealthAndRecover(checker, rig.db.backupStore, rig.graph.backupData),
            importBackup = ImportBackup(rig.graph.backupData, rig.db.backupStore, rig.transfer, rig.graph.automaticBackup, rig.graph.observeDrivingState),
            store = rig.db.backupStore,
            backups = rig.backups,
        )
        val saved = BackupData(
            trips = emptyList(), calibrations = emptyList(), items = emptyList(), services = emptyList(),
            expenses = listOf(Expense(7, ExpenseCategory.SEGURO, 9_000_00, CalendarDay(2026, 3, 1), null, null)),
            parkingSpots = emptyList(), settings = Settings(currency = "R$", firstUseComplete = true),
        )
    }

    @Test fun `a healthy database opens the app without asking anything`() = test {
        val r = Rig(backgroundScope, DataHealthReport(databaseIntact = true, migrationFailed = false, consecutiveFailedOpens = 0))
        assertEquals(StartupResult.Healthy, r.recovery.open())
        assertEquals(0, r.checker.moved)
    }

    @Test fun `a damaged database with a copy is set aside and the newest copy comes back`() = test {
        val r = Rig(backgroundScope, DataHealthReport(databaseIntact = false, migrationFailed = false, consecutiveFailedOpens = 1))
        r.rig.db.backupStore.save(r.saved, 1_700_000_000_000L)
        val result = r.recovery.open()
        assertEquals(1, r.checker.moved) // set aside, never deleted
        assertTrue(result is StartupResult.Restored)
        assertEquals(r.saved.recordCount, (result as StartupResult.Restored).copy.recordCount)
        assertEquals("R$", r.rig.db.settingsRepo.get().currency)
        assertEquals(1, r.rig.db.expenseRepo.inRange(app.guantera.domain.time.DayRange()).size)
    }

    @Test fun `a damaged database without a copy asks the owner, with the old data already set aside`() = test {
        val r = Rig(backgroundScope, DataHealthReport(databaseIntact = false, migrationFailed = false, consecutiveFailedOpens = 1))
        val result = r.recovery.open()
        assertEquals(StartupResult.NeedsUser(RecoveryCause.NoValidCopy, emptyList()), result)
        assertEquals(1, r.checker.moved)
        // "Empezar de cero" does not move anything twice.
        assertTrue(r.recovery.startFresh())
        assertEquals(1, r.checker.moved)
    }

    @Test fun `a database that cannot be moved is reported and nothing is replaced`() = test {
        val r = Rig(backgroundScope, DataHealthReport(databaseIntact = false, migrationFailed = false, consecutiveFailedOpens = 1), canMove = false)
        r.rig.db.backupStore.save(r.saved, 1_700_000_000_000L)
        val result = r.recovery.open()
        assertTrue(result is StartupResult.NeedsUser)
        assertEquals(RecoveryCause.DatabaseNotMoved, (result as StartupResult.NeedsUser).cause)
        assertEquals("$", r.rig.db.settingsRepo.get().currency)
    }

    @Test fun `a failed migration offers the copies, and restoring one sets the current data aside first`() = test {
        val r = Rig(backgroundScope, DataHealthReport(databaseIntact = true, migrationFailed = true, consecutiveFailedOpens = 0))
        r.rig.db.backupStore.save(r.saved, 1_700_000_000_000L)
        val result = r.recovery.open() as StartupResult.NeedsUser
        assertEquals(RecoveryCause.MigrationFailed, result.cause)
        assertEquals(1, result.copies.size)
        assertEquals(0, r.checker.moved)
        assertEquals(RestoreOutcome.Restored, r.recovery.restore(result.copies.first().id))
        assertEquals(1, r.checker.moved)
        assertEquals("R$", r.rig.db.settingsRepo.get().currency)
    }

    @Test fun `three failed opens in a row open P12 as well`() = test {
        val r = Rig(backgroundScope, DataHealthReport(databaseIntact = true, migrationFailed = false, consecutiveFailedOpens = 3))
        val result = r.recovery.open() as StartupResult.NeedsUser
        assertEquals(RecoveryCause.RepeatedCrashes, result.cause)
    }

    @Test fun `if even the check cannot run, P12 opens instead of crashing`() = test {
        val r = Rig(backgroundScope, DataHealthReport(true, false, 0))
        val broken = RealRecovery(
            check = CheckDataHealthAndRecover(
                object : DataHealthChecker {
                    override suspend fun check(): DataHealthReport = throw IllegalStateException("disk gone")
                    override suspend fun quarantineDatabase() = false
                },
                r.rig.db.backupStore, r.rig.graph.backupData,
            ),
            importBackup = ImportBackup(r.rig.graph.backupData, r.rig.db.backupStore, r.rig.transfer, r.rig.graph.automaticBackup, r.rig.graph.observeDrivingState),
            store = r.rig.db.backupStore,
            backups = r.rig.backups,
        )
        assertEquals(StartupResult.NeedsUser(RecoveryCause.CheckFailed, emptyList()), broken.open())
    }
}
