package com.hortus1556.exilum.db.dao

import androidx.room.Update
import androidx.room.Transaction
import androidx.room.Insert
import androidx.room.Dao
import androidx.room.Query
import com.hortus1556.exilum.db.entity.ModuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModuleDao {

    @Query("SELECT id FROM modules WHERE filePath = :path")
    suspend fun idForPath(path: String): Int?

    @Insert
    suspend fun insert(item: ModuleEntity)

    /** @return how many rows it changed: none when the row it names is gone. */
    @Update
    suspend fun update(item: ModuleEntity): Int

    /**
     * Writes [items] by their path, all in one transaction.
     *
     * Not Room's @Upsert, which tries an insert and, when that clashes with a
     * row already there, updates by primary key. Rows are made for a path,
     * without their id - 0 - and an update where id is 0 matches nothing: a
     * file the browser had listed before it was tested had its result thrown
     * away without a word, never showed its title or format, and was opened
     * and tested again on every visit to its folder, hundreds of files at a
     * time. And one transaction for the lot, rather than one per row: each is
     * a notice to every query on this table - the open folder's list among
     * them, rebuilt on screen once for every file.
     *
     * A row that does carry its id - one read from here and changed - is
     * written straight to it, without being looked up again.
     */
    @Transaction
    suspend fun upsertByPath(items: List<ModuleEntity>) {
        for (item in items) {
            // An id whose row has gone meanwhile is no id.
            if (item.id != 0 && update(item) > 0) continue
            val id = idForPath(item.filePath)
            if (id == null) insert(item.copy(id = 0)) else update(item.copy(id = id))
        }
    }

    @Query(
        "SELECT * FROM modules WHERE parentPath = :parentPath " +
            "ORDER BY isDirectory DESC, filename ASC"
    )
    fun getChildren(parentPath: String): Flow<List<ModuleEntity>>

    @Query(
        "SELECT * FROM modules WHERE parentPath = :parentPath " +
            "ORDER BY isDirectory DESC, filename ASC"
    )
    suspend fun getChildrenOnce(parentPath: String): List<ModuleEntity>

    /**
     * Everything below [root], however deep - the folders and what is in
     * them - in one query, walking the parent links.
     *
     * By the links rather than by a LIKE on the path: a document's address
     * is opaque for some providers (the Downloads one hands out plain
     * numbers), so sharing the start of a path does not say one is inside
     * the other, and the links indexing recorded always do.
     */
    @Query(
        "WITH RECURSIVE tree(path) AS (" +
            "SELECT :root UNION ALL " +
            "SELECT m.filePath FROM modules m JOIN tree ON m.parentPath = tree.path WHERE m.isDirectory = 1" +
            ") SELECT m.* FROM modules m JOIN tree ON m.parentPath = tree.path"
    )
    suspend fun getSubtree(root: String): List<ModuleEntity>

    /**
     * What below [root] has [pattern] - a LIKE pattern - in its name: folders,
     * and the files that are modules or not yet known not to be, those by
     * their title as well. LIKE takes no notice of case for ASCII letters
     * only, so this is for a search in ASCII; see ModuleRepository.
     */
    @Query(
        "WITH RECURSIVE tree(path) AS (" +
            "SELECT :root UNION ALL " +
            "SELECT m.filePath FROM modules m JOIN tree ON m.parentPath = tree.path WHERE m.isDirectory = 1" +
            ") SELECT m.* FROM modules m JOIN tree ON m.parentPath = tree.path WHERE " +
            "(m.isDirectory = 1 AND m.filename LIKE :pattern ESCAPE '\\') OR " +
            "(m.isDirectory = 0 AND (m.isValidModule = 1 OR m.isTested = 0) AND " +
            "(m.filename LIKE :pattern ESCAPE '\\' OR m.moduleName LIKE :pattern ESCAPE '\\'))"
    )
    suspend fun searchSubtree(root: String, pattern: String): List<ModuleEntity>

    @Query("SELECT * FROM modules WHERE isValidModule = 1 AND isDirectory = 0")
    suspend fun getAllValidModules(): List<ModuleEntity>

    @Query("SELECT filePath FROM modules")
    suspend fun getAllFilePaths(): List<String>

    @Query("SELECT filePath FROM modules WHERE isDirectory = 1 OR isTested = 1")
    suspend fun getConfirmedPaths(): List<String>

    /** Sends every file found not to be a module back to be tested again - see ModuleRepository. */
    @Query("UPDATE modules SET isTested = 0 WHERE isDirectory = 0 AND isValidModule = 0 AND isTested = 1")
    suspend fun markRejectedUntested()

    @Query("DELETE FROM modules WHERE filePath IN (:paths)")
    suspend fun deleteByFilePathsChunk(paths: List<String>)

    /**
     * Deletes the rows of [paths], however many. A few hundred at a time: each
     * path is a variable bound to the statement, of which SQLite takes 999
     * before Android 12 - a library moved, or a thousand files deleted, and
     * the scan that came to clear their rows away crashed instead, every
     * time it ran.
     */
    @Transaction
    suspend fun deleteByFilePaths(paths: List<String>) {
        for (chunk in paths.chunked(500)) deleteByFilePathsChunk(chunk)
    }

    @Query("SELECT * FROM modules WHERE filePath IN (:paths)")
    suspend fun getByFilePaths(paths: List<String>): List<ModuleEntity>

    @Query(
        "SELECT * FROM modules WHERE moduleName LIKE " +
            "'%' || :query || '%' OR fileName LIKE '%' || :query || '%'"
    )
    suspend fun searchBy(query: String): List<ModuleEntity>
}
