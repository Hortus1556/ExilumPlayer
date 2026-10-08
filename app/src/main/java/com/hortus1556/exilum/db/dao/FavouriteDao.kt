package com.hortus1556.exilum.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hortus1556.exilum.db.entity.FavouriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteDao {

    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    fun getAll(): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    suspend fun getAllOnce(): List<FavouriteEntity>

    /** Emits whenever this particular module - or subsong of it - is starred or unstarred. */
    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE uri = :uri AND subsong = :subsong)")
    fun isFavourite(uri: String, subsong: Int): Flow<Boolean>

    // REPLACE, because starring is idempotent - the user pressing the star on
    // something already starred must not blow up on a primary key conflict.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(item: FavouriteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(items: List<FavouriteEntity>)

    @Query("DELETE FROM favourites WHERE uri = :uri AND subsong = :subsong")
    suspend fun remove(uri: String, subsong: Int)

    @Query("DELETE FROM favourites")
    suspend fun clear()
}
