package app.guantera

import app.guantera.domain.backup.BackupData
import app.guantera.domain.backup.BackupFile
import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.backup.CURRENT_BACKUP_SCHEMA
import app.guantera.domain.expense.Expense
import app.guantera.domain.expense.ExpenseCategory
import app.guantera.domain.settings.Settings
import app.guantera.domain.time.CalendarDay
import app.guantera.domain.time.DayRange
import app.guantera.presentation.port.ExportOutcome
import app.guantera.presentation.port.ImportOutcome
import app.guantera.real.RealRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exportar e Importar de P11 con cada resultado posible, sobre los casos de uso reales. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupActionsTest {
    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private val incoming = BackupData(
        trips = emptyList(), calibrations = emptyList(), items = emptyList(), services = emptyList(),
        expenses = listOf(Expense(3, ExpenseCategory.LAVADO, 12_000_00, CalendarDay(2026, 2, 2), null, null)),
        parkingSpots = emptyList(), settings = Settings(currency = "€", firstUseComplete = true),
    )

    private fun file(schema: Int = CURRENT_BACKUP_SCHEMA, checksum: String = "ok", declared: Int = incoming.recordCount) =
        BackupReadResult.Read(BackupFile(schema, 1_700_000_000_000L, declared, "ok", checksum, incoming))

    private fun RealRig.driveWith(vararg named: Pair<String, BackupReadResult>) { named.forEach { transfer.files[it.first] = it.second } }

    // ---- Exportar -------------------------------------------------------------------------

    @Test fun `exporting reaches the pendrive, says where, and marks the export date`() = test {
        val rig = RealRig(backgroundScope)
        val result = rig.backups.export()
        assertTrue(result is ExportOutcome.Done)
        assertEquals(1, rig.transfer.exports)
        assertEquals(rig.clock.utc, rig.db.settingsRepo.get().lastExportUtcMs)
    }

    @Test fun `without a pendrive nothing is exported and the date does not move`() = test {
        val rig = RealRig(backgroundScope)
        rig.transfer.present = false
        assertEquals(ExportOutcome.DriveMissing, rig.backups.export())
        assertNull(rig.db.settingsRepo.get().lastExportUtcMs)
    }

    @Test fun `a full pendrive is told apart`() = test {
        val rig = RealRig(backgroundScope)
        rig.transfer.full = true
        assertEquals(ExportOutcome.DriveFull, rig.backups.export())
        assertNull(rig.db.settingsRepo.get().lastExportUtcMs)
    }

    @Test fun `a pendrive that fails while writing is a failure, not a success`() = test {
        val rig = RealRig(backgroundScope)
        rig.transfer.failExport = true
        assertEquals(ExportOutcome.Failed, rig.backups.export())
        assertNull(rig.db.settingsRepo.get().lastExportUtcMs)
    }

    // ---- Importar -------------------------------------------------------------------------

    @Test fun `importing the newest valid copy replaces the data`() = test {
        val rig = RealRig(backgroundScope)
        rig.driveWith("backup-1000-1" to file(), "backup-2000-1" to file())
        assertEquals(ImportOutcome.Done(incoming.recordCount), rig.backups.importFromDrive())
        assertEquals("€", rig.db.settingsRepo.get().currency)
        assertEquals(1, rig.db.expenseRepo.inRange(DayRange()).size)
    }

    @Test fun `no pendrive and a pendrive without copies are different messages`() = test {
        val rig = RealRig(backgroundScope)
        rig.transfer.present = false
        assertEquals(ImportOutcome.DriveMissing, rig.backups.importFromDrive())
        rig.transfer.present = true
        assertEquals(ImportOutcome.NoCopy, rig.backups.importFromDrive())
    }

    @Test fun `a damaged copy is refused and the data stays`() = test {
        val rig = RealRig(backgroundScope)
        rig.driveWith("backup-1000-1" to file(checksum = "other"))
        assertEquals(ImportOutcome.Damaged, rig.backups.importFromDrive())
        assertEquals("$", rig.db.settingsRepo.get().currency)
        rig.transfer.files.clear()
        rig.driveWith("backup-1000-1" to file(declared = 99))
        assertEquals(ImportOutcome.Damaged, rig.backups.importFromDrive())
    }

    @Test fun `an unreadable copy is reported`() = test {
        val rig = RealRig(backgroundScope)
        rig.driveWith("backup-1000-1" to BackupReadResult.Unreadable("not json"))
        assertEquals(ImportOutcome.Unreadable, rig.backups.importFromDrive())
    }

    @Test fun `a copy from a newer version is refused`() = test {
        val rig = RealRig(backgroundScope)
        rig.driveWith("backup-1000-1" to file(schema = CURRENT_BACKUP_SCHEMA + 1))
        assertEquals(ImportOutcome.NewerVersion, rig.backups.importFromDrive())
        assertEquals("$", rig.db.settingsRepo.get().currency)
    }

    @Test fun `a failing disk fails the import and leaves the data as it was`() = test {
        val rig = RealRig(backgroundScope)
        rig.driveWith("backup-1000-1" to file())
        rig.db.simulatedFailure = app.guantera.domain.port.StorageFailure.IO
        assertEquals(ImportOutcome.StorageFailed, rig.backups.importFromDrive())
        rig.db.simulatedFailure = null
        assertEquals("$", rig.db.settingsRepo.get().currency)
    }

    @Test fun `with data already there a copy of it is kept before replacing`() = test {
        val rig = RealRig(backgroundScope)
        rig.db.expenseRepo.save(Expense(0, ExpenseCategory.OTROS, 100_00, CalendarDay(2026, 1, 1), null, null))
        rig.driveWith("backup-1000-1" to file())
        assertEquals(ImportOutcome.Done(incoming.recordCount), rig.backups.importFromDrive())
        assertEquals(1, rig.db.backupStore.list().size) // the safety copy of what was there
    }

    @Test fun `the date of the last copy is the newest kept in the radio`() = test {
        val rig = RealRig(backgroundScope)
        assertNull(rig.backups.lastCopyUtcMs())
        rig.db.backupStore.save(incoming, 1_000L)
        rig.db.backupStore.save(incoming, 5_000L)
        assertEquals(5_000L, rig.backups.lastCopyUtcMs())
    }
}
