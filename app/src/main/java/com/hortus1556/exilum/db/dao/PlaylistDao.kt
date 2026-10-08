package com.hortus1556.exilum.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.db.entity.PlaylistEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    suspend fun getEntries(id: Long): List<PlaylistEntryEntity>

    /**
     * One playlist's entries, as a stream.
     *
     * For the page that shows them. It read them once, when it was opened, so
     * a track added from the player or the browser meanwhile was not on it -
     * and the next reorder, which wrote the page's list back as the playlist,
     * took that track out again without a word.
     */
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    fun observeEntries(id: Long): Flow<List<PlaylistEntryEntity>>

    /**
     * Every entry across every playlist, as a stream.
     *
     * Exists so persistence can react to a track being added, removed or
     * reordered - none of which touch the playlists table, so observing that
     * alone would miss them entirely.
     */
    @Query("SELECT * FROM playlist_entries ORDER BY playlistId, position")
    fun getAllEntries(): Flow<List<PlaylistEntryEntity>>

    @Insert
    suspend fun createPlaylist(playlist: PlaylistEntity): Long

    @Insert
    suspend fun addEntry(entry: PlaylistEntryEntity)

    @Insert
    suspend fun addEntries(entries: List<PlaylistEntryEntity>)

    /**
     * Adds [entries] to the end of the playlist, whatever position they came
     * with.
     *
     * The position given is the one after the last row, read in the same
     * transaction that uses it. It was the number of rows, which is a position
     * already taken once any entry but the last has been removed - and the
     * insert then failed on the primary key: a crash when adding from the
     * player, nothing added and nothing said from the browser, for that
     * playlist every time until something happened to renumber it.
     *
     * A track the playlist already has is left out, and so is the second of
     * two the same in [entries]. An entry is its file and subsong to
     * everything that removes one - see [removeEntry] - so the same track
     * twice was two rows nothing could tell apart, and a playlist's page
     * crashed on being opened with both.
     */
    @Transaction
    suspend fun append(playlistId: Long, entries: List<PlaylistEntryEntity>) {
        val present = getEntries(playlistId)
        val start = (present.lastOrNull()?.position ?: -1) + 1
        val seen = present.mapTo(HashSet()) { it.uri to it.subsong }
        addEntries(
            entries
                .filter { seen.add(it.uri to it.subsong) }
                .mapIndexed { i, entry -> entry.copy(playlistId = playlistId, position = start + i) }
        )
    }

    /**
     * Makes [entries], in the order given, the whole of the playlist, numbered
     * from 0.
     *
     * Every change that is not an append ends here, which is what keeps the
     * positions without gaps, and it is one transaction: the playlist used to
     * be emptied and filled again a row at a time, each row on its own, and
     * whatever read or wrote it in between found part of a playlist. Nothing
     * is written when the playlist is that already.
     */
    @Transaction
    suspend fun replaceEntries(playlistId: Long, entries: List<PlaylistEntryEntity>) {
        val numbered = entries.mapIndexed { i, entry -> entry.copy(playlistId = playlistId, position = i) }
        if (numbered == getEntries(playlistId)) return
        clearEntries(playlistId)
        addEntries(numbered)
    }

    /**
     * Puts the playlist's entries in [order], going by each one's file and
     * subsong.
     *
     * Only ever a new order for the rows that are there: an entry [order]
     * does not have keeps its place after the ones it does, and one with no
     * row adds nothing. The list a page hands in can be older than the table,
     * and writing that list back as the playlist is how a track added from
     * somewhere else was lost again - or how rows were written under the id
     * of a playlist that no longer existed.
     */
    @Transaction
    suspend fun reorderEntries(playlistId: Long, order: List<PlaylistEntryEntity>) {
        // A track that is in twice - from before append refused that - takes
        // its places in turn.
        val places = HashMap<Pair<String, Int>, ArrayDeque<Int>>()
        order.forEachIndexed { place, entry ->
            places.getOrPut(entry.uri to entry.subsong) { ArrayDeque() }.addLast(place)
        }
        val placed = getEntries(playlistId).map { entry ->
            (places[entry.uri to entry.subsong]?.removeFirstOrNull() ?: Int.MAX_VALUE) to entry
        }
        replaceEntries(playlistId, placed.sortedBy { it.first }.map { it.second })
    }

    /**
     * Takes [uri] as [subsong] out of the playlist and closes the gap it
     * leaves, so the positions go on counting from 0 with none missing.
     */
    @Transaction
    suspend fun removeEntry(playlistId: Long, uri: String, subsong: Int) {
        replaceEntries(
            playlistId,
            getEntries(playlistId).filterNot { it.uri == uri && it.subsong == subsong },
        )
    }

    @Query("SELECT DISTINCT playlistId FROM playlist_entries WHERE uri = :uri AND subsong = :subsong")
    suspend fun playlistsHolding(uri: String, subsong: Int): List<Long>

    /** Every entry of [uri] as [subsong], in every playlist - for a loop preset that is deleted. */
    @Transaction
    suspend fun removeEverywhere(uri: String, subsong: Int) {
        for (playlistId in playlistsHolding(uri, subsong)) removeEntry(playlistId, uri, subsong)
    }

    /**
     * Makes the playlists, and what is in them, those of [wanted] - for
     * tracker.conf being read back in.
     *
     * A playlist that is already there, under the same name and made at the
     * same moment, keeps its row and has only its entries brought in line.
     * They were all deleted and made again, so every playlist had a new id
     * each time the file was read, which is every start of the app: a
     * playlist's page that came back with the app was left holding an id that
     * no longer existed, and showed nothing, or removed and reordered nothing
     * while looking as if it had.
     */
    @Transaction
    suspend fun replaceAll(wanted: List<Pair<PlaylistEntity, List<PlaylistEntryEntity>>>) {
        val unclaimed = getAllPlaylistsOnce().toMutableList()
        for ((playlist, entries) in wanted) {
            val kept = unclaimed.firstOrNull {
                it.name == playlist.name && it.createdAt == playlist.createdAt
            }
            val id = if (kept == null) {
                createPlaylist(playlist.copy(id = 0))
            } else {
                unclaimed.remove(kept)
                if (kept.comment != playlist.comment) {
                    updatePlaylist(kept.id, playlist.name, playlist.comment)
                }
                kept.id
            }
            replaceEntries(id, entries)
        }
        for (gone in unclaimed) {
            clearEntries(gone.id)
            deletePlaylist(gone.id)
        }
    }

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :id")
    suspend fun clearEntries(id: Long)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylistsOnce(): List<PlaylistEntity>

    @Query("UPDATE playlists SET name = :name, comment = :comment WHERE id = :id")
    suspend fun updatePlaylist(id: Long, name: String, comment: String)

    @Query("SELECT * FROM playlist_entries WHERE name LIKE '%' || :query || '%' ORDER BY name")
    suspend fun searchByName(query: String): List<PlaylistEntryEntity>
}
