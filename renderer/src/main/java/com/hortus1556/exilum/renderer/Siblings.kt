package com.hortus1556.exilum.renderer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * The files beside one the player was handed, which the native side cannot
 * reach from the one descriptor it gets: a PSF's libraries (see
 * [PsfCompanions]), the other half of a GameCube stream split in two (see
 * [StreamPairs]).
 */
internal object Siblings {

    /** The file called [name] in the folder [of] is in, whatever the case of its name. */
    fun find(context: Context, of: Uri, name: String): Uri? {
        if (of.scheme == "file") {
            val parent = File(of.path ?: return null).parentFile ?: return null
            val exact = File(parent, name)
            if (exact.exists()) return Uri.fromFile(exact)
            return parent.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let(Uri::fromFile)
        }
        if (!DocumentsContract.isDocumentUri(context, of)) return null
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return try {
            val parentId = parentDocumentId(context, of) ?: return null
            // A document opened by itself, not under a tree the app was granted, has no folder to
            // list: building the URI throws, and there is nothing beside it to find.
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(of, parentId)
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1).equals(name, ignoreCase = true)) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(of, cursor.getString(0))
                    }
                }
                null
            }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The whole of the file at [uri]; null when it cannot be read, is empty, or is larger than [maxBytes]. */
    fun read(context: Context, uri: Uri, maxBytes: Long): ByteArray? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val size = input.channel.size()
                if (size <= 0 || size > maxBytes) return@use null
                ByteArray(size.toInt()).also { input.readFully(it) }
            }
        }
    } catch (e: SecurityException) {
        null
    } catch (e: IOException) {
        null
    }

    /** The name at the end of [name], a path's or a URI's. */
    fun baseName(name: String) = name.substringAfterLast('/').substringAfterLast('\\')

    /** Reads until [buffer] is full or the stream ends; how much was read. */
    fun FileInputStream.readFully(buffer: ByteArray): Int {
        var at = 0
        while (at < buffer.size) {
            val n = read(buffer, at, buffer.size - at)
            if (n < 0) break
            at += n
        }
        return at
    }

    /**
     * The folder a document is in. The provider says, where it can; otherwise
     * the id is read as the path the storage provider makes it.
     */
    private fun parentDocumentId(context: Context, uri: Uri): String? {
        val path = try {
            DocumentsContract.findDocumentPath(context.contentResolver, uri)?.path
        } catch (e: Exception) {
            null
        }
        if (path != null && path.size >= 2) return path[path.size - 2]
        val id = DocumentsContract.getDocumentId(uri)
        return when {
            '/' in id -> id.substringBeforeLast('/')
            ':' in id -> id.substringBefore(':') + ":"
            else -> null
        }
    }
}
