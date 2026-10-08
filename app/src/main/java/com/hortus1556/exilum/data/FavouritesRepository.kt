package com.hortus1556.exilum.data

import com.hortus1556.exilum.db.dao.FavouriteDao
import com.hortus1556.exilum.db.entity.FavouriteEntity
import com.hortus1556.exilum.db.entity.ModuleEntity
import kotlinx.coroutines.flow.Flow

class FavouritesRepository(private val dao: FavouriteDao) {

    val favourites: Flow<List<FavouriteEntity>> = dao.getAll()

    /** Whether [uri] is starred as [subsong] - see [FavouriteEntity.subsong]. */
    fun isFavourite(uri: String, subsong: Int): Flow<Boolean> = dao.isFavourite(uri, subsong)

    suspend fun add(module: ModuleEntity, subsong: Int) = dao.add(
        FavouriteEntity(
            uri = module.filePath,
            subsong = subsong,
            name = module.moduleName.ifBlank { module.filename },
            extension = module.fileExtension,
        )
    )

    suspend fun remove(uri: String, subsong: Int) = dao.remove(uri, subsong)

    /** Stars or unstars [subsong] of [module], returning whether it is starred now. */
    suspend fun toggle(module: ModuleEntity, subsong: Int): Boolean {
        val existing = dao.getAllOnce().any { it.uri == module.filePath && it.subsong == subsong }
        if (existing) remove(module.filePath, subsong) else add(module, subsong)
        return !existing
    }

    /**
     * Favourites as playable modules.
     *
     * Rebuilt rather than stored whole: a favourite only records what is
     * needed to find and name the file again, so the rest is left at defaults
     * the same way PlaylistRepository does it.
     */
    suspend fun getFavouriteModules(): List<ModuleEntity> = dao.getAllOnce().map {
        ModuleEntity(
            filename = it.name,
            fileExtension = it.extension,
            filePath = it.uri,
            parentPath = "",
            fileSize = 0L,
            isDirectory = false,
            isValidModule = true,
            isTested = true,
            moduleName = it.name,
            moduleType = it.extension,
            subsong = it.subsong,
        )
    }
}
