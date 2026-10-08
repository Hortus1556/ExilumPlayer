package com.hortus1556.exilum.ui.screens.playlists

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hortus1556.exilum.data.PlaylistRepository
import com.hortus1556.exilum.data.SubsongNamesRepository
import com.hortus1556.exilum.data.importZxtunePlaylists
import com.hortus1556.exilum.db.dao.ModuleDao
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.player.PlayerViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistsUiState(
    val playlists: ImmutableList<PlaylistEntity> = persistentListOf(),
    val selectedPlaylist: PlaylistEntity? = null,
    val entries: ImmutableList<ModuleEntity> = persistentListOf(),
    /** Subsong names, keyed by subsongKey - see SubsongNamesRepository. */
    val names: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val importResult: ImportResultUi? = null,
)

data class ImportResultUi(
    val playlistsImported: Int,
    val entriesImported: Int,
    val skipped: Int
)

/** What an imported ZXTune playlist says under its name. */
private const val ZXTUNE_COMMENT = "ZXTune"

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModel(
    private val appContext: Context,
    private val repo: PlaylistRepository,
    private val subsongNames: SubsongNamesRepository,
    private val modules: ModuleDao,
) : ViewModel() {

    val state: StateFlow<PlaylistsUiState>
        field = MutableStateFlow(PlaylistsUiState())

    init {
        repo.playlists
            .onEach { playlists ->
                state.update { s ->
                    s.copy(
                        playlists = playlists.toImmutableList(),
                        // The open playlist as it is now, and none once it is
                        // gone: its page went on removing from and reordering
                        // a playlist that tracker.conf had replaced meanwhile.
                        selectedPlaylist = s.selectedPlaylist?.let { open ->
                            playlists.firstOrNull { it.id == open.id }
                        },
                    )
                }
            }
            .launchIn(viewModelScope)

        // The entries follow the table for as long as their playlist is the
        // one selected. They were read once, when the page was opened, and
        // every tab stays up: a track added from the player or the browser
        // was not on the page, and the next reorder wrote the page's old list
        // back over it.
        state.map { it.selectedPlaylist?.id }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(emptyList<ModuleEntity>())
                } else {
                    repo.observePlaylistFiles(id).catch { e ->
                        state.update { it.copy(error = e.message, isLoading = false) }
                    }
                }
            }
            .onEach { files ->
                state.update { it.copy(entries = files.toImmutableList(), isLoading = false) }
            }
            .launchIn(viewModelScope)

        subsongNames.names
            .onEach { names -> state.update { it.copy(names = names) } }
            .launchIn(viewModelScope)
    }

    /** Names the subsong [file] plays; blank takes the name away. */
    fun renameSubsong(file: ModuleEntity, name: String) {
        viewModelScope.launch { subsongNames.rename(file.filePath, file.subsong, name) }
    }

    /** Creates a new empty playlist with [name]. */
    fun createPlaylist(name: String, comment: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.createPlaylist(name.trim(), comment.trim()) }
                .onFailure { state.update { s -> s.copy(error = it.message) } }
        }
    }

    /** Renames [playlist] to [newName]. */
    fun renamePlaylist(playlist: PlaylistEntity, newName: String, newComment: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            runCatching {
                repo.renamePlaylist(playlist.id, newName.trim(), newComment.trim())
            }.onFailure { state.update { s -> s.copy(error = it.message) } }
        }
    }

    /** Deletes [playlist] and all its entries. */
    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            runCatching { repo.deletePlaylist(playlist.id) }
                .onFailure { state.update { s -> s.copy(error = it.message) } }

            // If the deleted playlist was selected, deselect it
            if (state.value.selectedPlaylist?.id == playlist.id) {
                state.update { it.copy(selectedPlaylist = null, entries = persistentListOf()) }
            }
        }
    }

    /**
     * Selects the playlist a page was opened on, for a page that is asking
     * for its own.
     *
     * The back stack is saved and restored across the app being killed, so
     * the entries page can come back up with nothing having told this which
     * playlist it is on - and it came back to an empty list that only going
     * back and in again would fill.
     *
     * By [id], or failing that by [name]: a page can outlive the database
     * its id came from, and the playlists made again from tracker.conf are
     * the same ones under new ids.
     */
    fun selectPlaylist(id: Long, name: String) {
        if (state.value.selectedPlaylist?.id == id) return
        viewModelScope.launch {
            // Asked of the table rather than of the state, which on a cold
            // start has not been given the playlists yet when the restored
            // page asks.
            val playlists = runCatching { repo.playlists.first() }.getOrNull() ?: return@launch
            val playlist = playlists.firstOrNull { it.id == id }
                ?: playlists.firstOrNull { it.name == name }
                ?: return@launch
            selectPlaylist(playlist)
        }
    }

    /** Selects [playlist]; its entries follow by themselves - see init. */
    fun selectPlaylist(playlist: PlaylistEntity) {
        state.update {
            if (it.selectedPlaylist?.id == playlist.id) {
                it.copy(selectedPlaylist = playlist)
            } else {
                // Only another playlist has entries to wait for. The same one
                // again is not read again, and nothing would end the wait.
                it.copy(selectedPlaylist = playlist, entries = persistentListOf(), isLoading = true)
            }
        }
    }

    /** Adds [file] to [playlist]. Refreshes entries if [playlist] is currently selected. */
    fun addToPlaylist(playlist: PlaylistEntity, file: ModuleEntity) {
        viewModelScope.launch {
            runCatching { repo.addToPlaylist(playlist.id, file) }
                .onSuccess {
                    if (state.value.selectedPlaylist?.id == playlist.id) {
                        refreshEntries()
                    }
                }
                .onFailure { state.update { s -> s.copy(error = it.message) } }
        }
    }

    /** Removes [file] from the currently selected playlist. */
    fun removeFromPlaylist(file: ModuleEntity) {
        val playlist = state.value.selectedPlaylist ?: return
        viewModelScope.launch {
            runCatching { repo.removeFromPlaylist(playlist.id, file.filePath, file.subsong) }
                .onFailure { state.update { s -> s.copy(error = it.message) } }
        }
    }

    /**
     * Moves the entry at [fromIndex] to [toIndex] on screen. [commitReorder]
     * is what saves it.
     *
     * This is called for every row a drag crosses, and each call used to
     * rewrite the whole playlist as well. Two of those rewrites overlapping
     * left the head of one order and the tail of the other in the table,
     * the page then showed what it found there, and the next move saved
     * that - a long playlist dragged quickly lost its entries for good.
     */
    fun reorderEntry(fromIndex: Int, toIndex: Int) {
        val entries = state.value.entries.toMutableList()
        if (fromIndex !in entries.indices || toIndex !in entries.indices) return

        entries.add(toIndex, entries.removeAt(fromIndex))

        state.update { it.copy(entries = entries.toImmutableList()) }
    }

    /** Saves the order the entries were dragged into, once, when the row is let go. */
    fun commitReorder() {
        val playlist = state.value.selectedPlaylist ?: return
        val entries = state.value.entries
        viewModelScope.launch {
            runCatching { repo.reorderEntries(playlist.id, entries) }
                .onFailure { e ->
                    state.update { s -> s.copy(error = e.message) }
                    refreshEntries() // back to the order that is saved
                }
        }
    }

    /** Clears the selected playlist. */
    fun deselectPlaylist() {
        state.update { it.copy(selectedPlaylist = null, entries = persistentListOf()) }
    }

    /**
     * Brings in the playlists ZXTune saved in [treeUri] - its Playlists
     * folder, or the ZXTune folder above it. Call after the user picks the
     * folder. See importZxtunePlaylists.
     */
    fun importFromZxtune(treeUri: Uri) {
        viewModelScope.launch {
            state.update { it.copy(isLoading = true) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val result = importZxtunePlaylists(appContext, treeUri, modules.getAllValidModules())
                    result.playlists.forEach { (name, files) ->
                        val id = repo.createPlaylist(name, ZXTUNE_COMMENT)
                        repo.addAllToPlaylist(id, files)
                    }
                    result
                }
            }.onSuccess { result ->
                state.update {
                    it.copy(
                        isLoading = false,
                        importResult = ImportResultUi(
                            playlistsImported = result.playlists.size,
                            entriesImported = result.tracks - result.skipped,
                            skipped = result.skipped,
                        )
                    )
                }
            }.onFailure { e ->
                state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    fun dismissImportResult() = state.update { it.copy(importResult = null) }

    /**
     * Plays the open playlist through [player] - the activity's own, handed
     * in by the screen.
     *
     * This used to be given a player view model of its own by the injector:
     * one no screen was showing and nothing ever cleared. The music played,
     * but the one the screens do show never heard what had been started, so
     * the mini player stayed away, or the star and "add to playlist" went on
     * acting on the file from before.
     */
    fun playPlaylist(player: PlayerViewModel, startAt: Int = 0, isShuffle: Boolean = false) {
        val entries = state.value.entries
        if (entries.isEmpty()) return
        player.playAll(
            files = entries,
            startAt = startAt,
            isShuffle = isShuffle,
            // Shuffling a playlist shuffles the playlist, not the library.
            keepRoot = false,
        )
    }

    /** Plays the open playlist through [player], from [file] on. */
    fun playEntry(player: PlayerViewModel, file: ModuleEntity) {
        val entries = state.value.entries
        val index = entries.indexOf(file).coerceAtLeast(0)
        player.playAll(
            files = entries,
            startAt = index,
            // The listener's own setting: a tap on a track used to switch
            // shuffle off, and it stayed off everywhere until put back.
            isShuffle = player.state.value.isShuffle,
        )
    }

    private fun refreshEntries() {
        val playlist = state.value.selectedPlaylist ?: return
        viewModelScope.launch {
            runCatching { repo.getPlaylistFiles(playlist.id).toImmutableList() }
                .onSuccess { files -> state.update { it.copy(entries = files) } }
                .onFailure { e -> state.update { s -> s.copy(error = e.message) } }
        }
    }
}
