package com.hortus1556.exilum.renderer

import android.content.Context
import android.net.Uri

/**
 * The other half of a GameCube stream split in two files, the left and the
 * right - "m01_l.dsp" and "m01_r.dsp" - read from beside it for the native
 * side, which pairs them (see gc-formats.cpp, pairUp).
 *
 * The names are vgmstream's, in its order, each way round: the native side
 * takes the first it is handed that exists, so every name that could be the
 * partner is looked for, and the ones there are handed over.
 */
internal object StreamPairs {
    /** A long song's half: nothing a GameCube stream holds is bigger. */
    private const val MAX_BYTES = 64L shl 20

    /** The extensions a standard .dsp comes under, as vgmstream checks them. */
    private val EXTENSIONS = setOf("dsp", "adp", "", "wav", "lwav", "dat", "ldat", "rsm", "adpcm")

    private class Pair(val left: String, val right: String, val dotted: Boolean)

    private val PAIRS = listOf(
        Pair("L", "R", false), Pair("l", "r", false), Pair("left", "right", false), Pair("Left", "Right", false),
        Pair(".V0", ".V1", true), Pair(".L", ".R", true), Pair("_0.dsp", "_1.dsp", true),
    )

    /** The partners of the file at [uri] that are beside it; empty for most files. */
    fun collect(context: Context, uri: Uri, name: String): List<kotlin.Pair<String, ByteArray>> {
        val found = LinkedHashMap<String, kotlin.Pair<String, ByteArray>>()
        for (candidate in partnerNames(name)) {
            if (candidate.lowercase() in found) continue
            val sibling = Siblings.find(context, uri, candidate) ?: continue
            val bytes = Siblings.read(context, sibling, MAX_BYTES) ?: continue
            found[candidate.lowercase()] = candidate to bytes
        }
        return found.values.toList()
    }

    /** Every name the other half of [name] could have, as the native side looks for them. */
    fun partnerNames(name: String): List<String> {
        val dot = name.lastIndexOf('.')
        val extension = if (dot < 0) "" else name.substring(dot + 1).lowercase()
        val names = ArrayList<String>()
        for (pair in PAIRS) {
            for ((self, other) in listOf(pair.left to pair.right, pair.right to pair.left)) {
                if (pair.dotted) {
                    if (name.length > self.length && name.endsWith(self)) names += name.dropLast(self.length) + other
                } else if (extension in EXTENSIONS) {
                    val ext = if (dot < 0) "" else name.substring(dot)
                    val stem = name.dropLast(ext.length)
                    if (stem.length > self.length && stem.endsWith(self)) names += stem.dropLast(self.length) + other + ext
                }
            }
        }
        return names
    }
}
