package com.hortus1556.exilum.ui.liquid

import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.layout
import androidx.compose.ui.composed
import androidx.compose.animation.core.spring
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import com.kyant.shapes.Capsule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The height the large title adds to the bar. */
private val LargeTitleArea = 52.dp

/**
 * iOS 26's navigation bar. With a [scrollBehavior], the page's title stands
 * large and bold on the left under the bar's buttons; scrolling folds it away
 * and brings it back small and centred in the bar - snapping to one or the
 * other when let go. Without one, only the small centred title.
 *
 * The bar has no background of its own: the buttons float in glass, and the
 * page fades out under the bar - iOS's scroll edge effect - once it has
 * started to go under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidNavigationBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    /** A capsule of its own at the start of the bar - see [LiquidBarRow]; null for nothing. */
    status: (@Composable () -> Unit)? = null,
) {
    val colors = LocalLiquidColors.current
    val density = LocalDensity.current
    val large = scrollBehavior != null
    val barPx = with(density) { LiquidDimens.BarHeight.roundToPx() }
    val largePx = if (large) with(density) { LargeTitleArea.roundToPx() } else 0

    val limit = -largePx.toFloat()
    if (scrollBehavior != null && scrollBehavior.state.heightOffsetLimit != limit) {
        SideEffect { scrollBehavior.state.heightOffsetLimit = limit }
    }
    val fraction: () -> Float = { if (large) scrollBehavior?.state?.collapsedFraction ?: 0f else 1f }
    // Held still while its page moves, and its items popping in and out as
    // its page comes and goes - iOS's bar belongs to the stack, not the page.
    val motion = LocalLiquidPageMotion.current
    // What leaves goes first and what arrives follows it, so two screens'
    // buttons are never both in the bar at once.
    val presence = rememberLiquidPresence(enterDelayMillis = 110)
    // With a host, the bar's row is drawn in the host's overlay over every
    // screen, and this screen keeps only its large title and its edge: so it
    // moves with the screen, and the row does not move at all.
    val host = LocalLiquidBarHost.current
    val barShift: () -> Float = { if (host != null) 0f else motion?.barShift?.invoke() ?: 0f }
    if (host != null) {
        val backdrop = LocalLiquidBackdrop.current
        val entry = remember(host) {
            LiquidBarEntry(
                order = host.nextOrder(),
                presence = presence,
                navigationIcon = mutableStateOf(navigationIcon),
                actions = mutableStateOf(actions),
                title = mutableStateOf(title),
                subtitle = mutableStateOf(if (large) null else subtitle),
                status = mutableStateOf(status),
                collapsed = mutableStateOf(fraction),
                large = mutableStateOf(large),
                backdrop = mutableStateOf(backdrop),
            )
        }
        SideEffect {
            (entry.navigationIcon as MutableState).value = navigationIcon
            (entry.actions as MutableState).value = actions
            (entry.title as MutableState).value = title
            (entry.subtitle as MutableState).value = if (large) null else subtitle
            (entry.status as MutableState).value = status
            (entry.collapsed as MutableState).value = fraction
            (entry.large as MutableState).value = large
            (entry.backdrop as MutableState).value = backdrop
        }
        val id = remember { Any() }
        DisposableEffect(host, id) {
            host.entries[id] = entry
            onDispose { host.entries.remove(id) }
        }
    }

    Layout(
        modifier = modifier
            .graphicsLayer { translationX = barShift() * size.width }
            // The scroll edge effect: the page behind fades into the bar's
            // background, reaching a little below it, once anything of the
            // page has slid underneath.
            .drawBehind {
                val f = fraction()
                val fade = 24.dp.toPx()
                val here = presence.value.coerceIn(0f, 1f)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to colors.background.copy(alpha = (if (large) 0.5f + 0.5f * f else 1f) * here),
                        (size.height / (size.height + fade)) to colors.background.copy(alpha = (if (large) 0.4f + 0.52f * f else 0.92f) * here),
                        1f to colors.background.copy(alpha = 0f),
                        endY = size.height + fade,
                    ),
                    size = Size(size.width, size.height + fade),
                )
            }
            // Not clipped: the glass buttons' soft shadows would be cut off
            // square. The large title needs no clip - it has faded out by the
            // time it rides up under the bar.
            .windowInsetsPadding(windowInsets),
        content = {
            // The bar's own row, here only where there is no host to draw it.
            if (host != null) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(LiquidDimens.BarHeight))
            } else {
                // The bar's own row, drawn the way the host draws everyone's.
                LiquidBarRow(
                    presence = presence,
                    navigationIcon = navigationIcon,
                    actions = actions,
                    title = title,
                    subtitle = if (large) null else subtitle,
                    titleShown = { if (large) ((fraction() - 0.55f) / 0.45f).coerceIn(0f, 1f) else 1f },
                    colors = colors,
                    status = status,
                )
            }
            // The large title.
            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .graphicsLayer {
                        val f = fraction()
                        alpha = (1f - f * 1.6f).coerceIn(0f, 1f)
                    },
                contentAlignment = Alignment.BottomStart,
            ) {
                CompositionLocalProvider(LocalContentColor provides colors.label) {
                    ProvideTextStyle(LiquidType.LargeTitle) { OneLine { title() } }
                }
            }
        },
    ) { measurables, constraints ->
        val offset = if (large) scrollBehavior?.state?.heightOffset ?: 0f else 0f
        val width = constraints.maxWidth
        val height = (barPx + largePx + offset).roundToInt().coerceIn(barPx, barPx + largePx)
        val bar = measurables[0].measure(Constraints.fixed(width, barPx))
        val title = measurables[1].measure(Constraints(maxWidth = width, maxHeight = largePx.coerceAtLeast(0)))
        layout(width, height) {
            bar.placeRelative(0, 0)
            if (large) {
                // Riding up under the bar as it folds - and, being the page's
                // own, moving with the page rather than staying with the bar.
                title.placeRelativeWithLayer(0, (barPx + offset + (largePx - title.height) - 4.dp.roundToPx()).roundToInt()) {
                    translationX = -barShift() * width
                }
            }
        }
    }
}

