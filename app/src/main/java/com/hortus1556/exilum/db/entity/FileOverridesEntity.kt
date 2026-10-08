package com.hortus1556.exilum.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One file's overrides, as the JSON of a FileOverrides.
 *
 * A blob rather than columns: which settings a file can override depends on
 * which library plays it, and the set grows with the settings screen - a
 * column per setting would be a migration per setting.
 */
@Entity(tableName = "file_overrides")
data class FileOverridesEntity(
    @PrimaryKey val uri: String,
    val data: String,
)
