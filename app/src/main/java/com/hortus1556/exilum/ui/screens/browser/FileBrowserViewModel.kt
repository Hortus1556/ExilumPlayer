package com.hortus1556.exilum.ui.screens.browser

import android.os.Bundle
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.hortus1556.exilum.player.soundcheck.LoudnessAnalyzer
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.hortus1556.exilum.R
import com.hortus1556.exilum.data.ModuleRepository
import com.hortus1556.exilum.data.PlaylistRepository
import com.hortus1556.exilum.data.sortedByKey
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.util.takeReadWritePermission
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state and domain models for the SAF file browser. */

enum class BrowserSortOrder { NAME, TYPE, SIZE }

@Immutable
data class FileItem(
    val isDirectory: Boolean,
    val name: String,
    val size: Long,
    val uri: Uri
)

/**
 * The folder above the open one, for the column beside it on a wide screen:
 * where it is, how deep, what is in it, and which of its folders is open.
 */
@Immutable
data class ParentFolder(
    val path: String,
    /** How many folders down from the top it is, the top being 1. */
    val depth: Int,
    val directories: ImmutableList<FileItem>,
    val files: ImmutableList<ModuleEntity>,
    /** The open folder, among [directories]. */
    val open: String,
)

@Immutable
data class BrowserUiState(
    val breadcrumbs: ImmutableList<String> = persistentListOf(),
    val currentPath: String = "",
    val directories: ImmutableList<FileItem> = persistentListOf(),
    val error: String? = null,
    val files: ImmutableList<ModuleEntity> = persistentListOf(),
    /** The folder above this one, while it has a column of its own; null at the top. */
    val parent: ParentFolder? = null,
    val filterQuery: String = "",
    val hasStorageAccess: Boolean = false,
    /** True only until the saved root URI has been resolved from prefs — a single fast read. */
    val isLoading: Boolean = true,
    /** True while an explicit (pull-to-refresh) full re-scan is running. Content stays visible underneath. */
    val isRefreshing: Boolean = false,
    val isShuffle: Boolean = false,
    /** Shuffle over the whole library, set from the player; only with [isShuffle]. */
    val isShuffleRoot: Boolean = false,
    /** True while [BrowserUiState.files] holds matches from below this folder, not its own contents. */
    val isSearching: Boolean = false,
    /**
     * Whether [directories] and [files] are the open folder's, read: false from
     * the moment a folder is opened until its rows are there, when it is
     * neither full nor empty yet and is drawn as neither.
     */
    val listed: Boolean = false,
    @param:StringRes val loadingReason: Int? = null,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sortOrder: BrowserSortOrder = BrowserSortOrder.NAME
)

/**
 * Where each folder's list was left, by the names on the way down to it: the
 * open one's, and - on a wide window - that of the folder above, which has a
 * column of its own. Kept by the view model rather than by the screen, which
 * is put together anew at every rotation and every fold, and started each
 * list at its top again.
 */
