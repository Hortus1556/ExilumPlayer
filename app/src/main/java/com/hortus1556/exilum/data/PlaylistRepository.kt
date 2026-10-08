package com.hortus1556.exilum.data

import com.hortus1556.exilum.db.dao.PlaylistDao
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.db.entity.PlaylistEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PlaylistRepository(private val dao: PlaylistDao) {

    val playlists: Flow<List<PlaylistEntity>> = dao.getAllPlaylists()

    suspend fun createPlaylist(entity: PlaylistEntity): Long = dao.createPlaylist(entity)

    suspend fun createPlaylist(name: String, comment: String): Long =
        dao.createPlaylist(PlaylistEntity(name = name, comment = comment))

    /** Adds [file] to the end of the playlist, unless it is in it already - see [PlaylistDao.append]. */
    suspend fun addToPlaylist(playlistId: Long, file: ModuleEntity) {
        addAllToPlaylist(playlistId, listOf(file))
    }

    /** Adds [files] to the end of the playlist, each once and none that is in it already. */
    suspend fun addAllToPlaylist(playlistId: Long, files: List<ModuleEntity>) {
        dao.append(playlistId, files.map { entryOf(playlistId, it) })
    }

    /**
     * The playlist's entries as they are and as they change - see
     * [PlaylistDao.observeEntries].
     */
    fun observePlaylistFiles(playlistId: Long): Flow<List<ModuleEntity>> =
        dao.observeEntries(playlistId)
            // The same rows again - a write to some other playlist - are not
            // news.
            .distinctUntilChanged()
            .map { entries -> entries.map(::fileOf) }

    suspend fun getPlaylistFiles(playlistId: Long): List<ModuleEntity> =
        dao.getEntries(playlistId).map(::fileOf)

    suspend fun deletePlaylist(id: Long) {
        dao.clearEntries(id)
        dao.deletePlaylist(id)
    }

    suspend fun getAllPlaylistsOnce(): List<PlaylistEntity> =
        dao.getAllPlaylistsOnce()

    suspend fun getEntriesRaw(playlistId: Long): List<PlaylistEntryEntity> =
        dao.getEntries(playlistId)

    suspend fun addRawEntry(entry: PlaylistEntryEntity) =
        dao.addEntry(entry)

    /** Takes [uri] as [subsong] out of every playlist. */
    suspend fun removeEverywhere(uri: String, subsong: Int) = dao.removeEverywhere(uri, subsong)

    suspend fun renamePlaylist(id: Long, name: String, comment: String) =
        dao.updatePlaylist(id, name, comment)

    /** Removes [uri] as [subsong] - not the file's other subsongs saved beside it. */
    suspend fun removeFromPlaylist(playlistId: Long, uri: String, subsong: Int) =
        dao.removeEntry(playlistId, uri, subsong)

    /**
     * Puts the playlist's entries in the order of [files].
     *
     * One transaction, and only an order: it used to empty the playlist and
     * insert [files] one row at a time, and two of those running at once -
     * one per row a drag crossed - left the playlist with the head of one
     * and the tail of the other. See [PlaylistDao.reorderEntries].
     */
    suspend fun reorderEntries(playlistId: Long, files: List<ModuleEntity>) {
        dao.reorderEntries(playlistId, files.map { entryOf(playlistId, it) })
    }

    /** [file] as an entry of the playlist. Its place is the DAO's to give, on the way in. */
    private fun entryOf(playlistId: Long, file: ModuleEntity) = PlaylistEntryEntity(
        playlistId = playlistId,
        position = 0,
        uri = file.filePath,
        name = file.name,
        extension = file.fileExtension,
        subsong = file.subsong,
    )

    private fun fileOf(entry: PlaylistEntryEntity) = ModuleEntity(
        filename = entry.name,
        fileExtension = entry.extension,
        filePath = entry.uri,
        fileSize = 0L,
        subsong = entry.subsong,
    )
}
