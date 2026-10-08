package com.hortus1556.exilum.ui.screens.browser.components

import androidx.compose.animation.core.tween
import com.hortus1556.exilum.ui.liquid.LiquidPane
import com.hortus1556.exilum.ui.liquid.LocalLiquidOverlayHost
import com.hortus1556.exilum.ui.liquid.LiquidOverlayWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import com.hortus1556.exilum.player.soundcheck.LoudnessAnalyzer
import com.hortus1556.exilum.ui.liquid.LiquidMenuSeparator
import com.hortus1556.exilum.ui.liquid.LiquidMenuItem
import com.hortus1556.exilum.ui.liquid.LiquidMenu
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import com.hortus1556.exilum.ui.liquid.LiquidPopIn
import com.hortus1556.exilum.ui.liquid.frost
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.rememberCoroutineScope
import com.hortus1556.exilum.ui.liquid.buttonGlass
import com.hortus1556.exilum.ui.liquid.tintGlass
import com.hortus1556.exilum.ui.liquid.tintGlassPlain
import com.hortus1556.exilum.ui.liquid.liquidGel
import com.hortus1556.exilum.ui.liquid.liquidButtonGlass
import com.hortus1556.exilum.ui.liquid.liquidGlassUnion
import com.hortus1556.exilum.ui.liquid.rememberLiquidGlassUnion
import com.hortus1556.exilum.ui.liquid.LiquidPressHighlight
import com.hortus1556.exilum.ui.liquid.LocalLiquidBackdrop
import com.hortus1556.exilum.ui.liquid.LocalLiquidGlassCarrier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.components.AppTopAppBar
import com.hortus1556.exilum.ui.liquid.CapsuleShape
import com.hortus1556.exilum.ui.liquid.ContinuousShape
import com.hortus1556.exilum.ui.liquid.LiquidActivityIndicator
import com.hortus1556.exilum.ui.liquid.LiquidGlyphButton
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.glassTint
import com.hortus1556.exilum.ui.liquid.liquidGlassPlain
import com.hortus1556.exilum.ui.liquid.liquidPress
import kotlinx.collections.immutable.ImmutableList

/**
 * The browser's top as an iOS 26 file browser draws it: the folder that is
 * open as the page's large title, a glass back button to the one above it -
 * held, it lists every folder up to the top, as iOS's back button does - the
 * page's buttons in glass on the right. The path is in the back button, so
 * there are no breadcrumbs, and the search is the tab bar's, at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiquidBrowserTopBar(
    breadcrumbs: ImmutableList<String>,
    onBreadcrumb: (Int) -> Unit,
    sortMenu: @Composable () -> Unit,
    onShuffleAll: () -> Unit,
    onFolderPick: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    /** Sound Check's pass over the library: counted beside the buttons while it runs. */
    soundCheck: State<LoudnessAnalyzer.Progress>,
    /** And what it is working through, listed when the count is tapped. */
    soundCheckQueue: State<LoudnessAnalyzer.Queue>,
) {
    Column {
        val root = stringResource(R.string.nav_browser)
        // Whether it is running, which changes twice a pass; the count itself
        // is read by the capsule alone.
        val analysing by remember(soundCheck) {
            derivedStateOf { soundCheck.value.running && soundCheck.value.total > 0 }
        }
        AppTopAppBar(
            title = {
                // The name moves the way the folder does: in from the side
                // going deeper, back out coming up.
                AnimatedContent(
                    targetState = breadcrumbs,
                    transitionSpec = {
                        val deeper = targetState.size > initialState.size
                        val slide = spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntOffset(1, 1))
                        // Through rather than across: the old name has gone
                        // before the new one shows. Crossing, the two were
                        // written over each other halfway - "Browser" into
                        // "nsf" read "Bronsfer".
                        (slideInHorizontally(slide) { if (deeper) it / 3 else -it / 3 } +
                            fadeIn(tween(durationMillis = 160, delayMillis = 90))) togetherWith
                            (slideOutHorizontally(slide) { if (deeper) -it / 3 else it / 3 } +
                                fadeOut(tween(durationMillis = 90))) using
                            SizeTransform(clip = false)
                    },
                    label = "folder_title",
                ) { crumbs ->
                    Text(text = if (crumbs.size > 1) crumbs.last() else root, maxLines = 1)
                }
            },
            navigationIcon = {
                // Pops in on the way into a folder and away at the top; the
                // glass around it grows from and shrinks to nothing.
                LiquidPopIn(visible = breadcrumbs.size > 1) {
                    HistoryBackButton(breadcrumbs = breadcrumbs, onBreadcrumb = onBreadcrumb)
                }
            },
            actions = {
                IconButton(onClick = onShuffleAll) {
                    Icon(
                        imageVector = Icons.Default.Casino,
                        contentDescription = stringResource(R.string.desc_shuffle_all_root),
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = onFolderPick) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = stringResource(R.string.desc_folder_pick),
                        modifier = Modifier.size(22.dp),
                    )
                }
                sortMenu()
            },
            scrollBehavior = scrollBehavior,
            status = if (analysing) {
                { SoundCheckCount(soundCheck, soundCheckQueue) }
            } else {
                null
            },
        )
    }
}

