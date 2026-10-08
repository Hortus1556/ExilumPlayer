package com.hortus1556.exilum.data

import com.hortus1556.exilum.db.dao.FileOverridesDao
import com.hortus1556.exilum.db.entity.FileOverridesEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber

/** See [FileOverrides]. */
class FileOverridesRepository(private val dao: FileOverridesDao) {

    /** Every file's overrides, by uri. A row that does not parse reads as none. */
    val all: Flow<Map<String, FileOverrides>> = dao.getAll().map { rows ->
        rows.associate { it.uri to decode(it) }
    }

    suspend fun get(uri: String): FileOverrides = dao.get(uri)?.let(::decode) ?: FileOverrides()

    /** One change at a time: each reads the row the one before it wrote. */
    private val writes = Mutex()

    /**
     * Changes [uri]'s overrides, dropping the row once nothing is left set.
     *
     * One at a time, in the order asked: two changes that overlapped each
     * read the row before the other had written it, and the later write put
     * the earlier one's setting back. And a change that changes nothing
     * writes nothing - a slider says where it is at every move of the finger,
     * mostly the value it already has, and each of those was a write, a new
     * list of every file's overrides for whoever follows it, and the
     * settings file saved again.
     */
    suspend fun update(uri: String, transform: (FileOverrides) -> FileOverrides) = writes.withLock {
        val current = get(uri)
        val next = transform(current)
        if (next == current) return@withLock
        if (next.isEmpty) dao.remove(uri) else dao.set(FileOverridesEntity(uri, encode(next)))
    }

    private fun decode(row: FileOverridesEntity): FileOverrides =
        runCatching { json.decodeFromString<FileOverrides>(row.data) }
            .onFailure { Timber.w(it, "file_overrides: unreadable row for %s", row.uri) }
            .getOrDefault(FileOverrides())
            .let {
                it.copy(
                    boostDb = 0,
                    settings = it.settings.filterKeys { key -> !OverrideKeys.isRetired(key) },
                )
            }

    companion object {
        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun encode(overrides: FileOverrides): String = json.encodeToString(overrides)
    }
}
