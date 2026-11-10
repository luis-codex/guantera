package app.guantera.sonda.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.guantera.sonda.domain.model.ExportResult
import app.guantera.sonda.domain.port.ReportStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer

/** Saves reports to the public Downloads folder: MediaStore on Android 10+, direct file access before. */
class DownloadsReportStore(private val context: Context) : ReportStore {
    override fun save(fileName: String, body: (Writer) -> Unit): ExportResult = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(fileName, body) else saveToFile(fileName, body)
    } catch (e: Exception) {
        ExportResult.Failed(e.message ?: e.toString())
    }

    private fun saveWithMediaStore(fileName: String, body: (Writer) -> Unit): ExportResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TEXT)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, PENDING)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore no creó el archivo")
        try {
            writeTo(uri, body)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, PUBLISHED) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return ExportResult.Saved("${Environment.DIRECTORY_DOWNLOADS}/$fileName")
    }

    private fun writeTo(uri: Uri, body: (Writer) -> Unit) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "w") ?: throw IOException("No se pudo abrir el archivo exportado")
        descriptor.use {
            val output = FileOutputStream(it.fileDescriptor)
            val writer = output.bufferedWriter(Charsets.UTF_8)
            body(writer)
            writer.flush()
            output.fd.sync()
        }
    }

    private fun saveToFile(fileName: String, body: (Writer) -> Unit): ExportResult {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return ExportResult.StoragePermissionRequired
        }
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo crear ${directory.absolutePath}")
        val file = File(directory, fileName)
        writeAtomically(file, body = body)
        return ExportResult.Saved(file.absolutePath)
    }

    private companion object {
        const val MIME_TEXT = "text/plain"
        const val PENDING = 1
        const val PUBLISHED = 0
    }
}
