package com.hortus1556.exilum.db.entity

import androidx.room.Entity

/**
 * What one subsong of one file is called.
 *
 * Kept apart from favourites and playlists because the name belongs to the
 * subsong, not to wherever it was saved: renaming the third tune of an NSF in
 * the favourites renames it in every playlist it is in and in the player's own
 * subsong list. Filled two ways - with the name the format gives, when a
 * subsong is saved and the format has one, and by the listener, for the ones
 * where it does not.
 */
@Entity(
    tableName = "subsong_names",
    primaryKeys = ["uri", "subsong"],
)
data class SubsongNameEntity(
    val uri: String,
    val subsong: Int,
    val name: String,
)
