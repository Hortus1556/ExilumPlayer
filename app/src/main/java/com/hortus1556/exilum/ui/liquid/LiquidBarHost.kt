package com.hortus1556.exilum.ui.liquid

import androidx.compose.runtime.SideEffect
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

// iOS 26 has one navigation bar for a whole stack of screens: the screens
// slide underneath it while its buttons and title change in place. Here each
// screen declares its bar, and the host draws every declared bar's row in one
// overlay above all the screens - so a screen sliding past never paints over
// another's buttons while they pop - each row popping in or out as its
// screen comes or goes.

/** The spring every bar item pops in and out on: the same both ways, as iOS's do. */
val LiquidPopSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 480f)

/** One screen's bar, as the host draws it. */
@Stable
class LiquidBarEntry(
    val presence: State<Float>,
    val navigationIcon: State<@Composable () -> Unit>,
    val actions: State<@Composable RowScope.() -> Unit>,
    val title: State<@Composable () -> Unit>,
    val subtitle: State<(@Composable () -> Unit)?>,
    /** What the screen has to say beside its buttons, in a capsule of its own; null for nothing. */
    val status: State<(@Composable () -> Unit)?>,
    /** How folded the screen's large title is: the small title shows once it is mostly folded. */
    val collapsed: State<() -> Float>,
    val large: State<Boolean>,
    val backdrop: State<Backdrop?>,
    /**
     * When this bar joined the host. The rows are drawn in this order and
     * never another: a map hands its entries back in whatever order it likes,
     * and two bars that swap places between frames flicker.
     */
    val order: Long,
)

/** Where screens declare their bars, for [LiquidBarOverlay] to draw. */
@Stable
class LiquidBarHost {
    internal val entries = mutableStateMapOf<Any, LiquidBarEntry>()
    private var next = 0L

    /** The next bar's place in the order; they are drawn oldest first. */
    fun nextOrder(): Long = next++
}

/** The bar host of the screens here; null where each screen draws its own bar. */
val LocalLiquidBarHost = compositionLocalOf<LiquidBarHost?> { null }

// A page's floating action belongs to the bars in the same way. It is
// anchored to them, not to the page, so it cannot be drawn inside the page:
// the pager clips each page to its slot, and a button held still while its
// page slides away is cut off by that edge - the button wiping away rather
// than popping. Declared here instead, it is drawn over every page.

/** One screen's floating action, as [LiquidActionOverlay] draws it. */
@Stable
class LiquidActionEntry(
    val presence: State<Float>,
    /** Whether its page is the one being shown, for what it holds to follow. */
    val present: State<Boolean>,
    val content: State<@Composable (Modifier) -> Unit>,
    /** Its page's own glass source, carried along with it. */
    val backdrop: State<Backdrop?>,
    /** When it joined, so two actions never change places between frames. */
    val order: Long,
)

/** Where screens declare their floating action, for [LiquidActionOverlay] to draw. */
@Stable
class LiquidActionHost {
    internal val entries = mutableStateMapOf<Any, LiquidActionEntry>()
    private var next = 0L

    /** The next action's place in the order; they are drawn oldest first. */
    fun nextOrder(): Long = next++
}

/** The action host of the screens here; null where each screen draws its own. */
val LocalLiquidActionHost = compositionLocalOf<LiquidActionHost?> { null }

/**
 * Every screen's floating action, drawn over them all and moved to where
 * [bars] say it goes, popping in and out with its screen.
 */
@Composable
fun LiquidActionOverlay(host: LiquidActionHost, bars: LiquidBarsState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        val ordered = host.entries.entries.sortedBy { it.value.order }
        for ((id, entry) in ordered) {
            key(id) {
                // Whether it is there at all, which changes twice a pop: the
                // pop itself is drawn, and read here it rebuilt this every
                // frame of every one.
                val shown by remember(entry) { derivedStateOf { entry.presence.value > 0.001f } }
                if (shown) {
                    // Its glass is a piece of the bars' sheet, which cannot see
                    // what this does to it: how far it has popped and faded,
                    // and - read for nothing but to be followed - what moves it.
                    val carrier = remember(bars, entry) {
                        bars.union?.let { union ->
                            LiquidGlassCarrier(union) {
                                follow(bars.fold.value, bars.actionArea, bars.foldedActionArea)
                                (1f - bars.open.value * 2f).coerceIn(0f, 1f) * entry.presence.value.coerceIn(0f, 1f)
                            }
                        }
                    }
                    CompositionLocalProvider(
                        LocalLiquidBackdrop provides entry.backdrop.value,
                        LocalLiquidBars provides bars,
                        LocalLiquidGlassCarrier provides carrier,
                        // Its page's, so what it holds still knows whether the
                        // page is the one being shown - an open menu closes
                        // with it, and only the shown page's action takes room
                        // in the folded row.
                        LocalLiquidPageMotion provides remember(entry.present.value) {
                            LiquidPageMotion(barShift = { 0f }, present = entry.present.value)
                        },
                    ) {
                        entry.content.value(Modifier.liquidBarsAnchored(bars, entry.presence))
                    }
                }
            }
        }
    }
}

