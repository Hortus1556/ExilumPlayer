package com.hortus1556.exilum.ui.screens.downloads

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.hortus1556.exilum.data.DownloadHistoryRepository
import com.hortus1556.exilum.data.ModArchiveService
import com.hortus1556.exilum.data.ResultPagingSource
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.model.Module
import com.hortus1556.exilum.model.ModuleResult
import com.hortus1556.exilum.model.ResultItem
import com.hortus1556.exilum.util.findDownloadedModule
import com.hortus1556.exilum.util.getOrCreateOutputFile
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.IOException
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class SearchType { ARTIST, TITLE }

enum class SearchResultType { NONE, MODULES, ARTISTS }

private sealed interface SearchCommand {
    data object Idle : SearchCommand
    data class FileOrTitle(val query: String) : SearchCommand
    data class Artist(val query: String) : SearchCommand
    data class ArtistById(val id: Int) : SearchCommand
}

@Immutable
data class DownloadSearchState(
    val title: String = "",
    val activeType: SearchResultType = SearchResultType.NONE,
    val isLoading: Boolean = false
)

@Immutable
sealed class DownloadStatus {
    data class Error(val message: String) : DownloadStatus()
    data class Progress(val percent: Float) : DownloadStatus()
    data object Loading : DownloadStatus()
    data object None : DownloadStatus()
    data object Success : DownloadStatus()
}

