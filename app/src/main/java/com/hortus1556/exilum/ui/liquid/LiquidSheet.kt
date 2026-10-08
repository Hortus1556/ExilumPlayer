package com.hortus1556.exilum.ui.liquid

import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// iOS's sheets: a card with the large continuous corners of the screen
// itself, the system's raised gray, and the grabber at the top.

/** The corners of a sheet: the device's own, as iOS draws them. */
val LiquidSheetShape: Shape = ContinuousShape(radius = 34.dp, topStart = true, topEnd = true, bottomEnd = false, bottomStart = false)

/** A sheet's colour in each mode: the raised gray in the dark, white in the light. */
val LiquidColors.sheet: Color
    get() = if (isDark) Color(0xFF1C1C1E) else Color.White

/** The grabber at the top of a sheet. */
@Composable
fun LiquidSheetGrabber() {
    val colors = LocalLiquidColors.current
    Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 5.dp)
                .clip(CapsuleShape)
                .background(colors.tertiaryLabel),
        )
    }
}

/**
 * iOS 26's sheet, in the app's own window: a slab of the same glass as the
 * rest, risen from the bottom edge over a dimmed page, its grabber at the
 * top. Where [dragToDismiss], it is pulled down to close - by anywhere that
 * is not a list, or by a list already at its top - and springs back when it
 * is not pulled far enough.
 *
 * Drawn here rather than as a window of its own for the reasons the alerts
 * are (see LiquidOverlay): the sheets were the last of Android's windows
 * left in the app, and with them the last glass that bent nothing.
 */
@Composable
fun LiquidBottomSheet(
    host: LiquidOverlayHost,
    onDismissRequest: () -> Unit,
    dragToDismiss: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    LiquidOverlayWindow(
        host = host,
        onDismiss = onDismissRequest,
        sheet = true,
    ) {
        SheetPane(
            onDismissRequest = onDismissRequest,
            dragToDismiss = dragToDismiss,
            content = content,
        )
    }
}

@Composable
private fun SheetPane(
    onDismissRequest: () -> Unit,
    dragToDismiss: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalLiquidColors.current
    val scope = rememberCoroutineScope()
    val dismiss = rememberUpdatedState(onDismissRequest)
    // How far it has been pulled down from where it rests.
    var pulled by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    var settling by remember { mutableStateOf<Job?>(null) }
    // Let go: closed if pulled a good part of the way or thrown down,
    // otherwise back up where it was. The rest of a close is the layer's
    // slide, which carries on from wherever the finger left it.
    val release: (Float) -> Unit = remember(scope) {
        { velocity ->
            settling?.cancel()
            if (pulled > height * 0.28f || velocity > 1400f) {
                dismiss.value()
            } else {
                settling = scope.launch {
                    animate(
                        initialValue = pulled,
                        targetValue = 0f,
                        initialVelocity = velocity,
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
                    ) { value, _ -> pulled = value }
                }
            }
        }
    }
    // A list inside it scrolls until it is at its top; past that, the same
    // pull brings the sheet down, and pushing back up raises it again before
    // the list moves at all.
    val connection = remember(scope) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && pulled > 0f) {
                    val used = maxOf(available.y, -pulled)
                    pulled += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    settling?.cancel()
                    pulled += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled > 0f) {
                    release(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    val drag = rememberDraggableState { delta ->
        settling?.cancel()
        pulled = (pulled + delta).coerceAtLeast(0f)
    }
    LiquidPane(
        shape = LiquidSheetShape,
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .onSizeChanged { height = it.height }
            .offset { IntOffset(0, pulled.roundToInt()) }
            .then(
                if (dragToDismiss) {
                    Modifier
                        .nestedScroll(connection)
                        .draggable(
                            state = drag,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity -> release(velocity) },
                        )
                } else {
                    Modifier
                }
            ),
        // Painted, a sheet is iOS's own: the raised gray, or white.
        paint = colors.sheet,
    ) {
        // Up to the keyboard and no further: a field at the bottom of a long
        // sheet - a length to type, under a dozen sliders - was typed into
        // from behind it, the keyboard having risen over it and its button.
        // The glass still runs to the bottom edge.
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            LiquidSheetGrabber()
            content()
        }
    }
}
