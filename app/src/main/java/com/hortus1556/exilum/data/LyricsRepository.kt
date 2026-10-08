package com.hortus1556.exilum.data

import android.content.Context
import androidx.core.net.toUri
import com.hortus1556.exilum.db.dao.ModuleDao
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.renderer.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The words of a song: the ones the file itself carries, in its tags, or
 * failing those the ones in a .lrc of the same name beside it.
 */
class LyricsRepository(
    private val context: Context,
    private val dao: ModuleDao,
) {

    /**
     * The words of [module], which is the file loaded: the renderer is asked
     * for what its tags hold, so this is for the file playing and no other.
     * Null when it has none.
     */
    suspend fun ofPlaying(module: ModuleEntity): Lyrics? = withContext(Dispatchers.IO) {
        val own = runCatching { Player.getLyrics() }.getOrNull()
        own?.let(Lyrics::parse) ?: beside(module)?.let(Lyrics::parse)
    }

    /** The text of the .lrc beside [module], found among the rows of its folder. */
    private suspend fun beside(module: ModuleEntity): String? {
        if (module.parentPath.isBlank()) return null
        val wanted = module.filename.substringBeforeLast('.') + ".lrc"
        val row = dao.getChildrenOnce(module.parentPath)
            .firstOrNull { !it.isDirectory && it.filename.equals(wanted, ignoreCase = true) }
            ?: return null
        return runCatching {
            context.contentResolver.openInputStream(row.filePath.toUri())?.use { stream ->
                // A song's words are kilobytes: anything much longer is not that.
                val bytes = stream.readNBytesCompat(MAX_BYTES)
                decode(bytes)
            }
        }.onFailure { Timber.w(it, "lyrics: could not read %s", wanted) }.getOrNull()
    }

    /** UTF-8 unless the file says it is UTF-16, as editors on Windows save it. */
    private fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (out.size() < limit) {
            val got = read(chunk, 0, minOf(chunk.size, limit - out.size()))
            if (got <= 0) break
            out.write(chunk, 0, got)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_BYTES = 512 * 1024
    }
}
