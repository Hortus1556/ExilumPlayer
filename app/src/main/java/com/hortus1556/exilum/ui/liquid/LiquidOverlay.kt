package com.hortus1556.exilum.ui.liquid

import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.material3.LocalContentColor
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.ColumnScope
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.flow.first

/**
 * Where a window opened under Liquid Glass actually lives.
 *
 * Not in a window of Android's. An alert or a menu used to be a Dialog or a
 * Popup, which is a second window on top of the app's, and that is why none
 * of them were ever really glass: the backdrop everything here bends is a
 * layer recorded while the app's own window draws, and a different window
 * cannot reach into it. What was seen instead was the page blurred uniformly
 * by FLAG_BLUR_BEHIND with a flat panel laid over it - no bending at the rim,
 * no magnification, nothing that moves when what is behind it moves. The
 * opening was Android's too: a square window fading up under the rounded
 * thing Compose then drew on it.
 *
 * So they are drawn here instead, in the app's own window, above everything
 * and inside the composition the backdrop belongs to. They get the real
 * shader, they light up under a finger like every other piece of glass, and
 * what opens is the rounded thing from its first frame because there is no
 * window to open.
 */
@Stable
class LiquidOverlayHost {
    internal val entries = mutableStateListOf<LiquidOverlayEntry>()

    internal fun add(entry: LiquidOverlayEntry) {
        entries += entry
    }

    internal fun remove(entry: LiquidOverlayEntry) {
        entries -= entry
    }

    /** Drops every window: for when nothing is left to draw them. */
    fun clear() {
        entries.clear()
    }
}

/**
 * One window on the stack.
 *
 * Everything about it is state rather than constructor arguments: the caller
 * stays composed where it is and keeps writing to this, so the content it
 * hands over is never a frame stale, and what is drawn is drawn from here -
 * which is what lets it finish fading out after the caller has gone.
 */
@Stable
class LiquidOverlayEntry {
    internal val visible = MutableTransitionState(false)
    internal var retiring by mutableStateOf(false)
    internal var scrim by mutableStateOf(true)
    internal var dismissOnClickOutside by mutableStateOf(true)
    internal var dismissOnBack by mutableStateOf(true)
    internal var onDismiss: () -> Unit by mutableStateOf({})

    /** Rises from the bottom edge instead of opening in place. */
    internal var sheet by mutableStateOf(false)

    /** Where it hangs from, in the root's coordinates; null centres it. */
    internal var anchor: Rect? by mutableStateOf(null)
    internal var content: @Composable () -> Unit by mutableStateOf({})
}

/** The host for the window being opened, or null under the other styles. */
val LocalLiquidOverlayHost = compositionLocalOf<LiquidOverlayHost?> { null }

/**
 * Puts [content] on [host] for as long as this is composed, and lets it fade
 * out afterwards.
 */
@Composable
fun LiquidOverlayWindow(
    host: LiquidOverlayHost,
    onDismiss: () -> Unit,
    anchor: Rect? = null,
    scrim: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    dismissOnBack: Boolean = true,
    sheet: Boolean = false,
    content: @Composable () -> Unit,
) {
    val entry = remember { LiquidOverlayEntry() }
    entry.sheet = sheet
    entry.onDismiss = onDismiss
    entry.anchor = anchor
    entry.scrim = scrim
    entry.dismissOnClickOutside = dismissOnClickOutside
    entry.dismissOnBack = dismissOnBack
    entry.content = content
    DisposableEffect(host) {
        host.add(entry)
        onDispose {
            // Not removed here: it has an exit to play, and the layer takes
            // it off the stack once that has finished.
            entry.retiring = true
            entry.visible.targetState = false
        }
    }
}

/**
 * Draws everything on [host], over the app and over each other, with
 * [backdrop] - what the app drew this frame - as what their glass bends.
 */
