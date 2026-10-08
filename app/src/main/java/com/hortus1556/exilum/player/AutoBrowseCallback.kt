package com.hortus1556.exilum.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.db.dao.ModuleDao
import com.hortus1556.exilum.db.dao.PlaylistDao
import com.hortus1556.exilum.db.entity.ModuleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * [MediaLibrarySession.Callback] implementation for Android Auto integration.
 * All file-browser content is served from the Room database — no SAF scanning at browse time.
 */
@OptIn(UnstableApi::class)
class AutoBrowseCallback(
    private val context: Context,
    private val prefs: AppPreferences,
    private val playlistDao: PlaylistDao,
    private val modulesDao: ModuleDao,
    private val scope: CoroutineScope
) : MediaLibrarySession.Callback {

    private val contentStyleExtras = Bundle().apply {
        putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
        putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2)
    }

    /**
     * The last search and what it found. One, where every query ever made
     * used to be kept with its whole result until the service died - and in a
     * plain map written on an IO thread while the session's thread read it.
     */
    @Volatile
    private var searchResults: Pair<String, List<MediaItem>>? = null
    private var activeSearchJob: Job? = null

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Timber.d("onGetLibraryRoot: browser=${browser.packageName}")
        val root = buildBrowsableItem(
            id = AutoMediaId.ROOT,
            title = context.getString(R.string.app_name),
            type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        )
        return Futures.immediateFuture(LibraryResult.ofItem(root, null))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        Timber.d("onGetChildren: parentId=$parentId page=$page")
        return ioFuture {
            val items = when {
                parentId == AutoMediaId.ROOT -> getRootChildren()
                parentId == AutoMediaId.HOME -> getHomeChildren()
                parentId == AutoMediaId.FILE_BROWSER -> getFileBrowserRoot()
                parentId == AutoMediaId.PLAYLISTS -> getPlaylists()
                AutoMediaId.isDir(parentId) -> getDirectoryChildren(parentId)
                else -> ImmutableList.of()
            }
            // The page asked for, not the whole folder whatever was asked.
            // Media3 checks what comes back against the page size and throws
            // on a longer list - on the main thread, with nothing to catch
            // it: any browser that pages, opening a folder of more files than
            // its page, took the app down in the middle of playback.
            val from = (page.toLong() * pageSize).coerceIn(0L, items.size.toLong()).toInt()
            val to = (from.toLong() + pageSize).coerceIn(from.toLong(), items.size.toLong()).toInt()
            LibraryResult.ofItemList(items.subList(from, to), params)
        }
    }

    /**
     * For PLAY_ALL / SHUFFLE_ALL, queries all valid modules from the DB.
     * For a single file tap, resolves its siblings from the DB using parentPath.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val singleId = mediaItems.singleOrNull()?.mediaId
        val player = mediaSession.player as? ModPlayer
        if (singleId == AutoMediaId.SHUFFLE_ALL && player != null) {
            // Shuffle over the library, the player's own: one track now and
            // the rest dealt a window at a time. The whole library used to be
            // handed over as the queue - every file an item built on the
            // phone's main thread, then written out as the saved queue.
            player.setShuffleRoot(true)
            return ioFuture {
                val pick = modulesDao.getAllValidModules().randomOrNull()
                    ?: throw IllegalStateException("Nothing to shuffle")
                MediaSession.MediaItemsWithStartPosition(
                    listOf(buildPlayableItem(AutoMediaId.file(pick.filePath), pick.name, pick.uri)),
                    0,
                    0L,
                )
            }
        }
        if (singleId == AutoMediaId.PLAY_ALL || singleId == AutoMediaId.SHUFFLE_ALL) {
            return buildPlayAllFuture(shuffle = singleId == AutoMediaId.SHUFFLE_ALL)
        }

        if (singleId == null || !AutoMediaId.isFile(singleId)) {
            return super.onSetMediaItems(
                mediaSession,
                controller,
                mediaItems,
                startIndex,
                startPositionMs
            )
        }

        // Read here, on the session's thread: the item is the caller's.
        val tappedTitle = mediaItems.single().mediaMetadata.title?.toString()
        return ioFuture {
            val selectedFilePath = AutoMediaId.uriFromFile(singleId)
            val parentPath =
                modulesDao.getByFilePaths(listOf(selectedFilePath)).firstOrNull()?.parentPath
            if (parentPath == null) {
                // Not a file the library has indexed - a playlist entry from
                // another folder, which the search offers too. It has no
                // siblings to queue, and plays on its own; this used to throw,
                // and tapping such a result did nothing at all.
                val uri = selectedFilePath.toUri()
                val title = tappedTitle ?: uri.lastPathSegment ?: selectedFilePath
                return@ioFuture MediaSession.MediaItemsWithStartPosition(
                    listOf(buildPlayableItem(singleId, title, uri)),
                    0,
                    0L,
                )
            }

            val siblings = modulesDao.getChildrenOnce(parentPath)
                .filter { !it.isDirectory && it.isValidModule }
                .map { buildPlayableItem(AutoMediaId.file(it.filePath), it.name, it.uri) }

            val startIdx = siblings.indexOfFirst { it.mediaId == singleId }.coerceAtLeast(0)
            MediaSession.MediaItemsWithStartPosition(siblings, startIdx, 0L)
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        val resolved = mediaItems.map { item ->
            val uri = when {
                AutoMediaId.isFile(item.mediaId) -> AutoMediaId.uriFromFile(item.mediaId).toUri()
                else -> item.localConfiguration?.uri
            }
            if (uri != null) item.buildUpon().setUri(uri).build() else item
        }
        return Futures.immediateFuture(resolved)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        Timber.d("onSearch: query='$query'")
        val future = SettableFuture.create<LibraryResult<Void>>()
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch(Dispatchers.IO) {
            try {
                val playlistItems = playlistDao.searchByName(query).map { entry ->
                    buildPlayableItem(AutoMediaId.file(entry.uri), entry.name, entry.uri.toUri())
                }
                val fileItems = modulesDao.searchBy(query)
                    .filter { it.isValidModule }
                    .map { buildPlayableItem(AutoMediaId.file(it.filePath), it.name, it.uri) }

                val seen = mutableSetOf<String>()
                val items = (playlistItems + fileItems).filter { seen.add(it.mediaId) }
                searchResults = query to items

                Timber.d("onSearch: found ${items.size} results for '$query'")
                session.notifySearchResultChanged(browser, query, items.size, params)
                future.set(LibraryResult.ofVoid())
            } finally {
                // Overtaken by the next search, or failed: answered all the
                // same. A search cancelled by the one after it used to leave
                // its caller waiting on this for ever.
                if (!future.isDone) future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
            }
        }
        return future
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        Timber.d("onGetSearchResult: query='$query' page=$page pageSize=$pageSize")
        val found = searchResults?.takeIf { it.first == query }?.second ?: emptyList()
        val paged = found.drop(page * pageSize).take(pageSize)
        return Futures.immediateFuture(
            LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
        )
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Timber.d("onPlaybackResumption: isForPlayback=$isForPlayback")
        return ioFuture {
            val state = prefs.getQueueState()
                ?: throw UnsupportedOperationException("No saved queue")
            val files = Json.decodeFromString<List<ModuleEntity>>(state.json)
            if (files.isEmpty()) throw UnsupportedOperationException("Queue is empty")

            val mediaItems = files.map { file ->
                buildPlayableItem(
                    id = file.filePath,
                    title = file.filename,
                    uri = file.uri,
                    artworkUri = drawableUri(R.drawable.aa_file),
                )
            }
            MediaSession.MediaItemsWithStartPosition(mediaItems, state.index, state.positionMs)
        }
    }

    private fun getRootChildren(): ImmutableList<MediaItem> = ImmutableList.of(
        buildBrowsableItem(
            id = AutoMediaId.HOME,
            title = "Home",
            type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            extras = contentStyleExtras,
            artworkUri = drawableUri(R.drawable.aa_home)
        ),
        buildBrowsableItem(
            id = AutoMediaId.PLAYLISTS,
            title = "Playlists",
            type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            extras = contentStyleExtras,
            artworkUri = drawableUri(R.drawable.aa_playlists)
        ),
        buildBrowsableItem(
            id = AutoMediaId.FILE_BROWSER,
            title = "File Browser",
            type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            artworkUri = drawableUri(R.drawable.aa_browser)
        ),
    )

    private fun getPlaylists(): ImmutableList<MediaItem> = ImmutableList.of(
        buildBrowsableItem(
            id = "playlists_coming_soon",
            title = "Coming Soon",
            type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
        )
    )

    private fun getHomeChildren(): ImmutableList<MediaItem> = ImmutableList.of(
        buildPlayableItem(
            id = AutoMediaId.SHUFFLE_ALL,
            title = "Shuffle",
            artworkUri = drawableUri(R.drawable.aa_shuffle)
        ),
        buildPlayableItem(
            id = AutoMediaId.PLAY_ALL,
            title = "Play all",
            artworkUri = drawableUri(R.drawable.aa_play_all)
        )
    )

    private suspend fun getFileBrowserRoot(): ImmutableList<MediaItem> {
        val savedUri = prefs.getLastDirectoryUri() ?: return ImmutableList.of()
        return buildChildItems(savedUri)
    }

    private suspend fun getDirectoryChildren(parentId: String): ImmutableList<MediaItem> =
        buildChildItems(AutoMediaId.pathFromDir(parentId))

    /**
     * Queries the DB for all direct children of [parentPath].
     * Directories become browsable items; valid module files become playable items.
     */
    private suspend fun buildChildItems(parentPath: String): ImmutableList<MediaItem> =
        ImmutableList.copyOf(
            modulesDao.getChildrenOnce(parentPath).mapNotNull { entity ->
                when {
                    entity.isDirectory -> buildBrowsableItem(
                        id = AutoMediaId.dir(entity.filePath),
                        title = entity.filename,
                        type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        artworkUri = drawableUri(R.drawable.aa_folder)
                    )

                    entity.isValidModule -> buildPlayableItem(
                        id = AutoMediaId.file(entity.filePath),
                        title = entity.name,
                        uri = entity.uri,
                        artworkUri = drawableUri(R.drawable.aa_file)
                    )

                    else -> null
                }
            }
        )

    private fun buildPlayAllFuture(
        shuffle: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = ioFuture {
        val items = modulesDao.getAllValidModules()
            .map { buildPlayableItem(AutoMediaId.file(it.filePath), it.name, it.uri) }
            .toMutableList()
        if (shuffle) items.shuffle()
        MediaSession.MediaItemsWithStartPosition(items, 0, 0L)
    }

    /** Runs [block] on the IO dispatcher, completing the returned future with its result. */
    private fun <T> ioFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch(Dispatchers.IO) {
            try {
                future.set(block())
            } catch (e: Exception) {
                Timber.e(e, "Auto browse future failed")
                future.setException(e)
            }
        }
        return future
    }

    private fun drawableUri(resId: Int): Uri =
        "android.resource://${context.packageName}/$resId".toUri()

    private fun buildBrowsableItem(
        id: String,
        title: String,
        type: Int,
        artworkUri: Uri? = null,
        extras: Bundle? = null
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .setArtworkUri(artworkUri)
                .setExtras(extras)
                .build()
        )
        .build()

    private fun buildPlayableItem(
        id: String,
        title: String,
        uri: Uri? = null,
        artworkUri: Uri? = null
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .apply {
            if (uri != null) {
                setUri(uri)
                setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            }
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setArtworkUri(artworkUri)
                .build()
        )
        .build()
}
