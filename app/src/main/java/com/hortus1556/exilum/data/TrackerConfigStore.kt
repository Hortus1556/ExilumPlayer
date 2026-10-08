package com.hortus1556.exilum.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.db.dao.FavouriteDao
import com.hortus1556.exilum.db.dao.FileOverridesDao
import com.hortus1556.exilum.db.dao.PlaylistDao
import com.hortus1556.exilum.db.dao.SubsongNameDao
import com.hortus1556.exilum.db.entity.FavouriteEntity
import com.hortus1556.exilum.db.entity.FileOverridesEntity
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.db.entity.PlaylistEntryEntity
import com.hortus1556.exilum.db.entity.SubsongNameEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Name of the file written into the user's chosen root folder. */
const val TRACKER_CONFIG_FILENAME = "tracker.conf"

/**
 * Mime type used when creating that file.
 *
 * Deliberately one no extension maps back from. SAF providers append the
 * extension of the type they are handed, so "application/json" here is what
 * turned the file into `tracker.conf.json`.
 */
private const val TRACKER_CONFIG_MIME = "application/x-tracker-config"

/**
 * Version 2 added subsongs: [TrackerEntry.subsong], [TrackerFavourite.subsong]
 * and [subsongNames]. A version 1 file reads as every entry being a whole file
 * and no names given, which is what it meant.
 *
 * Version 3 added [overrides]: per-file volume boost, lengths, core settings
 * and loop presets. Older files read as none.
 */
@Serializable
data class TrackerConfig(
    val version: Int = 3,
    val playlists: List<TrackerPlaylist> = emptyList(),
    val favourites: List<TrackerFavourite> = emptyList(),
    val subsongNames: List<TrackerSubsongName> = emptyList(),
    val overrides: List<TrackerOverride> = emptyList(),
)

/** One file's overrides - see [FileOverrides]. */
@Serializable
data class TrackerOverride(
    val uri: String,
    val overrides: FileOverrides,
)

@Serializable
data class TrackerPlaylist(
    val name: String,
    val comment: String = "",
    val createdAt: Long = 0L,
    val entries: List<TrackerEntry> = emptyList(),
)

@Serializable
data class TrackerEntry(
    val position: Int,
    val uri: String,
    val name: String,
    val extension: String,
    /** The subsong played, or -1 for the file as a whole. */
    val subsong: Int = -1,
)

@Serializable
data class TrackerFavourite(
    val uri: String,
    val name: String,
    val extension: String,
    val addedAt: Long = 0L,
    /** The subsong starred, or -1 for the file as a whole. */
    val subsong: Int = -1,
)

/** A subsong's name - the format's own, kept when it was saved, or one the listener gave it. */
@Serializable
data class TrackerSubsongName(
    val uri: String,
    val subsong: Int,
    val name: String,
)

/**
 * Keeps playlists and favourites in a `tracker.conf` beside the user's music,
 * with the file - not the database - as the authority.
 *
 * The point is surviving a reinstall. Room lives in app-private storage, which
 * Android deletes with the app; the chosen music folder does not, and the app
 * already holds a persisted read-write grant on it. So the database is treated
 * as a cache that can be rebuilt: on startup the file wins and the tables are
 * made to match it, and thereafter any change to either table rewrites it.
 *
 * The one situation this cannot cover is the folder being unreachable - no
 * root picked yet, or the grant revoked. Then there is nowhere to read from or
 * write to, and the database is all there is; [load] and [save] both no-op
 * rather than destroying what is in it.
 */
