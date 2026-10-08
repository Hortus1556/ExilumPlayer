package com.hortus1556.exilum.ui.components

import com.hortus1556.exilum.ui.liquid.liquidStackTransition
import com.hortus1556.exilum.ui.liquid.LiquidNavDirection
import com.hortus1556.exilum.ui.theme.isLiquid
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import com.hortus1556.exilum.ui.theme.isOneUi

private typealias Transition<T> = AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform

/** Settles like One UI's pages: quick, and without a bounce. */
private val slide = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset(1, 1),
)
private val fade = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * One UI moving on to a page: the new one slides in from the side over the
 * old, which drifts a quarter of the way the same way and dims under it.
 */
private fun <T : Any> oneUiPush(): Transition<T> = {
    ContentTransform(
        targetContentEnter = slideInHorizontally(slide) { it },
        initialContentExit = slideOutHorizontally(slide) { -it / 4 } + fadeOut(fade, targetAlpha = 0.5f),
        targetContentZIndex = 1f,
    )
}

/** One UI going back: the same, the other way - the page on top slides off. */
private fun <T : Any> oneUiPop(): Transition<T> = {
    ContentTransform(
        targetContentEnter = slideInHorizontally(slide) { -it / 4 } + fadeIn(fade, initialAlpha = 0.5f),
        initialContentExit = slideOutHorizontally(slide) { it },
        targetContentZIndex = -1f,
    )
}

/** iOS's pace: a spring with no bounce that takes about a third of a second. */
private val iosSlide = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 380f,
    visibilityThreshold = IntOffset(1, 1),
)
private val iosFade = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)

/**
 * iOS pushing a page: the new one slides the whole way in from the trailing
 * edge over the old, which slides a third of the way out and darkens under it.
 */
private fun <T : Any> iosPush(): Transition<T> = {
    ContentTransform(
        targetContentEnter = slideInHorizontally(iosSlide) { it },
        initialContentExit = slideOutHorizontally(iosSlide) { -it / 3 } + fadeOut(iosFade, targetAlpha = 0.7f),
        targetContentZIndex = 1f,
    )
}

/** iOS popping a page: the same, backwards - the page on top slides off. */
private fun <T : Any> iosPop(): Transition<T> = {
    ContentTransform(
        targetContentEnter = slideInHorizontally(iosSlide) { -it / 3 } + fadeIn(iosFade, initialAlpha = 0.7f),
        initialContentExit = slideOutHorizontally(iosSlide) { it },
        targetContentZIndex = -1f,
    )
}

/** How a NavDisplay moves on to a page, in the style the app is drawn in. */
@Composable
fun <T : Any> pushTransitionSpec(): Transition<T> = when {
    isOneUi -> oneUiPush()
    isLiquid -> iosPush()
    else -> defaultTransitionSpec()
}

/** How a NavDisplay goes back, in the style the app is drawn in. */
@Composable
fun <T : Any> popTransitionSpec(): Transition<T> = when {
    isOneUi -> oneUiPop()
    isLiquid -> iosPop()
    else -> defaultPopTransitionSpec()
}

/**
 * Going back with the back gesture, which One UI and iOS draw as they do any
 * other going back - on iOS the page follows the finger off to the side.
 */
@Composable
fun <T : Any> predictivePopTransitionSpec(): AnimatedContentTransitionScope<Scene<T>>.(Int) -> ContentTransform {
    val pop: Transition<T> = when {
        isOneUi -> oneUiPop()
        isLiquid -> iosPop()
        else -> return defaultPredictivePopTransitionSpec()
    }
    return { _ -> pop() }
}

/**
 * A stack's push under the style the app is drawn in, for a stack whose
 * pages move themselves under Liquid Glass (see
 * rememberLiquidPageDecorator) - so their bars can stay still - and as
 * [pushTransitionSpec] otherwise.
 */
@Composable
fun <T : Any> pagePushTransitionSpec(direction: LiquidNavDirection): Transition<T> =
    if (isLiquid) liquidStackTransition(direction, push = true) else pushTransitionSpec()

/** The going back that goes with [pagePushTransitionSpec]. */
@Composable
fun <T : Any> pagePopTransitionSpec(direction: LiquidNavDirection): Transition<T> =
    if (isLiquid) liquidStackTransition(direction, push = false) else popTransitionSpec()

/** The back gesture that goes with [pagePushTransitionSpec]. */
@Composable
fun <T : Any> pagePredictivePopTransitionSpec(
    direction: LiquidNavDirection,
): AnimatedContentTransitionScope<Scene<T>>.(Int) -> ContentTransform {
    if (!isLiquid) return predictivePopTransitionSpec()
    val pop = liquidStackTransition<T>(direction, push = false)
    return { _ -> pop() }
}
