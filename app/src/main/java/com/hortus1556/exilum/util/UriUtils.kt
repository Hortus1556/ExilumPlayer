package com.hortus1556.exilum.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hortus1556.exilum.db.entity.ModuleEntity

/**
 * Converts a URI received from an external app (file manager, etc.) into a [ModuleEntity].
 * Handles both content:// (any provider) and file:// schemes.
 * Returns null if the URI cannot be resolved.
 */
fun Uri.toModuleEntity(context: Context): ModuleEntity? = when (scheme) {
    ContentResolver.SCHEME_CONTENT -> toModuleEntityFromContent(context)
    ContentResolver.SCHEME_FILE -> toModuleEntityFromFile()
    else -> null
}

private fun Uri.toModuleEntityFromContent(context: Context): ModuleEntity? = try {
    val cursor = context.contentResolver.query(
        this,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    ) ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        val name = it.getString(0) ?: return null
        val size = if (it.isNull(1)) 0L else it.getLong(1)
        ModuleEntity(
            filename = name,
            fileExtension = name.substringAfterLast('.', "").lowercase(),
            filePath = toString(),
            fileSize = size,
        )
    }
} catch (e: SecurityException) {
    // A URI handed to us via an external ACTION_VIEW intent (e.g. "open with"
    // from a file manager) whose grant has already lapsed - same failure
    // class as the persisted-folder crash this session fixed elsewhere.
    null
}

private fun Uri.toModuleEntityFromFile(): ModuleEntity? {
    val file = path?.let { java.io.File(it) } ?: return null
    return ModuleEntity(
        filename = file.name,
        fileExtension = file.extension.lowercase(),
        filePath = toString(),
        fileSize = file.length(),
    )
}
