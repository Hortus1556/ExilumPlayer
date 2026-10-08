package com.hortus1556.exilum.ui.liquid

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.hortus1556.exilum.ui.oneui.OneUiTab
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import androidx.compose.ui.util.lerp as lerpFloat

/** The bar, and the round search button beside it. */
private val BarHeight = 64.dp

/** Between the bar's rim and the row of tabs, and the drop's height inside it. */
private val BarInset = 4.dp
private val DropHeight = 56.dp

/** The bar and the search button folded down to while a page is being read. */
private val FoldedSize = 52.dp

/** The accessory's height, over the bar or beside it. */
private val AccessoryHeight = 52.dp

/** Between any two pieces of glass. */
private val Gap = 8.dp

/** What the folded bar's icon drops by, once the label under it has gone. */
private val IconDrop = 7.dp

/** How far the whole bar gives when the drop is pulled past either end. */
private val PanelGive = 4.dp

/**
 * How near two pieces of the bars' glass come before they run together,
 * halfway through a fold. At rest it is the gap between them and no more, so
 * that a row of pieces is a row of pieces.
 */
private val MeltReach = 28.dp

/** The room the bars take at the bottom of the screen, with or without an accessory. */
fun liquidBarsHeight(withAccessory: Boolean): Dp =
    if (withAccessory) BarHeight + Gap + AccessoryHeight else BarHeight

/** How much larger the tabs are seen through the drop while it is held. */
private val LocalTabMagnification = staticCompositionLocalOf { { 1f } }

/**
 * iOS 26's tab bar, drawn the way Kyant0's AndroidLiquidGlass catalog
 * reproduces it:
 *
 * - A capsule of glass floating over the page, with the tabs on it.
 * - The selected tab sits in a drop: a faint capsule at rest that, pressed,
 *   swells out of the bar into a lens. It can be dragged from tab to tab,
 *   magnifying what is under it in the tint, and snaps to the nearest one
 *   with a bounce, stretching with its speed on the way. A tap on another
 *   tab sends it there the same way.
 * - The search is a round button of its own beside the bar; opened, the bar
 *   folds to its selected tab and the button opens into a field along the
 *   bottom.
 * - While a page scrolls on, the bar folds into a circle holding the
 *   selected tab, and the accessory (the mini player) comes down between it
 *   and the search, all on one spring.
 */
