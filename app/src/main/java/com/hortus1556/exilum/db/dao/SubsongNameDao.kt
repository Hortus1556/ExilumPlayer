package com.hortus1556.exilum.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hortus1556.exilum.db.entity.SubsongNameEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubsongNameDao {

    @Query("SELECT * FROM subsong_names")
    fun getAll(): Flow<List<SubsongNameEntity>>

    @Query("SELECT * FROM subsong_names")
    suspend fun getAllOnce(): List<SubsongNameEntity>

    @Query("SELECT * FROM subsong_names WHERE uri = :uri AND subsong = :subsong")
    suspend fun get(uri: String, subsong: Int): SubsongNameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(item: SubsongNameEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(items: List<SubsongNameEntity>)

    @Query("DELETE FROM subsong_names WHERE uri = :uri AND subsong = :subsong")
    suspend fun remove(uri: String, subsong: Int)

    @Query("DELETE FROM subsong_names")
    suspend fun clear()
}
