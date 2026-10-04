package com.example.transferase.server

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.util.UUID

data class SharedFileItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val size: Long,
    val mimeType: String,
    val uri: Uri? = null,
    val localFile: File? = null,
    val textContent: String? = null
)

data class ReceivedFileItem(
    val name: String,
    val size: Long,
    val path: String,
    val lastModified: Long,
    val textContent: String? = null
)

object SharedFileManager {
    private val _sharedFiles = MutableStateFlow<List<SharedFileItem>>(emptyList())
    val sharedFiles = _sharedFiles.asStateFlow()

    private val _receivedFiles = MutableStateFlow<List<ReceivedFileItem>>(emptyList())
    val receivedFiles = _receivedFiles.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage = _lastMessage.asStateFlow()

    fun getDownloadDir(context: Context): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val transferaseDir = File(downloadDir, "Transferase")
        if (!transferaseDir.exists()) {
            transferaseDir.mkdirs()
        }
        return if (transferaseDir.canWrite()) transferaseDir else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    }

    fun addSharedFile(context: Context, uri: Uri) {
        var name = "shared_file_${System.currentTimeMillis()}"
        var size = 0L
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }

        val item = SharedFileItem(
            name = name,
            size = size,
            mimeType = mimeType,
            uri = uri
        )
        _sharedFiles.value = _sharedFiles.value + item
    }

    fun addSharedText(context: Context, text: String) {
        if (text.isBlank()) return
        val snippet = text.trim().take(16).replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "note" }
        val fileName = "note_${snippet}_${System.currentTimeMillis() % 10000}.txt"
        val cacheFile = File(context.cacheDir, fileName).apply {
            writeText(text, Charsets.UTF_8)
        }

        val item = SharedFileItem(
            name = fileName,
            size = cacheFile.length(),
            mimeType = "text/plain; charset=utf-8",
            localFile = cacheFile,
            textContent = text
        )
        _sharedFiles.value = listOf(item) + _sharedFiles.value
        setMessage(text)
    }

    fun removeSharedFile(id: String) {
        _sharedFiles.value = _sharedFiles.value.filter { it.id != id }
    }

    fun clearSharedFiles() {
        _sharedFiles.value = emptyList()
    }

    fun clearReceivedFiles(context: Context) {
        val dir = getDownloadDir(context)
        dir.listFiles()?.forEach { file ->
            try { file.delete() } catch (ignored: Exception) {}
        }
        refreshReceivedFiles(context)
    }

    fun refreshReceivedFiles(context: Context) {
        val dir = getDownloadDir(context)
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        _receivedFiles.value = files.map { file ->
            var textSnippet: String? = null
            if (file.name.endsWith(".txt", ignoreCase = true) || file.name.startsWith("note_")) {
                if (file.length() < 100_000) {
                    try {
                        textSnippet = file.readText(Charsets.UTF_8)
                    } catch (ignored: Exception) {}
                }
            }
            ReceivedFileItem(
                name = file.name,
                size = file.length(),
                path = file.absolutePath,
                lastModified = file.lastModified(),
                textContent = textSnippet
            )
        }
    }

    fun setMessage(msg: String) {
        _lastMessage.value = msg
    }

    fun openInputStreamForSharedFile(context: Context, id: String): Pair<SharedFileItem, InputStream>? {
        val item = _sharedFiles.value.find { it.id == id } ?: return null
        return try {
            val stream = if (item.uri != null) {
                context.contentResolver.openInputStream(item.uri)
            } else if (item.localFile != null && item.localFile.exists()) {
                item.localFile.inputStream()
            } else null

            if (stream != null) Pair(item, stream) else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