@Composable
fun LiquidTabBar(
    tabs: List<OneUiTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    minimized: Boolean,
    onExpand: () -> Unit,
    backdrop: Backdrop,
    state: LiquidBarsState,
    search: LiquidSearchState?,
    searchPlaceholder: String,
    modifier: Modifier = Modifier,
    /**
     * Whether the search belongs on the page being shown. It is the browser's;
     * on the others the button goes away and the bar takes its room.
     */
    searchVisible: Boolean = search != null,
    /** Where the pages are, while a swipe between them is moving the drop along. */
    pagePosition: () -> Float = { selected.toFloat() },
    pageDragged: () -> Boolean = { false },
    pageMoving: () -> Boolean = { false },
    accessory: (@Composable (inline: Boolean) -> Unit)? = null,
) {
    val colors = LocalLiquidColors.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val searching = search?.active == true
    // Away on a page that has nothing to search. The two move in turn, as iOS
    // does it: the bar gives up its room before the button appears in it, and
    // the button pops away before the bar takes the room back.
    val searchShown = search != null && (searchVisible || searching)
    val roomAnim = remember { Animatable(if (searchShown) 1f else 0f) }
    val popAnim = remember { Animatable(if (searchShown) 1f else 0f) }
    LaunchedEffect(searchShown) {
        if (!searchShown) delay(140)
        roomAnim.animateTo(if (searchShown) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 320f))
    }
    LaunchedEffect(searchShown) {
        if (searchShown) delay(140)
        popAnim.animateTo(if (searchShown) 1f else 0f, LiquidPopSpring)
    }
    // Read where they are used - in layout and drawing - and not here: they
    // run for a third of a second on every change of section that shows or
    // hides the search, and read in this body they rebuilt the whole bar,
    // mini player and all, every frame of that.
    val searchRoom: () -> Float = { roomAnim.value }
    val searchPop: () -> Float = { popAnim.value }
    // Whether the search is there at all: that much is a matter for
    // composition, and changes twice per animation rather than every frame.
    val searchPresent by remember {
        derivedStateOf { roomAnim.value > 0.01f || popAnim.value > 0.01f }
    }

    val fold = state.fold
    val open = state.open
    LaunchedEffect(minimized, searching) {
        fold.animateTo(if (minimized || searching) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 340f))
    }
    LaunchedEffect(searching) {
        open.animateTo(if (searching) 1f else 0f, spring(dampingRatio = 0.76f, stiffness = 300f))
    }

    val closeSearch: () -> Unit = {
        search?.field?.clearText()
        search?.active = false
    }
    BackHandler(enabled = searching, onBack = closeSearch)

    // Where the bars are, for the pages' own floating actions: kept as state
    // so the room the search gives up is followed as it animates, not only
    // when something is laid out again.
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    // The top of the bars steps by the whole height of the accessory the
    // moment the mini player comes or goes. A page's own action sits above
    // that top, so it follows it on a spring rather than jumping the 60dp
    // with it. The bottom does not move at all: the bars hang from it.
    val barTop = remember { Animatable(0f) }
    LaunchedEffect(barOrigin.y) {
        if (barTop.value == 0f) barTop.snapTo(barOrigin.y)
        // Over and done with before anything pops in. A button that is there
        // through the change should follow rather than jump, but one arriving
        // after it wants the bars already where they are going - and a spring,
        // however stiff, has a tail far longer than the pause before a pop.
        else barTop.animateTo(barOrigin.y, tween(durationMillis = 90, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(barOrigin, barSize) {
        if (barSize.width == 0) return@LaunchedEffect
        val width = barSize.width.toFloat()
        val bottom = barOrigin.y + barSize.height.toFloat()
        // Collected rather than keyed on, so the room the search gives up and
        // the top on its way there are followed frame by frame without
        // starting this again in each of them.
        snapshotFlow { roomAnim.value to barTop.value }.collect { (room, top) ->
            with(density) {
                val gapPx = Gap.toPx()
                val foldedPx = FoldedSize.toPx()
                state.actionArea = Rect(
                    barOrigin.x,
                    top - 12.dp.toPx() - BarHeight.toPx(),
                    barOrigin.x + width,
                    top - 12.dp.toPx(),
                )
                state.foldedActionArea = Rect(
                    barOrigin.x + foldedPx + gapPx,
                    bottom - foldedPx,
                    barOrigin.x + width - (foldedPx + gapPx) * room,
                    bottom,
                )
            }
        }
    }

    // The bar, the accessory and the search are pieces of one sheet of
    // glass - LiquidBarsGlass, under all of this: folding, they cross each
    // other, and run together as they do rather than one passing over the
    // other.
    val union = state.union
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(liquidBarsHeight(accessory != null))
            // Tells the page where its own floating action belongs: above the
            // bars while they are open, and in the folded row, beside the
            // search, once they fold.
            .onGloballyPositioned { coordinates ->
                barOrigin = coordinates.positionInRoot()
                barSize = coordinates.size
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val barH: Float
        val dropH: Float
        val inset: Float
        val folded: Float
        val accH: Float
        val gap: Float
        with(density) {
            barH = BarHeight.toPx()
            dropH = DropHeight.toPx()
            inset = BarInset.toPx()
            folded = FoldedSize.toPx()
            accH = AccessoryHeight.toPx()
            gap = Gap.toPx()
        }
        val count = tabs.size.coerceAtLeast(1)
        val openBarW: () -> Float = { w - (barH + gap) * searchRoom() }
        val tabW: () -> Float = { (openBarW() - inset * 2f) / count }
        // What is in a tab is laid out at one width - the narrowest the tab
        // gets, beside the search - however much room the search leaves it.
        // Each name is fitted to its tab by trying one size after another:
        // laid out at the tab's width of the moment, all ten names were
        // fitted again at every width the tabs went through, every frame of
        // the search coming or going. The tab itself still takes all of its
        // room, to be tapped.
        val contentW = ((w - (if (search != null) barH + gap else 0f) - inset * 2f) / count)
            .roundToInt().coerceAtLeast(0)
        // A tab's width moves while the search makes room for itself or gives
        // it back. The drop must not be built again for that: a new one is a
        // new pointer input, and the gesture under the finger dies with the
        // old one - a drag that simply stopped halfway.
        val tabWidth = rememberUpdatedState(tabW)

        // Where each piece of glass is, from how folded the bars are and
        // how far the search is open.
        val barRect = { f: Float ->
            val bw = lerpFloat(openBarW(), folded, f)
            val bh = lerpFloat(barH, folded, f)
            Rect(0f, h - bh, bw, h)
        }
        val searchRect = { f: Float, o: Float ->
            val s = lerpFloat(barH, folded, f)
            val circle = Rect(w - s, h - s, w, h)
            val field = Rect(folded + gap, h - folded, w, h)
            lerp(circle, field, o)
        }
        // A page's own action in the folded row takes its room beside the
        // search, and the accessory gives it up.
        val reserve by animateFloatAsState(
            targetValue = state.actionWidth.toFloat(),
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
            label = "accessory_room",
        )
        // The accessory arrives from the left, sliding into its place, while
        // the page's own buttons pop away out of the corner it is heading
        // for. It leaves the same way when it is slid off.
        val accessoryEntry = remember { Animatable(0f) }
        LaunchedEffect(accessory != null) {
            if (accessory == null) {
                state.accessoryShift = Offset.Zero
                accessoryEntry.snapTo(0f)
            } else {
                accessoryEntry.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 260f))
            }
        }
        val accessoryRect = { f: Float ->
            val above = Rect(0f, h - barH - gap - accH, w, h - barH - gap)
            val right = (w - (folded + gap) * searchRoom()) - (if (reserve > 0.5f) reserve + gap else 0f)
            val beside = Rect(folded + gap, h - folded, right.coerceAtLeast(folded + gap + folded), h)
            // Slid aside by a pull on it, and in from off the left edge as
            // it arrives - by being placed there, not by a transform over
            // it, so its glass goes on refracting what it is actually over
            // the whole way.
            val arriving = (accessoryEntry.value - 1f) * (w + gap)
            lerp(above, beside, f).translate(state.accessoryShift + Offset(arriving, 0f))
        }

        // Which tab the bar itself last picked, so the answer coming back
        // from the pages does not send the drop off a second time.
        var barIndex by remember { mutableIntStateOf(selected) }
        val give = remember { Animatable(0f) }
        val panelOffset by remember(w) {
            derivedStateOf {
                val fraction = (give.value / w).coerceIn(-1f, 1f)
                with(density) { PanelGive.toPx() } * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
        val pick: (Int) -> Unit = { index ->
            if (index != barIndex) {
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                barIndex = index
                onSelect(index)
            }
        }
        val drop = remember(scope, count) {
            LiquidDragAnimation(
                scope = scope,
                initialValue = selected.toFloat(),
                valueRange = 0f..(count - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                // Swells out of the bar, but only so far.
                pressedScale = 1.3f,
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.roundToInt().coerceIn(0, count - 1)
                    animateToValue(target.toFloat())
                    pick(target)
                    scope.launch { give.animateTo(0f, spring(1f, 300f, 0.5f)) }
                },
                onDrag = { _, amount ->
                    updateValue(targetValue + amount.x / tabWidth.value())
                    scope.launch { give.snapTo(give.value + amount.x) }
                },
            )
        }
        // A swipe between pages carries the drop along under the finger,
        // flat, the way the pages move - it only lifts for the bar's own taps
        // and drags.
        var following by remember { mutableStateOf(false) }
        LaunchedEffect(drop) {
            snapshotFlow { Triple(pageDragged(), pageMoving(), pagePosition()) }.collect { (dragged, moving, position) ->
                if (dragged) following = true
                if (following) {
                    drop.snapNow(position)
                    if (!dragged && !moving) following = false
                }
            }
        }
        LaunchedEffect(selected) {
            if (selected != barIndex) {
                barIndex = selected
                if (!following) drop.animateToValue(selected.toFloat())
            }
        }
        val barLight = remember(scope) {
            LiquidPressHighlight(scope) { size, _ ->
                Offset(inset + (drop.value + 0.5f) * tabWidth.value() + panelOffset, size.height / 2f)
            }
        }
        val tabsBackdrop = rememberLayerBackdrop()
        val tabClick: (Int) -> Unit = { index ->
            if (index != barIndex) {
                drop.animateToValue(index.toFloat())
                pick(index)
            }
        }

        // The accessory, first so the bar folds over it.
        if (accessory != null) {
            val light = remember(scope) { LiquidPressHighlight(scope) }
            val shown by remember { derivedStateOf { open.value < 0.98f } }
            val zoom = LocalLiquidZoomSource.current
            if (shown) {
                OwnBox(
                    modifier = Modifier
                        .placedAt { accessoryRect(fold.value) }
                        // Where Now Playing zooms out of and back into.
                        .onGloballyPositioned { zoom?.rect = it.boundsInRoot() }
                        // No gel on it: a finger that slides it takes the
                        // whole capsule with it already, and glass leaning
                        // after the finger inside its own buffer as well is
                        // glass with its ends cut off.
                        .barGlassPiece(
                            backdrop, colors, union,
                            between = light.gestureModifier,
                            whole = {
                                // Fading as a slide carries it off the screen.
                                val gone = if (size.width > 0f) {
                                    (abs(state.accessoryShift.x) / size.width).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                alpha = (1f - open.value * 1.6f).coerceIn(0f, 1f) * (1f - gone)
                            },
                        )
                        .then(light.modifier)
                        .clip(Capsule()),
                ) {
                    val inline by remember { derivedStateOf { fold.value > 0.5f } }
                    accessory(inline)
                }
            }
        }

        // The bar.
        OwnBox(modifier = Modifier.placedAt { barRect(fold.value) }) {
            val foldedNow by remember { derivedStateOf { fold.value > 0.5f } }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .barGlassPiece(
                        backdrop, colors, union,
                        whole = { translationX += panelOffset * (1f - fold.value) },
                        glass = {
                            val scale = lerpFloat(1f, 1f + 16.dp.toPx() / size.width, drop.pressProgress)
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                    .then(barLight.modifier)
                    .clip(Capsule())
                    .then(
                        if (foldedNow) {
                            Modifier.clickable(interactionSource = null, indication = null, role = Role.Button) {
                                if (searching) closeSearch() else onExpand()
                            }
                        } else {
                            Modifier
                        }
                    ),
            ) {
                TabRow(
                    tabs = tabs,
                    selected = barIndex,
                    tabWidth = tabW,
                    contentWidth = contentW,
                    color = { index ->
                        if (index == barIndex) lerp(colors.label, colors.tint, fold.value) else colors.label
                    },
                    fold = { fold.value },
                    // Folded, the one tab left is the way back to the whole
                    // bar rather than a tab: a tap on it opens the bar. The
                    // glass behind it says the same, but a disabled tab over
                    // it was taking the tap and doing nothing with it.
                    enabled = true,
                    onClick = { index ->
                        if (foldedNow) {
                            if (searching) closeSearch() else onExpand()
                        } else {
                            tabClick(index)
                        }
                    },
                    modifier = Modifier.layout { measurable, constraints ->
                        val rowW = (tabW() * count).roundToInt()
                        val rowH = dropH.roundToInt()
                        val placeable = measurable.measure(Constraints.fixed(rowW, rowH))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val f = fold.value
                            val center = inset + tabW() * (barIndex + 0.5f)
                            val shift = lerpFloat(0f, folded / 2f - center, f)
                            placeable.place(
                                (inset + shift).roundToInt(),
                                ((constraints.maxHeight - rowH) / 2f).roundToInt(),
                            )
                        }
                    },
                )
            }

            // Past this point, only while the bar is open: the tinted copy of
            // the tabs the drop looks at, and the drop.
            if (!foldedNow) {
                CompositionLocalProvider(
                    LocalTabMagnification provides { lerpFloat(1f, 1.2f, drop.pressProgress) },
                ) {
                    TabRow(
                        tabs = tabs,
                        selected = barIndex,
                        tabWidth = tabW,
                        contentWidth = contentW,
                        color = { colors.label },
                        fold = { 0f },
                        // Over the real tabs, so it is what a tap lands on.
                        enabled = true,
                        onClick = tabClick,
                        modifier = Modifier
                            .clearAndSetSemantics {}
                            .alpha(0f)
                            .layerBackdrop(tabsBackdrop)
                            .layout { measurable, constraints ->
                                val rowW = openBarW().roundToInt()
                                val rowH = dropH.roundToInt()
                                val placeable = measurable.measure(Constraints.fixed(rowW, rowH))
                                layout(constraints.maxWidth, constraints.maxHeight) {
                                    placeable.place(0, ((constraints.maxHeight - rowH) / 2f).roundToInt())
                                }
                            }
                            .graphicsLayer { translationX = panelOffset }
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { Capsule() },
                                effects = {
                                    val progress = drop.pressProgress
                                    vibrancy()
                                    if (colors.frost > 0f) blur(8.dp.toPx() * colors.frost)
                                    if (LiquidRefraction.on) lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                                },
                                highlight = { Highlight.Default.copy(alpha = drop.pressProgress) },
                                onDrawSurface = { drawRect(colors.barGlass) },
                            )
                            .then(barLight.modifier)
                            .padding(horizontal = BarInset)
                            .graphicsLayer(colorFilter = ColorFilter.tint(colors.tint)),
                    )
                }

                val light = !colors.isDark
                Box(
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(
                                Constraints.fixed(tabW().roundToInt(), dropH.roundToInt())
                            )
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(
                                    (inset + drop.value * tabW() + panelOffset).roundToInt(),
                                    ((constraints.maxHeight - dropH) / 2f).roundToInt(),
                                )
                            }
                        }
                        .graphicsLayer { alpha = (1f - fold.value * 3f).coerceIn(0f, 1f) }
                        .then(barLight.gestureModifier)
                        .then(drop.modifier)
                        .drawBackdrop(
                            backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                            shape = { Capsule() },
                            effects = {
                                val progress = drop.pressProgress
                                if (LiquidRefraction.on) lens(
                                    10.dp.toPx() * progress,
                                    14.dp.toPx() * progress,
                                    chromaticAberration = true,
                                )
                            },
                            highlight = { Highlight.Default.copy(alpha = drop.pressProgress) },
                            shadow = { Shadow(alpha = drop.pressProgress) },
                            innerShadow = {
                                val progress = drop.pressProgress
                                InnerShadow(radius = 8.dp * progress, alpha = progress)
                            },
                            layerBlock = {
                                // Stretched along the way it is going, whichever
                                // way that is, and thinned to match - within limits.
                                stretchWith(drop.velocity / 10f, drop.scaleX, drop.scaleY, max = 0.04f)
                            },
                            onDrawSurface = {
                                val progress = drop.pressProgress
                                drawRect(
                                    if (light) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f),
                                    alpha = 1f - progress,
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            },
                        ),
                )
            }
        }

        // The search: a round button, or the field it opens into.
        if (search != null && searchPresent) {
            val light = remember(scope) { LiquidPressHighlight(scope) }
            val focus = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            LaunchedEffect(searching) {
                if (searching) {
                    withFrameNanosOnce()
                    runCatching { focus.requestFocus() }
                } else {
                    focusManager.clearFocus()
                }
            }
            Box(
                modifier = Modifier
                    .placedAt { searchRect(fold.value, open.value) }
                    .barGlassPiece(
                        backdrop, colors, union,
                        between = if (searching) Modifier else light.gestureModifier,
                        // Pops away with the page that had something to search.
                        whole = {
                            val pop = searchPop()
                            alpha = pop.coerceIn(0f, 1f)
                            val scale = 0.6f + 0.4f * pop
                            scaleX *= scale
                            scaleY *= scale
                        },
                        glass = { if (!searching) liquidGel(light) },
                    )
                    .then(light.modifier)
                    .clip(Capsule())
                    .clickable(
                        enabled = !searching,
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = searchPlaceholder,
                    ) { search.active = true },
            ) {
                // The glass stays on the left as the button opens.
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = searchPlaceholder,
                    tint = if (searching) colors.secondaryLabel else colors.label,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(Constraints())
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                val centered = (constraints.maxWidth - placeable.width) / 2f
                                val left = with(density) { 16.dp.toPx() }
                                placeable.place(
                                    lerpFloat(centered, left, open.value).roundToInt(),
                                    (constraints.maxHeight - placeable.height) / 2,
                                )
                            }
                        }
                        .size(24.dp),
                )
                // There from the moment the search opens, so it can take the
                // keyboard straight away, and until it has finished closing.
                val fieldShown by remember { derivedStateOf { open.value > 0.02f } }
                if (fieldShown || searching) {
                    BasicTextField(
                        state = search.field,
                        textStyle = LiquidType.Body.copy(color = colors.label),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        cursorBrush = SolidColor(colors.tint),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        onKeyboardAction = { focusManager.clearFocus() },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = ((open.value - 0.4f) / 0.6f).coerceIn(0f, 1f) }
                            .focusRequester(focus)
                            .padding(start = 50.dp, end = 44.dp),
                        decorator = { field ->
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                if (search.field.text.isEmpty()) {
                                    Text(
                                        text = searchPlaceholder,
                                        style = LiquidType.Body,
                                        color = colors.secondaryLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                field()
                            }
                        },
                    )
                    // Whether there is anything to clear, and not the text:
                    // read as the text, every letter typed and every move of
                    // the cursor had the whole bar built again, in the frame
                    // the browser was filtering its list for that letter.
                    val hasText by remember(search) { derivedStateOf { search.field.text.isNotEmpty() } }
                    if (hasText) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = colors.secondaryLabel,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 14.dp)
                                .size(20.dp)
                                .clip(Capsule())
                                .clickable { search.field.clearText() },
                        )
                    }
                }
            }
        }
    }
}

