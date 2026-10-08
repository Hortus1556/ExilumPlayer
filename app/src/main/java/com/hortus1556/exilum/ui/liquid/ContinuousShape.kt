package com.hortus1556.exilum.ui.liquid

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Apple's continuous corners: a rounded rectangle whose corners ease into
 * their curve instead of meeting it at a joint, the way every rounded thing
 * on iOS is drawn. A plain rounded corner is a quarter circle stuck onto a
 * straight edge, and the join shows; this one starts curving well before the
 * circle would, so it reads as one smooth shape - the squircle.
 *
 * The curve is the one iOS draws, taken apart by PaintCode: each corner
 * reaches 1.528665 radii along each edge. A corner asked for more than the
 * sides have room for is drawn as a plain round one instead, as iOS does for
 * a capsule's ends.
 *
 * A CornerBasedShape, so Material's own components can be given it too.
 */
class ContinuousShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    /** [radius] at the corners asked for, square at the others. */
    constructor(
        radius: Dp,
        topStart: Boolean = true,
        topEnd: Boolean = true,
        bottomEnd: Boolean = true,
        bottomStart: Boolean = true,
    ) : this(
        CornerSize(if (topStart) radius else 0.dp),
        CornerSize(if (topEnd) radius else 0.dp),
        CornerSize(if (bottomEnd) radius else 0.dp),
        CornerSize(if (bottomStart) radius else 0.dp),
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = ContinuousShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val tl = if (rtl) topEnd else topStart
        val tr = if (rtl) topStart else topEnd
        val br = if (rtl) bottomStart else bottomEnd
        val bl = if (rtl) bottomEnd else bottomStart
        val half = min(size.width, size.height) / 2f
        if (tl + tr + br + bl == 0f) return Outline.Rectangle(size.toRect())

        // Any corner without room for the continuous curve makes the whole
        // shape a plain rounded one: a capsule, mostly.
        if (maxOf(tl, tr, br, bl) * EXTENT > half) {
            return Outline.Rounded(
                RoundRect(
                    left = 0f, top = 0f, right = size.width, bottom = size.height,
                    topLeftCornerRadius = CornerRadius(min(tl, half)),
                    topRightCornerRadius = CornerRadius(min(tr, half)),
                    bottomRightCornerRadius = CornerRadius(min(br, half)),
                    bottomLeftCornerRadius = CornerRadius(min(bl, half)),
                )
            )
        }

        val w = size.width
        val h = size.height
        val path = Path()
        // The top edge, left to right, then the corners clockwise, each in
        // its own frame: (back along the edge coming in, in from that edge).
        path.moveTo(EXTENT * tl, 0f)
        corner(path, tr) { along, inset -> Offset(w - along, inset) }
        corner(path, br) { along, inset -> Offset(w - inset, h - along) }
        corner(path, bl) { along, inset -> Offset(along, h - inset) }
        corner(path, tl) { along, inset -> Offset(inset, along) }
        path.close()
        return Outline.Generic(path)
    }

    override fun toString(): String = "ContinuousShape($topStart, $topEnd, $bottomEnd, $bottomStart)"

    override fun equals(other: Any?): Boolean =
        other is ContinuousShape && other.topStart == topStart && other.topEnd == topEnd &&
            other.bottomEnd == bottomEnd && other.bottomStart == bottomStart

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return result
    }

    private companion object {
        /** How far along each edge a corner's curve reaches, in radii. */
        const val EXTENT = 1.52866483f

        /** One corner of radius [r]; a square one for 0. */
        fun corner(path: Path, r: Float, at: (Float, Float) -> Offset) {
            if (r <= 0f) {
                val c = at(0f, 0f)
                path.lineTo(c.x, c.y)
                return
            }
            fun line(a: Float, b: Float) = at(a * r, b * r).let { path.lineTo(it.x, it.y) }
            fun curve(a1: Float, b1: Float, a2: Float, b2: Float, a3: Float, b3: Float) {
                val c1 = at(a1 * r, b1 * r)
                val c2 = at(a2 * r, b2 * r)
                val e = at(a3 * r, b3 * r)
                path.cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
            }
            line(1.52866471f, 0f)
            curve(1.08849323f, 0f, 0.86840689f, 0f, 0.66993427f, 0.06549600f)
            line(0.63149399f, 0.07491100f)
            curve(0.37282392f, 0.16905899f, 0.16905899f, 0.37282401f, 0.07491100f, 0.63149399f)
            line(0.06549600f, 0.66993427f)
            curve(0f, 0.86840701f, 0f, 1.08849299f, 0f, 1.52866483f)
        }
    }
}

/** A capsule: round ends, however long. */
val CapsuleShape: Shape = ContinuousShape(1000.dp)
