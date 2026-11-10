package app.guantera.sonda.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer

/** Writes to `<name>.tmp`, forces it to disk and renames it, so a power cut never leaves a truncated report under the final name. */
internal fun writeAtomically(
    target: File,
    sync: (FileOutputStream) -> Unit = { it.fd.sync() },
    body: (Writer) -> Unit,
) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    try {
        FileOutputStream(temp).use { output ->
            val writer = output.bufferedWriter(Charsets.UTF_8)
            body(writer)
            writer.flush()
            sync(output)
        }
        if (!temp.renameTo(target)) throw IOException("No se pudo renombrar ${temp.name} a ${target.name}")
    } catch (e: Exception) {
        temp.delete()
        throw e
    }
}