/**
 * Whatever [content] puts here, grouped on one piece of glass: a circle for
 * one button, a capsule for several - and nothing at all for none, since an
 * empty row has no size to draw glass on. When a button comes or goes the
 * glass reshapes itself around what is left, growing from nothing or
 * shrinking away, rather than jumping.
 */
@Composable
fun LiquidGlassCluster(
    modifier: Modifier = Modifier,
    /** The glass's wash; the theme's for a button when unspecified. */
    surface: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalLiquidColors.current
    val wash = if (surface == Color.Unspecified) colors.buttonGlass else surface
    val backdrop = LocalLiquidBackdrop.current
    val scope = rememberCoroutineScope()
    val light = remember(scope) { LiquidPressHighlight(scope) }
    Row(
        modifier = modifier.resizeSmoothly().then(if (backdrop != null) {
            // Real glass, bending the page scrolling under the bar.
            Modifier
                .then(light.gestureModifier)
                .liquidButtonGlass(backdrop, wash, frost = colors.frost, layerBlock = { liquidGel(light) })
                .then(light.modifier)
                .clip(Capsule())
        } else {
            Modifier
                .shadow(
                    elevation = 8.dp,
                    shape = CapsuleShape,
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                )
                .liquidGlassPlain(CapsuleShape, colors.glassTint(1.7f))
                .clip(CapsuleShape)
        }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (contentColor == Color.Unspecified) colors.label else contentColor,
        ) { content() }
    }
}

/** A title squeezed into the bar keeps to one line. */
@Composable
private fun OneLine(content: @Composable () -> Unit) {
    val lineHeight = with(LocalDensity.current) { LocalTextStyle.current.lineHeight.toDp() }
    Box(
        modifier = Modifier
            .heightIn(max = lineHeight)
            // One line, cut above and below only - see LiquidOneLine. Cut all
            // round, the large title sliding in or out as the folder changed
            // lost its first letters or its last: "rowser", "Br".
            .drawWithContent {
                clipRect(left = -size.width * 4f, top = 0f, right = size.width * 5f, bottom = size.height) {
                    this@drawWithContent.drawContent()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Follows its content's size on a spring, the way animateContentSize does,
 * but without clipping: the glass it holds swells past its bounds when
 * pulled, and a clip would cut that off square.
 */
private fun Modifier.resizeSmoothly(): Modifier = composed {
    val scope = rememberCoroutineScope()
    val spec = remember { spring<Float>(dampingRatio = 0.72f, stiffness = 380f) }
    val width = remember { androidx.compose.animation.core.Animatable(-1f) }
    val height = remember { androidx.compose.animation.core.Animatable(-1f) }
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val targetW = placeable.width.toFloat()
        val targetH = placeable.height.toFloat()
        if (width.value < 0f) {
            scope.launch {
                width.snapTo(targetW)
                height.snapTo(targetH)
            }
        } else if (width.targetValue != targetW || height.targetValue != targetH) {
            scope.launch { width.animateTo(targetW, spec) }
            scope.launch { height.animateTo(targetH, spec) }
        }
        val w = if (width.value < 0f) placeable.width else width.value.roundToInt().coerceAtLeast(0)
        val h = if (height.value < 0f) placeable.height else height.value.roundToInt().coerceAtLeast(0)
        layout(w, h) {
            placeable.place((w - placeable.width) / 2, (h - placeable.height) / 2)
        }
    }
}
