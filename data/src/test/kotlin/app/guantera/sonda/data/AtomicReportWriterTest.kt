package app.guantera.sonda.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AtomicReportWriterTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun writesCompleteFileAndLeavesNoTemporary() {
        val target = File(temp.root, "informe.txt")
        writeAtomically(target) { it.write("hola") }
        assertEquals("hola", target.readText())
        assertFalse(File(temp.root, "informe.txt.tmp").exists())
    }

    @Test fun failureMidWriteLeavesNothingUnderTheFinalName() {
        val target = File(temp.root, "informe.txt")
        try {
            writeAtomically(target) { it.write("parcial"); throw IOException("corte") }
            fail("debía propagar el error")
        } catch (e: IOException) {
            assertEquals("corte", e.message)
        }
        assertFalse(target.exists())
        assertFalse(File(temp.root, "informe.txt.tmp").exists())
    }

    @Test fun forcesDataToDiskBeforeRenaming() {
        var synced = false
        val target = File(temp.root, "informe.txt")
        writeAtomically(target, sync = { synced = true; assertFalse(target.exists()) }) { it.write("x") }
        assertTrue(synced)
    }

    @Test fun previousReportIsKeptWhenTheNewOneFails() {
        val target = File(temp.root, "informe.txt").apply { writeText("viejo") }
        runCatching { writeAtomically(target) { throw IOException("x") } }
        assertEquals("viejo", target.readText())
    }
}
