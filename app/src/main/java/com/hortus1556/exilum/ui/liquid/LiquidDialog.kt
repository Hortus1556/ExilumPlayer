package com.hortus1556.exilum.ui.liquid

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.rememberCoroutineScope
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.SubcomposeLayout
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Hands the window a dialog or a sheet lives in over to what Compose draws
 * in it.
 *
 * Android animates a window of its own accord - the theme's animation, on
 * the whole square window, dim and all - and Compose then brings the content
 * in on top of that with its own. So every window in the app opened as a
 * square that smoothed its corners afterwards: two animations, one of them
 * the wrong shape.
 *
 * There is nothing behind what Compose draws now, and nothing animates the
 * window itself: what opens is the rounded thing, from its first frame.
 */
@Composable
fun OwnWindowAnimation() {
    val view = LocalView.current
    // In composition, not in an effect. An effect runs after the frame it
    // belongs to has been drawn, and one frame is all it takes: the window
    // comes up with the theme's own background, an opaque square, and the
    // rounded thing is drawn on top of it - so every window in the app was
    // seen as a square first and rounded a moment later.
    remember(view) {
        dialogWindow(view)?.apply {
            setWindowAnimations(0)
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        true
    }
}

/**
 * The window a dialog or a sheet lives in, from a view inside it.
 *
 * Up the tree rather than one step: a dialog hands its window to the view
 * right above the content, a bottom sheet to one further up again, and a
 * cast that only tried the parent silently did nothing for half of them.
 */
fun dialogWindow(view: android.view.View): android.view.Window? {
    var parent: android.view.ViewParent? = view.parent
    while (parent != null) {
        (parent as? DialogWindowProvider)?.let { return it.window }
        parent = (parent as? android.view.View)?.parent
    }
    return null
}

/**
 * iOS 26's alert: a slab of glass in the middle of the screen with deep
 * continuous corners, what it says centred, and the actions as capsules side
 * by side at the bottom - the preferred one filled with the tint, the other
 * gray. It pops in from slightly larger, over a lightly dimmed page it is
 * genuinely see-through to.
 *
 * Drawn in the app's own window wherever there is a host for it, which is
 * everywhere under Liquid Glass: that is what lets it bend the page behind
 * it instead of being a flat panel over a uniformly blurred screen, and what
 * makes it open as the rounded thing it is. The Dialog below is the fallback
 * for the other styles, which have no such host.
 *
 * The buttons come in as whatever the caller built (a TextButton): each is
 * stretched to fill its capsule, so the whole capsule answers a tap, and its
 * text is recoloured for the capsule it sits in.
 */
@Composable
fun LiquidAlert(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val host = LocalLiquidOverlayHost.current
    if (host != null) {
        LiquidOverlayWindow(
            host = host,
            onDismiss = onDismissRequest,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            dismissOnBack = properties.dismissOnBackPress,
        ) {
            AlertPane(
                modifier = modifier,
                dismissButton = dismissButton,
                confirmButton = confirmButton,
                title = title,
                text = text,
            )
        }
        return
    }
    val colors = LocalLiquidColors.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        OwnWindowAnimation()
        val view = LocalView.current
        remember(view, colors.isDark) {
            dialogWindow(view)?.apply {
                setDimAmount(if (colors.isDark) 0.4f else 0.2f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    attributes = attributes.apply { blurBehindRadius = 40 }
                }
            }
            true
        }
        val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(properties.dismissOnClickOutside) {
                    detectTapGestures { if (properties.dismissOnClickOutside) onDismissRequest() }
                }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 36.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visibleState = shown,
                enter = scaleIn(
                    initialScale = 1.12f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                ) + fadeIn(tween(durationMillis = 140)),
            ) {
                AlertPane(
                    modifier = modifier,
                    dismissButton = dismissButton,
                    confirmButton = confirmButton,
                    title = title,
                    text = text,
                )
            }
        }
    }
}