@Composable
fun LiquidOverlayLayer(host: LiquidOverlayHost, backdrop: Backdrop) {
    val colors = LocalLiquidColors.current
    var rootHeight by remember { mutableIntStateOf(0) }
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { rootHeight = it.height })
    // What the next window up bends: the app, and every window under it.
    // Each one is recorded as it draws and added to this, so a menu opened
    // on a sheet bends the sheet - not the app behind it, which would have
    // made the menu a hole cut through the sheet.
    var below: Backdrop = backdrop
    host.entries.forEachIndexed { index, entry ->
        key(entry) {
            val own = rememberLayerBackdrop()
            val bends = below
            LaunchedEffect(entry) {
                // A window already closed is never opened again. The layer
                // stops being drawn when the style changes - which is what
                // picking a style in its own window does - and a window
                // caught leaving then stayed on the stack: going back to
                // Liquid Glass opened it once more, with nothing left to
                // close it, and its scrim took every touch in the app.
                if (entry.retiring) {
                    host.remove(entry)
                    return@LaunchedEffect
                }
                entry.visible.targetState = true
            }
            LaunchedEffect(entry) {
                snapshotFlow { entry.retiring && entry.visible.isIdle && !entry.visible.currentState }
                    .first { it }
                host.remove(entry)
            }
            // Back closes the topmost one, and only while it is really there.
            val top = index == host.entries.lastIndex
            BackHandler(enabled = top && !entry.retiring && entry.dismissOnBack) {
                entry.onDismiss()
            }
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(own)) {
                // A tap anywhere but on the window itself closes it. Always
                // present, painted only when this window dims what is under
                // it - a menu does not, but it still catches the tap.
                AnimatedVisibility(
                    visibleState = entry.visible,
                    enter = fadeIn(tween(durationMillis = 160)),
                    exit = fadeOut(tween(durationMillis = 140)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (entry.scrim) {
                                    Color.Black.copy(alpha = if (colors.isDark) 0.4f else 0.2f)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .pointerInput(entry) {
                                detectTapGestures {
                                    if (entry.dismissOnClickOutside) entry.onDismiss()
                                }
                            },
                    )
                }
                val anchor = entry.anchor
                val body: @Composable () -> Unit = {
                    // What a Material surface would have given it: out of the
                    // popup that used to hold it, a menu's text and icons fell
                    // back to the root's content colour - black, on dark glass.
                    CompositionLocalProvider(
                        LocalLiquidBackdrop provides bends,
                        LocalContentColor provides colors.label,
                    ) {
                        // The window itself swallows taps, so the catcher
                        // underneath never sees the ones meant for it.
                        Box(modifier = Modifier.pointerInput(Unit) { detectTapGestures { } }) {
                            entry.content()
                        }
                    }
                }
                if (entry.sheet) {
                    // Up from the bottom edge, and never under the status
                    // bar: a sheet as tall as its content, as far as that.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(top = 10.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        AnimatedVisibility(
                            visibleState = entry.visible,
                            enter = slideInVertically(
                                animationSpec = spring(
                                    dampingRatio = 0.9f,
                                    stiffness = 420f,
                                    visibilityThreshold = IntOffset.VisibilityThreshold,
                                ),
                            ) { it },
                            exit = slideOutVertically(animationSpec = tween(durationMillis = 240)) { it },
                            content = { body() },
                        )
                    }
                } else if (anchor == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .imePadding()
                            .padding(horizontal = 36.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedVisibility(
                            visibleState = entry.visible,
                            enter = scaleIn(
                                initialScale = 1.12f,
                                animationSpec = spring(
                                    dampingRatio = 0.78f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) + fadeIn(tween(durationMillis = 140)),
                            exit = scaleOut(targetScale = 0.94f, animationSpec = tween(140)) +
                                fadeOut(tween(durationMillis = 120)),
                            content = { body() },
                        )
                    }
                } else {
                    val upwards = rootHeight > 0 && anchor.center.y > rootHeight / 2f
                    val origin = TransformOrigin(0.5f, if (upwards) 1f else 0f)
                    AnchoredAt(anchor, upwards) {
                        AnimatedVisibility(
                            visibleState = entry.visible,
                            // Out of the thing that opened it, as iOS grows a
                            // menu from the button it belongs to.
                            enter = scaleIn(
                                initialScale = 0.86f,
                                transformOrigin = origin,
                                animationSpec = spring(
                                    dampingRatio = 0.82f,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            ) + fadeIn(tween(durationMillis = 110)),
                            exit = scaleOut(
                                targetScale = 0.9f,
                                transformOrigin = origin,
                                animationSpec = tween(110),
                            ) + fadeOut(tween(durationMillis = 100)),
                            content = { body() },
                        )
                    }
                }
            }
            below = rememberCombinedBackdrop(bends, own)
        }
    }
}

