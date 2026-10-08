package com.hortus1556.exilum.ui.screens.browser

import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import com.hortus1556.exilum.ui.components.isWideWindow
import com.hortus1556.exilum.player.soundcheck.LoudnessAnalyzer
import com.hortus1556.exilum.ui.liquid.LiquidFloatingAction
import com.hortus1556.exilum.ui.liquid.liquidPageContainer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.background
import androidx.compose.animation.core.spring
import com.hortus1556.exilum.ui.liquid.LocalLiquidBars
import com.hortus1556.exilum.ui.liquid.LocalLiquidBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.hortus1556.exilum.ui.liquid.LocalLiquidSearch
import androidx.compose.ui.Alignment
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import com.hortus1556.exilum.ui.screens.browser.components.SortMenu
import com.hortus1556.exilum.ui.screens.browser.components.LiquidRefreshIndicator
import com.hortus1556.exilum.ui.screens.browser.components.LiquidPlayAllBar
import com.hortus1556.exilum.ui.screens.browser.components.LiquidBrowserTopBar
import com.hortus1556.exilum.ui.components.rememberAppBarScrollBehavior
import com.hortus1556.exilum.ui.theme.isLiquid
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.player.PlaybackStatus
import com.hortus1556.exilum.player.PlayerUiState
import com.hortus1556.exilum.player.PlayerViewModel
import com.hortus1556.exilum.ui.components.MessageBox
import com.hortus1556.exilum.ui.components.PaddingSides
import com.hortus1556.exilum.ui.components.PlaylistPickerDialog
import com.hortus1556.exilum.ui.components.ProgressbarIndicator
import com.hortus1556.exilum.ui.screens.browser.components.BreadCrumbs
import com.hortus1556.exilum.ui.screens.browser.components.BrowserInputField
import com.hortus1556.exilum.ui.screens.browser.components.ModuleList
import com.hortus1556.exilum.ui.screens.player.components.MiniPlayerBar
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.isOneUi
import com.hortus1556.exilum.ui.components.appContentWindowInsets
import com.hortus1556.exilum.util.takeReadWritePermission
import com.hortus1556.exilum.renderer.model.FrameInfo
import com.hortus1556.exilum.renderer.model.ModVars
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun FileBrowserScreen(
    onNavigateToPlayer: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val browserViewModel = koinViewModel<FileBrowserViewModel>()
    val playerViewModel = koinViewModel<PlayerViewModel>(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity
    )
    val scope = rememberCoroutineScope()

    val browserState by browserViewModel.state.collectAsStateWithLifecycle()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val frameInfo = playerViewModel.frameInfo.collectAsStateWithLifecycle()
    val playlists by browserViewModel.playlists.collectAsStateWithLifecycle()
    // Handed down whole, and read only by the count that shows it.
    val soundCheck = browserViewModel.soundCheck.collectAsStateWithLifecycle()
    val soundCheckQueue = browserViewModel.soundCheckQueue.collectAsStateWithLifecycle()

    // Pending "add to playlist" target: a single file, or null meaning the current folder.
    var pendingAdd by remember { mutableStateOf<ModuleEntity?>(null) }
    var pendingFolderAdd by remember { mutableStateOf<FileItem?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let {
                context.takeReadWritePermission(it)
                browserViewModel.onRootFolderPicked(it)
            }
        }
    )

    // Up a folder while there is one. At the root it lets back go on to
    // MainNavigation, which asks for a second press before leaving - this
    // used to close the app itself, on the first press.
    BackHandler(enabled = browserState.breadcrumbs.size > 1) {
        browserViewModel.navigateUp()
    }

    val dialogTarget = pendingAdd ?: if (pendingFolderAdd != null) pendingFolderAdd else null
    if (dialogTarget != null) {
        PlaylistPickerDialog(
            playlists = playlists,
            onDismiss = {
                pendingAdd = null
                pendingFolderAdd = null
            },
            onPick = { playlist ->
                val file = pendingAdd
                val folder = pendingFolderAdd
                if (file != null) {
                    browserViewModel.addFileToPlaylist(playlist, file)
                } else if (folder != null) {
                    browserViewModel.addFolderToPlaylist(playlist, folder)
                }
                pendingAdd = null
                pendingFolderAdd = null
            },
        )
    }

    FileBrowserScreenContent(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        browserState = browserState,
        playerState = playerState,
        frameInfo = frameInfo,
        soundCheck = soundCheck,
        soundCheckQueue = soundCheckQueue,
        onRefresh = browserViewModel::onRefresh,
        onFilter = browserViewModel::setFilter,
        onShuffle = browserViewModel::setShuffle,
        onRepeatMode = browserViewModel::setRepeatMode,
        onFolderPick = { folderPicker.launch(null) },
        onSortOrder = browserViewModel::setSortOrder,
        onBreadcrumb = browserViewModel::navigateToBreadcrumb,
        onShuffleAll = {
            // The whole library from the root down, in a random order,
            // whichever folder the browser is showing. Shuffle is turned on
            // with it and stays on, like choosing it by hand.
            scope.launch {
                val files = browserViewModel.getRootModules().toImmutableList()
                if (files.isNotEmpty()) {
                    playerViewModel.playAll(
                        files = files,
                        startAt = files.indices.random(),
                        isShuffle = true,
                        repeatMode = browserState.repeatMode,
                    )
                } else {
                    val text = resources.getString(R.string.snack_nothing_to_play)
                    snackbarHostState.showSnackbar(text)
                }
            }
        },
        onPlayAll = {
            scope.launch {
                val files = browserViewModel.getRecursiveModules().toImmutableList()
                if (files.isNotEmpty()) {
                    // Deliberately does not open the player. Starting a
                    // track is not a request to leave the folder you started
                    // it from - the bar at the bottom says what is playing,
                    // and tapping it is how you go and look.
                    playerViewModel.playAll(
                        files = files,
                        startAt = if (browserState.isShuffle) files.indices.random() else 0,
                        isShuffle = browserState.isShuffle,
                        repeatMode = browserState.repeatMode,
                    )
                } else {
                    val text = resources.getString(R.string.snack_nothing_to_play)
                    snackbarHostState.showSnackbar(text)
                }
            }
        },
        onSelect = { file ->
            // One tapped on a page already on its way out is no longer in the
            // list: nothing, rather than the first file of the folder now
            // open playing in its place.
            val index = browserState.files.indexOf(file)
            if (index >= 0) {
                playerViewModel.playAll(
                    files = browserState.files,
                    startAt = index,
                    isShuffle = browserState.isShuffle,
                    repeatMode = browserState.repeatMode,
                )
            }
        },
        onDir = browserViewModel::navigateInto,
        onDirAcross = browserViewModel::navigateAcross,
        onParentShown = browserViewModel::setParentShown,
        positions = browserViewModel.positions,
        onSelectInParent = { file ->
            // The column's own files, as picking one in that folder would.
            val files = browserState.parent?.files ?: persistentListOf()
            val index = files.indexOf(file)
            if (index >= 0) {
                playerViewModel.playAll(
                    files = files,
                    startAt = index,
                    isShuffle = browserState.isShuffle,
                    repeatMode = browserState.repeatMode,
                )
            }
        },
        onAddToPlaylist = { pendingAdd = it },
        onAddFolderToPlaylist = { pendingFolderAdd = it },
        onMiniPlayerTap = onNavigateToPlayer,
        onMiniPlayerToggle = playerViewModel::togglePlayPause,
        onMiniPlayerNext = playerViewModel::next,
        onMiniPlayerPrev = playerViewModel::previous,
        onMiniPlayerStop = playerViewModel::stop,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FileBrowserScreenContent(
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState,
    browserState: BrowserUiState,
    playerState: PlayerUiState,
    frameInfo: State<FrameInfo>,
    soundCheck: State<LoudnessAnalyzer.Progress>,
    soundCheckQueue: State<LoudnessAnalyzer.Queue> = mutableStateOf(LoudnessAnalyzer.Queue(null, emptyList())),
    onRefresh: () -> Unit,
    onFilter: (String) -> Unit,
    onShuffle: () -> Unit,
    onRepeatMode: (Int) -> Unit,
    onFolderPick: () -> Unit,
    onSortOrder: (BrowserSortOrder) -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onShuffleAll: () -> Unit,
    onPlayAll: () -> Unit,
    onSelect: (ModuleEntity) -> Unit,
    onDir: (FileItem) -> Unit,
    onAddToPlaylist: (ModuleEntity) -> Unit,
    onAddFolderToPlaylist: (FileItem) -> Unit,
    onMiniPlayerTap: () -> Unit,
    onMiniPlayerToggle: () -> Unit,
    onMiniPlayerNext: () -> Unit,
    onMiniPlayerPrev: () -> Unit,
    onMiniPlayerStop: () -> Unit,
    onDirAcross: (FileItem) -> Unit = {},
    onParentShown: (Boolean) -> Unit = {},
    onSelectInParent: (ModuleEntity) -> Unit = {},
    positions: FolderPositions = remember { FolderPositions() },
) {
    val searchBarState = rememberSearchBarWithGapState()
    // Under Liquid Glass the search is the tab bar's, along the bottom.
    val ownField = rememberTextFieldState()
    val tabBarSearch = LocalLiquidSearch.current
    val textFieldState = if (isLiquid) tabBarSearch?.field ?: ownField else ownField
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    val appBarWithSearchColors = SearchBarDefaults.appBarWithSearchColors(
        scrolledSearchBarContainerColor = Color.Unspecified,
        scrolledAppBarContainerColor = Color.Unspecified,
    )
    // Each folder's scroll position, while it is on the way back up; the
    // ones below the folder open are forgotten, as iOS forgets a page popped.
    // The view model's, so that they are there after a rotation.
    val listStates = positions.open
    // A wide window has the folder above the open one beside it, in columns
    // as iPad's Files lays them out. Each column keeps scroll positions of
    // its own: a folder is in both for a moment as they move over by one.
    val columns = isLiquid && isWideWindow()
    LaunchedEffect(columns) { onParentShown(columns) }
    val parentListStates = positions.parent
    // Folders popped are forgotten the next time the way leads down again,
    // as iOS forgets a page popped - not the moment they are popped: a page
    // on its way out looked its place up as it went, and found the top.
    var lastDepth by remember { mutableIntStateOf(browserState.breadcrumbs.size) }
    LaunchedEffect(browserState.breadcrumbs) {
        val depth = browserState.breadcrumbs.size
        if (depth > lastDepth) {
            val popped = lastDepth
            // Not the folder just gone into: its page has been built by now,
            // with the place this would take away from it.
            val now = browserState.breadcrumbs.toList()
            listStates.keys.removeAll { it.size > popped && it != now }
            parentListStates.keys.removeAll { it.size > popped && it != now }
        }
        lastDepth = depth
    }
    val hasModule = playerState.currentModule != null // Its fine not being wrapped in remember.

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .collect { onFilter(it) }
    }

    // A filter left in place after the search bar closed is still something
    // open: back clears it before it goes up a folder.
    // Derived, so typing recomposes this only when the field turns empty or
    // stops being empty, not on every keystroke.
    val hasFilterText by remember { derivedStateOf { textFieldState.text.isNotEmpty() } }
    BackHandler(enabled = hasFilterText) {
        textFieldState.clearText()
        onFilter("")
    }
    // The tab bar's search, while it is open, is what back closes first -
    // before going up a folder. Registered after the browser's own, so it
    // is asked first.
    BackHandler(enabled = isLiquid && tabBarSearch?.active == true) {
        textFieldState.clearText()
        onFilter("")
        tabBarSearch?.active = false
    }

    // A folder opened is opened to be looked into: the search that found it,
    // or that was running when it was picked, ends there. It used to go in
    // with it, so a folder found by its name showed what in it matched that
    // name - nothing, as a rule - in place of what it holds.
    val search = rememberUpdatedState(tabBarSearch)
    val endSearch = remember(textFieldState, onFilter) {
        {
            if (textFieldState.text.isNotEmpty()) {
                textFieldState.clearText()
                onFilter("")
                search.value?.active = false
            }
        }
    }
    val openDir = remember(endSearch, onDir) { { dir: FileItem -> endSearch(); onDir(dir) } }
    val openDirAcross = remember(endSearch, onDirAcross) { { dir: FileItem -> endSearch(); onDirAcross(dir) } }

    val inputField: @Composable () -> Unit = {
        BrowserInputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            colors = appBarWithSearchColors.searchBarColors.inputFieldColors,
            sortOrder = browserState.sortOrder,
            onSortOrder = onSortOrder,
            onFolderPick = onFolderPick,
            onShuffleAll = onShuffleAll,
            onFilter = onFilter,
        )
    }

    // One UI's and iOS's search fields and floating bars are pills.
    val barShape = if (isOneUi || isLiquid) CircleShape else MaterialTheme.shapes.small
    // iOS: a large title that folds into the bar, over its own search field.
    val liquid = isLiquid
    val liquidScroll = rememberAppBarScrollBehavior()
    // The list, captured for the glass over it - the bar's buttons and the
    // play controls - to bend as it scrolls underneath them.
    val pageBackground = MaterialTheme.colorScheme.background
    val pageBackdrop = rememberLayerBackdrop {
        drawRect(pageBackground)
        drawContent()
    }

    CompositionLocalProvider(
        LocalLiquidBackdrop provides if (liquid) pageBackdrop else LocalLiquidBackdrop.current,
    ) {
    Scaffold(
        containerColor = liquidPageContainer(),
        modifier = modifier.nestedScroll(
            if (liquid) liquidScroll.nestedScrollConnection else scrollBehavior.nestedScrollConnection
        ),
        contentWindowInsets = appContentWindowInsets(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (liquid) {
                LiquidBrowserTopBar(
                    breadcrumbs = browserState.breadcrumbs,
                    onBreadcrumb = onBreadcrumb,
                    sortMenu = { SortMenu(sortOrder = browserState.sortOrder, onSortOrder = onSortOrder) },
                    onShuffleAll = onShuffleAll,
                    onFolderPick = onFolderPick,
                    scrollBehavior = liquidScroll,
                    soundCheck = soundCheck,
                    soundCheckQueue = soundCheckQueue,
                )
            } else Column {
                AppBarWithSearch(
                    scrollBehavior = scrollBehavior,
                    state = searchBarState,
                    colors = appBarWithSearchColors,
                    inputField = inputField,
                    shape = barShape,
                )
                ExpandedDockedSearchBarWithGap(
                    state = searchBarState,
                    inputField = inputField,
                    shape = barShape,
                    content = { /* TODO maybe add this, for single plays */ }
                )
                if (browserState.breadcrumbs.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        BreadCrumbs(
                            breadcrumbs = browserState.breadcrumbs,
                            onCrumbClick = onBreadcrumb,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        floatingActionButton = {
            if (liquid) {
                // Folds with the tab bar: shuffle and repeat go, and play
                // drops into the folded row beside the search. Nothing clips
                // it, so it keeps its shape wherever it is on the way there.
                val bars = LocalLiquidBars.current
                LiquidFloatingAction(visible = !hasModule && browserState.hasStorageAccess) { anchored ->
                    LiquidPlayAllBar(
                        isShuffle = browserState.isShuffle,
                        isShuffleRoot = browserState.isShuffleRoot,
                        repeatMode = browserState.repeatMode,
                        onShuffle = onShuffle,
                        onRepeatMode = onRepeatMode,
                        onPlayAll = onPlayAll,
                        fold = { bars?.fold?.value ?: 0f },
                        modifier = anchored,
                    )
                }
                return@Scaffold
            }
            AnimatedVisibility(
                visible = !hasModule && browserState.hasStorageAccess,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                HorizontalFloatingToolbar(
                    modifier = Modifier.offset(y = 12.dp),
                    expanded = true,
                    shape = barShape,
                    floatingActionButton = {
                        // Round, like the play button in the player and the
                        // mini player - it was the one square one left.
                        FloatingActionButton(
                            shape = CircleShape,
                            onClick = onPlayAll,
                            content = {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                            }
                        )
                    },
                    content = {
                        IconButton(
                            onClick = onShuffle,
                            content = {
                                val icon = when {
                                    browserState.isShuffle && browserState.isShuffleRoot ->
                                        Icons.Default.AllInclusive
                                    browserState.isShuffle -> Icons.Default.ShuffleOn
                                    else -> Icons.Default.Shuffle
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (browserState.isShuffle) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        )

                        IconButton(
                            onClick = {
                                val next = when (browserState.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                onRepeatMode(next)
                            },
                            content = {
                                Icon(
                                    imageVector = when (browserState.repeatMode) {
                                        Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOneOn
                                        Player.REPEAT_MODE_ALL -> Icons.Default.RepeatOn
                                        else -> Icons.Default.Repeat
                                    },
                                    contentDescription = "Repeat",
                                    tint = if (browserState.repeatMode !=
                                        Player.REPEAT_MODE_OFF
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        )
                    }
                )
            }
        },
        content = { padding ->
            val state = rememberPullToRefreshState()
            // iOS: the list runs up under the bar, and only its first row
            // starts below it. Sides of the Scaffold's padding, picked here
            // and read where things are laid out: see PaddingSides.
            val underBar = PaddingSides(padding, top = liquid, bottom = false, start = false, end = false)
            val listPadding = PaddingSides(padding, top = liquid)

            PullToRefreshBox(
                state = state,
                isRefreshing = browserState.isRefreshing,
                onRefresh = onRefresh,
                indicator = {
                    if (liquid) {
                        LiquidRefreshIndicator(
                            state = state,
                            isRefreshing = browserState.isRefreshing,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(underBar),
                        )
                    } else {
                        PullToRefreshDefaults.Indicator(
                            state = state,
                            isRefreshing = browserState.isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                },
                modifier = if (liquid) {
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(pageBackdrop)
                } else {
                    Modifier
                        .fillMaxSize()
                        .padding(PaddingSides(padding, bottom = false, start = false, end = false))
                },
            ) {
                if (columns && browserState.hasStorageAccess && !browserState.isLoading) {
                    FolderColumns(
                        state = browserState,
                        padding = listPadding,
                        listStates = listStates,
                        parentListStates = parentListStates,
                        onDir = openDir,
                        onDirAcross = openDirAcross,
                        onSelect = onSelect,
                        onSelectInParent = onSelectInParent,
                        onAddToPlaylist = onAddToPlaylist,
                        onAddFolderToPlaylist = onAddFolderToPlaylist,
                    )
                } else
                // Under Liquid Glass each folder is a page of its own: going
                // into one pushes it in from the side, as iOS's Files does,
                // going back slides it off again, and each keeps where it
                // was scrolled to.
                FolderPages(state = browserState, animate = liquid) { folder ->
                    // A place of its own for every folder, in every style.
                    // One list state shared by them all opened a folder at
                    // the row its parent had been scrolled to, and brought
                    // the parent back at wherever its child had left it.
                    val folderList = listStates.getOrPut(folder.breadcrumbs.toList()) { LazyListState() }
                    when {
                        folder.isLoading -> ProgressbarIndicator(
                            modifier = Modifier.padding(underBar).fillMaxSize(),
                            text = folder.loadingReason?.let { stringResource(it) }
                        )

                        !folder.hasStorageAccess -> MessageBox(
                            modifier = Modifier.padding(underBar),
                            icon = Icons.Default.FolderOpen,
                            title = stringResource(R.string.no_folder_selected),
                            text = stringResource(R.string.no_folder_selected_message),
                            actions = {
                                TextButton(
                                    onClick = onFolderPick,
                                    content = { Text(text = stringResource(R.string.choose_directory)) }
                                )
                            }
                        )

                        // Opened, and its rows not read yet: neither the
                        // last folder's nor "nothing here".
                        !folder.listed -> Box(modifier = Modifier.fillMaxSize())

                        folder.files.isEmpty() &&
                            folder.directories.isEmpty() -> MessageBox(
                            modifier = Modifier.padding(underBar).fillMaxSize(),
                            text = if (folder.filterQuery.isBlank()) {
                                stringResource(R.string.message_no_modules_here)
                            } else {
                                stringResource(
                                    R.string.message_no_search_results,
                                    folder.filterQuery,
                                )
                            },
                        )

                        else -> ModuleList(
                            directories = folder.directories,
                            files = folder.files,
                            isSearching = folder.isSearching,
                            padding = listPadding,
                            listState = folderList,
                            onDir = openDir,
                            onSelect = onSelect,
                            onAddToPlaylist = onAddToPlaylist,
                            onAddFolderToPlaylist = onAddFolderToPlaylist,
                        )
                    }
                }
            }
        }
    )
    }
}

/**
 * Preview
 */

private data class BrowserPreviewState(
    val browserState: BrowserUiState,
    val playerState: PlayerUiState,
    val description: String
)

private class BrowserPreviewParameter : PreviewParameterProvider<BrowserPreviewState> {

    private val sampleFiles = persistentListOf(
        ModuleEntity(
            filePath = "content://preview/1",
            filename = "a_journey_into_sound.far",
            fileExtension = "far",
            fileSize = 123_456L,
            moduleName = "A Journey Into Sound",
            moduleType = "Farandole Composer",
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
            filename = "alpharapii.mod",
            fileExtension = "mod",
            fileSize = 45_678L,
            moduleName = "alpharapii",
            moduleType = "Amiga Protracker/Compatible",
        ),
        ModuleEntity(
            filePath = "content://preview/4",
            filename = "chiptune_no_184.mod",
            fileExtension = "mod",
            fileSize = 6_658L,
            moduleName = "Chiptune No. 184",
            moduleType = "Amiga Protracker/Compatible",
        ),
    )

    private val sampleDirs = persistentListOf(
        FileItem(name = "TheModArchive", uri = "1".toUri(), isDirectory = true, size = 0L),
        FileItem(name = "Demos", uri = "2".toUri(), isDirectory = true, size = 0L),
    )

    private val playingState = PlayerUiState(
        status = PlaybackStatus.PLAYING,
        currentModule = sampleFiles[1],
        currentQueueIndex = 1,
        queue = sampleFiles,
        modVars = ModVars(
            name = "Beneath the Fallen Stars",
            type = "Impulse Tracker",
        ),
    )

    private val baseBrowserState = BrowserUiState(
        currentPath = "primary:Xmp/Modules",
        hasStorageAccess = true,
        isLoading = false,
        isShuffle = false,
        breadcrumbs = persistentListOf("Xmp", "Modules"),
        directories = sampleDirs,
        files = sampleFiles,
        sortOrder = BrowserSortOrder.NAME,
    )

    override val values = sequenceOf(
        // Normal browsing, no playback
        BrowserPreviewState(
            description = "Browsing - idle",
            browserState = baseBrowserState.copy(
                isShuffle = true,
                repeatMode = Player.REPEAT_MODE_ONE
            ),
            playerState = PlayerUiState(),
        ),
        // Browsing with mini player visible
        BrowserPreviewState(
            description = "Browsing - playing",
            browserState = baseBrowserState,
            playerState = playingState,
        ),
        // No storage access yet
        BrowserPreviewState(
            description = "No storage access",
            browserState = BrowserUiState(hasStorageAccess = false, isLoading = false),
            playerState = PlayerUiState(),
        ),
        // Loading state
        BrowserPreviewState(
            description = "Loading",
            browserState = BrowserUiState(hasStorageAccess = true, isLoading = true),
            playerState = PlayerUiState(),
        ),
        // Empty directory
        BrowserPreviewState(
            description = "Empty directory",
            browserState = baseBrowserState.copy(
                files = persistentListOf(),
                directories = persistentListOf(),
                breadcrumbs = persistentListOf("Xmp", "Empty"),
            ),
            playerState = PlayerUiState(),
        ),
        // Filtered results
        BrowserPreviewState(
            description = "Filtered - 'mod'",
            browserState = baseBrowserState.copy(
                filterQuery = "mod",
                files = persistentListOf(sampleFiles[2], sampleFiles[3]),
                directories = persistentListOf(),
            ),
            playerState = playingState,
        ),
        // Sorted by size
        BrowserPreviewState(
            description = "Sorted by size",
            browserState = baseBrowserState.copy(
                sortOrder = BrowserSortOrder.SIZE,
                files = persistentListOf(
                    sampleFiles[3], // 6KB
                    sampleFiles[2], // 45KB
                    sampleFiles[0], // 123KB
                    sampleFiles[1], // 1.8MB
                ),
            ),
            playerState = PlayerUiState(),
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun Preview(
    @PreviewParameter(BrowserPreviewParameter::class) params: BrowserPreviewState
) {
    AppTheme {
        FileBrowserScreenContent(
            snackbarHostState = SnackbarHostState(),
            browserState = params.browserState,
            playerState = params.playerState,
            frameInfo = remember { mutableStateOf(FrameInfo(time = 47_000, totalTime = 237_000)) },
            soundCheck = remember { mutableStateOf(LoudnessAnalyzer.Progress(done = 12, total = 340, running = true)) },
            onRefresh = {},
            onFilter = {},
            onShuffle = {},
            onRepeatMode = {},
            onFolderPick = {},
            onSortOrder = {},
            onBreadcrumb = {},
            onShuffleAll = {},
            onPlayAll = {},
            onSelect = {},
            onDir = {},
            onAddToPlaylist = {},
            onAddFolderToPlaylist = {},
            onMiniPlayerTap = {},
            onMiniPlayerToggle = {},
            onMiniPlayerNext = {},
            onMiniPlayerPrev = {},
            onMiniPlayerStop = {},
        )
    }
}

/** What a column of [FolderColumns] lists: a folder's rows, and how they came to be the ones shown. */
@Immutable
private data class FolderListing(
    val directories: ImmutableList<FileItem>,
    val files: ImmutableList<ModuleEntity>,
    val isSearching: Boolean = false,
    /** Whether they are the folder's, read - see [BrowserUiState.listed]. */
    val listed: Boolean = true,
    val filterQuery: String = "",
)

/** One column of [FolderColumns]: which folder, what is in it - null for none yet - and which of its folders is open. */
@Immutable
private data class FolderColumn(
    val crumbs: List<String>,
    val listing: FolderListing?,
    val open: String?,
)

/**
 * The browser on a wide screen, in columns as iPad's Files lays them out: the
 * folder above the open one on the left, with the open folder picked out in
 * it, and the open folder on the right. Going deeper moves both over by one -
 * the folder that was open to the left, the new one in on the right - and
 * coming back moves them the other way; a folder picked on the left takes
 * the open one's place on the right. At the top there is nothing above: the
 * top is on the left, and the right waits for a folder.
 */
@Composable
private fun FolderColumns(
    state: BrowserUiState,
    padding: PaddingValues,
    listStates: MutableMap<List<String>, LazyListState>,
    parentListStates: MutableMap<List<String>, LazyListState>,
    onDir: (FileItem) -> Unit,
    onDirAcross: (FileItem) -> Unit,
    onSelect: (ModuleEntity) -> Unit,
    onSelectInParent: (ModuleEntity) -> Unit,
    onAddToPlaylist: (ModuleEntity) -> Unit,
    onAddFolderToPlaylist: (FileItem) -> Unit,
) {
    val parent = state.parent
    val crumbs = state.breadcrumbs.toList()
    val own = FolderListing(
        directories = state.directories,
        files = state.files,
        isSearching = state.isSearching,
        listed = state.listed,
        filterQuery = state.filterQuery,
    )
    val left = if (parent != null) {
        FolderColumn(
            crumbs = crumbs.take(parent.depth),
            listing = FolderListing(directories = parent.directories, files = parent.files),
            open = parent.open,
        )
    } else {
        FolderColumn(crumbs = crumbs, listing = own, open = null)
    }
    val right = FolderColumn(crumbs = crumbs, listing = if (parent != null) own else null, open = null)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val leftWidth = (maxWidth * 0.4f).coerceIn(280.dp, 420.dp)
        Row(modifier = Modifier.fillMaxSize()) {
            ColumnPages(column = left, modifier = Modifier.width(leftWidth).fillMaxHeight()) { column ->
                val listing = column.listing ?: return@ColumnPages
                // The folder above: picking a folder in it opens that one in
                // the open one's place. At the top, the top: going into one.
                val above = column.open != null
                ModuleList(
                    directories = listing.directories,
                    files = listing.files,
                    isSearching = listing.isSearching,
                    padding = PaddingSides(padding, end = false),
                    listState = parentListStates.getOrPut(column.crumbs) {
                        // Where it was as the folder open, on its way over.
                        listStates[column.crumbs]?.let {
                            LazyListState(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset)
                        } ?: LazyListState()
                    },
                    onDir = if (above) onDirAcross else onDir,
                    onSelect = if (above) onSelectInParent else onSelect,
                    onAddToPlaylist = onAddToPlaylist,
                    onAddFolderToPlaylist = onAddFolderToPlaylist,
                    open = column.open,
                )
            }
            ColumnPages(column = right, modifier = Modifier.weight(1f).fillMaxHeight()) { column ->
                val listing = column.listing
                val underBar = PaddingSides(padding, bottom = false, start = false, end = false)
                when {
                    listing == null -> MessageBox(
                        modifier = Modifier.padding(underBar).fillMaxSize(),
                        icon = Icons.Default.FolderOpen,
                        text = stringResource(R.string.browser_pick_folder),
                    )

                    // Opened, and its rows not read yet.
                    !listing.listed -> Box(modifier = Modifier.fillMaxSize())

                    listing.files.isEmpty() && listing.directories.isEmpty() -> MessageBox(
                        modifier = Modifier.padding(underBar).fillMaxSize(),
                        text = if (listing.filterQuery.isBlank()) {
                            stringResource(R.string.message_no_modules_here)
                        } else {
                            stringResource(R.string.message_no_search_results, listing.filterQuery)
                        },
                    )

                    else -> ModuleList(
                        directories = listing.directories,
                        files = listing.files,
                        isSearching = listing.isSearching,
                        padding = PaddingSides(padding, start = false),
                        listState = listStates.getOrPut(column.crumbs) {
                            parentListStates[column.crumbs]?.let {
                                LazyListState(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset)
                            } ?: LazyListState()
                        },
                        onDir = onDir,
                        onSelect = onSelect,
                        onAddToPlaylist = onAddToPlaylist,
                        onAddFolderToPlaylist = onAddFolderToPlaylist,
                    )
                }
            }
        }
    }
}

/**
 * A column's move from one folder to another: over to the left by a whole
 * column going deeper, back to the right coming up, and in place - a fade -
 * for a folder beside it. Cut at its own edges, so the two columns move
 * together without either running over the other.
 */
@Composable
private fun ColumnPages(
    column: FolderColumn,
    modifier: Modifier,
    content: @Composable (FolderColumn) -> Unit,
) {
    KeyedPages(
        key = column.crumbs to (column.listing == null),
        value = column,
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            val slide = spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntOffset(1, 1))
            val fade = spring<Float>(dampingRatio = 1f, stiffness = 500f)
            val from = initialState.first.size + (if (initialState.second) 0 else 1)
            val to = targetState.first.size + (if (targetState.second) 0 else 1)
            when {
                // The folder moving over is in both columns for a moment, at
                // two widths: the one leaving fades as it goes and the one
                // arriving shows a beat later, rather than the two meeting at
                // the edge between the columns, their lines out of step.
                to > from -> (slideInHorizontally(slide) { it } + fadeIn(tween(220, delayMillis = 60))) togetherWith
                    (slideOutHorizontally(slide) { -it } + fadeOut(tween(140)))
                to < from -> (slideInHorizontally(slide) { -it } + fadeIn(tween(220, delayMillis = 60))) togetherWith
                    (slideOutHorizontally(slide) { it } + fadeOut(tween(140)))
                else -> fadeIn(fade) togetherWith fadeOut(fade)
            }
        },
        label = "folder_column",
    ) { shown ->
        Box(modifier = Modifier.fillMaxSize()) { content(shown) }
    }
}

/**
 * AnimatedContent moved by [key] alone: a new [value] under the same key is
 * shown in place, and only a new key moves the pages.
 *
 * Handed the value itself - a folder's listing, which comes in a moment
 * after the folder opens - its target moved twice in one move, and a target
 * moved again while a move is under way drops the page on its way out: the
 * folder being left vanished at once, rather than sliding off.
 */
@Composable
private fun <K, V> KeyedPages(
    key: K,
    value: V,
    modifier: Modifier,
    transitionSpec: AnimatedContentTransitionScope<K>.() -> ContentTransform,
    label: String,
    content: @Composable (V) -> Unit,
) {
    // What each page last showed, for one on its way out to go on showing it.
    val shown = remember { LinkedHashMap<K, V>() }
    SideEffect {
        shown.remove(key)
        shown[key] = value
        while (shown.size > KEPT_PAGES) shown.remove(shown.keys.first())
    }
    AnimatedContent(targetState = key, modifier = modifier, transitionSpec = transitionSpec, label = label) { page ->
        val pageValue = if (page == key) value else shown[page] ?: return@AnimatedContent
        content(pageValue)
    }
}

/** How many pages [KeyedPages] remembers: more than can be on their way out at once. */
private const val KEPT_PAGES = 6

/**
 * The folder shown, and under Liquid Glass the move between two: iOS's push
 * going deeper - the new folder slides in whole over the old, which slides a
 * third of the way off and dims - and its reverse coming back up.
 */
@Composable
private fun FolderPages(
    state: BrowserUiState,
    animate: Boolean,
    content: @Composable (BrowserUiState) -> Unit,
) {
    if (!animate) {
        content(state)
        return
    }
    val background = MaterialTheme.colorScheme.background
    KeyedPages(
        key = state.breadcrumbs.toList(),
        value = state,
        modifier = Modifier,
        transitionSpec = {
            val slide = spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntOffset(1, 1))
            val fade = spring<Float>(dampingRatio = 1f, stiffness = 380f)
            if (targetState.size > initialState.size) {
                (slideInHorizontally(slide) { it } togetherWith
                    (slideOutHorizontally(slide) { -it / 3 } + fadeOut(fade, targetAlpha = 0.6f)))
                    .apply { targetContentZIndex = 1f }
            } else {
                ((slideInHorizontally(slide) { -it / 3 } + fadeIn(fade, initialAlpha = 0.6f)) togetherWith
                    slideOutHorizontally(slide) { it })
                    .apply { targetContentZIndex = -1f }
            }
        },
        label = "folder_pages",
    ) { folder ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background),
        ) {
            content(folder)
        }
    }
}
