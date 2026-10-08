package com.hortus1556.exilum.ui

import com.hortus1556.exilum.ui.liquid.LocalLiquidBackdrop
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import com.hortus1556.exilum.ui.liquid.LocalLiquidActionHost
import com.hortus1556.exilum.ui.liquid.LiquidActionOverlay
import com.hortus1556.exilum.ui.liquid.LiquidActionHost
import com.hortus1556.exilum.ui.liquid.LocalLiquidBarHost
import com.hortus1556.exilum.ui.liquid.LiquidBarOverlay
import com.hortus1556.exilum.ui.liquid.LiquidBarsGlass
import com.hortus1556.exilum.ui.liquid.LiquidBarHost
import com.hortus1556.exilum.ui.liquid.LocalLiquidPageMotion
import com.hortus1556.exilum.ui.liquid.LiquidPageMotion
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import com.hortus1556.exilum.ui.liquid.LocalLiquidBars
import com.hortus1556.exilum.ui.liquid.LiquidBarsState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.input.TextFieldState
import com.hortus1556.exilum.ui.liquid.LocalLiquidSearch
import com.hortus1556.exilum.ui.liquid.LiquidSearchState
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.screens.player.components.LiquidMiniPlayer
import com.hortus1556.exilum.ui.liquid.LiquidTabBar
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.retain.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.hortus1556.exilum.data.StorageAccessEvents
import com.hortus1556.exilum.di.appModule
import com.hortus1556.exilum.ui.screens.browser.FileBrowserScreen
import com.hortus1556.exilum.ui.screens.downloads.NavDownloads
import com.hortus1556.exilum.ui.screens.favourites.NavFavourites
import com.hortus1556.exilum.player.PlaybackStatus
import com.hortus1556.exilum.player.PlayerViewModel
import com.hortus1556.exilum.ui.screens.player.components.MiniPlayerBar
import com.hortus1556.exilum.ui.screens.playlists.NavPlaylists
import com.hortus1556.exilum.ui.screens.preferences.NavPreferences
import com.hortus1556.exilum.ui.components.LocalFloatingBarInset
import com.hortus1556.exilum.ui.oneui.Backdrop
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.oneui.OneUiDimens
import com.hortus1556.exilum.ui.oneui.OneUiFloatingTabBar
import com.hortus1556.exilum.ui.oneui.OneUiTab
import com.hortus1556.exilum.ui.oneui.backdropSource
import com.hortus1556.exilum.ui.oneui.frostedGlass
import com.hortus1556.exilum.ui.oneui.rememberBackdrop
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.isOneUi
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.koin.android.ext.koin.androidContext
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.GlobalContext.startKoin

private sealed class NavKeyMain(@param:StringRes val title: Int) {
    abstract val selectedIcon: ImageVector
    abstract val unselectedIcon: ImageVector

    data object Browser : NavKeyMain(R.string.nav_browser) {
        override val selectedIcon = Icons.Filled.Folder
        override val unselectedIcon = Icons.Outlined.Folder
    }

    data object Playlists : NavKeyMain(R.string.nav_playlists) {
        override val selectedIcon = Icons.AutoMirrored.Filled.List
        override val unselectedIcon = Icons.AutoMirrored.Outlined.List
    }

    data object Favourites : NavKeyMain(R.string.nav_favourites) {
        override val selectedIcon = Icons.Filled.Star
        override val unselectedIcon = Icons.Outlined.StarBorder
    }

    data object Downloads : NavKeyMain(R.string.nav_downloads) {
        override val selectedIcon = Icons.Filled.Download
        override val unselectedIcon = Icons.Outlined.Download
    }

    data object Settings : NavKeyMain(R.string.nav_settings) {
        override val selectedIcon = Icons.Filled.Settings
        override val unselectedIcon = Icons.Outlined.Settings
    }
}

/** How far a section must be dragged before releasing settles on it. */
private const val SECTION_SNAP_THRESHOLD = 0.08f