/** Waits for the next frame, so a field just composed can take focus. */
private suspend fun withFrameNanosOnce() {
    androidx.compose.runtime.withFrameNanos { }
}

/**
 * The tabs in a row, each [tabWidth] wide: the icon over its name, in
 * [color], laid out [contentWidth] wide in the middle of it. Folding, the
 * others fade, the name goes and the icon drops to the middle of the circle.
 */
@Composable
private fun TabRow(
    tabs: List<OneUiTab>,
    selected: Int,
    tabWidth: () -> Float,
    contentWidth: Int,
    color: (Int) -> Color,
    fold: () -> Float,
    enabled: Boolean,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val magnification = LocalTabMagnification.current
    Layout(
        modifier = modifier,
        content = {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selected
                Column(
                    modifier = Modifier
                        .clickable(
                            enabled = enabled,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClickLabel = tab.label,
                        ) { onClick(index) }
                        .graphicsLayer {
                            val scale = magnification()
                            scaleX = scale
                            scaleY = scale
                            alpha = if (isSelected) 1f else (1f - fold() * 2.5f).coerceIn(0f, 1f)
                        }
                        // The same width whatever the tab's: measured again
                        // with nothing changed, the icon and the name are
                        // left as they were.
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(Constraints.fixed(contentWidth, constraints.maxHeight))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place((constraints.maxWidth - placeable.width) / 2, 0)
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val tint = color(index)
                    Icon(
                        imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier
                            .graphicsLayer { translationY = with(density) { IconDrop.toPx() } * fold() }
                            .size(26.dp),
                    )
                    // The whole name, always: a long one is set a little
                    // smaller to fit its tab, as iOS does, never cut short.
                    BasicText(
                        text = tab.label,
                        style = LiquidType.TabLabel.copy(color = tint, textAlign = TextAlign.Center),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 10.sp, stepSize = 0.25.sp),
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .graphicsLayer { alpha = (1f - fold() * 2f).coerceIn(0f, 1f) },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val width = tabWidth()
        val each = width.roundToInt().coerceAtLeast(0)
        val height = constraints.maxHeight
        val placeables = measurables.map { it.measure(Constraints.fixed(each, height)) }
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place((index * width).roundToInt(), 0)
            }
        }
    }
}

