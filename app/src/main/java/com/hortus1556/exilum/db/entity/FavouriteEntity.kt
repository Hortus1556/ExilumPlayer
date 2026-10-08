package com.hortus1556.exilum.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A module the user starred.
 *
 * Keyed by [uri] rather than a generated id, so starring something already
 * starred replaces the row instead of duplicating it, and un-starring is a
 * delete by the same value the player already has to hand. There is no
 * ordering column: favourites are a set, not a sequence, and the composite
 * position key on playlist_entries is exactly the shape that makes reordering
 * and inserting collide.
 */
@Entity(
    tableName = "favourites",
    primaryKeys = ["uri", "subsong"],
)
data class FavouriteEntity(
    val uri: String,
    /**
     * The subsong starred, or -1 for the file as a whole. Part of the key: the
     * third and the fifth tune of one NSF are two favourites.
     */
    @ColumnInfo(defaultValue = "-1")
    val subsong: Int = -1,
    val name: String,
    val extension: String,
    val addedAt: Long = System.currentTimeMillis(),
)
