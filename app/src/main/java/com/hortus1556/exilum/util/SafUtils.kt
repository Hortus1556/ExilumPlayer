package com.hortus1556.exilum.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.hortus1556.exilum.data.TRACKER_CONFIG_FILENAME
import com.hortus1556.exilum.model.Module
import java.io.OutputStream
import timber.log.Timber

/** A single child entry returned by [queryDirectoryEntries]. */
data class DirectoryEntry(
    val childUri: Uri,
    val docId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val isDirectory: Boolean
)

/**
 * Whether this is one of the app's own files rather than the user's.
 *
 * tracker.conf is written into the music folder on purpose - it belongs with
 * the music it describes, and travels with it - but it is bookkeeping, not
 * something to be listed among the tunes. The prefix rather than the exact
 * name because a SAF provider that has been handed the file twice will have
 * numbered the second one.
 */
fun DirectoryEntry.isHidden(): Boolean =
    !isDirectory && name.startsWith(TRACKER_CONFIG_FILENAME)

/**
 * Returns true if this SAF URI is still accessible.
 * Returns false if the file no longer exists or the app no longer holds permission.
 */
fun Uri.isAccessible(context: Context): Boolean = try {
    context.contentResolver.query(
        this,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
        null,
        null,
        null,
    )?.use { it.count >= 0 } ?: false
} catch (e: Exception) {
    Timber.w(e, "URI accessibility check failed: $this")
    false
}

/**
 * Resolves the correct document ID for this URI, handling both tree and document URI forms.
 */
fun Uri.resolveDocId(context: Context): String = when {
    DocumentsContract.isTreeUri(this) &&
        DocumentsContract.isDocumentUri(context, this) ->
        DocumentsContract.getDocumentId(this)

    DocumentsContract.isTreeUri(this) ->
        DocumentsContract.getTreeDocumentId(this)

    else ->
        DocumentsContract.getDocumentId(this)
}

/**
 * Queries all immediate children of [docId] inside [treeRoot].
 *
 * Returns null if the query itself fails - most commonly a lost persisted SAF
 * grant (e.g. the app was uninstalled/reinstalled since the folder was picked,
 * which drops the grant even though Room's saved path survives). This is
 * deliberately distinct from an empty list, which means a genuinely empty,
 * successfully-read directory: a caller that deletes rows no longer "seen"
 * during a walk must never treat a failed query as an empty one, or a lost
 * grant would look identical to "the user deleted every file" and wipe their
 * library instead of just failing to refresh it - see the callers in
 * ModuleRepository.
 */
fun ContentResolver.queryDirectoryEntries(treeRoot: Uri, docId: String): List<DirectoryEntry>? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeRoot, docId)
    val entries = mutableListOf<DirectoryEntry>()
    try {
        // No cursor at all is the provider failing to answer - a card taken
        // out, a folder renamed away - and not a folder with nothing in it:
        // read as empty, it had every row below it deleted as no longer
        // there, and the whole library to be tested again once it was back.
        val cursor = query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        ) ?: return null
        cursor.use {
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: continue
                val size = cursor.getLong(sizeCol)
                entries.add(
                    DirectoryEntry(
                        childUri = DocumentsContract.buildDocumentUriUsingTree(treeRoot, childId),
                        docId = childId,
                        name = name,
                        mime = mime,
                        size = size,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                )
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "queryDirectoryEntries failed for $treeRoot / $docId")
        return null
    }
    return entries
}

/**
 * Checks whether a SAF URI is still accessible.
 * Queries the content resolver and returns false if the file no longer exists
 * or the app no longer holds permission.
 */
fun ContentResolver.isUriAccessible(uri: String): Boolean = try {
    this.query(
        uri.toUri(),
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
        null,
        null,
        null,
    )?.use { it.count >= 0 } ?: false
} catch (e: Exception) {
    Timber.w("URI accessibility check failed: $this - ${e.message}")
    false
}

/**
 * Finds a child directory by [name] under [parentDocId] in the given [treeUri].
 * Returns the document ID of the found directory, or null if not found.
 */
fun ContentResolver.findChildDir(
    treeUri: Uri,
    parentDocId: String,
    name: String
): String? = findChild(
    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId),
    name,
    dirsOnly = true,
)

/**
 * The id of the document called [name] among [childrenUri]'s, a folder when
 * [dirsOnly].
 *
 * Under the name as it was given, where there is one - a provider that keeps
 * names as they come, and whatever was made there before names were put
 * right - and otherwise under what the system's provider makes of it (see
 * [toDocumentName]), which is where a name it had to change is.
 */