/**
 * Lays its content out at [rect] within the parent - a rect read at layout
 * time, so the bars can move every frame of a fold without recomposing.
 */
/**
 * A Box whose content is recomposed by what it reads, and nothing round it
 * is. Box itself takes its content inline, so a state read in one belongs to
 * whatever the Box is in: the bar and the accessory each ask, halfway through
 * a fold, whether they are folded yet, and the answer changing rebuilt the
 * whole of the bars - mini player, search and all - in the middle of the
 * spring.
 */
@Composable
private fun OwnBox(modifier: Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier = modifier, content = content)

/**
 * The glass of the bars and of the pages' floating actions: one sheet for
 * them all, so that where two of them meet - the bar folding across the mini
 * player, a page's button coming down beside the search - they run together.
 * It is drawn here and the pieces lie on it: put it under LiquidActionOverlay
 * and LiquidTabBar, as big as the two of them together.
 */
@Composable
fun LiquidBarsGlass(state: LiquidBarsState, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val colors = LocalLiquidColors.current
    Box(
        modifier.liquidGlassUnion(state.union, backdrop, colors.frost) {
            // No more than the gap at rest; more while the bars fold - and past
            // either end too: the fold is a spring, and where it carries a
            // piece into its neighbour the two should meet softly.
            val f = state.fold.value
            lerpFloat(Gap.toPx(), MeltReach.toPx(), abs(4f * f * (1f - f)).coerceAtMost(1f))
        }
    )
}

