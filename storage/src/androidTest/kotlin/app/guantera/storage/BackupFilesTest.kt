package app.guantera.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guantera.domain.backup.BackupReadResult
import app.guantera.domain.backup.BackupValidator
import app.guantera.domain.backup.BackupVerdict
import app.guantera.domain.odometer.OdometerCalibration
import app.guantera.domain.port.BackupWriteResult
import app.guantera.domain.port.ExternalExportResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupFilesTest {
    private lateinit var storage: StorageComponents
    private lateinit var dir: File
    private lateinit var store: FileBackupStore

    @Before fun setUp() {
        resetStorageFiles()
        storage = StorageFactory.create(appContext)
        dir = newBackupDir("backups")
        store = FileBackupStore(dir)
    }
    @After fun tearDown() { dir.setWritable(true); storage.handle.close() }

    private suspend fun saved(created: Long = 1_000) = (store.save(richData(), created) as BackupWriteResult.Validated).backup

    private fun jsonOf(id: String) = File(dir, "$id.json")

    @Test fun aCopyRoundTripsThroughTheDatabaseUnchanged() = runBlocking<Unit> {
        val original = richData()
        storage.access().replace(original)
        val exported = storage.access().read()
        assertEquals(original.sorted(), exported.sorted())

        val backup = (store.save(exported, 5_000) as BackupWriteResult.Validated).backup
        assertEquals(original.recordCount, backup.recordCount)
        storage.access().replace(bigData(3))
        val file = (store.read(backup.id) as BackupReadResult.Read).file
        val verdict = BackupValidator.validate(file) as BackupVerdict.Valid
        assertEquals(5_000L, file.createdUtcMs)
        storage.access().replace(verdict.data)
        assertEquals(original.sorted(), storage.access().read().sorted())
    }

    @Test fun aNegativeZeroDoesNotInvalidateTheCopy() = runBlocking<Unit> {
        val data = richData().copy(trips = listOf(trip(1, 1, -0.0)))
        val backup = (store.save(data, 1) as BackupWriteResult.Validated).backup
        val file = (store.read(backup.id) as BackupReadResult.Read).file
        assertEquals(file.declaredChecksum, file.actualChecksum)
    }

    @Test fun aLoneSurrogateInANoteDoesNotStopEveryCopy() = runBlocking<Unit> {
        val data = richData().copy(expenses = listOf(expense(1, note = "corte \uD83D")))
        val result = store.save(data, 1)
        assertTrue("got $result", result is BackupWriteResult.Validated)
    }

    @Test fun anAlteredCopyIsRejectedByItsChecksum() = runBlocking<Unit> {
        val backup = saved()
        val root = JSONObject(jsonOf(backup.id).readText())
        root.getJSONObject("data").getJSONArray("expenses").getJSONObject(0).put("amount", 1)
        jsonOf(backup.id).writeText(root.toString())
        val file = (store.read(backup.id) as BackupReadResult.Read).file
        assertEquals(BackupVerdict.ChecksumMismatch, BackupValidator.validate(file))
    }

    @Test fun aWrongRecordCountIsRejected() = runBlocking<Unit> {
        val backup = saved()
        val root = JSONObject(jsonOf(backup.id).readText()).put("count", 1)
        jsonOf(backup.id).writeText(root.toString())
        assertEquals(BackupVerdict.RecordCountMismatch, BackupValidator.validate((store.read(backup.id) as BackupReadResult.Read).file))
    }

    @Test fun aNewerSchemaIsRecognisedEvenWithAnUnknownBody() = runBlocking<Unit> {
        val backup = saved()
        jsonOf(backup.id).writeText(JSONObject().put("schema", 99).put("created", 5).put("count", 0).put("checksum", "x").put("data", JSONObject().put("otro", 1)).toString())
        val file = (store.read(backup.id) as BackupReadResult.Read).file
        assertEquals(BackupVerdict.NewerSchema(99), BackupValidator.validate(file))
    }

    @Test fun aTruncatedCopyIsListedUnreadableAndCanBeSetAside() = runBlocking<Unit> {
        val backup = saved()
        val bytes = jsonOf(backup.id).readBytes()
        jsonOf(backup.id).writeBytes(bytes.copyOf(bytes.size / 2))
        assertTrue(store.read(backup.id) is BackupReadResult.Unreadable)
        assertEquals("a damaged copy has to reach the recovery that sets it aside", listOf(backup.id), store.list().map { it.id })
        store.quarantine(backup.id)
        assertTrue(store.list().isEmpty())
        val aside = dir.listFiles()!!.single()
        assertTrue(bytes.copyOf(bytes.size / 2).contentEquals(aside.readBytes()))
    }

    @Test fun aFailedSaveKeepsThePreviousCopyAndDoesNotThrow() = runBlocking<Unit> {
        val first = saved()
        val before = jsonOf(first.id).readBytes()
        assertTrue(dir.setWritable(false))
        val result = store.save(richData(), 2_000)
        assertTrue("got $result", result is BackupWriteResult.Failed)
        assertTrue(dir.setWritable(true))
        assertEquals(listOf(first.id), store.list().map { it.id })
        assertTrue(before.contentEquals(jsonOf(first.id).readBytes()))
    }

    @Test fun dataThatCannotBeEncodedIsAFailureNotACrash() = runBlocking<Unit> {
        val result = store.save(richData().copy(calibrations = listOf(OdometerCalibration(1, Double.NaN, 0.0))), 1)
        assertTrue("got $result", result is BackupWriteResult.Failed)
    }

    @Test fun aSuccessfulSaveLeavesNothingBehind() = runBlocking<Unit> {
        val backup = saved()
        assertEquals(listOf("${backup.id}.json"), dir.list()!!.toList())
    }

    @Test fun aStaleTemporaryIsNotListedAndIsCleanedUp() = runBlocking<Unit> {
        val stale = File(dir, "backup-1-1.json.tmp").apply { writeText("{medio") }
        assertTrue(store.list().isEmpty())
        saved()
        assertFalse(stale.exists())
    }

    @Test fun noDriveIsReportedWithoutThrowing() = runBlocking<Unit> {
        org.junit.Assume.assumeFalse("a removable volume is mounted", hasRealVolume())
        assertEquals(ExternalExportResult.DriveMissing, storage.externalBackups.export(richData(), 1))
        assertTrue(storage.externalBackups.available().isEmpty())
        assertTrue(storage.externalBackups.read("nada") is BackupReadResult.Unreadable)
    }

    private fun storeWriting(temp: File, stages: MutableList<BackupStage> = mutableListOf()) =
        FileBackupStore(dir, { stages += it }, { temp })

    /** The app may not open /dev/full, so this is the exception libcore throws for ENOSPC. The real one is the volume test. */
    @Test fun noSpaceIsReportedAsFullAndKeepsOldCopies() = runBlocking<Unit> {
        val first = saved()
        val noSpace = java.io.IOException(
            "write failed: ENOSPC (No space left on device)",
            android.system.ErrnoException("write", android.system.OsConstants.ENOSPC),
        )
        val result = FileBackupStore(dir, {}, { throw noSpace }).save(richData(), 2)
        assertEquals(BackupWriteResult.StorageFull, result)
        assertEquals(listOf(first.id), store.list().map { it.id })
    }

    @Test fun stagesRunInTheOrderThatMakesACopyDurable() = runBlocking<Unit> {
        val stages = mutableListOf<BackupStage>()
        val stageStore = FileBackupStore(dir, { stages += it }, { File(it.parentFile, "${it.name}.tmp") })
        assertTrue(stageStore.save(richData(), 1) is BackupWriteResult.Validated)
        assertEquals(BackupStage.values().toList(), stages)
    }

    @Test fun aBadTemporaryIsNeverRenamedIntoPlace() = runBlocking<Unit> {
        val first = saved()
        val corrupting = FileBackupStore(dir, { if (it == BackupStage.TEMP_SYNCED) dir.listFiles { f -> f.name.endsWith(".tmp") }!!.forEach { t -> t.writeText("{basura") } }, { File(it.parentFile, "${it.name}.tmp") })
        assertTrue(corrupting.save(richData(), 2) is BackupWriteResult.Failed)
        assertEquals(listOf(first.id), store.list().map { it.id })
        assertFalse(dir.list()!!.any { it.endsWith(".tmp") })
    }

    @Test fun retentionSetsADamagedCopyAsideInsteadOfDeletingIt() = runBlocking<Unit> {
        val good = saved(1)
        val bad = saved(2)
        File(dir, "${bad.id}.json").writeText("{roto")
        store.delete(listOf(good.id, bad.id))
        assertFalse(File(dir, "${good.id}.json").exists())
        assertTrue(File(dir, "${bad.id}.quarantine.json").exists())
    }

    @Test fun withoutThePublicFolderCopiesGoToThePrivateOneAndStayVisible() = runBlocking<Unit> {
        val publicDir = newBackupDir("public")
        val privateDir = newBackupDir("private")
        var usable = false
        val both = PublicOrPrivateBackupStore(FileBackupStore(publicDir), FileBackupStore(privateDir)) { usable }
        val inPrivate = (both.save(richData(), 1) as BackupWriteResult.Validated).backup
        assertTrue(publicDir.list()!!.isEmpty())
        usable = true
        val inPublic = (both.save(richData(), 2) as BackupWriteResult.Validated).backup
        assertEquals(setOf(inPrivate.id, inPublic.id), both.list().map { it.id }.toSet())
        assertTrue(both.read(inPrivate.id) is BackupReadResult.Read)
        assertTrue(both.read(inPublic.id) is BackupReadResult.Read)
        assertTrue(privateDir.list()!!.size == 1 && publicDir.list()!!.size == 1)
    }

    @Test fun aPublicFolderThatFailsFallsBackToThePrivateOne() = runBlocking<Unit> {
        val publicDir = newBackupDir("public2").apply { setWritable(false) }
        val privateDir = newBackupDir("private2")
        val both = PublicOrPrivateBackupStore(FileBackupStore(publicDir), FileBackupStore(privateDir)) { true }
        assertTrue(both.save(richData(), 1) is BackupWriteResult.Validated)
        publicDir.setWritable(true)
        assertEquals(1, privateDir.list()!!.size)
    }

    @Test fun theRealPublicFolderWorksWithThePermissionGranted() = runBlocking<Unit> {
        val granted = appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        org.junit.Assume.assumeTrue("needs the permission", granted)
        val folder = File(android.os.Environment.getExternalStorageDirectory(), "Guantera/respaldos")
        val direct = FileBackupStore(folder).save(richData(), 122)
        assertTrue("public folder: $direct", direct is BackupWriteResult.Validated)
        File(folder, "${(direct as BackupWriteResult.Validated).backup.id}.json").delete()
        val backup = (storage.backups.save(richData(), 123) as BackupWriteResult.Validated).backup
        val public = File(folder, "${backup.id}.json")
        assertTrue(public.exists())
        assertTrue(storage.backups.read(backup.id) is BackupReadResult.Read)
        storage.backups.delete(listOf(backup.id))
        assertFalse(public.exists())
    }

    @Test fun theRealFactoryKeepsCopiesWithoutThePermission() = runBlocking<Unit> {
        val granted = appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        org.junit.Assume.assumeFalse("needs the permission revoked", granted)
        val backup = (storage.backups.save(richData(), 123) as BackupWriteResult.Validated).backup
        assertTrue(storage.backups.read(backup.id) is BackupReadResult.Read)
        assertTrue(backup.id in storage.backups.list().map { it.id })
        storage.backups.delete(listOf(backup.id))
    }

    private fun drive(root: File, present: () -> Boolean = { true }) = UsbBackupTransfer { if (present()) listOf(root) else emptyList() }

    @Test fun aSimulatedDriveExportsAndReadsBack() = runBlocking<Unit> {
        val root = newBackupDir("drive")
        val transfer = drive(root)
        val exported = transfer.export(richData(), 77) as ExternalExportResult.Exported
        assertTrue(File(exported.location).exists())
        val listed = transfer.available().single()
        val file = (transfer.read(listed.reference) as BackupReadResult.Read).file
        assertTrue(BackupValidator.validate(file) is BackupVerdict.Valid)
    }

    @Test fun aDrivePulledDuringExportIsReportedAsMissing() = runBlocking<Unit> {
        val root = newBackupDir("drive2")
        var present = true
        File(root, "Guantera").apply { mkdirs(); setWritable(false) }
        val transfer = UsbBackupTransfer { if (present.also { present = false }) listOf(root) else emptyList() }
        assertEquals(ExternalExportResult.DriveMissing, transfer.export(richData(), 1))
        File(root, "Guantera").setWritable(true)
    }

    @Test fun aReadOnlyDriveIsAFailureNotACrash() = runBlocking<Unit> {
        val root = newBackupDir("drive3")
        File(root, "Guantera").apply { mkdirs(); setWritable(false) }
        assertTrue(drive(root).export(richData(), 1) is ExternalExportResult.Failed)
        File(root, "Guantera").setWritable(true)
    }

    @Test fun volumesThatThrowCountAsNoDrive() = runBlocking<Unit> {
        val transfer = UsbBackupTransfer { throw IllegalArgumentException("not a storage path") }
        assertEquals(ExternalExportResult.DriveMissing, transfer.export(richData(), 1))
        assertTrue(transfer.available().isEmpty())
        assertTrue(transfer.read("x") is BackupReadResult.Unreadable)
    }

    private fun hasRealVolume() = appContext.getExternalFilesDirs(null).filterNotNull().any {
        runCatching { android.os.Environment.isExternalStorageRemovable(it) }.getOrDefault(false)
    }

    @Test fun aRealRemovableVolumeExportsAndReadsBack() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue("no removable volume mounted", hasRealVolume())
        val exported = storage.externalBackups.export(richData(), 4242) as ExternalExportResult.Exported
        val listed = storage.externalBackups.available().first { exported.location.endsWith(it.displayName) }
        val file = (storage.externalBackups.read(listed.reference) as BackupReadResult.Read).file
        assertEquals(richData().recordCount, file.declaredRecordCount)
        assertTrue(BackupValidator.validate(file) is BackupVerdict.Valid)
        assertTrue(File(exported.location).delete())
    }

    @Test fun aRealFullVolumeIsReportedAsFull() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue("no removable volume mounted", hasRealVolume())
        val volume = appContext.getExternalFilesDirs(null).filterNotNull().first { android.os.Environment.isExternalStorageRemovable(it) }
        val junk = File(volume, "junk.bin")
        try {
            val chunk = ByteArray(1 shl 20)
            runCatching { java.io.FileOutputStream(junk).use { out -> while (true) { out.write(chunk); out.fd.sync() } } }
            assertEquals(ExternalExportResult.DriveFull, storage.externalBackups.export(bigData(), 1))
        } finally {
            junk.delete()
        }
    }

    @Test fun fsyncWorksOnARealDirectory() { fsyncDirectory(dir) }
}
