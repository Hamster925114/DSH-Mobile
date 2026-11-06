package com.dshmobile.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Receives a `blob:` download from the WebView in bounded chunks and writes it
 * to the public Downloads collection. `DownloadManager` cannot resolve blob
 * URLs, which is how DSH exports generated PDFs and session archives.
 */
class BlobDownloadBridge(private val context: Context) {
    private data class Pending(
        val file: File,
        val name: String,
        val mime: String,
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    @JavascriptInterface
    fun begin(name: String?, mime: String?): String? {
        return runCatching {
            val directory = File(context.cacheDir, "dsh-downloads").apply { mkdirs() }
            val file = File.createTempFile("blob-", ".part", directory)
            val id = UUID.randomUUID().toString()
            synchronized(lock) {
                pending[id] = Pending(
                    file = file,
                    name = safeName(name),
                    mime = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                )
            }
            id
        }.getOrNull()
    }

    @JavascriptInterface
    fun writeChunk(id: String, base64: String): Boolean {
        return runCatching {
            val entry = synchronized(lock) { pending[id] } ?: return false
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            FileOutputStream(entry.file, true).use { it.write(bytes) }
            true
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun finish(id: String): Boolean {
        val entry = synchronized(lock) { pending.remove(id) } ?: return false
        val saved = runCatching { save(entry) }.getOrDefault(false)
        entry.file.delete()
        toast(
            if (saved) "已保存到下载目录：${entry.name}"
            else "保存失败：${entry.name}",
        )
        return saved
    }

    @JavascriptInterface
    fun abort(id: String) {
        val entry = synchronized(lock) { pending.remove(id) } ?: return
        entry.file.delete()
    }

    private fun save(entry: Pending): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
                put(MediaStore.Downloads.MIME_TYPE, entry.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            return try {
                resolver.openOutputStream(uri)?.use { output ->
                    entry.file.inputStream().use { input -> input.copyTo(output) }
                } ?: return false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        }

        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val target = File(directory, entry.name)
        return runCatching {
            entry.file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
    }

    private fun safeName(value: String?): String {
        val cleaned = (value ?: "download")
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .take(120)
        return cleaned.ifBlank { "download" }
    }

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