private fun ContentResolver.findChild(childrenUri: Uri, name: String, dirsOnly: Boolean): String? {
    val made = name.toDocumentName()
    var underMadeName: String? = null
    query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            if (dirsOnly && cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) continue
            val shown = cursor.getString(1)
            if (shown == name) return cursor.getString(0)
            if (underMadeName == null && shown == made) underMadeName = cursor.getString(0)
        }
    }
    return underMadeName
}

/** The characters a FAT volume cannot hold in a name. */
private const val UNNAMEABLE = "\"*/:<>?\\|"

/**
 * [this] as the documents provider names a file or a folder made with it:
 * the characters a FAT volume cannot hold, and control characters, each
 * become an underscore. An artist called "AC/DC" got a folder called
 * "AC_DC", which the raw name was then never found under - so every
 * download made another numbered folder, and its module was shown as not
 * downloaded.
 */
fun String.toDocumentName(): String {
    // What the provider calls a name it cannot use at all.
    if (isEmpty() || this == "." || this == "..") return "(invalid)"
    return map { c -> if (c.code < 0x20 || c.code == 0x7F || c in UNNAMEABLE) '_' else c }.joinToString("")
}

/**
 * Finds or creates a child directory by [name] under [parentDocId].
 * Returns the document ID, or null if creation fails.
 */
fun ContentResolver.findOrCreateChildDir(
    treeUri: Uri,
    parentDocId: String,
    name: String
): String? {
    findChildDir(treeUri, parentDocId, name)?.let { return it }
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
    val newUri = DocumentsContract.createDocument(
        this,
        parentUri,
        DocumentsContract.Document.MIME_TYPE_DIR,
        name.toDocumentName()
    )
    return newUri?.let { DocumentsContract.getDocumentId(it) }
}

/**
 * Returns the URI of the download directory for [module] under
 * `TheModArchive/{artist}/`, creating it if needed.
 */
fun Context.getDownloadDir(
    rootUri: Uri,
    module: Module
): Uri? = try {
    val rootDoc = DocumentsContract.getTreeDocumentId(rootUri)
    val cr = contentResolver
    val tmaDoc = cr.findOrCreateChildDir(rootUri, rootDoc, "TheModArchive") ?: return null
    val artistDoc = cr.findOrCreateChildDir(rootUri, tmaDoc, module.artist) ?: tmaDoc
    DocumentsContract.buildDocumentUriUsingTree(rootUri, artistDoc)
} catch (e: Exception) {
    Timber.e(e)
    null
}

/**
 * Locates a downloaded module file by [filename] under the artist's directory.
 * Returns the file URI, or null if not found.
 */
fun Context.findDownloadedModule(
    rootUri: Uri,
    artist: String,
    filename: String
): Uri? = try {
    val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
    val cr = contentResolver
    val tmaDocId = cr.findChildDir(rootUri, rootDocId, "TheModArchive") ?: return null
    val artistDocId = cr.findChildDir(rootUri, tmaDocId, artist) ?: tmaDocId
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, artistDocId)
    cr.findChild(childrenUri, filename, dirsOnly = false)
        ?.let { DocumentsContract.buildDocumentUriUsingTree(rootUri, it) }
} catch (e: Exception) {
    Timber.e(e)
    null
}

/**
 * Returns an [OutputStream] for [module]'s file in the download directory,
 * creating the file if it does not exist. Returns null on failure.
 */
fun Context.getOrCreateOutputFile(
    rootUri: Uri,
    module: Module
): OutputStream? = try {
    val dirUri = getDownloadDir(rootUri, module) ?: return null
    val dirDocId = DocumentsContract.getDocumentId(dirUri)
    val filename = module.url.substringAfterLast('#')
    val cr = contentResolver

    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, dirDocId)
    val fileUri = cr.findChild(childrenUri, filename, dirsOnly = false)
        ?.let { DocumentsContract.buildDocumentUriUsingTree(rootUri, it) }
        ?: DocumentsContract.createDocument(cr, dirUri, "application/octet-stream", filename.toDocumentName())

    // Truncating: since Android 10 a plain "w" writes over the start of a
    // file that is already there and leaves the rest of it where it was.
    fileUri?.let { cr.openOutputStream(it, "wt") }
} catch (e: Exception) {
    Timber.e(e)
    null
}
