package com.hortus1556.exilum.ui.screens.player.components.views

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.withTimeout

/**
 * How much further than the system touch slop a finger may travel and still
 * count as a tap.
 *
 * The system value is tuned for scrolling lists, where treating a sloppy touch
 * as a drag costs nothing. Here a tap toggles a mute on a target the width of
 * one pattern column, and the same sloppiness silently turned it into a pan of
 * a few pixels instead - which is what made muting feel like it only worked
 * sometimes.
 */
private const val TAP_SLOP_FACTOR = 3f

/**
 * Tap, long press and pan/zoom from a single gesture loop.
 *
 * These used to be two separate `pointerInput` modifiers, `detectTapGestures`
 * over `detectTransformGestures`, each deciding on its own what the finger was
 * doing. They disagreed: any touch that wandered past the system slop was a
 * pan to one and a cancelled tap to the other, so the tap was simply lost.
 * Deciding once, here, is what makes a tap land every time.
 *
 * [onTransform] is called with the pan and zoom since the previous event, and
 * only once the gesture has committed to being a transform - which it does on
 * a second finger, or on travel past the tap slop above.
 */
internal suspend fun PointerInputScope.detectViewGestures(
    onTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    onTransform: (pan: Offset, zoom: Float) -> Unit,
    onGestureStart: () -> Unit = {},
    onFling: (Velocity) -> Unit = {},
) {
    val velocity = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onGestureStart()
        velocity.resetTracking()
        val slop = viewConfiguration.touchSlop * TAP_SLOP_FACTOR

        var travelled = 0f
        var transforming = false
        var longPressed = false
        var lifted = false
        var cancelled = false

        // First, up to the long-press timeout, work out which of the three
        // this is. withTimeout throws from inside awaitPointerEvent when it
        // expires, which is the same mechanism detectTapGestures uses.
        try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    // Someone outside took the gesture - the system's back
                    // swipe is the one that matters here. The pointer stops
                    // being pressed without ever having been lifted, and
                    // without this that reads as a finger down and up in the
                    // same place: a tap, muting whatever channel the back
                    // gesture happened to start over.
                    if (event.changes.any { it.isConsumed }) {
                        cancelled = true
                        return@withTimeout
                    }
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) {
                        lifted = true
                        return@withTimeout
                    }
                    if (pressed > 1) {
                        transforming = true
                        return@withTimeout
                    }
                    velocity.addPointerInputChange(event.changes[0])
                    travelled += event.changes[0].positionChange().getDistance()
                    if (travelled > slop) {
                        transforming = true
                        return@withTimeout
                    }
                }
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            // Held still and long enough: a long press, and the rest of the
            // gesture is ignored so a wobbling finger cannot also pan.
            longPressed = true
            onLongPress(down.position)
        }

        var oneFinger = true
        if (!lifted && !cancelled) {
            while (true) {
                val event = awaitPointerEvent()
                // Checked before this pass consumes anything of its own, so
                // what it finds is always somebody else's claim on the gesture.
                if (!transforming && event.changes.any { it.isConsumed }) {
                    cancelled = true
                    break
                }
                if (event.changes.none { it.pressed }) break

                if (longPressed) continue

                val pressed = event.changes.count { it.pressed }
                if (pressed > 1) {
                    transforming = true
                    // A pinch is not a fling, and its samples would poison the
                    // velocity of whatever single-finger drag came before it.
                    oneFinger = false
                    velocity.resetTracking()
                } else {
                    oneFinger = true
                    velocity.addPointerInputChange(event.changes[0])
                    if (!transforming) {
                        travelled += event.changes[0].positionChange().getDistance()
                        if (travelled > slop) transforming = true
                    }
                }

                if (transforming) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) onTransform(pan, zoom)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        }

        when {
            cancelled || longPressed -> Unit
            !transforming -> onTap(down.position)
            oneFinger -> onFling(velocity.calculateVelocity())
        }
    }
}