class FolderPositions {
    val open = mutableMapOf<List<String>, LazyListState>()
    val parent = mutableMapOf<List<String>, LazyListState>()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class FileBrowserViewModel(
    private val appContext: Context,
    private val prefs: AppPreferences,
    private val db: ModuleRepository,
    private val playlistRepo: PlaylistRepository,
    analyzer: LoudnessAnalyzer,
    private val saved: SavedStateHandle,
) : ViewModel() {

    val state: StateFlow<BrowserUiState>
        field = MutableStateFlow(BrowserUiState())

    val playlists: StateFlow<ImmutableList<PlaylistEntity>>
        field = MutableStateFlow<ImmutableList<PlaylistEntity>>(persistentListOf())

    /** Sound Check's pass over the library, counted beside the bar's buttons while it runs. */
    val soundCheck: StateFlow<LoudnessAnalyzer.Progress> = analyzer.progress

    /** And what it is working through, for the list its capsule opens. */
    val soundCheckQueue: StateFlow<LoudnessAnalyzer.Queue> = analyzer.queue

    /** A folder's rows, as the database has them. */
    private class Listing(val path: String, val children: List<ModuleEntity>)

    /** What a search below a folder turned up. */
    private class Found(val path: String, val matches: List<ModuleEntity>)

    /**
     * A folder laid out for the list: filtered, sorted, split into its folders
     * and its files. Made off the main thread, by the flows below.
     */
    private class Shown(
        val path: String,
        val order: BrowserSortOrder,
        /** Its own contents with nothing filtered out, which is what is worth keeping of it. */
        val whole: Boolean,
        val isSearching: Boolean,
        val listed: Boolean,
        val directories: ImmutableList<FileItem>,
        val files: ImmutableList<ModuleEntity>,
    )

    private val currentPath = MutableStateFlow<String?>(null)
    private val filterQuery = MutableStateFlow("")
    private val sortOrder = MutableStateFlow(BrowserSortOrder.NAME)

    /**
     * Matches from the whole subtree, or null when nothing is being searched.
     * One of the things the list is built from, in the one place it is built:
     * the folder's own contents keep streaming in from Room while a search is
     * running, and two writers would race to decide which of the two the list
     * is showing.
     */
    private val searchResults = MutableStateFlow<Found?>(null)

    /**
     * The folders on the way down that have been read through the documents
     * provider. Until the open one has been, a folder with no rows is one not
     * listed yet, and not an empty one. All of them and not the last: a big
     * folder's listing, coming in after that of the empty one opened from it,
     * took the empty one's "listed" away again.
     */
    private val listedPaths = MutableStateFlow<Set<String>>(emptySet())

    // The folder above the open one, and whether there is a column to show
    // it in - it is only read while there is. What each folder on the way
    // down looked like is kept too, so a column is never left waiting for a
    // folder that was on screen a moment ago, and going back up shows a
    // folder at once, as it was left.
    private val parentPath = MutableStateFlow<String?>(null)
    private val parentShown = MutableStateFlow(false)
    private var parentListing: Shown? = null
    private val seen = mutableMapOf<String, Shown>()

    // Stack of (uri string, display name) for breadcrumb navigation
    private val dirStack = ArrayDeque<Pair<String, String>>()

    /** The folder the library starts at, as last opened here. */
    private var root: String? = null

    /** The walk of the whole library under [root], while it runs: one at a time. */
    private var libraryWalk: Job? = null

    /** See [FolderPositions]. */
    val positions = FolderPositions()

    /**
     * The way down as it was when the process was last put away, for the
     * first root opened to go back down - if it is still the same root. Left
     * three folders deep, the app came back at the top of the library.
     */
    private var putAway: List<Pair<String, String>>? = null

    init {
        saved.get<Bundle>(SAVED)?.let { bundle ->
            val paths = bundle.getStringArrayList(SAVED_PATHS).orEmpty()
            val names = bundle.getStringArrayList(SAVED_NAMES).orEmpty()
            if (paths.isNotEmpty() && paths.size == names.size) putAway = paths.zip(names)
            val first = bundle.getIntArray(SAVED_FIRST) ?: IntArray(0)
            val offset = bundle.getIntArray(SAVED_OFFSET) ?: IntArray(0)
            // One place for each folder on the way, in their order.
            if (first.size == names.size && offset.size == names.size) {
                for (depth in names.indices) {
                    if (first[depth] < 0) continue
                    positions.open[names.subList(0, depth + 1).toList()] = LazyListState(first[depth], offset[depth])
                }
            }
        }
        saved.setSavedStateProvider(SAVED) {
            val names = dirStack.map { it.second }
            Bundle().apply {
                putStringArrayList(SAVED_PATHS, ArrayList(dirStack.map { it.first }))
                putStringArrayList(SAVED_NAMES, ArrayList(names))
                val places = names.indices.map { depth -> positions.open[names.subList(0, depth + 1)] }
                putIntArray(SAVED_FIRST, places.map { it?.firstVisibleItemIndex ?: -1 }.toIntArray())
                putIntArray(SAVED_OFFSET, places.map { it?.firstVisibleItemScrollOffset ?: 0 }.toIntArray())
            }
        }

        // The music folder, wherever it is chosen: here, or in the settings -
        // which only write it down. Read once, as it was, a folder picked
        // there left this screen, its index and Shuffle all on the old one
        // until the app was started again.
        prefs.getLastDirectoryFlow()
            .map { it?.ifBlank { null } }
            .distinctUntilChanged()
            .onEach { openRoot(it) }
            .launchIn(viewModelScope)

        // The open folder's list. Everything it is made of comes together
        // here and is laid out off the main thread: the table tells every
        // query on it about every write to it, so this runs for each batch of
        // files tested anywhere in the library - and filtering and sorting a
        // few thousand rows on the main thread each time was frames lost for
        // as long as anything was being indexed.
        combine(
            currentPath.filterNotNull().flatMapLatest { path ->
                // The same rows again - a write to some other folder - are
                // not news.
                db.getChildren(path).distinctUntilChanged().map { Listing(path, it) }
            },
            filterQuery,
            sortOrder,
            searchResults,
            listedPaths,
        ) { listing, query, order, found, listed ->
            val matches = found?.takeIf { it.path == listing.path }?.matches
            val source = matches ?: listing.children
            Shown(
                path = listing.path,
                order = order,
                whole = matches == null && query.isBlank(),
                isSearching = matches != null,
                listed = listing.children.isNotEmpty() || listing.path in listed,
                directories = directoriesOf(source, query),
                files = filesOf(source, query, order),
            )
        }
            .flowOn(Dispatchers.Default)
            .onEach { shown ->
                // One that was on its way when the folder changed.
                if (shown.path != currentPath.value) return@onEach
                if (shown.whole) seen[shown.path] = shown
                state.update {
                    it.copy(
                        directories = shown.directories,
                        files = shown.files,
                        isSearching = shown.isSearching,
                        listed = shown.listed,
                    )
                }
            }
            .launchIn(viewModelScope)

        combine(
            combine(parentPath, parentShown) { path, shown -> path.takeIf { shown } }
                .distinctUntilChanged()
                .flatMapLatest<String?, Listing?> { path ->
                    if (path == null) flowOf(null) else db.getChildren(path).distinctUntilChanged().map { Listing(path, it) }
                },
            sortOrder,
        ) { listing, order ->
            listing?.let {
                Shown(
                    path = it.path,
                    order = order,
                    whole = true,
                    isSearching = false,
                    listed = true,
                    directories = directoriesOf(it.children, query = ""),
                    files = filesOf(it.children, query = "", order = order),
                )
            }
        }
            .flowOn(Dispatchers.Default)
            .onEach { listing ->
                parentListing = listing
                applyParent()
            }
            .launchIn(viewModelScope)

        // Search spans the subtree below the open folder, not just the folder
        // itself. The browser only ever shows one level at a time, so a filter
        // over what is already on screen can never turn up anything the user
        // could not already see - which is not what anyone means by "search".
        combine(
            filterQuery.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS },
            currentPath.filterNotNull(),
        ) { query, path -> query to path }
            .flatMapLatest { (query, path) ->
                if (query.isBlank()) {
                    flowOf<Found?>(null)
                } else {
                    flow<Found?> { emit(Found(path, db.searchDescendants(path, query))) }
                }
            }
            .onEach { searchResults.value = it }
            .launchIn(viewModelScope)

        prefs.getGlobalShuffleFlow().onEach { isShuffle ->
            state.update { it.copy(isShuffle = isShuffle) }
        }.launchIn(viewModelScope)

        prefs.getGlobalShuffleRootFlow().onEach { shuffleRoot ->
            state.update { it.copy(isShuffleRoot = shuffleRoot) }
        }.launchIn(viewModelScope)

        // The same stored repeat the player uses. It was this screen's own,
        // starting at off, and every file picked here handed that to the player.
        prefs.getGlobalRepeatFlow().onEach { mode ->
            state.update { it.copy(repeatMode = mode) }
        }.launchIn(viewModelScope)

        playlistRepo.playlists
            .onEach { playlists.value = it.toImmutableList() }
            .launchIn(viewModelScope)
    }

