package com.hortus1556.exilum.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

// Junction Table
@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "position"],
)
data class PlaylistEntryEntity(
    val playlistId: Long,
    val position: Int,
    val uri: String,
    val name: String,
    val extension: String,
    /** The subsong this entry plays, or -1 for the file as a whole. */
    @ColumnInfo(defaultValue = "-1")
    val subsong: Int = -1,
)
