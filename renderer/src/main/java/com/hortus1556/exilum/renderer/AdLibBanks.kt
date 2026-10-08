package com.hortus1556.exilum.renderer

import android.content.Context
import android.net.Uri

/**
 * The instruments of an AdLib tune that keeps them in another file, read
 * from beside it for the native side - which is handed the one file's
 * descriptor, and AdPlug's players ask for the other by name.
 *
 * The names are the ones those players look for, in their order: a .ksm's
 * insts.dat (ksm.cpp), a .rol's standard.bnk (rol.cpp), the timbres of a
 * .mus and the bank of an .ims under the tune's own name or the usual ones
 * (mus.cpp), an AdTrack .sng's .ins (adtrack.cpp), a Sierra .sci's
 * patch.003 behind the first three characters of its name (mid.cpp).
 *
 * Without its bank a .ksm or a .rol is no tune at all, and is not listed;
 * a .mus plays without one, in a voice of nothing.
 */
internal object AdLibBanks {
    /** The largest of them is a few hundred kilobytes. */
    private const val MAX_BYTES = 4L shl 20

    /** The banks of the file at [uri] called [name] that are beside it; empty for any other kind of file. */
    fun collect(context: Context, uri: Uri, name: String): List<Pair<String, ByteArray>> {
        val found = ArrayList<Pair<String, ByteArray>>()
        for (candidate in names(name)) {
            val sibling = Siblings.find(context, uri, candidate) ?: continue
            val bytes = Siblings.read(context, sibling, MAX_BYTES) ?: continue
            found += candidate to bytes
        }
        return found
    }

    /** Every name the bank of the tune called [name] could have. */
    fun names(name: String): List<String> {
        val leaf = Siblings.baseName(name)
        val dot = leaf.lastIndexOf('.')
        if (dot <= 0) return emptyList()
        val stem = leaf.substring(0, dot)
        return when (leaf.substring(dot + 1).lowercase()) {
            "ksm" -> listOf("insts.dat")
            "rol" -> listOf("standard.bnk")
            "mus" -> listOf("$stem.snd", "$stem.tim", "$stem.tbr", "timbres.snd", "timbres.tim", "timbres.tbr")
            "ims" -> listOf("$stem.bnk", "implay.bnk", "standard.bnk")
            "sng" -> listOf("$stem.ins")
            "sci" -> listOf(leaf.take(3) + "patch.003")
            else -> emptyList()
        }
    }
}
