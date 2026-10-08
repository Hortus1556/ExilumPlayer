package com.hortus1556.exilum.ui.oneui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * How much of the screen's height an expanded app bar takes: Samsung's own
 * figure, which puts the title in the top third - out of reach, where it is
 * only read - and everything to be tapped below it, where the thumb is.
 */
private const val EXPANDED_PROPORTION = 0.3967f

/** Screens shorter than this have no room to spare for an expanded title. */
private val MinHeightToExpand = 560.dp

/**
 * One UI's app bar. With a [scrollBehavior] it opens expanded: a big centred
 * title in the top third of the screen, over a toolbar row with the back
 * button and the actions. Scrolling folds the title away into the toolbar,
 * and letting go part way snaps it open or shut, never leaving it half way.
 * In landscape, or on a short screen, and without a [scrollBehavior], only
 * the toolbar row is shown.
 *
 * The back button and the actions float in round buttons of their own, as in
 * One UI 8.5.
 *
 * The [scrollBehavior] should be an exit-until-collapsed one (see
 * rememberAppBarScrollBehavior), wired to the page with nestedScroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneUiTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val colors = LocalOneUiColors.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val expandable = scrollBehavior != null &&
        configuration.orientation != Configuration.ORIENTATION_LANDSCAPE &&
        screenHeight >= MinHeightToExpand

    val collapsedHeight = OneUiDimens.ToolbarHeight
    val expandedHeight = if (expandable) {
        (screenHeight * EXPANDED_PROPORTION).coerceAtLeast(collapsedHeight)
    } else {
        collapsedHeight
    }
    val collapsedPx = with(density) { collapsedHeight.roundToPx() }
    val expandedPx = with(density) { expandedHeight.roundToPx() }

    // How far the bar may fold: all of the expanded part, none when there is
    // no expanded part at all.
    val limit = (collapsedPx - expandedPx).toFloat()
    if (scrollBehavior != null && scrollBehavior.state.heightOffsetLimit != limit) {
        SideEffect { scrollBehavior.state.heightOffsetLimit = limit }
    }

    // Read in the layout and draw lambdas only, so folding the bar lays it
    // out again without recomposing it.
    val fraction: () -> Float = {
        if (expandable) scrollBehavior?.state?.collapsedFraction ?: 0f else 1f
    }

    Layout(
        modifier = modifier
            .background(colors.background)
            .windowInsetsPadding(windowInsets)
            .clipToBounds(),
        content = {
            // The expanded title: gone by the time the bar is half folded.
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .graphicsLayer {
                        val f = fraction()
                        alpha = (1f - f * 2f).coerceIn(0f, 1f)
                        val scale = 1f - f * 0.08f
                        scaleX = scale
                        scaleY = scale
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompositionLocalProvider(LocalContentColor provides colors.text) {
                    ProvideTextStyle(OneUiType.ExpandedTitle.copy(textAlign = TextAlign.Center)) {
                        title()
                    }
                }
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    CompositionLocalProvider(LocalContentColor provides colors.textSecondary) {
                        ProvideTextStyle(OneUiType.ExpandedSubtitle.copy(textAlign = TextAlign.Center)) {
                            subtitle()
                        }
                    }
                }
            }

            // The toolbar row, always there.
            Row(
                modifier = Modifier
                    .height(collapsedHeight)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Round, and only as big as what is in it: with no back
                // button there is nothing to draw a circle behind.
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.floatingButton),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides colors.text) {
                        navigationIcon()
                    }
                }
                // The collapsed title only fades in near the end of the fold,
                // once the big one is long gone.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .graphicsLayer {
                            alpha = ((fraction() - 0.6f) / 0.4f).coerceIn(0f, 1f)
                        },
                ) {
                    Column {
                        CompositionLocalProvider(LocalContentColor provides colors.text) {
                            ProvideTextStyle(OneUiType.CollapsedTitle) {
                                SingleLine { title() }
                            }
                        }
                        // With no expanded title to carry it, the subtitle
                        // goes under the toolbar's.
                        if (!expandable && subtitle != null) {
                            CompositionLocalProvider(LocalContentColor provides colors.textSecondary) {
                                ProvideTextStyle(OneUiType.ExpandedSubtitle) {
                                    SingleLine { subtitle() }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.floatingButton),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalContentColor provides colors.text) {
                        actions()
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val offset = if (expandable) scrollBehavior?.state?.heightOffset ?: 0f else 0f
        val width = constraints.maxWidth
        val height = (expandedPx + offset).roundToInt().coerceIn(collapsedPx, expandedPx)
        val titleArea = expandedPx - collapsedPx

        val toolbar = measurables[1].measure(
            Constraints.fixed(width, collapsedPx)
        )
        val expandedTitle = if (titleArea > 0) {
            measurables[0].measure(Constraints(maxWidth = width, maxHeight = titleArea))
        } else {
            null
        }

        layout(width, height) {
            // Slightly below the middle of its area, the way Samsung sits it,
            // and carried up with the fold.
            expandedTitle?.placeRelative(
                x = (width - expandedTitle.width) / 2,
                y = (offset + (titleArea - expandedTitle.height) * 0.58f).roundToInt(),
            )
            toolbar.placeRelative(0, height - collapsedPx)
        }
    }
}

/**
 * A title squeezed into the toolbar keeps to one line. Titles arrive as
 * whatever Text the screen built, so their line count cannot be set from
 * here; clipping to one line's height does the same job, and since text
 * wraps between words what is left showing is whole words.
 */
@Composable
private fun SingleLine(content: @Composable () -> Unit) {
    val lineHeight = with(LocalDensity.current) { LocalTextStyle.current.lineHeight.toDp() }
    Box(
        modifier = Modifier
            .height(lineHeight)
            .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}
