package com.hortus1556.exilum.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Xml
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.util.queryDirectoryEntries
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber

/**
 * Playlists saved by ZXTune for Android, brought into the library's own.
 *
 * ZXTune saves each playlist as an XSPF file in its storage folder, under
 * Playlists - ZXTune/Playlists on the device's storage unless it was pointed
 * elsewhere. Each track is a location in ZXTune's own form: a file: or
 * content: address, with a fragment for what is inside it - "#3" for the
 * third subtune of a file that has several, a path for a file inside an
 * archive. See app.zxtune.playlist.xspf and app.zxtune.core.Identifier in
 * ZXTune's source.
 *
 * A track is kept when it is a file this library has indexed, found by its
 * place on the storage or, failing that, by its name. What is not - a file
 * from one of ZXTune's online catalogues, or one inside an archive that was
 * never unpacked - is counted as skipped, and so is a track a playlist has a
 * second time.
 */

/** Where ZXTune keeps its playlists unless told otherwise: a hint for the folder picker. */
val ZXTUNE_PLAYLISTS_HINT: Uri = DocumentsContract.buildDocumentUri(
    "com.android.externalstorage.documents",
    "primary:ZXTune/Playlists",
)

data class ZxtunePlaylist(val name: String, val locations: List<String>)

data class ZxtuneImport(
    val playlists: List<Pair<String, List<ModuleEntity>>>,
    val tracks: Int,
    val skipped: Int,
)

/** Reads one XSPF playlist as ZXTune writes it. [fallbackName] names it when the file does not. */
fun parseZxtunePlaylist(stream: InputStream, fallbackName: String): ZxtunePlaylist {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(stream, null)

    var name: String? = null
    var desktop = false
    val locations = mutableListOf<String>()
    var property: String? = null
    var inTrack = false

    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "property" -> property = parser.getAttributeValue(null, "name")
                "track" -> inTrack = true
                "location" -> if (inTrack) {
                    parser.nextText().trim().takeIf { it.isNotEmpty() }?.let { locations += it }
                }
            }

            XmlPullParser.TEXT -> when (property) {
                "zxtune.app.playlist.name" -> name = parser.text.trim().takeIf { it.isNotEmpty() }
                "zxtune.app.playlist.creator" -> desktop = parser.text.trim().startsWith("zxtune-qt")
            }

            XmlPullParser.END_TAG -> when (parser.name) {
                "property" -> property = null
                "track" -> inTrack = false
            }
        }
    }
    // The desktop player writes a subtune as file.ay?#1; the Android one reads
    // it the way ZXTune's own XspfIterator does.
    val fixed = if (desktop) locations.map { it.replace("?#", "#%23").replace('?', '#') } else locations
    return ZxtunePlaylist(name ?: fallbackName, fixed)
}

/**
 * Every .xspf in [treeUri] - or in its Playlists folder, when ZXTune's own
 * folder was picked - with its tracks matched to [library].
 */
fun importZxtunePlaylists(context: Context, treeUri: Uri, library: List<ModuleEntity>): ZxtuneImport {
    val resolver = context.contentResolver
    val rootDoc = DocumentsContract.getTreeDocumentId(treeUri)
    var entries = resolver.queryDirectoryEntries(treeUri, rootDoc) ?: error("Could not read the folder")
    if (entries.none { !it.isDirectory && it.name.endsWith(".xspf", ignoreCase = true) }) {
        entries.firstOrNull { it.isDirectory && it.name.equals("Playlists", ignoreCase = true) }?.let { dir ->
            entries = resolver.queryDirectoryEntries(treeUri, dir.docId) ?: entries
        }
    }
    val files = entries.filter { !it.isDirectory && it.name.endsWith(".xspf", ignoreCase = true) }
        .sortedBy { it.name.lowercase() }

    val matcher = LibraryMatcher(library)
    var tracks = 0
    var skipped = 0
    val playlists = files.mapNotNull { file ->
        val playlist = runCatching {
            resolver.openInputStream(file.childUri)?.use { parseZxtunePlaylist(it, file.name.substringBeforeLast('.')) }
        }.onFailure { Timber.w(it, "ZXTune playlist ${file.name} could not be read") }.getOrNull()
            ?: return@mapNotNull null
        val found = playlist.locations.mapNotNull { location ->
            tracks++
            matcher.resolve(location).also {
                if (it == null) {
                    skipped++
                    Timber.i("ZXTune import: no library file for $location")
                }
            }
        }
        // A track the playlist names twice, or two of its tracks that came to
        // the same library file, is one entry here - see PlaylistDao.append -
        // and the second is counted as skipped, so the number of entries the
        // import reports is the number there are.
        val unique = found.distinctBy { it.filePath to it.subsong }
        skipped += found.size - unique.size
        playlist.name to unique
    }
    return ZxtuneImport(playlists, tracks, skipped)
}