@Immutable
data class ModuleResultState(
    val downloadStatus: DownloadStatus = DownloadStatus.None,
    val isLoading: Boolean = false,
    val isRandom: Boolean = false,
    val moduleExists: Boolean = false,
    val result: ModuleResult? = null,
    val softError: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModel(
    private val appContext: Context,
    private val service: ModArchiveService,
    private val httpClient: HttpClient,
    private val historyRepo: DownloadHistoryRepository,
    prefs: AppPreferences
) : ViewModel() {

    private var downloadJob: Job? = null
    private val lastDirectory: Flow<String?> = prefs.getLastDirectoryFlow()
    private val searchCommand = MutableStateFlow<SearchCommand>(SearchCommand.Idle)
    private val pagingConfig = PagingConfig(pageSize = 25, enablePlaceholders = false)

    val moduleResultState: StateFlow<ModuleResultState>
        field = MutableStateFlow(ModuleResultState())

    val historyState: StateFlow<ImmutableList<Module>> = historyRepo.getAllFlow()
        .map { it.toPersistentList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val searchState: StateFlow<DownloadSearchState>
        field = MutableStateFlow(DownloadSearchState())

    val pagingData: Flow<PagingData<ResultItem>> = searchCommand
        .flatMapLatest { command ->
            searchState.update { it.copy(isLoading = true) }
            when (command) {
                is SearchCommand.FileOrTitle -> Pager(pagingConfig) {
                    ResultPagingSource(
                        fetch = { page -> service.searchByFileNameOrTitle(command.query, page) },
                        extract = {
                            searchState.update { state -> state.copy(isLoading = false) }
                            it.module
                        }
                    )
                }.flow

                is SearchCommand.ArtistById -> Pager(pagingConfig) {
                    ResultPagingSource(
                        fetch = { page -> service.getArtistById(command.id, page) },
                        extract = {
                            searchState.update { state -> state.copy(isLoading = false) }
                            it.module
                        }
                    )
                }.flow

                is SearchCommand.Artist -> Pager(pagingConfig) {
                    ResultPagingSource(
                        fetch = { page -> service.getArtistSearch(command.query, page) },
                        extract = {
                            searchState.update { state -> state.copy(isLoading = false) }
                            it.listItems
                        }
                    )
                }.flow

                SearchCommand.Idle -> {
                    searchState.update { state -> state.copy(isLoading = false) }
                    flowOf(PagingData.empty())
                }
            }
        }
        .cachedIn(viewModelScope)

    fun getModuleById(id: Int) {
        if (id < 0) {
            getRandomModule()
            return
        }
        viewModelScope.launch {
            moduleResultState.update { it.copy(isLoading = true, isRandom = false) }
            service.getModuleById(id).fold(
                onSuccess = { result -> showModule(result) },
                onFailure = ::handleFailure
            )
        }
    }

    fun getRandomModule() {
        viewModelScope.launch {
            moduleResultState.update { it.copy(isLoading = true, isRandom = true) }
            service.getRandomModule().fold(
                onSuccess = { result -> showModule(result) },
                onFailure = ::handleFailure
            )
        }
    }

    private suspend fun showModule(result: ModuleResult) {
        // Looked up before the update rather than inside it: the lookup lists
        // folders, and an update that loses a race runs its block again.
        val exists = checkExists(result.module)
        moduleResultState.update {
            it.copy(
                result = result,
                moduleExists = exists,
                isLoading = false,
                softError = null,
                // How the last module's download went says nothing about
                // this one, which Random puts on the same page.
                downloadStatus = DownloadStatus.None,
            )
        }
        historyRepo.add(result.module)
    }

    fun downloadModule(module: Module) {
        val previous = downloadJob
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            // A download that is stopped clears away what it wrote, and has to
            // be done with that before this one writes to the same file.
            previous?.cancelAndJoin()

            // Whether the module's file may have been made by now, and so
            // has to go again if the download does not get to its end.
            var fileTouched = false
            try {
                moduleResultState.update { it.copy(downloadStatus = DownloadStatus.Loading) }

                val rootUri = lastDirectory.firstOrNull()?.toUri() ?: run {
                    moduleResultState.update {
                        it.copy(downloadStatus = DownloadStatus.Error("No folder selected"))
                    }
                    return@launch
                }

                // The answer is looked at before a file is made for it, and
                // read as it arrives. The file used to come first and the
                // whole body after it, in one piece: with no network that
                // left an empty file behind, an error page from the server
                // was saved as the module, and the progress shown was only
                // ever that of the copy out of memory.
                httpClient.prepareGet(module.downloadUrl).execute { response ->
                    if (!response.status.isSuccess()) {
                        throw IOException("Download failed: ${response.status}")
                    }

                    fileTouched = true
                    val outputFile = appContext.getOrCreateOutputFile(
                        rootUri = rootUri,
                        module = module
                    ) ?: throw IOException("Could not create output file")

                    val totalBytes = response.contentLength() ?: 0L
                    val channel = response.bodyAsChannel()
                    var bytesRead = 0L
                    val buf = ByteArray(8192)

                    outputFile.use { out ->
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buf, 0, buf.size)
                            if (read < 0) break
                            out.write(buf, 0, read)
                            bytesRead += read
                            if (totalBytes > 0) {
                                moduleResultState.update {
                                    it.copy(
                                        downloadStatus = DownloadStatus.Progress(
                                            bytesRead * 100f / totalBytes
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (bytesRead == 0L) throw IOException("Download failed: nothing was sent")
                }
                moduleResultState.update {
                    it.copy(
                        downloadStatus = DownloadStatus.Success,
                        moduleExists = true
                    )
                }
            } catch (e: Exception) {
                // Whatever was written is not the module. Left where it was,
                // the page found it by name the next time, offered Play, and
                // played an empty or cut-off file. Leaving the page is what
                // cancels a download, so this has to outlive being cancelled.
                if (fileTouched) withContext(NonCancellable) { deleteDownload(module) }
                // Stopped on purpose, and there is nobody left to tell. A
                // connection that was cut can end the same way without this
                // job having been stopped, and that one is a failure to show.
                if (e is CancellationException) ensureActive()
                Timber.e(e)
                moduleResultState.update {
                    it.copy(
                        downloadStatus = DownloadStatus.Error(
                            e.message ?: "Download failed"
                        )
                    )
                }
            }
        }
    }

    private suspend fun deleteDownload(module: Module) {
        try {
            val fileUri = findModuleUri(module) ?: return
            DocumentsContract.deleteDocument(appContext.contentResolver, fileUri)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    // The failure has been said. Kept, it was said again each time the page
    // was put together anew, which a rotation is enough for.
    fun downloadErrorShown() {
        moduleResultState.update {
            if (it.downloadStatus is DownloadStatus.Error) {
                it.copy(downloadStatus = DownloadStatus.None)
            } else {
                it
            }
        }
    }

    fun deleteModule(module: Module) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileUri = findModuleUri(module) ?: return@launch
                DocumentsContract.deleteDocument(appContext.contentResolver, fileUri)
                moduleResultState.update { it.copy(moduleExists = false) }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepo.clear()
        }
    }

    // Both searches start once for each view model, which is once for each
    // results page. The page asks again every time it comes back on screen,
    // and on the way back from a module that used to swap the artist's modules
    // being looked through for the artist search they had been reached from.
    fun searchFileOrTitle(query: String) {
        if (searchCommand.value != SearchCommand.Idle) return
        searchState.update {
            it.copy(title = "Results: $query", activeType = SearchResultType.MODULES)
        }
        searchCommand.value = SearchCommand.FileOrTitle(query)
    }

    fun searchArtist(query: String) {
        if (searchCommand.value != SearchCommand.Idle) return
        searchState.update {
            it.copy(title = "Artists: $query", activeType = SearchResultType.ARTISTS)
        }
        searchCommand.value = SearchCommand.Artist(query)
    }

    fun getArtistById(id: Int) {
        searchState.update { it.copy(activeType = SearchResultType.MODULES) }
        searchCommand.value = SearchCommand.ArtistById(id)
    }

    private fun handleFailure(t: Throwable) {
        Timber.e(t)
        moduleResultState.update { it.copy(softError = t.message, isLoading = false) }
    }

    private suspend fun checkExists(module: Module) = findModuleUri(module) != null

    // Up to three folders listed through the documents provider, each a
    // blocking query that takes longer the more the folder holds: kept off the
    // main thread, where it used to stall a module page just as it appeared.
    private suspend fun findModuleUri(module: Module): Uri? = withContext(Dispatchers.IO) {
        val rootUri = (lastDirectory.firstOrNull() ?: return@withContext null).toUri()
        appContext.findDownloadedModule(
            rootUri = rootUri,
            artist = module.artist,
            filename = module.url.substringAfterLast('#'),
        )
    }
}