/** The slab itself: glass, what it says, and the actions along the bottom. */
@Composable
private fun AlertPane(
    modifier: Modifier,
    dismissButton: (@Composable () -> Unit)?,
    confirmButton: @Composable () -> Unit,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
) {
    val colors = LocalLiquidColors.current
    // Real glass where there is anything to bend: the page the alert was
    // opened from is still being drawn behind it, so it can be looked
    // through rather than merely painted to look as though it could.
    LiquidPane(
        shape = ContinuousShape(LiquidDimens.AlertCorner),
        modifier = modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth(),
        shadow = 24.dp,
    ) {
        Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 16.dp)) {
            // iOS centres what an alert says. The centring is the text's own,
            // over the whole width, rather than the column's: a list of things to
            // pick from fills the alert as it should, instead of being squeezed
            // into the middle.
            if (title != null) {
                CompositionLocalProvider(LocalContentColor provides colors.label) {
                    ProvideTextStyle(
                        LiquidType.Headline.copy(textAlign = TextAlign.Center),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) { title() }
                    }
                }
                Spacer(modifier = Modifier.height(if (text != null) 8.dp else 4.dp))
            }
            if (text != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides colors.label) {
                        ProvideTextStyle(
                            LiquidType.Subheadline.copy(textAlign = TextAlign.Center),
                        ) { text() }
                    }
                }
            }
            AlertActions(dismissButton = dismissButton, confirmButton = confirmButton)
        }
    }
}
/**
 * The alert's actions as iOS 26 lays them out: side by side in capsules of
 * equal width, cancel gray and the action in the tint - or one capsule across
 * the whole alert when there is only one. A button slot the caller left
 * empty gets no capsule at all.
 */
@Composable
private fun AlertActions(
    dismissButton: (@Composable () -> Unit)?,
    confirmButton: @Composable () -> Unit,
) {
    val colors = LocalLiquidColors.current
    val gap = 10.dp
    val height = 50.dp
    // One light per capsule, lit from here. They used to watch from the
    // capsule itself - but the capsule lies under the caller's button, and
    // of two things stacked on each other only the top one is ever told of
    // a touch: the capsules never lit. This is the parent of both, and is
    // told of every touch on either.
    val scope = rememberCoroutineScope()
    val lights = remember(scope) { List(2) { LiquidPressHighlight(scope) } }
    // Where the capsules are, for the touch to find its own: how many, how
    // wide each, and the gap between.
    val geometry = remember { IntArray(3) }
    SubcomposeLayout(
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val (count, each, space) = geometry
                if (count == 0) return@awaitEachGesture
                val index = (down.position.x / (each + space)).toInt().coerceIn(0, count - 1)
                val origin = Offset(index * (each + space).toFloat(), 20.dp.toPx())
                val light = lights[index]
                light.press(down.position - origin)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    light.move(change.position - origin)
                }
                light.release()
            }
        },
    ) { constraints ->
        val dismiss = dismissButton?.let {
            subcompose("dismiss") { CapsuleTheme(colors.label, emphasized = false, button = it) }
        }.orEmpty()
        val confirm = subcompose("confirm") { CapsuleTheme(Color.White, emphasized = true, button = confirmButton) }
        val slots = buildList {
            if (dismiss.isNotEmpty()) add(colors.tertiaryFill to dismiss)
            if (confirm.isNotEmpty()) add(colors.tint to confirm)
        }
        val width = constraints.maxWidth
        val h = height.roundToPx()
        geometry[0] = 0
        if (slots.isEmpty()) return@SubcomposeLayout layout(width, 0) {}
        val space = gap.roundToPx()
        val each = (width - space * (slots.size - 1)) / slots.size
        geometry[0] = slots.size
        geometry[1] = each
        geometry[2] = space
        val fixed = Constraints.fixed(each, h)
        // The gap above the capsules is theirs: an alert with none - a list
        // to pick from - ends where its list does.
        val above = 20.dp.roundToPx()
        val placed = slots.mapIndexed { index, (fill, buttons) ->
            val capsule = subcompose("fill_$index") {
                Box(
                    modifier = Modifier
                        .clip(CapsuleShape)
                        .background(fill)
                        .then(lights[index].modifier),
                )
            }.map { it.measure(fixed) }
            capsule + buttons.map { it.measure(fixed) }
        }
        layout(width, above + h) {
            placed.forEachIndexed { index, placeables ->
                val x = index * (each + space)
                placeables.forEach { it.place(x, above) }
            }
        }
    }
}

/**
 * The caller's button with its text in [textColor] and iOS's type - the
 * theme it is drawn under is changed for it, so a stock TextButton picks
 * them up without knowing.
 */
@Composable
private fun CapsuleTheme(textColor: Color, emphasized: Boolean, button: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme.copy(primary = textColor)
    val type = MaterialTheme.typography.let {
        it.copy(labelLarge = LiquidType.Body.copy(fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal))
    }
    MaterialTheme(colorScheme = scheme, typography = type, shapes = MaterialTheme.shapes, content = button)
}
