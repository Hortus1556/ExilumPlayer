package com.hortus1556.exilum.data

import com.hortus1556.exilum.db.dao.DownloadHistoryDao
import com.hortus1556.exilum.db.entity.DownloadHistoryEntity
import com.hortus1556.exilum.model.Artist
import com.hortus1556.exilum.model.ArtistInfo
import com.hortus1556.exilum.model.Module
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DownloadHistoryRepository(private val dao: DownloadHistoryDao) {

    fun getAllFlow(): Flow<List<Module>> = dao.getAllFlow()
        .map { entities -> entities.map { it.toModule() } } // wow

    suspend fun add(module: Module) = dao.upsert(module.toEntity())

    suspend fun clear() = dao.clearAll()

    private fun DownloadHistoryEntity.toModule() = Module(
        id = id,
        filename = filename,
        songtitle = songTitle,
        format = format,
        bytes = bytes,
        artistInfo = ArtistInfo(
            artist = listOf(Artist(alias = artist))
        ),
    )

    private fun Module.toEntity() = DownloadHistoryEntity(
        id = id,
        filename = filename,
        songTitle = songtitle,
        format = format,
        bytes = bytes,
        artist = artist,
        viewedAt = System.currentTimeMillis(),
    )
}