    fun addFileToPlaylist(playlist: PlaylistEntity, file: ModuleEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { playlistRepo.addToPlaylist(playlist.id, file) }
        }
    }

    fun addFolderToPlaylist(playlist: PlaylistEntity, folder: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = db.getDescendantModules(folder.uri.toString())
            runCatching { playlistRepo.addAllToPlaylist(playlist.id, files) }
        }
    }

    fun onRootFolderPicked(uri: Uri) {
        appContext.takeReadWritePermission(uri)
        val picked = uri.toString()
        // The same one picked again is still a way back to its top.
        if (picked == root) openRoot(picked)
        viewModelScope.launch { prefs.setLastDirectoryUri(picked) }
    }

    /** Starts the browser at [path], the music folder - or at nothing, where none has been chosen. */
    private fun openRoot(path: String?) {
        root = path
        dirStack.clear()
        seen.clear()
        listedPaths.value = emptySet()
        if (path == null) {
            currentPath.value = null
            state.update {
                it.copy(
                    isLoading = false,
                    hasStorageAccess = false,
                    breadcrumbs = persistentListOf(),
                    directories = persistentListOf(),
                    files = persistentListOf(),
                    parent = null,
                    isSearching = false,
                    listed = false,
                )
            }
            return
        }
        state.update { it.copy(isLoading = false, hasStorageAccess = true) }
        val back = putAway?.takeIf { it.first().first == path }
        putAway = null
        if (back != null) {
            dirStack.addAll(back)
            openCurrentDir()
        } else {
            pushDir(path, displayName(path))
        }

        // One-shot background warm-up so search / Android Auto / "add folder to
        // playlist" work across the whole library, without ever blocking browsing.
        // Not at once: it lists every folder there is, through the same
        // provider the folder on screen is being read from, in the seconds
        // the app is starting and the first list is filling in.
        // The walk that also clears away what it does not come across: the
        // rows of files and folders gone since, and of a root chosen before.
        libraryWalk?.cancel()
        libraryWalk = viewModelScope.launch(Dispatchers.IO) {
            delay(LIBRARY_WALK_DELAY_MS)
            db.reindexDirectory(path.toUri())
        }
    }

    fun onRefresh() {
        val rootUri = dirStack.firstOrNull()?.first?.toUri() ?: return
        state.update { it.copy(isRefreshing = true, loadingReason = R.string.reindexing) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.reindexDirectory(rootUri)
            } finally {
                state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun navigateInto(item: FileItem) {
        // A second tap on the row, landing before the page has moved, is not
        // a folder inside itself.
        if (dirStack.lastOrNull()?.first == item.uri.toString()) return
        pushDir(item.uri.toString(), item.name)
    }

    /**
     * Opens [item] in place of the open folder: one beside it, picked in the
     * column of the folder above.
     */
    fun navigateAcross(item: FileItem) {
        if (dirStack.size < 2 || dirStack.last().first == item.uri.toString()) return
        dirStack.removeLast()
        pushDir(item.uri.toString(), item.name)
    }

    /** Whether the folder above the open one has a column of its own, beside it. */
    fun setParentShown(shown: Boolean) {
        parentShown.value = shown
        applyParent()
    }

    fun navigateUp(): Boolean {
        if (dirStack.size <= 1) return false
        dirStack.removeLast()
        openCurrentDir()
        return true
    }

    fun navigateToBreadcrumb(index: Int) {
        // Never above the top. The back button is still there, and still
        // takes a tap, for the fifth of a second it takes to pop away once
        // the top is reached: a second tap asked for the folder before the
        // first one, and emptied the stack.
        if (index < 0 || index >= dirStack.size) return
        while (dirStack.size > index + 1) dirStack.removeLast()
        openCurrentDir()
    }

    fun setShuffle() {
        val value = !state.value.isShuffle
        viewModelScope.launch {
            prefs.setGlobalShuffle(value)
            // Off here is off, library shuffle included.
            if (!value) prefs.setGlobalShuffleRoot(false)
        }
    }

    fun setRepeatMode(mode: Int) {
        state.update { it.copy(repeatMode = mode) }
        viewModelScope.launch { prefs.setGlobalRepeat(mode) }
    }

    fun setSortOrder(order: BrowserSortOrder) {
        state.update { it.copy(sortOrder = order) }
        // Both lists follow: the open folder's and the one above it.
        sortOrder.value = order
    }

    fun setFilter(query: String) {
        state.update { it.copy(filterQuery = query) }
        // The list narrows to what is already held as soon as it can be laid
        // out; the subtree search catches up behind the debounce. Safe
        // because a longer query can only ever match a subset of a shorter
        // one's results.
        filterQuery.value = query
    }

    private fun pushDir(path: String, name: String) {
        dirStack.addLast(path to name)
        openCurrentDir()
    }

    /**
     * Shows the directory now on top of [dirStack], and scans it: a fast,
     * non-recursive listing of just this directory, with native validation of
     * new or changed entries going on behind it.
     *
     * The folder's name and its rows change in one write. The name used to go
     * first and the rows follow when the database had been read, and for the
     * frames in between the page of the new folder was built out of the old
     * one's rows - a screenful of them made and thrown away at the start of
     * every move, and a parent coming back scrolled to wherever its shorter
     * child would let it be. So a folder seen on the way down comes back as it
     * was, at once, and one not seen yet is an empty page until its rows are
     * there - not the last folder's, and not "nothing here" either.
     */
    private fun openCurrentDir() {
        val path = dirStack.lastOrNull()?.first ?: return
        val kept = seen[path]?.takeIf { it.order == sortOrder.value }
        searchResults.value = null
        currentPath.value = path
        state.update {
            it.copy(
                breadcrumbs = dirStack.map { (_, name) -> name }.toImmutableList(),
                directories = kept?.directories ?: persistentListOf(),
                files = kept?.files ?: persistentListOf(),
                isSearching = false,
                listed = kept?.listed == true,
            )
        }
        moveParent()
        db.indexDirectoryInstant(path.toUri()).invokeOnCompletion { listedPaths.update { it + path } }
    }

    /** The folder above has changed with the open one: follows it, and forgets folders left behind. */
    private fun moveParent() {
        parentPath.value = dirStack.getOrNull(dirStack.size - 2)?.first
        val onTheWay = dirStack.mapTo(HashSet()) { it.first }
        seen.keys.retainAll(onTheWay)
        listedPaths.update { listed -> listed.filterTo(HashSet()) { it in onTheWay } }
        applyParent()
    }

    /** Lays out the folder above the open one, for the column beside it. */
    private fun applyParent() {
        val path = parentPath.value
        val open = dirStack.lastOrNull()?.first
        if (!parentShown.value || path == null || open == null) {
            if (state.value.parent != null) state.update { it.copy(parent = null) }
            return
        }
        // As the database has it once it has been read; until then as it was
        // when it was the folder open. Neither, and it stays as it was.
        val order = sortOrder.value
        val listing = parentListing?.takeIf { it.path == path && it.order == order }
            ?: seen[path]?.takeIf { it.order == order }
            ?: return
        state.update {
            it.copy(
                parent = ParentFolder(
                    path = path,
                    depth = dirStack.size - 1,
                    directories = listing.directories,
                    files = listing.files,
                    open = open,
                )
            )
        }
    }

    suspend fun getRecursiveModules(): List<ModuleEntity> {
        val path = currentPath.value ?: return emptyList()
        return db.getDescendantModules(path)
    }

    /**
     * Every module under the root folder that was picked, however deep - not
     * under the folder being looked at. The bottom of [dirStack] is that root
     * wherever the browser has been since.
     */
    suspend fun getRootModules(): List<ModuleEntity> {
        val root = dirStack.firstOrNull()?.first ?: return emptyList()
        return db.getDescendantModules(root)
    }

    private fun displayName(path: String): String = documentDisplayName(path)

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 220L

        /** How long after the music folder is opened the walk of everything in it starts. */
        const val LIBRARY_WALK_DELAY_MS = 4_000L

        // A row's key is worked out once, not at every comparison: sorting a
        // folder by a lower-cased name made two new strings each time two
        // rows were compared - some forty thousand for two thousand files.
        fun directoriesOf(children: List<ModuleEntity>, query: String): ImmutableList<FileItem> =
            children
                .filter { it.isDirectory && (query.isBlank() || it.filename.contains(query, ignoreCase = true)) }
                .sortedByKey { it.filename.lowercase() }
                .map { FileItem(name = it.filename, uri = it.uri, isDirectory = true, size = 0L) }
                .toImmutableList()

        fun filesOf(children: List<ModuleEntity>, query: String, order: BrowserSortOrder): ImmutableList<ModuleEntity> {
            // Show confirmed modules plus not-yet-validated placeholders (instant browsing);
            // only drop files that were actually tested and confirmed non-module.
            val files = children.filter { entity ->
                !entity.isDirectory && (entity.isValidModule || !entity.isTested) && (
                    query.isBlank() ||
                        entity.moduleName.contains(query, ignoreCase = true) ||
                        entity.filename.contains(query, ignoreCase = true)
                    )
            }
            return when (order) {
                BrowserSortOrder.NAME -> files.sortedByKey { it.moduleName.ifBlank { it.filename }.lowercase() }
                BrowserSortOrder.TYPE -> files.sortedByKey { it.moduleType.lowercase() }
                BrowserSortOrder.SIZE -> files.sortedBy { it.fileSize }
            }.toImmutableList()
        }
    }
}

/** Readable name for a SAF document path: `content://.../primary%3AMusic%2FMods` -> `Mods`. */
internal fun documentDisplayName(path: String): String = Uri.decode(path)
    .substringAfterLast('/')
    .substringAfterLast(':')
    .ifBlank { "Root" }

/** What the browser puts away with the process: the way down, and where each list on it was. */
private const val SAVED = "browser"
private const val SAVED_PATHS = "paths"
private const val SAVED_NAMES = "names"
private const val SAVED_FIRST = "first"
private const val SAVED_OFFSET = "offset"