/**
 * How far Sound Check has got through the library - files analysed out of
 * all of them - with its level meter, for the capsule in the bar while it
 * runs. Tapped, it lists what is being analysed and what is left.
 */
@Composable
private fun SoundCheckCount(progress: State<LoudnessAnalyzer.Progress>, queue: State<LoudnessAnalyzer.Queue>) {
    val now = progress.value
    val description = stringResource(R.string.soundcheck_capsule_desc, now.done, now.total)
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    Row(
        modifier = Modifier
            .height(48.dp)
            .onGloballyPositioned { anchor = it.boundsInRoot() }
            .clearAndSetSemantics { contentDescription = description }
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_notification_soundcheck),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.soundcheck_capsule, now.done, now.total),
            // Figures of one width, so the capsule does not twitch as they change.
            style = LiquidType.Subheadline.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
    // Gone with the capsule once the pass is over.
    if (open) {
        SoundCheckQueueWindow(progress = progress, queue = queue, anchor = anchor, onDismiss = { open = false })
    }
}

/**
 * Sound Check's pass as a list, hung from its capsule on a pane of glass:
 * how far it has got, the file it is analysing now with a spinner beside it,
 * and the ones still to come, in the order they will be analysed.
 */
@Composable
private fun SoundCheckQueueWindow(
    progress: State<LoudnessAnalyzer.Progress>,
    queue: State<LoudnessAnalyzer.Queue>,
    anchor: Rect?,
    onDismiss: () -> Unit,
) {
    val host = LocalLiquidOverlayHost.current ?: return
    LiquidOverlayWindow(host = host, onDismiss = onDismiss, anchor = anchor, scrim = false) {
        val colors = LocalLiquidColors.current
        LiquidPane(
            shape = ContinuousShape(14.dp),
            modifier = Modifier
                .width(320.dp)
                .heightIn(max = 440.dp),
        ) {
            Column {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
                    Text(
                        text = stringResource(R.string.pref_sound_check),
                        style = LiquidType.Headline,
                        color = colors.label,
                    )
                    val done = progress.value
                    Text(
                        text = stringResource(R.string.soundcheck_window_count, done.done, done.total),
                        style = LiquidType.Footnote.copy(fontFeatureSettings = "tnum"),
                        color = colors.secondaryLabel,
                    )
                }
                LiquidMenuSeparator()
                val work = queue.value
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    work.now?.let { title ->
                        item(key = "now", contentType = "now") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LiquidActivityIndicator(size = 18.dp)
                                Text(
                                    text = title,
                                    style = LiquidType.Body,
                                    color = colors.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    items(count = work.next.size, contentType = { "next" }) { index ->
                        Text(
                            text = work.next[index],
                            style = LiquidType.Body,
                            color = colors.secondaryLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 44.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The back button: a chevron that goes up one folder, and when held, iOS's
 * menu of every folder above this one, the nearest first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryBackButton(
    breadcrumbs: ImmutableList<String>,
    onBreadcrumb: (Int) -> Unit,
) {
    var history by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    Box(modifier = Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CapsuleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.desc_back_button),
                    onLongClick = { history = true },
                    // Still there to be tapped while it pops away at the top.
                    onClick = { if (breadcrumbs.size > 1) onBreadcrumb(breadcrumbs.lastIndex - 1) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBackIosNew,
                contentDescription = stringResource(R.string.desc_back_button),
                modifier = Modifier.size(20.dp),
            )
        }
        LiquidMenu(
            expanded = history,
            anchor = anchor,
            onDismissRequest = { history = false },
        ) {
            for (index in breadcrumbs.lastIndex - 1 downTo 0) {
                if (index != breadcrumbs.lastIndex - 1) LiquidMenuSeparator()
                LiquidMenuItem(
                    text = breadcrumbs[index],
                    icon = Icons.Default.Folder,
                    onClick = {
                        history = false
                        onBreadcrumb(index)
                    },
                )
            }
        }
    }
}

/**
 * Play all, shuffle and repeat as iOS 26 floats them over a page: the two
 * toggles as glyphs on one capsule of glass - tinted while on - and play on
 * a circle of the tint's own glass beside it.
 */
/** Between shuffle and repeat's capsule and play. */
private val PlayGap = 10.dp

@Composable
internal fun LiquidPlayAllBar(
    isShuffle: Boolean,
    isShuffleRoot: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onRepeatMode: (Int) -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** How folded the tab bar is: folding, shuffle and repeat go and play shrinks to the folded row. */
    fold: () -> Float = { 0f },
) {
    val colors = LocalLiquidColors.current
    val backdrop = LocalLiquidBackdrop.current
    val scope = rememberCoroutineScope()
    val toggleLight = remember(scope) { LiquidPressHighlight(scope) }
    val playLight = remember(scope) { LiquidPressHighlight(scope) }
    // One sheet of glass for the two: folding, shuffle and repeat are drawn
    // into play as a drop is into a bigger one. Over the pages, as a floating
    // action, the sheet is the bars' - they come down beside the search - and
    // anywhere else it is this row's own.
    val carrier = LocalLiquidGlassCarrier.current
    val own = rememberLiquidGlassUnion().takeIf { backdrop != null && carrier == null }
    val union = (carrier?.union ?: own).takeIf { backdrop != null }
    Row(
        modifier = modifier.liquidGlassUnion(own, backdrop, colors.frost) {
            // The gap between them at rest, so that they are two; more while one goes into the other.
            val f = fold().coerceIn(0f, 1f)
            androidx.compose.ui.util.lerp(PlayGap.toPx(), 22.dp.toPx(), 4f * f * (1f - f))
        },
        horizontalArrangement = Arrangement.spacedBy(PlayGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                // Shrinks away into play as the bars fold.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val f = fold().coerceIn(0f, 1f)
                    layout((placeable.width * (1f - f)).roundToInt(), placeable.height) {
                        placeable.placeWithLayer(((placeable.width * (1f - f)) - placeable.width).roundToInt(), 0) {
                            alpha = (1f - f * 2f).coerceIn(0f, 1f)
                            // In a union the glass shrinks by its own block, below, and what is on it with it.
                            if (union == null) {
                                val scale = 1f - 0.4f * f
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(1f, 0.5f)
                            }
                        }
                    }
                }
                .then(
                    if (backdrop != null) {
                        Modifier
                            .then(toggleLight.gestureModifier)
                            .liquidButtonGlass(
                                backdrop, colors.buttonGlass, frost = colors.frost,
                                union = union, carried = carrier?.presence,
                                layerBlock = {
                                    liquidGel(toggleLight)
                                    val f = fold().coerceIn(0f, 1f)
                                    if (union != null && f > 0f) {
                                        // Smaller, and across the gap into the middle of play: by the
                                        // end all of it is inside, and play is all there is to see.
                                        val scale = 1f - 0.62f * f
                                        scaleX *= scale
                                        scaleY *= scale
                                        transformOrigin = TransformOrigin(1f, 0.5f)
                                        translationX += f * (PlayGap.toPx() + 26.dp.toPx() + size.width * scale / 2f)
                                    }
                                },
                            )
                            .then(toggleLight.modifier)
                    } else {
                        Modifier
                            .shadow(10.dp, CapsuleShape, ambientColor = Color.Black.copy(alpha = 0.14f), spotColor = Color.Black.copy(alpha = 0.14f))
                            .liquidGlassPlain(CapsuleShape, colors.glassTint(1.7f))
                    }
                )
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidGlyphButton(
                icon = if (isShuffle && isShuffleRoot) Icons.Default.AllInclusive else Icons.Default.Shuffle,
                contentDescription = null,
                onClick = onShuffle,
                glyph = 22.dp,
                tint = if (isShuffle) colors.tint else colors.label,
            )
            LiquidGlyphButton(
                icon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                contentDescription = "Repeat",
                onClick = {
                    onRepeatMode(
                        when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                            else -> Player.REPEAT_MODE_OFF
                        }
                    )
                },
                glyph = 22.dp,
                tint = if (repeatMode != Player.REPEAT_MODE_OFF) colors.tint else colors.label,
            )
        }
        val source = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                // The height of the folded row, once folded.
                .layout { measurable, _ ->
                    val side = androidx.compose.ui.util.lerp(56.dp.toPx(), 52.dp.toPx(), fold().coerceIn(0f, 1f)).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(side, side))
                    layout(side, side) { placeable.place(0, 0) }
                }
                .then(
                    if (backdrop != null) {
                        // The prominent kind: glass tinted all through.
                        Modifier
                            .then(playLight.gestureModifier)
                            .liquidButtonGlass(
                                backdrop,
                                colors.buttonGlass, frost = colors.frost,
                                tint = colors.tintGlass,
                                union = union, carried = carrier?.presence,
                                layerBlock = { liquidGel(playLight) },
                            )
                            .then(playLight.modifier)
                    } else {
                        Modifier
                            .liquidPress(source)
                            .shadow(12.dp, CapsuleShape, ambientColor = colors.tint.copy(alpha = 0.3f), spotColor = colors.tint.copy(alpha = 0.3f))
                            .liquidGlassPlain(CapsuleShape, colors.tintGlassPlain, highlight = 0.8f)
                    }
                )
                .clip(CapsuleShape)
                .combinedClickable(interactionSource = source, indication = null, role = Role.Button, onClick = onPlayAll),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

/**
 * Pull to refresh as iOS draws it: the activity indicator filling in spoke by
 * spoke as the page is pulled, and spinning once let go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiquidRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(top = 14.dp)
            .graphicsLayer {
                val pulled = state.distanceFraction
                alpha = if (isRefreshing) 1f else pulled.coerceIn(0f, 1f)
                translationY = (if (isRefreshing) 1f else pulled.coerceIn(0f, 1.4f)) * 24.dp.toPx() - 24.dp.toPx()
            },
    ) {
        LiquidActivityIndicator(
            spinning = isRefreshing || state.distanceFraction >= 1f,
            progress = { if (isRefreshing) 1f else state.distanceFraction },
        )
    }
}