/** Read, and so followed by whoever reads this. */
@Suppress("UNUSED_PARAMETER")
private fun follow(vararg read: Any?) = Unit

/**
 * The bar rows of every screen in [host], drawn over them all: each screen's
 * buttons on glass at the ends and its small title in the middle, popping in
 * and out with its screen.
 */
@Composable
fun LiquidBarOverlay(host: LiquidBarHost, modifier: Modifier = Modifier) {
    val colors = LocalLiquidColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(androidx.compose.material3.TopAppBarDefaults.windowInsets)
            .height(LiquidDimens.BarHeight),
    ) {
        // Oldest first, so nothing changes places under the one over it.
        val ordered = host.entries.entries.sortedBy { it.value.order }
        for ((id, entry) in ordered) {
            key(id) {
                val presence = entry.presence
                val shown by remember(entry) { derivedStateOf { presence.value > 0.001f } }
                if (shown) {
                    CompositionLocalProvider(LocalLiquidBackdrop provides entry.backdrop.value) {
                        LiquidBarRow(
                            presence = presence,
                            navigationIcon = entry.navigationIcon.value,
                            actions = entry.actions.value,
                            title = entry.title.value,
                            subtitle = entry.subtitle.value,
                            status = entry.status.value,
                            titleShown = {
                                if (entry.large.value) ((entry.collapsed.value() - 0.55f) / 0.45f).coerceIn(0f, 1f) else 1f
                            },
                            colors = colors,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A bar's own row: the buttons at its ends, and the title in the middle of
 * the screen - not of the room left between them, as iOS centres it - as
 * wide as it can be without meeting either.
 */
@Composable
internal fun LiquidBarRow(
    presence: State<Float>,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    title: @Composable () -> Unit,
    subtitle: (@Composable () -> Unit)?,
    titleShown: () -> Float,
    colors: LiquidColors,
    modifier: Modifier = Modifier,
    /**
     * What the screen has to say - how far a long job has got - in a capsule
     * of glass of its own at the start of the bar, after the back button;
     * null for nothing.
     */
    status: (@Composable () -> Unit)? = null,
) {
    Layout(
        modifier = modifier
            .height(LiquidDimens.BarHeight)
            .padding(horizontal = 16.dp),
        content = {
            LiquidGlassCluster(modifier = Modifier.liquidPop(presence)) { navigationIcon() }
            LiquidGlassCluster(modifier = Modifier.liquidPop(presence), content = actions)
            Box { LiquidBarStatus(status, presence) }
            Box(
                modifier = Modifier.graphicsLayer {
                    // The title crossfades, where the buttons pop.
                    alpha = titleShown() * presence.value.coerceIn(0f, 1f)
                },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CompositionLocalProvider(LocalContentColor provides colors.label) {
                        ProvideTextStyle(LiquidType.Headline.copy(textAlign = TextAlign.Center)) {
                            LiquidOneLine { title() }
                        }
                    }
                    if (subtitle != null) {
                        CompositionLocalProvider(LocalContentColor provides colors.secondaryLabel) {
                            ProvideTextStyle(LiquidType.Footnote.copy(textAlign = TextAlign.Center)) {
                                LiquidOneLine { subtitle() }
                            }
                        }
                    }
                }
            }
        },
    ) { parts, rowConstraints ->
        val loose = rowConstraints.copy(minWidth = 0, minHeight = 0)
        val lead = parts[0].measure(loose)
        val trail = parts[1].measure(loose)
        // Its gap to the back button included, so that grows with it too.
        val status = parts[2].measure(loose)
        val w = rowConstraints.maxWidth
        val h = rowConstraints.maxHeight
        val gap = 8.dp.roundToPx()
        // Where the capsule starts: after the back button, or - with none -
        // at the very start, the gap before it hanging off the edge.
        val statusX = if (lead.width > 0) lead.width else -minOf(gap, status.width)
        val start = statusX + status.width
        // In the middle of the screen when it fits there; otherwise in the
        // room between the two ends, as iOS moves a long title over rather
        // than cut it short. Kept to the middle alone, a title with a wide
        // cluster of buttons beside it had the same room taken off both
        // sides: on a narrower phone "Browser" came out as "Br".
        val centred = (w - (maxOf(start, trail.width) + gap) * 2).coerceAtLeast(0)
        val between = (w - start - trail.width - gap * 2).coerceAtLeast(0)
        val fitsCentred = parts[3].maxIntrinsicWidth(h) <= centred
        val title = parts[3].measure(loose.copy(maxWidth = if (fitsCentred) centred else between))
        val titleX = if (fitsCentred) (w - title.width) / 2 else start + gap + (between - title.width) / 2
        layout(w, h) {
            lead.placeRelative(0, (h - lead.height) / 2)
            trail.placeRelative(w - trail.width, (h - trail.height) / 2)
            status.placeRelative(statusX, (h - status.height) / 2)
            title.placeRelative(titleX, (h - title.height) / 2)
        }
    }
}

/**
 * A bar's status capsule, while there is anything to say in it: in with a
 * pop and out the same way - saying the last thing it had to say until it
 * has gone - the room it takes growing and shrinking with it.
 */
@Composable
private fun LiquidBarStatus(status: (@Composable () -> Unit)?, presence: State<Float>) {
    val kept = remember { mutableStateOf(status) }
    SideEffect { if (status != null) kept.value = status }
    LiquidPopIn(visible = status != null) {
        val content = status ?: kept.value
        if (content != null) {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                LiquidGlassCluster(modifier = Modifier.liquidPop(presence)) { content() }
            }
        }
    }
}

/**
 * Keeps [content] while it pops in or out with [visible], on the same
 * spring both ways - scaling up from smaller and sharpening as it fades in,
 * and the reverse - the way iOS 26 brings a bar button in or takes it away.
 */
@Composable
fun LiquidPopIn(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) { progress.animateTo(if (visible) 1f else 0f, LiquidPopSpring) }
    // Whether it is there at all, which changes twice a pop; the pop itself
    // is laid out and drawn, not composed.
    val shown by remember { derivedStateOf { progress.value > 0.001f } }
    if (visible || shown) {
        Box(
            modifier = modifier
                // The room it takes grows and shrinks with it, so what it sits
                // in grows and shrinks too. Scaled only as it is drawn, it
                // kept its full size in the layout until it was gone: the
                // glass round a bar button stayed whole while the glyph in it
                // faded away, and only then shrank.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val p = progress.value.coerceIn(0f, 1f)
                    val w = (placeable.width * p).roundToInt()
                    val h = (placeable.height * p).roundToInt()
                    layout(w, h) {
                        placeable.place((w - placeable.width) / 2, (h - placeable.height) / 2)
                    }
                }
                .liquidPop(progress.asState()),
        ) { content() }
    }
}

/**
 * Where Now Playing zooms from and back into: the mini player's glass in the
 * tab bar, in root coordinates. Empty until it has been laid out.
 */
@Stable
class LiquidZoomSource {
    var rect by mutableStateOf(Rect.Zero)
}

/** The zoom source shared by the tab bar's accessory and Now Playing; null where there is none. */
val LocalLiquidZoomSource = compositionLocalOf<LiquidZoomSource?> { null }

/** Keeps a bar's title to one line, where a long one would wrap. */
@Composable
internal fun LiquidOneLine(content: @Composable () -> Unit) {
    val lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.material3.LocalTextStyle.current.lineHeight.toDp()
    }
    Box(
        modifier = Modifier
            .heightIn(max = lineHeight)
            // Cut to one line, above and below only. Clipped all round, a
            // title changing - its width animating from the old name's to the
            // new one's - was cut at the sides as well: "Browser" came back
            // as "owser".
            .drawWithContent {
                clipRect(left = -size.width * 4f, top = 0f, right = size.width * 5f, bottom = size.height) {
                    this@drawWithContent.drawContent()
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}
