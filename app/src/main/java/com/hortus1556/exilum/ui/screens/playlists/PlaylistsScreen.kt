package com.hortus1556.exilum.ui.screens.playlists

import androidx.compose.runtime.LaunchedEffect
import com.hortus1556.exilum.ui.liquid.LiquidFloatingAction
import com.hortus1556.exilum.ui.liquid.LiquidMenuScrim
import androidx.compose.ui.graphics.Color
import com.hortus1556.exilum.ui.liquid.liquidPageContainer
import androidx.compose.runtime.remember
import com.hortus1556.exilum.ui.components.pagePredictivePopTransitionSpec
import com.hortus1556.exilum.ui.components.pagePopTransitionSpec
import com.hortus1556.exilum.ui.components.pagePushTransitionSpec
import com.hortus1556.exilum.ui.liquid.rememberLiquidPageDecorator
import com.hortus1556.exilum.ui.liquid.LiquidNavDirection
import androidx.compose.runtime.CompositionLocalProvider
import com.hortus1556.exilum.ui.liquid.scrollPadding
import com.hortus1556.exilum.ui.liquid.pageSides
import com.hortus1556.exilum.ui.liquid.liquidPage
import com.hortus1556.exilum.ui.liquid.rememberLiquidPageBackdrop
import com.hortus1556.exilum.ui.liquid.LocalLiquidBackdrop
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.theme.isOneUi
import com.hortus1556.exilum.ui.components.withoutBottom
import com.hortus1556.exilum.ui.components.popTransitionSpec
import com.hortus1556.exilum.ui.components.predictivePopTransitionSpec
import com.hortus1556.exilum.ui.components.pushTransitionSpec
import com.hortus1556.exilum.ui.components.groupedListCard
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.hortus1556.exilum.ui.components.AppTopAppBar
import com.hortus1556.exilum.ui.components.appContentWindowInsets
import com.hortus1556.exilum.ui.components.rememberAppBarScrollBehavior
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.hortus1556.exilum.R
import com.hortus1556.exilum.data.ZXTUNE_PLAYLISTS_HINT
import com.hortus1556.exilum.data.defaultSubsongLabel
import com.hortus1556.exilum.data.subsongKey
import com.hortus1556.exilum.ui.components.RenameSubsongDialog
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.player.PlaybackStatus
import com.hortus1556.exilum.player.PlayerUiState
import com.hortus1556.exilum.player.PlayerViewModel
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.components.MessageBox
import com.hortus1556.exilum.ui.components.ProgressbarIndicator
import com.hortus1556.exilum.ui.screens.player.components.MiniPlayerBar
import com.hortus1556.exilum.ui.screens.playlists.components.DeletePlaylistDialog
import com.hortus1556.exilum.ui.screens.playlists.components.NewPlaylistDialog
import com.hortus1556.exilum.ui.screens.playlists.components.PlaylistEntriesFabMenu
import com.hortus1556.exilum.ui.screens.playlists.components.PlaylistEntryItem
import com.hortus1556.exilum.ui.screens.playlists.components.PlaylistListItem
import com.hortus1556.exilum.ui.screens.playlists.components.PlaylistsFabMenu
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.renderer.model.FrameInfo
import com.hortus1556.exilum.renderer.model.ModVars
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.hortus1556.exilum.ui.popOrKeepRoot
import com.hortus1556.exilum.ui.pushOnce

private sealed interface NavKeyPlaylists : NavKey {
    @Serializable data object List : NavKeyPlaylists

    @Serializable data class Entries(
        val playlistId: Long,
        val playlistName: String,
        val playlistComment: String
    ) : NavKeyPlaylists
}

