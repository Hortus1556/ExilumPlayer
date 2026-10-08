package com.hortus1556.exilum.data

import com.hortus1556.exilum.db.dao.SubsongNameDao
import com.hortus1556.exilum.db.entity.SubsongNameEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The subsong index saved with a favourite or playlist entry when it is not
 * one particular subsong - the file as a whole, which is also every entry
 * saved before subsongs were remembered.
 */
const val WHOLE_MODULE = -1

/** The key [SubsongNamesRepository.names] is looked up by. */
fun subsongKey(uri: String, subsong: Int): String = "$uri#$subsong"

/** What a subsong is called when nothing names it - the player's own subsong list says the same. */
fun defaultSubsongLabel(subsong: Int): String = when {
    isLoopPreset(subsong) -> "Loop ${loopPresetId(subsong) + 1}"
    subsong == 0 -> "Main Song"
    else -> "Sub Song $subsong"
}

/** See [SubsongNameEntity]. */
class SubsongNamesRepository(private val dao: SubsongNameDao) {

    /** Every stored name, keyed by [subsongKey]. */
    val names: Flow<Map<String, String>> = dao.getAll().map { rows ->
        rows.associate { subsongKey(it.uri, it.subsong) to it.name }
    }

    /** The listener's name for a subsong; blank takes the name away again. */
    suspend fun rename(uri: String, subsong: Int, name: String) {
        if (subsong < 0) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) dao.remove(uri, subsong) else dao.set(SubsongNameEntity(uri, subsong, trimmed))
    }

    /**
     * The format's own name for a subsong, stored when it is saved, so lists
     * can show it without loading the file. Never over a name already there,
     * which may be one the listener chose.
     */
    suspend fun rememberIfMissing(uri: String, subsong: Int, name: String) {
        if (subsong < 0 || name.isBlank()) return
        if (dao.get(uri, subsong) == null) dao.set(SubsongNameEntity(uri, subsong, name.trim()))
    }
}
