package com.hortus1556.exilum.ui.components

import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LiquidNavigationBar
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.ui.oneui.OneUiTopAppBar
import com.hortus1556.exilum.ui.theme.isOneUi

/**
 * How the app bar of a scrolling page reacts to it. Under One UI it folds
 * its expanded title away, and snaps open or shut when let go; under
 * Material Expressive the bar stays put, as it always has.
 *
 * Wire it to the page: `Scaffold(modifier = Modifier.nestedScroll(it.nestedScrollConnection))`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberAppBarScrollBehavior(): TopAppBarScrollBehavior =
    if (isOneUi || isLiquid) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            snapAnimationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        )
    } else {
        // Takes no part in scrolling at all.
        TopAppBarDefaults.pinnedScrollBehavior(canScroll = { false })
    }

/**
 * The top bar of a page, in the style the app is drawn in: One UI's
 * expanding app bar, iOS's large-title navigation bar, or Material's centred
 * one. [scrollBehavior] only
 * matters to One UI - see [rememberAppBarScrollBehavior].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    subtitle: (@Composable () -> Unit)? = null,
    /**
     * What the page has to say, while it has anything: a capsule of its own
     * at the start of the bar under Liquid Glass, the first of the actions in
     * the other styles.
     */
    status: (@Composable () -> Unit)? = null,
) {
    val withStatus: @Composable RowScope.() -> Unit = if (status == null) actions else ({ status(); actions() })
    if (isOneUi) {
        OneUiTopAppBar(
            title = title,
            modifier = modifier,
            subtitle = subtitle,
            navigationIcon = navigationIcon,
            actions = withStatus,
            scrollBehavior = scrollBehavior,
        )
    } else if (isLiquid) {
        LiquidNavigationBar(
            title = title,
            modifier = modifier,
            subtitle = subtitle,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
            status = status,
        )
    } else {
        CenterAlignedTopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = withStatus,
        )
    }
}

/**
 * How much of the bottom of a tab is covered by the bars floating over it -
 * One UI's tab bar and mini player, which the tabs scroll underneath instead
 * of stopping above. Zero when nothing floats.
 */
val LocalFloatingBarInset = compositionLocalOf { 0.dp }

/**
 * A Scaffold's content insets, plus whatever floats over the bottom of it
 * (see [LocalFloatingBarInset]): lists end clear of the floating bars, and
 * buttons and snackbars sit above them.
 */
@Composable
fun appContentWindowInsets(): WindowInsets {
    val floating: Dp = LocalFloatingBarInset.current
    return if (floating > 0.dp) {
        ScaffoldDefaults.contentWindowInsets.add(WindowInsets(bottom = floating))
    } else {
        ScaffoldDefaults.contentWindowInsets
    }
}

/**
 * A Scaffold's content padding less the bottom, for a page that scrolls: the
 * bottom goes at the end of what scrolls instead, so the page runs on under
 * whatever floats over the bottom of it and only its last row stops clear.
 */
@Composable
fun PaddingValues.withoutBottom(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
    )
}