@Composable
fun NavPlaylists(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToPlayer: () -> Unit
) {
    val playerViewModel = koinViewModel<PlayerViewModel>(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity
    )
    val playlistsViewModel = koinViewModel<PlaylistsViewModel>()

    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val frameInfo = playerViewModel.frameInfo.collectAsStateWithLifecycle()
    val playlistsState by playlistsViewModel.state.collectAsStateWithLifecycle()

    val backStack = rememberNavBackStack(NavKeyPlaylists.List)

    BackHandler(enabled = backStack.size > 1) {
        backStack.popOrKeepRoot()
    }

    // Under Liquid Glass the pages move themselves, so each can hold its
    // bar still while it slides, as iOS's navigation bar stays put.
    val direction = remember { LiquidNavDirection() }
    val pageDecorator = rememberLiquidPageDecorator<NavKey>(direction)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.popOrKeepRoot() },
        transitionSpec = pagePushTransitionSpec(direction),
        popTransitionSpec = pagePopTransitionSpec(direction),
        predictivePopTransitionSpec = pagePredictivePopTransitionSpec(direction),
        entryDecorators = listOfNotNull(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
            pageDecorator.takeIf { isLiquid },
        ),
        entryProvider = entryProvider {
            entry<NavKeyPlaylists.List> {
                PlaylistListScreen(
                    modifier = modifier,
                    state = playlistsState,
                    playerState = playerState,
                    frameInfo = frameInfo,
                    snackbarHostState = snackbarHostState,
                    onBack = onBack,
                    onNavigateToPlayer = onNavigateToPlayer,
                    onMiniPlayerToggle = playerViewModel::togglePlayPause,
                    onMiniPlayerNext = playerViewModel::next,
                    onMiniPlayerPrev = playerViewModel::previous,
                    onMiniPlayerStop = playerViewModel::stop,
                    onSelectPlaylist = { playlist ->
                        val entry = NavKeyPlaylists.Entries(
                            playlistId = playlist.id,
                            playlistName = playlist.name,
                            playlistComment = playlist.comment,
                        )
                        playlistsViewModel.selectPlaylist(playlist)
                        backStack.pushOnce(entry)
                    },
                    onCreatePlaylist = playlistsViewModel::createPlaylist,
                    onDeletePlaylist = playlistsViewModel::deletePlaylist,
                    onImportZxtune = playlistsViewModel::importFromZxtune,
                    onDismissError = playlistsViewModel::dismissError,
                    onDismissImport = playlistsViewModel::dismissImportResult,
                    onRenamePlaylist = { playlist, name, comment ->
                        playlistsViewModel.renamePlaylist(playlist, name, comment)
                    },
                )
            }

            entry<NavKeyPlaylists.Entries> { key ->
                // The page asks for its own playlist, from the key it was
                // opened with, rather than relying on the tap that opened it:
                // the back stack outlives the app being killed and the view
                // model does not, so a restored page came up empty.
                LaunchedEffect(key.playlistId) {
                    playlistsViewModel.selectPlaylist(key.playlistId, key.playlistName)
                }
                PlaylistEntriesScreen(
                    modifier = modifier,
                    playlistName = key.playlistName,
                    state = playlistsState,
                    playerState = playerState,
                    frameInfo = frameInfo,
                    snackbarHostState = snackbarHostState,
                    onBack = { backStack.popOrKeepRoot() },
                    onNavigateToPlayer = onNavigateToPlayer,
                    onMiniPlayerToggle = playerViewModel::togglePlayPause,
                    onMiniPlayerNext = playerViewModel::next,
                    onMiniPlayerPrev = playerViewModel::previous,
                    onMiniPlayerStop = playerViewModel::stop,
                    // Through the activity's player, the one every screen
                    // shows - see PlaylistsViewModel.playPlaylist.
                    onPlayEntry = { file -> playlistsViewModel.playEntry(playerViewModel, file) },
                    onPlayAll = { playlistsViewModel.playPlaylist(playerViewModel) },
                    onShuffle = { playlistsViewModel.playPlaylist(playerViewModel, isShuffle = true) },
                    onRemoveEntry = playlistsViewModel::removeFromPlaylist,
                    onReorderEntry = playlistsViewModel::reorderEntry,
                    onReorderDone = playlistsViewModel::commitReorder,
                    onRenameEntry = playlistsViewModel::renameSubsong,
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistListScreen(
    modifier: Modifier = Modifier,
    state: PlaylistsUiState,
    playerState: PlayerUiState,
    frameInfo: State<FrameInfo>,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onMiniPlayerToggle: () -> Unit,
    onMiniPlayerNext: () -> Unit,
    onMiniPlayerPrev: () -> Unit,
    onMiniPlayerStop: () -> Unit,
    onSelectPlaylist: (PlaylistEntity) -> Unit,
    onCreatePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit,
    onImportZxtune: (Uri) -> Unit,
    onDismissError: () -> Unit,
    onDismissImport: () -> Unit,
    onRenamePlaylist: (PlaylistEntity, String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val resource = LocalResources.current

    val hasModule by remember(playerState.currentModule) {
        mutableStateOf(playerState.currentModule != null)
    }
    var isFabExpanded by remember { mutableStateOf(false) }
    // An open FAB menu is not a window of its own, so nothing closes it on
    // back unless this does.
    BackHandler(enabled = isFabExpanded) { isFabExpanded = false }

    val showSnackBar: (String) -> Unit = {
        scope.launch { snackbarHostState.showSnackbar(message = it) }
    }

    // Import result snackbar
    LaunchedEffect(state.importResult) {
        state.importResult?.let { result ->
            val message = if (result.skipped > 0) {
                resource.getString(
                    R.string.snack_import_ok_skipped,
                    result.playlistsImported,
                    result.entriesImported,
                    result.skipped
                )
            } else {
                resource.getString(
                    R.string.snack_import_ok,
                    result.playlistsImported,
                    result.entriesImported
                )
            }
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = resource.getString(R.string.close)
            )
            onDismissImport()
        }
    }

    // New Playlist
    var isNewPlaylistDialog by remember { mutableStateOf(false) }
    if (isNewPlaylistDialog) {
        NewPlaylistDialog(
            onDismiss = { isNewPlaylistDialog = false },
            onCreate = onCreatePlaylist,
            onError = showSnackBar
        )
    }

    // Delete Playlist
    var isDeletePlaylistDialog by remember { mutableStateOf(false) }
    var deletePlaylistItem by remember { mutableStateOf<PlaylistEntity?>(null) }
    val showDeletePlaylistDialog: (PlaylistEntity) -> Unit = {
        deletePlaylistItem = it
        isDeletePlaylistDialog = true
    }
    val dismissDeletePlaylistDialog: () -> Unit = {
        isDeletePlaylistDialog = false
        deletePlaylistItem = null
    }
    if (isDeletePlaylistDialog && deletePlaylistItem != null) {
        DeletePlaylistDialog(
            playlist = deletePlaylistItem!!,
            onDismiss = dismissDeletePlaylistDialog,
            onConfirm = { onDeletePlaylist(deletePlaylistItem!!) },
        )
    }

    // Rename
    var isRenamePlaylistDialog by remember { mutableStateOf(false) }
    var renamePlaylistItem by remember { mutableStateOf<PlaylistEntity?>(null) }

    if (isRenamePlaylistDialog && renamePlaylistItem != null) {
        NewPlaylistDialog(
            initialName = renamePlaylistItem!!.name,
            initialComment = renamePlaylistItem!!.comment,
            onDismiss = {
                isRenamePlaylistDialog = false
                renamePlaylistItem = null
            },
            onCreate = { name, comment ->
                onRenamePlaylist(renamePlaylistItem!!, name, comment)
            },
            onError = showSnackBar,
        )
    }

    // Import from ZXTune: the folder its playlists are in, which the picker
    // opens on where ZXTune keeps them unless it was told otherwise.
    val zxtuneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri == null) {
                val text = resource.getString(R.string.snack_import_cancelled)
                showSnackBar(text)
                return@rememberLauncherForActivityResult
            }
            onImportZxtune(uri)
        }
    )

    // Content
    val appBarScroll = rememberAppBarScrollBehavior()
    // Under Liquid Glass the page is what its glass bends.
    val pageBackdrop = rememberLiquidPageBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides pageBackdrop) {
    Scaffold(
        containerColor = liquidPageContainer(),
        modifier = modifier.fillMaxSize().nestedScroll(appBarScroll.nestedScrollConnection),
        contentWindowInsets = appContentWindowInsets(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopAppBar(
                scrollBehavior = appBarScroll,
                title = { Text(text = stringResource(R.string.playlists)) },
                // A tab of its own under One UI and iOS, with nothing to go back to.
                navigationIcon = { if (!isOneUi && !isLiquid) BackButton(onBack = onBack) },
            )
        },
        floatingActionButton = {
            val fab = @Composable { modifier: Modifier ->
                PlaylistsFabMenu(
                    modifier = modifier,
                    expanded = isFabExpanded,
                    onExpand = { isFabExpanded = !isFabExpanded },
                    onImportZxtune = {
                        zxtuneLauncher.launch(ZXTUNE_PLAYLISTS_HINT)
                        isFabExpanded = false
                    },
                    onNewPlaylist = {
                        isNewPlaylistDialog = true
                        isFabExpanded = false
                    },
                )
            }
            if (isLiquid) {
                LiquidFloatingAction(visible = !state.isLoading && state.error == null) { anchored ->
                    fab(anchored)
                }
            } else {
                AnimatedVisibility(
                    visible = !state.isLoading && state.error == null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    content = { fab(Modifier) },
                )
            }
        },
    ) { padding ->
        val listState = rememberLazyListState()

        Crossfade(
            targetState = state.isLoading,
            content = { isLoading ->
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                        content = { ProgressbarIndicator() }
                    )
                } else {
                    if (!state.error.isNullOrBlank()) {
                        MessageBox(
                            text = state.error,
                            actions = {
                                TextButton(
                                    onClick = {
                                        onDismissError()
                                        onBack()
                                    },
                                    content = {
                                        Text(text = stringResource(R.string.desc_back_button))
                                    }
                                )
                            }
                        )
                    } else if (state.playlists.isEmpty()) {
                        MessageBox(
                            modifier = Modifier.padding(padding),
                            text = stringResource(R.string.message_no_playlists),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .padding(padding.pageSides())
                                .fillMaxSize()
                                .liquidPage(pageBackdrop),
                            contentPadding = padding.scrollPadding(),
                            state = listState,
                            content = {
                                itemsIndexed(
                                    items = state.playlists,
                                    key = { _, item -> item.id },
                                    itemContent = { index, item ->
                                        PlaylistListItem(
                                            modifier = Modifier.groupedListCard(index, state.playlists.size),
                                            item = item,
                                            onSelect = { onSelectPlaylist(item) },
                                            onDelete = { showDeletePlaylistDialog(item) },
                                            onRename = {
                                                renamePlaylistItem = item
                                                isRenamePlaylistDialog = true
                                            },
                                        )
                                    }
                                )
                            }
                        )
                    }
                }
            }
        )
        LiquidMenuScrim(expanded = isFabExpanded, onDismiss = { isFabExpanded = false })
    }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistEntriesScreen(
    modifier: Modifier = Modifier,
    playlistName: String,
    state: PlaylistsUiState,
    playerState: PlayerUiState,
    frameInfo: State<FrameInfo>,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onMiniPlayerToggle: () -> Unit,
    onMiniPlayerNext: () -> Unit,
    onMiniPlayerPrev: () -> Unit,
    onMiniPlayerStop: () -> Unit,
    onPlayEntry: (ModuleEntity) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onRemoveEntry: (ModuleEntity) -> Unit,
    onReorderEntry: (Int, Int) -> Unit,
    /** A dragged row was let go: the order it was left in is the one to save. */
    onReorderDone: () -> Unit,
    onRenameEntry: (ModuleEntity, String) -> Unit = { _, _ -> },
) {
    val hasModule = playerState.currentModule != null
    var isFabExpanded by remember { mutableStateOf(false) }

    var renaming by remember { mutableStateOf<ModuleEntity?>(null) }
    renaming?.let { file ->
        RenameSubsongDialog(
            initialName = state.names[subsongKey(file.filePath, file.subsong)].orEmpty(),
            placeholder = defaultSubsongLabel(file.subsong),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                onRenameEntry(file, name)
            },
        )
    }
    // An open FAB menu is not a window of its own, so nothing closes it on
    // back unless this does.
    BackHandler(enabled = isFabExpanded) { isFabExpanded = false }

    val appBarScroll = rememberAppBarScrollBehavior()
    // Under Liquid Glass the page is what its glass bends.
    val pageBackdrop = rememberLiquidPageBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides pageBackdrop) {
    Scaffold(
        containerColor = liquidPageContainer(),
        modifier = modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        contentWindowInsets = appContentWindowInsets(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopAppBar(
                scrollBehavior = appBarScroll,
                title = { Text(playlistName) },
                navigationIcon = { BackButton(onBack = onBack) },
            )
        },
        floatingActionButton = {
            val fab = @Composable { modifier: Modifier ->
                PlaylistEntriesFabMenu(
                    modifier = modifier,
                    expanded = isFabExpanded,
                    onExpand = { isFabExpanded = !isFabExpanded },
                    onPlayAll = onPlayAll,
                    onShuffle = onShuffle,
                )
            }
            if (isLiquid) {
                LiquidFloatingAction(visible = state.entries.isNotEmpty()) { anchored -> fab(anchored) }
            } else {
                AnimatedVisibility(
                    visible = state.entries.isNotEmpty(),
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) { fab(Modifier) }
            }
        },
    ) { padding ->
        val hapticFeedback = LocalHapticFeedback.current
        val lazyListState = rememberLazyListState()
        val reorderableLazyListState = rememberReorderableLazyListState(
            lazyListState = lazyListState,
            onMove = { from, to ->
                onReorderEntry(from.index, to.index)
                hapticFeedback.performHapticFeedback(
                    HapticFeedbackType.SegmentFrequentTick
                )
            }
        )

        Box(modifier = Modifier.padding(padding.pageSides())) {
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                    content = { ProgressbarIndicator() }
                )

                state.entries.isEmpty() -> MessageBox(
                    text = stringResource(R.string.message_no_playlist_items)
                )

                else -> {
                    // A key of its own for every row, whatever the table
                    // holds. It was the file and subsong alone - with the
                    // subsong, because one file saved as two of its subsongs
                    // is two entries - and nothing kept that pair to one row:
                    // a track added to the playlist twice gave two rows one
                    // key, and the page crashed as soon as both were laid
                    // out, each time it was opened.
                    val entryKeys = remember(state.entries) {
                        val seen = HashMap<String, Int>()
                        state.entries.map { file ->
                            val key = subsongKey(file.filePath, file.subsong)
                            val earlier = seen[key] ?: 0
                            seen[key] = earlier + 1
                            if (earlier == 0) key else "$key#$earlier"
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .liquidPage(pageBackdrop),
                        state = lazyListState,
                        contentPadding = padding.scrollPadding(),
                        // One card across the list under One UI and iOS; Material's
                        // segments stand a little apart.
                        verticalArrangement = Arrangement.spacedBy(if (isOneUi || isLiquid) 0.dp else 2.dp),
                    ) {
                        itemsIndexed(
                            items = state.entries,
                            key = { index, _ -> entryKeys[index] },
                        ) { index, file ->
                            ReorderableItem(
                                state = reorderableLazyListState,
                                key = entryKeys[index]
                            ) { isDragging ->
                                val elevation by animateDpAsState(
                                    if (isDragging) 4.dp else 0.dp,
                                    label = "elevation"
                                )
                                // See-through: the row draws its own card, and
                                // a surface of its own put a black square behind it.
                                Surface(color = Color.Transparent, shadowElevation = elevation) {
                                    with(this@ReorderableItem) {
                                        PlaylistEntryItem(
                                            index = index,
                                            count = state.entries.size,
                                            file = file,
                                            subsongName = if (file.subsong >= 0) {
                                                state.names[subsongKey(file.filePath, file.subsong)]
                                                    ?: defaultSubsongLabel(file.subsong)
                                            } else {
                                                null
                                            },
                                            onPlay = { onPlayEntry(file) },
                                            onRemove = { onRemoveEntry(file) },
                                            onRename = { renaming = file },
                                            onDropped = onReorderDone,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        LiquidMenuScrim(expanded = isFabExpanded, onDismiss = { isFabExpanded = false })
    }
    }
}

/***********
 * Preview *
 ***********/

private val samplePlaylists = persistentListOf(
    PlaylistEntity(
        id = 1L,
        name = "Amiga Classics",
        comment = "Comment Amiga Classics",
        createdAt = System.currentTimeMillis() - 86_400_000 * 7
    ),
    PlaylistEntity(
        id = 2L,
        name = "Fast Tracker II",
        comment = "Comment Fast Tracker II",
        createdAt = System.currentTimeMillis() - 86_400_000 * 3
    ),
    PlaylistEntity(
        id = 3L,
        name = "Impulse Tracker",
        comment = "Comment Impulse Tracker",
        createdAt = System.currentTimeMillis() - 86_400_000
    ),
    PlaylistEntity(
        id = 4L,
        name = "Scream Tracker 3",
        comment = "Comment Scream Tracker 3",
        createdAt = System.currentTimeMillis()
    ),
)

private val sampleEntries = persistentListOf(
    ModuleEntity(
        filePath = "content://preview/1",
        filename = "alpharapii.mod",
        fileExtension = "mod",
        fileSize = 45_678L,
        moduleName = "alpharapii",
        moduleType = "Amiga Protracker/Compatible",
    ),
    ModuleEntity(
        filePath = "content://preview/2",
        filename = "aegis_-_beneath_the_fallen_stars.it",
        fileExtension = "it",
        fileSize = 1_820_792L,
        moduleName = "Beneath the Fallen Stars",
        moduleType = "Impulse Tracker",
    ),
    ModuleEntity(
        filePath = "content://preview/3",
        filename = "ballada-remix.mod",
        fileExtension = "mod",
        fileSize = 22_334L,
        moduleName = "ballada-remix",
        moduleType = "Amiga Protracker/Compatible",
    ),
    ModuleEntity(
        filePath = "content://preview/4",
        filename = "KRAKEN.IT",
        fileExtension = "it",
        fileSize = 312_456L,
        moduleName = "Kraken of the Sea",
        moduleType = "Impulse Tracker",
    ),
    ModuleEntity(
        filePath = "content://preview/5",
        filename = "SONG0.S3M",
        fileExtension = "s3m",
        fileSize = 98_123L,
        moduleName = "Escape",
        moduleType = "Scream Tracker 3",
    ),
)

private val playingState = PlayerUiState(
    status = PlaybackStatus.PLAYING,
    currentModule = sampleEntries[1],
    currentQueueIndex = 1,
    queue = sampleEntries,
    modVars = ModVars(
        name = "Beneath the Fallen Stars",
        type = "Impulse Tracker",
    ),
)

private val playingFrameInfo = FrameInfo(time = 47_000, totalTime = 237_000)

private data class PlaylistPreviewState(
    val playlistsState: PlaylistsUiState,
    val playerState: PlayerUiState,
    val frameInfo: FrameInfo = FrameInfo(),
    val description: String
)

private class PlaylistListPreviewParameter : PreviewParameterProvider<PlaylistPreviewState> {
    override val values = sequenceOf(
        PlaylistPreviewState(
            description = "Empty",
            playlistsState = PlaylistsUiState(),
            playerState = PlayerUiState(),
        ),
        PlaylistPreviewState(
            description = "Playlists - idle",
            playlistsState = PlaylistsUiState(playlists = samplePlaylists),
            playerState = PlayerUiState(),
        ),
        PlaylistPreviewState(
            description = "Playlists - playing",
            playlistsState = PlaylistsUiState(playlists = samplePlaylists),
            playerState = playingState,
            frameInfo = playingFrameInfo,
        ),
        PlaylistPreviewState(
            description = "Error",
            playlistsState = PlaylistsUiState(error = "Failed to load."),
            playerState = PlayerUiState(),
        ),
    )

    override fun getDisplayName(index: Int): String =
        "$index -" + values.toList()[index].description
}

private class PlaylistEntriesPreviewParameter : PreviewParameterProvider<PlaylistPreviewState> {
    override val values = sequenceOf(
        PlaylistPreviewState(
            description = "Loading",
            playlistsState = PlaylistsUiState(
                playlists = samplePlaylists,
                selectedPlaylist = samplePlaylists[0],
                isLoading = true,
            ),
            playerState = PlayerUiState(),
        ),
        PlaylistPreviewState(
            description = "Entries - idle",
            playlistsState = PlaylistsUiState(
                playlists = samplePlaylists,
                selectedPlaylist = samplePlaylists[0],
                entries = sampleEntries,
            ),
            playerState = PlayerUiState(),
        ),
        PlaylistPreviewState(
            description = "Entries - playing",
            playlistsState = PlaylistsUiState(
                playlists = samplePlaylists,
                selectedPlaylist = samplePlaylists[1],
                entries = sampleEntries,
            ),
            playerState = playingState,
            frameInfo = playingFrameInfo,
        ),
        PlaylistPreviewState(
            description = "Empty playlist",
            playlistsState = PlaylistsUiState(
                playlists = samplePlaylists,
                selectedPlaylist = samplePlaylists[2],
                entries = persistentListOf(),
            ),
            playerState = PlayerUiState(),
        ),
    )

    override fun getDisplayName(index: Int): String =
        "$index -" + values.toList()[index].description
}

@Preview(showBackground = true, name = "Playlist List Screen")
@Composable
private fun PreviewPlaylistListScreen(
    @PreviewParameter(PlaylistListPreviewParameter::class) params: PlaylistPreviewState
) {
    AppTheme {
        PlaylistListScreen(
            modifier = Modifier,
            state = params.playlistsState,
            playerState = params.playerState,
            frameInfo = remember { mutableStateOf(params.frameInfo) },
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onNavigateToPlayer = {},
            onMiniPlayerToggle = {},
            onMiniPlayerNext = {},
            onMiniPlayerPrev = {},
            onMiniPlayerStop = {},
            onSelectPlaylist = {},
            onCreatePlaylist = { _, _ -> },
            onDeletePlaylist = {},
            onImportZxtune = {},
            onDismissError = {},
            onDismissImport = {},
            onRenamePlaylist = { _, _, _ -> }
        )
    }
}

@Preview(showBackground = true, name = "Playlist Entries Screen")
@Composable
private fun PreviewPlaylistEntriesScreen(
    @PreviewParameter(PlaylistEntriesPreviewParameter::class) params: PlaylistPreviewState
) {
    AppTheme {
        PlaylistEntriesScreen(
            modifier = Modifier,
            playlistName = params.playlistsState.selectedPlaylist?.name ?: "AAAAA",
            state = params.playlistsState,
            playerState = params.playerState,
            frameInfo = remember { mutableStateOf(params.frameInfo) },
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onNavigateToPlayer = {},
            onMiniPlayerToggle = {},
            onMiniPlayerNext = {},
            onMiniPlayerPrev = {},
            onMiniPlayerStop = {},
            onPlayEntry = {},
            onPlayAll = {},
            onShuffle = {},
            onRemoveEntry = {},
            onReorderEntry = { _, _ -> },
            onReorderDone = {},
        )
    }
}
