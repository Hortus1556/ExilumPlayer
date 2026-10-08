package com.hortus1556.exilum.renderer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.hortus1556.exilum.renderer.Siblings.readFully
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The libraries a file of the PSF family names in its tags - a .minigsf's
 * .gsflib - read from beside it for the native side, which is handed the one
 * file's descriptor and has no way to reach its neighbours from there.
 *
 * A library can name libraries of its own; they are looked for beside it.
 */
internal object PsfCompanions {
    private const val TAG = "PsfCompanions"
    private const val HEADER_BYTES = 16
    private const val MAX_TAG_BYTES = 50_000
    private const val MAX_DEPTH = 10
    /** A PlayStation 2's whole memory, twice over: nothing a library holds is bigger. */
    private const val MAX_LIBRARY_BYTES = 64L shl 20

    /** What the file at [uri] needs, by the names its tags give; empty for any other file. */
    fun collect(context: Context, uri: Uri): List<Pair<String, ByteArray>> {
        val libraries = librariesNamedIn(context, uri) ?: return emptyList()
        val found = LinkedHashMap<String, Pair<String, ByteArray>>()
        gather(context, uri, libraries, found, depth = 0)
        return found.values.toList()
    }

    private fun gather(
        context: Context,
        from: Uri,
        names: List<String>,
        found: MutableMap<String, Pair<String, ByteArray>>,
        depth: Int,
    ) {
        if (depth >= MAX_DEPTH) return
        for (name in names) {
            val key = Siblings.baseName(name).lowercase()
            if (key in found) continue
            val library = Siblings.find(context, from, Siblings.baseName(name))
            if (library == null) {
                Log.w(TAG, "Not found beside $from: $name")
                continue
            }
            val bytes = Siblings.read(context, library, MAX_LIBRARY_BYTES) ?: continue
            found[key] = name to bytes
            librariesNamedIn(bytes)?.let { gather(context, library, it, found, depth + 1) }
        }
    }

    /** The _lib, _lib2... tags of the file at [uri], in order; null when it is no PSF. */
    private fun librariesNamedIn(context: Context, uri: Uri): List<String>? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val header = ByteArray(HEADER_BYTES)
                if (input.readFully(header) < HEADER_BYTES || !isPsf(header)) return@use null
                val tagAt = tagOffset(header)
                val channel = input.channel
                if (tagAt >= channel.size()) return@use emptyList()
                channel.position(tagAt)
                val tail = ByteArray(minOf(channel.size() - tagAt, (MAX_TAG_BYTES + 5).toLong()).toInt())
                input.readFully(tail)
                librariesInTags(tail, 0, tail.size)
            }
        }
    } catch (e: SecurityException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun librariesNamedIn(bytes: ByteArray): List<String>? {
        if (bytes.size < HEADER_BYTES || !isPsf(bytes)) return null
        val tagAt = tagOffset(bytes)
        if (tagAt >= bytes.size) return emptyList()
        return librariesInTags(bytes, tagAt.toInt(), bytes.size - tagAt.toInt())
    }

    private fun isPsf(header: ByteArray) =
        header[0] == 'P'.code.toByte() && header[1] == 'S'.code.toByte() && header[2] == 'F'.code.toByte()

    private fun tagOffset(header: ByteArray): Long {
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val reserved = le.getInt(4).toLong() and 0xFFFFFFFFL
        val program = le.getInt(8).toLong() and 0xFFFFFFFFL
        return HEADER_BYTES + reserved + program
    }

    /** The library names in a "[TAG]" block at [offset]: _lib first, then _lib2 onward until one is missing. */
    private fun librariesInTags(bytes: ByteArray, offset: Int, length: Int): List<String> {
        if (length < 5 || String(bytes, offset, 5, Charsets.ISO_8859_1) != "[TAG]") return emptyList()
        val text = String(bytes, offset + 5, minOf(length - 5, MAX_TAG_BYTES), Charsets.UTF_8)
        val tags = HashMap<String, String>()
        for (line in text.split('\n')) {
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val key = line.substring(0, equals).trim { it <= ' ' }.lowercase()
            val value = line.substring(equals + 1).trim { it <= ' ' }
            if (key.startsWith("_lib") && value.isNotEmpty()) tags.putIfAbsent(key, value)
        }
        return buildList {
            tags["_lib"]?.let(::add)
            var n = 2
            while (true) add(tags["_lib${n++}"] ?: break)
        }
    }
}