/**
 * Places its content under [anchor] - or over it, when [upwards] or when there
 * is no room below - and never off the screen.
 *
 * Worked out in the root's own coordinates, the ones [anchor] is in. The
 * safe area is kept clear by clamping rather than by padding the box it is
 * placed in: padding shifts that box's origin, and a menu placed by numbers
 * from the root then lands a status bar's height too low - on top of the very
 * button that opened it.
 */
@Composable
private fun AnchoredAt(anchor: Rect, upwards: Boolean, content: @Composable () -> Unit) {
    val insets = WindowInsets.safeDrawing
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val top = insets.getTop(this)
                val bottom = height - insets.getBottom(this)
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxHeight = (bottom - top).coerceAtLeast(0),
                    )
                )
                val pad = 8.dp.roundToPx()
                val gap = 8.dp.roundToPx()
                val below = anchor.bottom.roundToInt() + gap
                val above = anchor.top.roundToInt() - gap - placeable.height
                val fitsBelow = below + placeable.height <= bottom
                val fitsAbove = above >= top
                val y = when {
                    upwards && fitsAbove -> above
                    fitsBelow -> below
                    fitsAbove -> above
                    else -> (bottom - placeable.height).coerceAtLeast(top)
                }
                val wanted = anchor.center.x.roundToInt() - placeable.width / 2
                val x = wanted.coerceIn(
                    pad,
                    (width - placeable.width - pad).coerceAtLeast(pad),
                )
                layout(width, height) { placeable.place(x, y) }
            },
            content = { content() },
        )
    }
}
/**
 * A menu hanging off [anchor] - the bounds, in the root's coordinates, of
 * whatever opened it - on a pane of real glass.
 *
 * Nothing at all where there is no host, so a caller keeps its own
 * DropdownMenu for the styles that are not Liquid Glass.
 */
@Composable
fun LiquidMenu(
    expanded: Boolean,
    anchor: Rect?,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalLiquidOverlayHost.current ?: return
    if (!expanded) return
    LiquidOverlayWindow(
        host = host,
        onDismiss = onDismissRequest,
        anchor = anchor,
        // A menu does not dim the page: iOS leaves it lit and lets the menu
        // sit on it as another piece of glass.
        scrim = false,
    ) {
        LiquidMenuPane(content = content)
    }
}

/**
 * One line of a menu on glass, as iOS 26 draws it: what it does, its symbol
 * at the trailing end, and the light a finger raises on it. [destructive] is
 * in red, as delete is.
 */
@Composable
fun LiquidMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    /**
     * For one of a choice, as iOS marks it: a check before the name when it is
     * the one picked, and the room for one when not, so the names line up.
     * Null for a row that is no choice.
     */
    checked: Boolean? = null,
) {
    val colors = LocalLiquidColors.current
    val light = rememberLiquidRowLight()
    val tint = if (destructive) colors.red else colors.label
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(light.modifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .then(light.gestureModifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            Box(modifier = Modifier.padding(end = 8.dp).size(20.dp), contentAlignment = Alignment.Center) {
                if (checked) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Text(
            text = text,
            style = LiquidType.Body,
            color = tint,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (icon != null) {
            // Some air before the symbol, even in a menu only as wide as its
            // longest line: there the name ran up against it.
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.padding(start = 12.dp).size(20.dp),
            )
        }
    }
}