/** How soon a second back press has to follow the first to leave the app. */
private const val EXIT_CONFIRM_MS = 2000L

/** Between iOS's floating bars and the bottom of the screen. */
private val LiquidBarMargin = 6.dp

/** How far a scroll has to move in one go to hide or bring back One UI's floating bars, in px. */
private const val BAR_HIDE_SLOP = 6f

/**
 * Whether the Downloads section is among the tabs. Off for now: the section
 * and everything behind it are still here, and this one word puts it back.
 */
private const val DOWNLOADS_SHOWN = false

private val bottomBarItems = listOfNotNull(
    NavKeyMain.Browser,
    NavKeyMain.Playlists,
    NavKeyMain.Favourites,
    NavKeyMain.Downloads.takeIf { DOWNLOADS_SHOWN },
    NavKeyMain.Settings,
).toPersistentList()

@Composable
fun MainNavigation(
    onNavigateToPlayer: () -> Unit,
    onBack: () -> Unit,
    /**
     * Whether the open player is covering all of this. Asked for where it is
     * wanted rather than handed over: only the mini player needs to know, and
     * as a value it would have every section composed again each time the
     * player finished opening.
     */
    covered: () -> Boolean = { false },
) {
    // A pager rather than a nav display: these are siblings laid out left to
    // right in the bar, so moving between them slides the way the bar does
    // instead of crossfading, and a swipe gets there as well as a tap.
    val pagerState = rememberPagerState(pageCount = { bottomBarItems.size })
    val scope = rememberCoroutineScope()

    // Well under the 0.5 default. At the default a section had to be dragged
    // more than halfway across before releasing would commit to it, so a
    // perfectly deliberate swipe that ran out of finger sprang back to where
    // it started and looked like it had been ignored.
    val fling = PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = SECTION_SNAP_THRESHOLD,
    )

    // Derived from the pager, never stored alongside it - two sources for
    // "which tab" is exactly what used to leave the bar showing Browser while
    // the content showed Favourites.
    val currentTab = bottomBarItems[pagerState.currentPage]

    val snackBarHostState = retain { SnackbarHostState() }

    val goToBrowser: () -> Unit = {
        scope.launch { pagerState.animateScrollToPage(bottomBarItems.indexOf(NavKeyMain.Browser)) }
    }

    // The last word on back, reached only when nothing on screen took it: every
    // section's own handler - a folder to go up, a page to pop, a menu to fold -
    // was added after this one and so is asked first. From another section it
    // goes to the browser; from the browser it leaves, but only when pressed
    // twice, so a back press meant for something that had already closed does
    // not throw the app away.
    val context = LocalContext.current

    // Android took back access to the music folder: say so, and what to do,
    // instead of leaving files to go missing from the browser without a word.
    val storageAccess = koinInject<StorageAccessEvents>()
    LaunchedEffect(storageAccess) {
        storageAccess.lost.collect {
            Toast.makeText(context, R.string.toast_storage_access_lost, Toast.LENGTH_LONG).show()
        }
    }

    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        if (currentTab != NavKeyMain.Browser) {
            goToBrowser()
        } else {
            val now = SystemClock.uptimeMillis()
            if (now - lastBackAt <= EXIT_CONFIRM_MS) {
                onBack()
            } else {
                lastBackAt = now
                Toast.makeText(context, R.string.toast_back_again_to_exit, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val playerViewModel = koinViewModel<PlayerViewModel>(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity
    )
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()

    // Whether the floating bars are up, rather than slid away (One UI) or
    // folded (Liquid Glass) while a page is scrolled on. Up here for the mini
    // player, which has to know when it has gone with them.
    var barsShown by remember { mutableStateOf(true) }

    // One mini player for the whole tab bar, not one per section.
    //
    // Each section used to carry its own in its own Scaffold, so every page
    // built and animated a copy of it, and swapping sections tore one down and
    // built another - for a bar showing the same track either side of the
    // swipe. Up here it is built once and simply stays put while the pages
    // move underneath.
    val frameInfo = playerViewModel.frameInfo.collectAsStateWithLifecycle()
    val miniPlayer: @Composable () -> Unit = {
        MiniPlayerBar(
            state = playerState,
            frameInfo = frameInfo,
            onTap = onNavigateToPlayer,
            onPlayPause = playerViewModel::togglePlayPause,
            onNext = playerViewModel::next,
            onPrevious = playerViewModel::previous,
            onDismiss = playerViewModel::stop,
            // Not once the bars have slid away, nor under the open player.
            // The bar could be told it was out of sight and never was, so
            // One UI's playback line went on being drawn on every frame of a
            // tune with nobody to see it.
            visible = barsShown && !covered(),
        )
    }

    // One UI: the tab bar and the mini player float over the pages, which
    // scroll on underneath them, frosted, and get out of the way while a page
    // is being read further down.
    val oneUi = isOneUi
    val liquid = isLiquid
    // Both float their bars over the pages rather than stacking them below.
    val floating = oneUi || liquid
    val backdrop = rememberBackdrop()
    // Liquid Glass bends the pages themselves: they are captured, over the
    // page background, for the tab bar's glass to draw from.
    val pageBackground = MaterialTheme.colorScheme.background
    val liquidBackdrop = rememberLayerBackdrop {
        drawRect(pageBackground)
        drawContent()
    }
    // The tab bar's search, read by the browser it searches.
    val liquidSearch = remember { LiquidSearchState(TextFieldState()) }
    // And how folded it is, for the pages' own floating actions to fold with it.
    val liquidBars = remember { LiquidBarsState() }
    // Every screen's top bar row, drawn once over all of them.
    val liquidBarHost = remember { LiquidBarHost() }
    // And every screen's floating action, for the same reason: it is anchored
    // to the bars, so a page sliding out from under it would cut it against
    // the page's own edge.
    val liquidActionHost = remember { LiquidActionHost() }
    val pagerDragged by pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(liquidSearch.active) {
        if (liquidSearch.active) {
            pagerState.animateScrollToPage(bottomBarItems.indexOf(NavKeyMain.Browser))
        }
    }
    var floatingInset by remember { mutableStateOf(0.dp) }
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            // Back at a drag down, whatever the page does with it: the bars
            // returning never takes anything away.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > BAR_HIDE_SLOP) barsShown = true
                return Offset.Zero
            }

            // Away only once the page has scrolled, by what it scrolled. This
            // used to be decided with the bars' return, from what the drag
            // offered before the page had any say: in a folder or a settings
            // page too short to scroll, a drag up took the tab bar and the
            // mini player off the screen with nothing having moved, and left
            // no navigation in sight until a drag down.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y < -BAR_HIDE_SLOP) {
                    barsShown = false
                } else if (available.y > 0f) {
                    // A drag down the page had nothing left to answer with:
                    // it is at its top, and however slow the drag, the bars
                    // are what is being asked for.
                    barsShown = true
                }
                return Offset.Zero
            }
        }
    }
    // A new section is not scrolled yet, so nothing of it is being read.
    LaunchedEffect(pagerState.currentPage) { barsShown = true }
    // And something starting to play is news: the mini player comes back up
    // to say what, with the tab bar under it.
    val playing = playerState.status == PlaybackStatus.PLAYING
    LaunchedEffect(playerState.currentModule, playing) {
        if (playing) barsShown = true
    }

    Scaffold(
        bottomBar = {
            if (!floating) Column {
                AnimatedVisibility(
                    visible = playerState.currentModule != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    miniPlayer()
                }
                ShortNavigationBar {
                bottomBarItems.forEach { destination ->
                    ShortNavigationBarItem(
                        selected = currentTab == destination,
                        label = { Text(text = stringResource(destination.title)) },
                        icon = {
                            Icon(
                                imageVector = if (currentTab == destination) {
                                    destination.selectedIcon
                                } else {
                                    destination.unselectedIcon
                                },
                                contentDescription = stringResource(destination.title),
                            )
                        },
                        onClick = {
                            if (currentTab != destination) {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        bottomBarItems.indexOf(destination)
                                    )
                                }
                            }
                        }
                    )
                    }
                }
            }
        }
    ) { padding ->
        // Every section stays composed, which is how an app with a tab bar
        // makes swiping between them cost nothing: the work happens once, on
        // the way in, and after that a swipe moves things that are already
        // built and measured.
        //
        // Building on demand was the whole problem. Doing it during the
        // gesture ate the frames the drag was being sampled in, so the pager
        // saw a fraction of the movement and sprang back from a swipe that was
        // perfectly deliberate. Deferring it until the page landed only moved
        // that collision to the next swipe in a quick run of them.
        //
        // Affordable because these are lazy lists - an off-screen one holds
        // its frame and the rows that would be on screen, not the library -
        // and because the mini player is no longer part of any of them.
        //
        // On the way in means over the first few frames, though, not inside
        // the first one. All five at once were five screens composed and
        // measured before anything was drawn, at every start and at every
        // rotation; one more a frame puts the section being looked at on
        // screen straight away, and the rest are still built long before a
        // finger can get to them.
        var sectionsBeside by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (sectionsBeside < bottomBarItems.size) {
                withFrameNanos { }
                sectionsBeside++
            }
        }
        // With the pages not answering a sideways drag, nothing consumes one,
        // and a row under the finger took it for a tap: a swipe opened the
        // folder it started on. This swallows the drag instead - the pages
        // stay put, and what is under them is let go of.
        val sidewaysDrag = rememberDraggableState { }
        // What One UI's bars blur. Kept, because backdropSource makes a
        // modifier that equals no other, the one before it included: made in
        // passing, it had the pager looking changed every time anything here
        // was read again - the bars sliding away, the track changing - and the
        // pages were recorded for the bars once more, for nothing.
        val oneUiSource = remember(backdrop) { Modifier.backdropSource(backdrop) }
        Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = if (floating) {
                Modifier
                    .fillMaxSize()
                    .nestedScroll(hideOnScroll)
                    .then(
                        if (liquid) {
                            Modifier.draggable(sidewaysDrag, Orientation.Horizontal)
                        } else {
                            Modifier
                        }
                    )
                    .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else oneUiSource)
            } else {
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            },
            key = { it },
            flingBehavior = fling,
            // Under Liquid Glass the tab bar is how sections are changed, as
            // it is on iOS: the drop is dragged from tab to tab and the pages
            // do not answer a swipe of their own.
            userScrollEnabled = !liquid,
            beyondViewportPageCount = sectionsBeside,
        ) { page ->
            // Under One UI nothing is consumed: each section runs under the
            // status bar and the floating bars, and makes room for them itself.
            val screenModifier = if (floating) Modifier else Modifier.consumeWindowInsets(padding)
            // Each section gets back handling of its own, switched off unless
            // it is the one on screen. They all stay composed (see above), and
            // with them every handler they register: the browser's, which is
            // always on, and each NavDisplay's, which is on whenever its stack
            // is deeper than one. Back went to whichever of those was added
            // last, not to the section being looked at - so from Settings it
            // went up a folder in the hidden browser, or popped a page in a
            // hidden tab, and appeared to do nothing at all.
            val sectionBack = rememberNavigationEventDispatcherOwner(
                enabled = page == pagerState.currentPage,
            )
            // One per page and state, not one per recomposition: every bar on
            // the page reads it, and a new one each time the pager
            // recomposed its pages rebuilt all of them on every change of
            // section - twenty bars for one tap.
            val present = page == pagerState.targetPage
            val pageMotion = remember(page, present) {
                LiquidPageMotion(
                    barShift = { -(page - (pagerState.currentPage + pagerState.currentPageOffsetFraction)) },
                    present = present,
                )
            }
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides sectionBack,
                LocalFloatingBarInset provides if (floating) floatingInset else 0.dp,
                LocalLiquidSearch provides if (liquid) liquidSearch else null,
                LocalLiquidBars provides if (liquid) liquidBars else null,
                // The pages' own glass is given a backdrop of the page it is
                // on, further in. This is the one everything else falls back
                // to - a dialog or a menu opened from anywhere, which lives
                // in a window of its own and would otherwise have nothing
                // behind it to bend and would only be painted to look as
                // though it had.
                LocalLiquidBackdrop provides if (liquid) liquidBackdrop else null,
                LocalLiquidBarHost provides if (liquid) liquidBarHost else null,
                LocalLiquidActionHost provides if (liquid) liquidActionHost else null,
                // A swipe between tabs slides the pages, but not their bars:
                // those stay put and trade places with a pop, as iOS's do.
                LocalLiquidPageMotion provides if (liquid) pageMotion else null,
            ) {
            when (bottomBarItems[page]) {
                NavKeyMain.Browser -> FileBrowserScreen(
                    modifier = screenModifier,
                    snackbarHostState = snackBarHostState,
                    onNavigateToPlayer = onNavigateToPlayer,
                )

                NavKeyMain.Playlists -> NavPlaylists(
                    modifier = screenModifier,
                    snackbarHostState = snackBarHostState,
                    onBack = goToBrowser,
                    onNavigateToPlayer = onNavigateToPlayer,
                )

                NavKeyMain.Favourites -> NavFavourites(
                    modifier = screenModifier,
                    onBack = goToBrowser,
                    onNavigateToPlayer = onNavigateToPlayer,
                )

                NavKeyMain.Downloads -> NavDownloads(
                    modifier = screenModifier,
                    snackbarHostState = snackBarHostState,
                    onBack = goToBrowser,
                    onNavigateToPlayer = onNavigateToPlayer,
                )

                NavKeyMain.Settings -> NavPreferences(
                    modifier = screenModifier,
                    snackbarHostState = snackBarHostState,
                    onBack = goToBrowser,
                )
            }
            }
        }

        // Made once and kept for as long as the labels stay what they are.
        // Whether a bar has been handed new tabs is told by which list it is,
        // not by what is in it, and a new list at every pass through here -
        // each time the bars folded or came back, each time the track changed
        // - had the whole tab bar composed again for five tabs that were the
        // same as before.
        val labels = bottomBarItems.map { stringResource(it.title) }
        val tabs = remember(labels) {
            bottomBarItems.mapIndexed { index, destination ->
                OneUiTab(
                    label = labels[index],
                    icon = destination.unselectedIcon,
                    selectedIcon = destination.selectedIcon,
                )
            }
        }
        if (liquid) {
            // The screens' bars, over every screen and under the tab bar.
            LiquidBarOverlay(host = liquidBarHost, modifier = Modifier.align(Alignment.TopCenter))
            // The glass of those actions and of the tab bar: one sheet, under both.
            LiquidBarsGlass(state = liquidBars, backdrop = liquidBackdrop, modifier = Modifier.fillMaxSize())
            // And their floating actions, over the pages and under the bars.
            LiquidActionOverlay(host = liquidActionHost, bars = liquidBars)
        }
        if (liquid) {
            // iOS 26: the tab bar folds to one button while a page is
            // scrolled on, rather than going, and the mini player - the bar's
            // accessory - comes down beside it.
            val density = LocalDensity.current
            val hasMiniPlayer = playerState.currentModule != null
            LiquidTabBar(
                tabs = tabs,
                // Where the pager is going rather than where it is, so a jump
                // across several tabs does not report each one it passes.
                selected = pagerState.targetPage,
                onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                minimized = !barsShown,
                onExpand = { barsShown = true },
                backdrop = liquidBackdrop,
                state = liquidBars,
                search = liquidSearch,
                // The browser's, and nobody else's: elsewhere the bar takes the room.
                searchVisible = bottomBarItems.getOrNull(pagerState.targetPage) == NavKeyMain.Browser,
                pagePosition = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                pageDragged = { pagerDragged },
                pageMoving = { pagerState.isScrollInProgress },
                searchPlaceholder = stringResource(R.string.search_placeholder),
                accessory = if (hasMiniPlayer) {
                    { inline ->
                        LiquidMiniPlayer(
                            state = playerState,
                            onTap = onNavigateToPlayer,
                            onPlayPause = playerViewModel::togglePlayPause,
                            onNext = playerViewModel::next,
                            onStop = playerViewModel::stop,
                            inline = inline,
                            // Slid to the left it goes, and the glass with it.
                            bars = liquidBars,
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // On a big screen it stays the size of a phone's, as
                    // iPadOS keeps it, rather than stretching edge to edge.
                    .widthIn(max = 640.dp)
                    .navigationBarsPadding()
                    // The search field rides up on the keyboard.
                    .imePadding()
                    .onSizeChanged { floatingInset = with(density) { it.height.toDp() } + LiquidBarMargin }
                    .padding(horizontal = 20.dp)
                    .padding(bottom = LiquidBarMargin),
            )
        }
        if (oneUi) {
            OneUiBottomBars(
                shown = barsShown,
                miniPlayerShown = playerState.currentModule != null,
                miniPlayer = miniPlayer,
                backdrop = backdrop,
                selected = pagerState.currentPage,
                position = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                tabs = tabs,
                onSelect = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
                onInset = { floatingInset = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        }
    }
}

/**
 * One UI's tab bar and mini player, floating clear of the bottom edge over
 * the pages, frosted over what scrolls underneath. They slide away while a
 * page is scrolled on and come back when it is scrolled back.
 *
 * [onInset] is told how much of the bottom of the screen they cover, above
 * the navigation bar, for the pages to leave room for.
 */
@Composable
private fun OneUiBottomBars(
    shown: Boolean,
    miniPlayerShown: Boolean,
    miniPlayer: @Composable () -> Unit,
    backdrop: Backdrop,
    selected: Int,
    position: () -> Float,
    tabs: List<OneUiTab>,
    onSelect: (Int) -> Unit,
    onInset: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOneUiColors.current
    val density = LocalDensity.current
    var height by remember { mutableIntStateOf(0) }
    val hidden by animateFloatAsState(
        targetValue = if (shown) 0f else 1f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "bars_hidden",
    )
    val card = RoundedCornerShape(24.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { height = it.height }
            // Down past the bottom edge, all of it, when hidden.
            .offset { IntOffset(0, (hidden * height).roundToInt()) }
            .navigationBarsPadding()
            .onSizeChanged { onInset(with(density) { it.height.toDp() }) }
            .padding(horizontal = 16.dp)
            .padding(bottom = OneUiDimens.BarMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = miniPlayerShown,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .shadow(
                        elevation = 10.dp,
                        shape = card,
                        ambientColor = Color.Black.copy(alpha = 0.10f),
                        spotColor = Color.Black.copy(alpha = 0.16f),
                    )
                    .frostedGlass(backdrop, card, tint = colors.bar, fallback = colors.barOpaque)
                    .border(width = 0.5.dp, color = colors.barBorder, shape = card),
            ) {
                miniPlayer()
            }
        }
        OneUiFloatingTabBar(
            tabs = tabs,
            selected = selected,
            position = position,
            onSelect = onSelect,
            backdrop = backdrop,
        )
    }
}

@Preview
@Composable
private fun Preview() {
    val context = LocalContext.current
    startKoin {
        androidContext(context)
        modules(appModule)
    }
    AppTheme {
        MainNavigation(
            onNavigateToPlayer = {},
            onBack = {},
        )
    }
}
