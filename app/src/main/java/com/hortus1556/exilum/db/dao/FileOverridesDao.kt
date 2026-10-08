package com.hortus1556.exilum.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hortus1556.exilum.db.entity.FileOverridesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileOverridesDao {

    @Query("SELECT * FROM file_overrides")
    fun getAll(): Flow<List<FileOverridesEntity>>

    @Query("SELECT * FROM file_overrides")
    suspend fun getAllOnce(): List<FileOverridesEntity>

    @Query("SELECT * FROM file_overrides WHERE uri = :uri")
    suspend fun get(uri: String): FileOverridesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(item: FileOverridesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(items: List<FileOverridesEntity>)

    @Query("DELETE FROM file_overrides WHERE uri = :uri")
    suspend fun remove(uri: String)

    @Query("DELETE FROM file_overrides")
    suspend fun clear()
}
