package com.hortus1556.exilum.ui.liquid

import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope

// iOS moves between screens with the navigation bar standing still: the
// page underneath slides, and the bar's buttons and title change in place -
// the old ones popping away and the new ones popping up. The bar belongs to
// the stack, not to the page. Here every page brings its own bar, so each
// bar is held still against its page's movement, and pops in or out as its
// page comes or goes.

/**
 * How the page a bar is on is moving.
 *
 * [barShift] is the fraction of the page's width to move the bar by so it
 * stays still on screen, against the page's own movement. [present] is
 * whether the page is the one being shown: its bar's items pop in when it
 * becomes so, and out when it stops being so.
 */
class LiquidPageMotion(
    val barShift: () -> Float,
    val present: Boolean,
    /** The page is on its way in: its bar's items start out of sight and pop in. */
    val entering: Boolean = false,
)

/** The movement of the page around this point; null where nothing moves it. */
val LocalLiquidPageMotion = compositionLocalOf<LiquidPageMotion?> { null }


/**
 * How present this page's bar items and floating buttons are: 1 while it is
 * the page being shown, 0 once it is not, popping between the two.
 */
@Composable
fun rememberLiquidPresence(enterDelayMillis: Int = 0): State<Float> {
    val motion = LocalLiquidPageMotion.current
    val present = motion?.present ?: true
    // A page being pushed is the shown one from its very first frame, and
    // starting its buttons there left nothing to animate: the back button of
    // a page opened in Settings was simply there. One on its way in starts
    // out of sight like one that is not shown, and pops in.
    val presence = remember { Animatable(if (present && motion?.entering != true) 1f else 0f) }
    LaunchedEffect(present) {
        // What is leaving goes first and what arrives follows, so two buttons
        // that share a corner are never both in it at full size - iOS staggers
        // the same way.
        if (present && enterDelayMillis > 0) kotlinx.coroutines.delay(enterDelayMillis.toLong())
        // The same spring both ways, as iOS's bar items come and go.
        presence.animateTo(if (present) 1f else 0f, LiquidPopSpring)
    }
    return presence.asState()
}

/**
 * Pops what it is on in and out with [presence]: growing into place from
 * smaller and coming into focus as it fades in, and the same backwards -
 * iOS 26's materialize.
 */
fun Modifier.liquidPop(presence: State<Float>): Modifier = graphicsLayer {
    val p = presence.value
    val scale = lerp(0.6f, 1f, p)
    scaleX = scale
    scaleY = scale
    alpha = p.coerceIn(0f, 1f)
    val blur = ((0.85f - p.coerceIn(0f, 1f)) / 0.85f).coerceAtLeast(0f) * 3.dp.toPx()
    renderEffect = if (blur > 0.5f && android.os.Build.VERSION.SDK_INT >= 31) {
        // Decal, not the clamp a blur takes by default: clamping repeats the
        // last row of pixels outwards, so where the button's bright rim meets
        // the edge of its layer - at the four points of the compass - it was
        // smeared into a pale nub. Outside the layer there is nothing, which
        // is what it should blur into.
        BlurEffect(blur, blur, TileMode.Decal)
    } else {
        null
    }
}

/** Which way a stack last moved, set by the transitions below as they start. */
class LiquidNavDirection {
    var push: Boolean = true
}

/** iOS's page slide: no bounce, about a third of a second. */
private val pageSlide = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)

/**
 * The stack's own transition, under Liquid Glass: nothing moves at this
 * level, both pages are kept until the pages' own movement - drawn by
 * [rememberLiquidPageDecorator] - is done. The page going on top is drawn
 * over the other.
 */
fun <T : Any> liquidStackTransition(
    direction: LiquidNavDirection,
    push: Boolean,
): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
    direction.push = push
    ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.KeepUntilTransitionsFinished,
        targetContentZIndex = if (push) 1f else -1f,
    )
}

/**
 * Moves each page of a stack the iOS way and holds its bar still.
 *
 * Pushing, the new page slides the whole way in from the trailing edge over
 * the old one, which slides a third of the way out and darkens; going back,
 * the reverse. The page's bar is told how far its page has moved, and moves
 * the same distance back so it does not move at all, and whether its page is
 * the one being shown, so its items pop.
 */
@Composable
fun <T : Any> rememberLiquidPageDecorator(direction: LiquidNavDirection): NavEntryDecorator<T> = remember(direction) {
    NavEntryDecorator(
        decorate = { entry ->
            val scope = LocalNavAnimatedContentScope.current
            val transition = scope.transition
            val offset = transition.animateFloat(transitionSpec = { pageSlide }, label = "page_offset") { state ->
                when (state) {
                    EnterExitState.PreEnter -> if (direction.push) 1f else -1f / 3f
                    EnterExitState.Visible -> 0f
                    EnterExitState.PostExit -> if (direction.push) -1f / 3f else 1f
                }
            }
            val shade = transition.animateFloat(transitionSpec = { pageSlide }, label = "page_shade") { state ->
                when (state) {
                    EnterExitState.PreEnter -> if (direction.push) 0f else 0.14f
                    EnterExitState.Visible -> 0f
                    EnterExitState.PostExit -> if (direction.push) 0.14f else 0f
                }
            }
            val outer = LocalLiquidPageMotion.current
            // The page's background, painted here since the page itself is
            // see-through - but not under the bar's own band at the top, so
            // a page sliding over another never covers the other's bar while
            // its buttons pop.
            val background = androidx.compose.material3.MaterialTheme.colorScheme.background
            // Where the bars are drawn over every page by a host, there is no
            // bar in the page to keep clear of, and the band left unpainted
            // showed the page underneath - darkened by its shade - as a gray
            // slab across the top of every page opened in light mode.
            val band = if (LocalLiquidBarHost.current != null) 0 else WindowInsets.statusBars
                .getTop(androidx.compose.ui.platform.LocalDensity.current) +
                with(androidx.compose.ui.platform.LocalDensity.current) { LiquidDimens.BarHeight.roundToPx() }
            // Remembered, for the same reason as the pages' own: a new one
            // on every recomposition rebuilt every bar that reads it.
            val present = transition.targetState == EnterExitState.Visible && (outer?.present ?: true)
            val entering = transition.currentState == EnterExitState.PreEnter
            val motion = remember(outer, present, entering) {
                LiquidPageMotion(
                    barShift = { (outer?.barShift?.invoke() ?: 0f) - offset.value },
                    present = present,
                    entering = entering,
                )
            }
            Box(
                modifier = Modifier
                    .graphicsLayer { translationX = offset.value * size.width }
                    .drawWithContent {
                        val top = band.toFloat().coerceAtMost(size.height)
                        drawRect(
                            color = background,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                            size = androidx.compose.ui.geometry.Size(size.width, size.height - top),
                        )
                        drawContent()
                        val dim = shade.value
                        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                    },
            ) {
                CompositionLocalProvider(LocalLiquidPageMotion provides motion) { entry.Content() }
            }
        },
    )
}