/**
 * One of the bars' pieces of glass. [whole] moves and fades all of it - the
 * glass, its shadow and what is on it - and [glass] is the glass's own lean
 * and swell under a finger; [between] goes between the two.
 *
 * In a [union] the two are one block, which the sheet of glass reads to know
 * where the piece is. Glass on its own cannot be faded by its own block -
 * its shadow would stay - so there [whole] is a layer round it.
 */
private fun Modifier.barGlassPiece(
    backdrop: Backdrop,
    colors: LiquidColors,
    union: LiquidGlassUnion?,
    between: Modifier = Modifier,
    whole: (GraphicsLayerScope.() -> Unit)? = null,
    glass: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier = if (union != null) {
    then(between).liquidBarGlass(
        backdrop, colors.barGlass, frost = colors.frost, union = union,
        layerBlock = {
            glass?.invoke(this)
            whole?.invoke(this)
        },
    )
} else {
    (if (whole != null) graphicsLayer(whole) else this)
        .then(between)
        .liquidBarGlass(backdrop, colors.barGlass, frost = colors.frost, layerBlock = glass)
}

private fun Modifier.placedAt(rect: () -> Rect): Modifier = layout { measurable, constraints ->
    val r = rect()
    val placeable = measurable.measure(
        Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0))
    )
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(r.left.roundToInt(), r.top.roundToInt())
    }
}

private fun lerp(a: Rect, b: Rect, t: Float): Rect = Rect(
    lerpFloat(a.left, b.left, t),
    lerpFloat(a.top, b.top, t),
    lerpFloat(a.right, b.right, t),
    lerpFloat(a.bottom, b.bottom, t),
)