/** Finds a ZXTune location among the files the library has indexed. */
private class LibraryMatcher(library: List<ModuleEntity>) {

    private val byPath = library.associateBy { it.filePath }
    private val byDocId = HashMap<String, ModuleEntity>()
    private val byName = HashMap<String, MutableList<Pair<String, ModuleEntity>>>()

    init {
        for (module in library) {
            val docId = runCatching { DocumentsContract.getDocumentId(Uri.parse(module.filePath)) }.getOrNull()
            if (docId != null) byDocId[docId.lowercase()] = module
            byName.getOrPut(module.filename.lowercase()) { mutableListOf() } += (docId ?: module.filePath) to module
        }
    }

    fun resolve(location: String): ModuleEntity? {
        val uri = Uri.parse(location)
        val subPath = uri.fragment.orEmpty()
        // "#3", or ".../#3": the third subtune.
        val subsong = Regex("(?:^|/)#(\\d+)$").find(subPath)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: -1
        // A file inside an archive: only its own name is any use here.
        val nested = subPath.split('/').lastOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("+un") }

        val module = if (nested == null) findFile(uri) else findByName(nested, null)
        return module?.copy(subsong = subsong)
    }

    private fun findFile(uri: Uri): ModuleEntity? {
        val data = uri.buildUpon().fragment(null).build()
        return when (data.scheme) {
            "content" -> byPath[data.toString()]
                ?: runCatching { DocumentsContract.getDocumentId(data) }.getOrNull()?.let { byDocId[it.lowercase()] }
                ?: data.lastPathSegment?.let { findByName(it.substringAfterLast('/'), it) }

            "file", null -> {
                val path = data.path ?: return null
                storageDocId(path)?.let { byDocId[it.lowercase()] } ?: findByName(path.substringAfterLast('/'), path)
            }

            // One of ZXTune's online catalogues: nothing on this device to point at.
            else -> null
        }
    }

    /** "/storage/emulated/0/Music/a.mod" as the storage provider names it: "primary:Music/a.mod". */
    private fun storageDocId(path: String): String? {
        val primary = listOf("/storage/emulated/0/", "/sdcard/", "/mnt/sdcard/")
            .firstOrNull { path.startsWith(it) }
        if (primary != null) return "primary:" + path.removePrefix(primary)
        val volume = Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/(.*)$").find(path) ?: return null
        return volume.groupValues[1] + ":" + volume.groupValues[2]
    }

    /**
     * By name, when the place did not match. Of several files of that name,
     * the one whose folders agree with [path] furthest back.
     */
    private fun findByName(name: String, path: String?): ModuleEntity? {
        val candidates = byName[name.lowercase()] ?: return null
        if (candidates.size == 1 || path == null) return candidates.first().second
        val wanted = path.lowercase().split('/', ':').filter { it.isNotEmpty() }.reversed()
        return candidates.maxBy { (id, _) ->
            val have = id.lowercase().split('/', ':').filter { it.isNotEmpty() }.reversed()
            wanted.zip(have).takeWhile { (a, b) -> a == b }.size
        }.second
    }
}