@OptIn(FlowPreview::class)
class TrackerConfigStore(
    private val context: Context,
    private val prefs: AppPreferences,
    private val playlistDao: PlaylistDao,
    private val favouriteDao: FavouriteDao,
    private val subsongNameDao: SubsongNameDao,
    private val fileOverridesDao: FileOverridesDao,
) {

    /** Resolved config file and the root it belongs to; see [configFile]. */
    private var cachedFile: Uri? = null
    private var cachedRoot: String? = null

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Restores from the file if there is one, then keeps it up to date.
     *
     * Keyed on the chosen root folder rather than run once at startup: there
     * is no file to read or write before a folder has been picked, and picking
     * a different one later moves the config with it. [collectLatest] tears the
     * writer down before the next folder's restore begins, so a stale writer
     * can never fire against the new file.
     *
     * Ordering inside each folder matters too: the restore has to finish
     * before the writer starts, or the writer would observe the pre-restore
     * (typically empty) tables and overwrite the very file being restored
     * from. [drop] then skips the emission the restore itself causes.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            prefs.getLastDirectoryFlow()
                .distinctUntilChanged()
                .collectLatest { root ->
                    if (root.isNullOrBlank()) return@collectLatest

                    runCatching { syncWithFolder() }
                        .onFailure { Timber.w(it, "tracker.conf: restore failed") }

                    combine(
                        playlistDao.getAllPlaylists(),
                        playlistDao.getAllEntries(),
                        favouriteDao.getAll(),
                        subsongNameDao.getAll(),
                        fileOverridesDao.getAll(),
                    ) { _, _, _, _, _ -> Unit }
                        .drop(1)
                        // Bulk edits - importing a playlist, adding a folder -
                        // land as a burst of table writes; without this the
                        // file would be rewritten once per row.
                        .debounce(SAVE_DEBOUNCE_MS)
                        .collect {
                            runCatching { save() }
                                .onFailure { e -> Timber.w(e, "tracker.conf: save failed") }
                        }
                }
        }
    }

    /**
     * Brings folder and database into agreement.
     *
     * A folder with a config is the authority and overwrites the tables. A
     * folder without one gets seeded from them instead - that is the upgrade
     * path for anyone who already had playlists before this file existed, and
     * it means their first reinstall is already covered rather than only the
     * one after their next edit.
     *
     * On IO because resolving the file enumerates the folder, and [start]
     * collects on the main thread.
     */
    private suspend fun syncWithFolder() = withContext(Dispatchers.IO) {
        if (configFile(create = false) != null) restore() else save()
    }

    /**
     * Makes the database hold whatever the file holds.
     *
     * Only what differs is written. This runs on every start of the app, when
     * the two nearly always agree already, and it used to empty and refill
     * every table regardless: each list on screen went blank and came back,
     * and every playlist was a new row with a new id - see
     * [PlaylistDao.replaceAll] for what that did to a playlist's open page.
     */
    suspend fun restore() = withContext(Dispatchers.IO) {
        val text = readConfigText() ?: return@withContext
        val config = runCatching { json.decodeFromString<TrackerConfig>(text) }.getOrNull()
            ?: run {
                // A corrupt file must not wipe the database. Leaving it alone
                // means the next save rewrites it from what is still in Room.
                Timber.w("tracker.conf: unreadable, keeping the database as-is")
                return@withContext
            }

        Timber.i(
            "tracker.conf: restoring %d playlists, %d favourites",
            config.playlists.size, config.favourites.size,
        )

        val favourites = config.favourites.map {
            FavouriteEntity(
                uri = it.uri,
                subsong = it.subsong,
                name = it.name,
                extension = it.extension,
                addedAt = it.addedAt,
            )
        }
        if (favouriteDao.getAllOnce().toSet() != favourites.toSet()) {
            favouriteDao.clear()
            favouriteDao.addAll(favourites)
        }

        val subsongNames = config.subsongNames.map {
            SubsongNameEntity(uri = it.uri, subsong = it.subsong, name = it.name)
        }
        if (subsongNameDao.getAllOnce().toSet() != subsongNames.toSet()) {
            subsongNameDao.clear()
            subsongNameDao.addAll(subsongNames)
        }

        val overrides = config.overrides
            .filterNot { it.overrides.isEmpty }
            .map { FileOverridesEntity(uri = it.uri, data = FileOverridesRepository.encode(it.overrides)) }
        if (fileOverridesDao.getAllOnce().toSet() != overrides.toSet()) {
            fileOverridesDao.clear()
            fileOverridesDao.addAll(overrides)
        }

        playlistDao.replaceAll(
            config.playlists.map { playlist ->
                PlaylistEntity(
                    name = playlist.name,
                    comment = playlist.comment,
                    createdAt = playlist.createdAt,
                ) to playlist.entries
                    .sortedBy { it.position }
                    // A file written while the same track could be added twice
                    // has it twice; the playlist is given it once. See
                    // PlaylistDao.append.
                    .distinctBy { it.uri to it.subsong }
                    .map { entry ->
                        PlaylistEntryEntity(
                            // Both are replaceAll's to give.
                            playlistId = 0,
                            position = 0,
                            uri = entry.uri,
                            name = entry.name,
                            extension = entry.extension,
                            subsong = entry.subsong,
                        )
                    }
            }
        )
    }

    /** Writes the current database contents out to the file. */
    suspend fun save() = withContext(Dispatchers.IO) {
        val playlists = playlistDao.getAllPlaylistsOnce().map { playlist ->
            TrackerPlaylist(
                name = playlist.name,
                comment = playlist.comment,
                createdAt = playlist.createdAt,
                entries = playlistDao.getEntries(playlist.id).map {
                    TrackerEntry(
                        position = it.position,
                        uri = it.uri,
                        name = it.name,
                        extension = it.extension,
                        subsong = it.subsong,
                    )
                },
            )
        }
        val favourites = favouriteDao.getAllOnce().map {
            TrackerFavourite(
                uri = it.uri,
                name = it.name,
                extension = it.extension,
                addedAt = it.addedAt,
                subsong = it.subsong,
            )
        }
        val subsongNames = subsongNameDao.getAllOnce().map {
            TrackerSubsongName(uri = it.uri, subsong = it.subsong, name = it.name)
        }
        val overrides = fileOverridesDao.getAllOnce().mapNotNull { row ->
            runCatching { FileOverridesRepository.json.decodeFromString<FileOverrides>(row.data) }
                .getOrNull()
                ?.let { TrackerOverride(uri = row.uri, overrides = it) }
        }
        writeConfigText(
            json.encodeToString(
                TrackerConfig(
                    playlists = playlists,
                    favourites = favourites,
                    subsongNames = subsongNames,
                    overrides = overrides,
                )
            )
        )
    }

    private suspend fun readConfigText(): String? =
        configFile(create = false)?.let { readText(it) }

    private suspend fun writeConfigText(text: String) {
        val fileUri = configFile(create = true) ?: return
        // "wt" and not the default "w": several SAF providers do not truncate
        // on plain "w", so a config that shrank would keep the tail of the
        // longer one it replaced and no longer parse.
        runCatching {
            context.contentResolver.openOutputStream(fileUri, "wt")?.use {
                it.write(text.encodeToByteArray())
            }
        }.onFailure { Timber.w(it, "tracker.conf: write failed") }
    }

    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
    }.getOrNull()

    /**
     * The one config file in the chosen folder, creating it if asked to.
     *
     * Cached per root folder: [save] runs on every change behind a debounce,
     * and each miss is a full directory enumeration.
     */
    private suspend fun configFile(create: Boolean): Uri? {
        val root = prefs.getLastDirectoryUri() ?: return null
        if (root != cachedRoot) {
            cachedRoot = root
            cachedFile = null
        }
        cachedFile?.let { return it }
        return resolveConfigFile(root.toUri(), create)?.also { cachedFile = it }
    }

    /**
     * Finds the config file, collapsing any duplicates down to one.
     *
     * There is duplicate-collapsing to do because the first version of this
     * created the document as `application/json`, and a SAF provider appends
     * the extension its mime type maps to - so the file that landed was
     * `tracker.conf.json`, which the exact-name lookup here then never
     * recognised, so every single save created another one. Matching on the
     * prefix is what finds those again; the rename below is what stops the
     * name drifting even if a provider still decides to decorate it.
     *
     * Deletion only ever touches a file whose name starts with the config's
     * and whose contents are blank or parse as a config, because everything
     * being deleted here is this app's own mess and nothing else is.
     */
    private fun resolveConfigFile(rootUri: Uri, create: Boolean): Uri? = runCatching {
        val resolver = context.contentResolver
        val treeDoc = DocumentsContract.getTreeDocumentId(rootUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, treeDoc)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, treeDoc)

        val found = mutableListOf<Pair<Uri, String>>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (!name.startsWith(TRACKER_CONFIG_FILENAME)) continue
                found += DocumentsContract.buildDocumentUriUsingTree(
                    rootUri, cursor.getString(0),
                ) to name
            }
        }

        if (found.isEmpty()) {
            if (!create) return@runCatching null
            return@runCatching createConfigFile(dirUri)
        }

        // The correctly named one if it is there, otherwise whichever actually
        // holds a readable config - the strays are mostly empty or half
        // written, and picking one of those would throw the real data away.
        val winner = found.firstOrNull { it.second == TRACKER_CONFIG_FILENAME }
            ?: found.firstOrNull { (uri, _) -> parses(readText(uri)) }
            ?: found.first()

        for ((uri, name) in found) {
            if (uri == winner.first) continue
            val text = readText(uri)
            if (text != null && text.isNotBlank() && !parses(text)) {
                Timber.w("tracker.conf: leaving '%s' alone, not one of ours", name)
                continue
            }
            Timber.i("tracker.conf: removing duplicate '%s'", name)
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }

        if (winner.second == TRACKER_CONFIG_FILENAME) {
            winner.first
        } else {
            Timber.i("tracker.conf: renaming '%s' back", winner.second)
            runCatching {
                DocumentsContract.renameDocument(resolver, winner.first, TRACKER_CONFIG_FILENAME)
            }.getOrNull() ?: winner.first
        }
    }.onFailure { Timber.w(it, "tracker.conf: could not resolve the file") }.getOrNull()

    /**
     * Creates the file, and puts the name back if the provider decorated it.
     *
     * The mime type is deliberately one nothing maps an extension to: a SAF
     * provider appends `.<ext>` for whatever `MimeTypeMap` gives back for the
     * type, so asking for `application/json` is what produced `tracker.conf.json`.
     */
    private fun createConfigFile(dirUri: Uri): Uri? {
        val resolver = context.contentResolver
        val created = runCatching {
            DocumentsContract.createDocument(
                resolver, dirUri, TRACKER_CONFIG_MIME, TRACKER_CONFIG_FILENAME,
            )
        }.getOrNull() ?: return null

        val name = runCatching {
            resolver.query(
                created,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

        if (name != null && name != TRACKER_CONFIG_FILENAME) {
            runCatching {
                DocumentsContract.renameDocument(resolver, created, TRACKER_CONFIG_FILENAME)
            }.getOrNull()?.let { return it }
        }
        return created
    }

    private fun parses(text: String?): Boolean =
        text != null && runCatching { json.decodeFromString<TrackerConfig>(text) }.isSuccess

    private companion object {
        const val SAVE_DEBOUNCE_MS = 750L
    }
}
