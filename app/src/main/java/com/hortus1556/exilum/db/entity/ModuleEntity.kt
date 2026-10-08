package com.hortus1556.exilum.db.entity

import android.net.Uri
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Immutable
@Serializable
@Entity(
    tableName = "modules",
    indices = [Index(value = ["filePath"], unique = true), Index(value = ["parentPath"])]
)
data class ModuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val filename: String = "",
    val fileExtension: String = "",
    val filePath: String = "",
    val parentPath: String = "",
    val fileSize: Long = 0L,
    val isDirectory: Boolean = false,
    val isValidModule: Boolean = false,
    /** False until native validation has actually run on this file — distinct from "confirmed invalid". */
    val isTested: Boolean = false,
    val moduleName: String = "",
    val moduleType: String = "",
    /**
     * Which subsong this queue entry plays, or -1 for the file as it opens.
     *
     * Always -1 for the files the browser indexes. Set on the entries built
     * from a favourite or a playlist entry that saved one subsong, so the
     * player selects it on load and does not walk on into the others - and
     * carried through the saved queue like the rest of the entry.
     */
    @ColumnInfo(defaultValue = "-1")
    val subsong: Int = -1,
) {
    val name: String get() = moduleName.ifBlank { filename.ifBlank { "<untitled>" } }

    val type: String get() = moduleType.ifBlank { fileExtension.ifBlank { "..." } }

    val uri: Uri get() = filePath.toUri()
}
