package com.hortus1556.exilum.ui.liquid

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pieces of glass that run together where they meet, as two drops of water
 * do - iOS 26's GlassEffectContainer: the tab bar, the mini player and the
 * search folding into one row, a button taking another into itself.
 *
 * Glass drawn piece by piece cannot do it: each piece is a layer of its own,
 * cut to its own shape, and where two of them cross one is simply on top of
 * the other. Here the pieces draw no glass at all. They say where they are,
 * and whatever holds them ([liquidGlassUnion]) draws one sheet of glass for
 * all of them, whose shape is theirs joined by a smooth minimum of their
 * distance fields: apart, each is itself, to the pixel; within reach of
 * each other they lean together and a neck forms between them; overlapping,
 * they are one body, with one rim for the light and the bending to follow.
 *
 * It is a shader, so there is none of it before Android 13 - there the pieces
 * are drawn as they always were, see [rememberLiquidGlassUnion].
 */
@Stable
class LiquidGlassUnion {
    internal val pieces = ArrayList<GlassPiece>()
    internal var sheet: DrawModifierNode? = null

    /** A piece has come, gone, moved or changed: the sheet is drawn again, this frame. */
    internal fun changed() {
        sheet?.invalidateDraw()
    }
}

/** A union for glass to join, or null where the device cannot draw one and each piece is its own glass. */
@Composable
fun rememberLiquidGlassUnion(): LiquidGlassUnion? =
    if (LiquidRefraction.supported) remember { LiquidGlassUnion() } else null

/**
 * For glass that is carried about by something above it - a page's floating
 * action, which the bars move, fade and pop - and belongs to a [union] drawn
 * elsewhere, under it. The sheet finds where such a piece is by itself, but
 * a layer above a piece says nothing when it changes and nothing of how far
 * it has faded: [presence] is that - how much of what is carried is there, 0
 * to 1 - and reads whatever moves it, so that the sheet follows.
 */
@Stable
class LiquidGlassCarrier(val union: LiquidGlassUnion, val presence: () -> Float)

/** What carries the glass under it, where something does: see [LiquidGlassCarrier]. */
val LocalLiquidGlassCarrier = compositionLocalOf<LiquidGlassCarrier?> { null }

/**
 * The two thicknesses of glass: how much each blurs what is behind it - and
 * the least it does where it cannot bend, so that there is glass at all -
 * and how wide the band along its rim that bends, at most and as a part of
 * its shorter side.
 */
internal enum class GlassKind(val blur: Dp, val leastBlur: Dp, val rim: Dp, val rimOfSide: Float) {
    Bar(blur = 8.dp, leastBlur = 6.dp, rim = 24.dp, rimOfSide = 0.3f),
    Button(blur = 2.dp, leastBlur = 4.dp, rim = 12.dp, rimOfSide = 0.25f),
}

/** How far the rim throws what it bends: the same for both kinds. */
internal val GlassBend = 24.dp
internal const val GlassBendOfSide = 0.4f

/**
 * One piece of a union: a capsule, where its node is, moved as its [layerBlock]
 * moves it, and there as much as that and what carries it ([carried]) say.
 */
internal class GlassPiece(
    var surface: Color,
    var tint: Color,
    var kind: GlassKind,
    var carried: (() -> Float)?,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
) {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Draws the glass of every piece that has joined [union], behind what this
 * holds: put on the one parent of them all. [reach] is how near two pieces
 * come before they start to run together, read as the glass is drawn - so a
 * row whose pieces sit a gap apart stays a row of pieces with a reach no
 * greater than the gap, and can be given more of it while they move.
 */
fun Modifier.liquidGlassUnion(
    union: LiquidGlassUnion?,
    backdrop: Backdrop?,
    frost: Float = 1f,
    reach: Density.() -> Float = { 8.dp.toPx() },
): Modifier =
    if (union == null || backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        this
    } else {
        this.then(GlassUnionElement(union, backdrop, frost, reach))
    }

/**
 * Joins [union] in place of drawing glass: what liquidBarGlass and
 * liquidButtonGlass become when they are given one. The layer block still
 * moves what is on the glass, and the sheet moves the piece's part of
 * itself by the same block.
 */
internal fun Modifier.liquidGlassPiece(
    union: LiquidGlassUnion,
    surface: Color,
    tint: Color,
    kind: GlassKind,
    carried: (() -> Float)?,
    layerBlock: (GraphicsLayerScope.() -> Unit)?,
): Modifier = this
    .then(GlassPieceElement(union, surface, tint, kind, carried, layerBlock))
    .then(if (layerBlock != null) Modifier.graphicsLayer(layerBlock) else Modifier)

private class GlassPieceElement(
    val union: LiquidGlassUnion,
    val surface: Color,
    val tint: Color,
    val kind: GlassKind,
    val carried: (() -> Float)?,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
) : ModifierNodeElement<GlassPieceNode>() {
    override fun create() = GlassPieceNode(union, GlassPiece(surface, tint, kind, carried, layerBlock))

    override fun update(node: GlassPieceNode) {
        if (node.union !== union) {
            node.leave()
            node.union = union
            node.join()
        }
        node.piece.surface = surface
        node.piece.tint = tint
        node.piece.kind = kind
        node.piece.carried = carried
        node.piece.layerBlock = layerBlock
        union.changed()
    }

    override fun equals(other: Any?): Boolean =
        other is GlassPieceElement && other.union === union && other.surface == surface && other.tint == tint &&
            other.kind == kind && other.carried == carried && other.layerBlock == layerBlock

    override fun hashCode(): Int {
        var result = union.hashCode()
        result = 31 * result + surface.hashCode()
        result = 31 * result + tint.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + (carried?.hashCode() ?: 0)
        return 31 * result + (layerBlock?.hashCode() ?: 0)
    }
}

/**
 * Where a piece is. A layout node of its own, though it lays nothing out:
 * that gives it coordinates of its own, outside the layer its block moves -
 * the sheet reads the block itself, and so follows what the block reads.
 */
private class GlassPieceNode(
    var union: LiquidGlassUnion,
    val piece: GlassPiece,
) : LayoutModifierNode, LayoutAwareModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {
    override val shouldAutoInvalidate: Boolean = false

    fun join() {
        union.pieces += piece
        // The sheet has no room for more, and a piece it leaves out has no glass at all.
        if (union.pieces.size > MaxPieces) Timber.w("A glass union holds %d pieces: one more than that goes undrawn", MaxPieces)
        union.changed()
    }

    fun leave() {
        union.pieces -= piece
        union.changed()
    }

    override fun onAttach() = join()

    override fun onDetach() {
        leave()
        piece.coordinates = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        piece.coordinates = coordinates
        union.changed()
    }

    // Moved by something above it that did not place it again.
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        piece.coordinates = coordinates
        union.changed()
    }
}

private class GlassUnionElement(
    val union: LiquidGlassUnion,
    val backdrop: Backdrop,
    val frost: Float,
    val reach: Density.() -> Float,
) : ModifierNodeElement<GlassUnionNode>() {
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun create() = GlassUnionNode(union, backdrop, frost, reach)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun update(node: GlassUnionNode) {
        if (node.union !== union) {
            node.release()
            node.union = union
            node.claim()
        }
        node.backdrop = backdrop
        node.frost = frost
        node.reach = reach
        node.invalidateDraw()
    }

    override fun equals(other: Any?): Boolean =
        other is GlassUnionElement && other.union === union && other.backdrop == backdrop && other.frost == frost &&
            other.reach == reach

    override fun hashCode(): Int {
        var result = union.hashCode()
        result = 31 * result + backdrop.hashCode()
        result = 31 * result + frost.hashCode()
        return 31 * result + reach.hashCode()
    }
}

/**
 * The sheet: what is behind the pieces - and a margin round them, for the
 * shadow - saturated and blurred as the library does it for a piece of
 * glass, and then put through [UnionGlassShader], which is where the pieces
 * are.
 *
 * Drawn a group at a time: the pieces near enough to one another to run
 * together, each group in a layer no bigger than it needs. Pieces further
 * apart than the reach have no say in each other's shape, and one layer over
 * all of them - a bar across the foot of the screen and a button above its
 * far end - was the whole of what lies between them blurred and gone through
 * for nothing, every frame the page under it moved. As two groups close on
 * each other they become one, and one sheet of glass.
 *
 * A group has one blur, the most any of its pieces asks for: a button that
 * has run into a bar is as frosted as the bar.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class GlassUnionNode(
    var union: LiquidGlassUnion,
    var backdrop: Backdrop,
    var frost: Float,
    var reach: Density.() -> Float,
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {
    override val shouldAutoInvalidate: Boolean = false

    /** A layer for each group there has been at once: made as they are needed, kept until this goes. */
    private val layers = arrayOfNulls<GraphicsLayer>(MaxPieces)
    private var coordinates: LayoutCoordinates? = null

    /** The shader for each number of pieces, made the first time there are that many. */
    private val shaders = arrayOfNulls<RuntimeShader>(MaxPieces + 1)
    private val moved = LayerMove()
    private val shadow = Shadow.Default
    private val light = HighlightStyle.Default()

    // Every piece there is, as the shader takes them: four numbers each.
    private val allRects = FloatArray(MaxPieces * 4)
    private val allLooks = FloatArray(MaxPieces * 4)
    private val allWashes = FloatArray(MaxPieces * 4)
    private val allTints = FloatArray(MaxPieces * 4)
    private val blurs = FloatArray(MaxPieces)

    /** Which group each piece is in: the first piece of it. */
    private val group = IntArray(MaxPieces)

    // And the pieces of the group being drawn.
    private val rects = FloatArray(MaxPieces * 4)
    private val looks = FloatArray(MaxPieces * 4)
    private val washes = FloatArray(MaxPieces * 4)
    private val tints = FloatArray(MaxPieces * 4)

    fun claim() {
        union.sheet = this
    }

    fun release() {
        if (union.sheet === this) union.sheet = null
    }

    override fun onAttach() = claim()

    override fun onDetach() {
        release()
        val context = requireGraphicsContext()
        for (i in layers.indices) {
            layers[i]?.let { context.releaseGraphicsLayer(it) }
            layers[i] = null
        }
        coordinates = null
    }

    // What is behind it is found from where it is.
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val coordinates = coordinates
        if (coordinates != null && coordinates.isAttached) sheet(coordinates)
        drawContent()
    }

    private fun ContentDrawScope.sheet(coordinates: LayoutCoordinates) {
        val bends = LiquidRefraction.on
        var count = 0
        for (piece in union.pieces) {
            if (count == MaxPieces) break
            val at = piece.coordinates?.takeIf { it.isAttached } ?: continue
            val box = coordinates.localBoundingBoxOf(at, clipBounds = false)
            if (box.width <= 0f || box.height <= 0f) continue
            var centreX = box.center.x
            var centreY = box.center.y
            var halfWidth = box.width / 2f
            var halfHeight = box.height / 2f
            var presence = piece.carried?.invoke()?.coerceIn(0f, 1f) ?: 1f
            piece.layerBlock?.let { block ->
                moved.read(box.size, this, block)
                val pivotX = box.left + box.width * moved.transformOrigin.pivotFractionX
                val pivotY = box.top + box.height * moved.transformOrigin.pivotFractionY
                centreX = pivotX + (centreX - pivotX) * moved.scaleX + moved.translationX
                centreY = pivotY + (centreY - pivotY) * moved.scaleY + moved.translationY
                halfWidth *= moved.scaleX
                halfHeight *= moved.scaleY
                presence *= moved.alpha.coerceIn(0f, 1f)
            }
            // Faded right out: not there, and not to be leaned towards.
            if (presence < 0.004f || halfWidth <= 0f || halfHeight <= 0f) continue
            val kind = piece.kind
            val side = 2f * min(halfWidth, halfHeight)
            val i = count * 4
            allRects[i] = centreX
            allRects[i + 1] = centreY
            allRects[i + 2] = halfWidth
            allRects[i + 3] = halfHeight
            allLooks[i] = side / 2f
            allLooks[i + 1] = presence
            allLooks[i + 2] = min(kind.rim.toPx(), side * kind.rimOfSide)
            // Thrown inwards, as the library's lens throws it.
            allLooks[i + 3] = if (bends) -min(GlassBend.toPx(), side * GlassBendOfSide) else 0f
            piece.surface.into(allWashes, i)
            if (piece.tint == Color.Unspecified) {
                allTints.fill(0f, i, i + 4)
            } else {
                piece.tint.into(allTints, i)
            }
            val radius = kind.blur.toPx() * frost
            blurs[count] = if (bends) radius else max(radius, kind.leastBlur.toPx())
            count++
        }
        if (count == 0) return

        // The groups: two pieces are in one when they are within reach of
        // each other, straight or through others. Apart by the reach or more,
        // neither changes the other's outline by anything.
        val reach = reach().coerceAtLeast(1f)
        for (i in 0 until count) group[i] = i
        for (i in 0 until count) {
            for (j in i + 1 until count) {
                val a = i * 4
                val b = j * 4
                val gapX = max(0f, abs(allRects[a] - allRects[b]) - allRects[a + 2] - allRects[b + 2])
                val gapY = max(0f, abs(allRects[a + 1] - allRects[b + 1]) - allRects[a + 3] - allRects[b + 3])
                if (gapX * gapX + gapY * gapY < (reach + 1f) * (reach + 1f)) {
                    // Into the one that comes first, so that a group is always
                    // named by its first piece - which is where it is drawn from.
                    val from = max(group[i], group[j])
                    val to = min(group[i], group[j])
                    if (from != to) for (k in 0 until count) if (group[k] == from) group[k] = to
                }
            }
        }

        val margin = Margin.toPx()
        // The spread of the blur the library's shadow is given, as the platform works it out.
        val spread = shadow.radius.toPx() * 0.57735f + 0.5f
        val density: Density = this
        var drawn = 0
        for (first in 0 until count) {
            if (group[first] != first) continue
            var members = 0
            var blur = 0f
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (i in first until count) {
                if (group[i] != first) continue
                allRects.copyInto(rects, members * 4, i * 4, i * 4 + 4)
                allLooks.copyInto(looks, members * 4, i * 4, i * 4 + 4)
                allWashes.copyInto(washes, members * 4, i * 4, i * 4 + 4)
                allTints.copyInto(tints, members * 4, i * 4, i * 4 + 4)
                blur = max(blur, blurs[i])
                left = min(left, allRects[i * 4] - allRects[i * 4 + 2])
                top = min(top, allRects[i * 4 + 1] - allRects[i * 4 + 3])
                right = max(right, allRects[i * 4] + allRects[i * 4 + 2])
                bottom = max(bottom, allRects[i * 4 + 1] + allRects[i * 4 + 3])
                members++
            }

            // As much of this node as the group and its shadow take, and no
            // more of what is off it than a margin; on a coarse grid, so that
            // pieces on the move do not give the layer a new size every frame.
            val x0 = floor(max(left - margin, -margin) / Grid).toInt() * Grid
            val y0 = floor(max(top - margin, -margin) / Grid).toInt() * Grid
            val x1 = ceil(min(right + margin, size.width + margin) / Grid).toInt() * Grid
            val y1 = ceil(min(bottom + margin, size.height + margin) / Grid).toInt() * Grid
            if (x1 <= x0 || y1 <= y0) continue

            // Made for exactly as many pieces as there are: a loop in a shader
            // is run to its end for every pixel, whatever it is told to skip.
            val shader = shaders[members]
                ?: RuntimeShader(UnionGlassShader.replace("PIECES", members.toString())).also { shaders[members] = it }
            shader.setFloatUniform("origin", -x0.toFloat(), -y0.toFloat())
            shader.setFloatUniform("rects", rects)
            shader.setFloatUniform("looks", looks)
            shader.setFloatUniform("washes", washes)
            shader.setFloatUniform("tints", tints)
            shader.setFloatUniform("reach", reach)
            shader.setFloatUniform("rim", ceil(0.5.dp.toPx()))
            shader.setFloatUniform("glint", light.color.alpha)
            shader.setFloatUniform(
                "shade",
                shadow.offset.x.toPx(), shadow.offset.y.toPx(), spread, shadow.color.alpha * shadow.alpha,
            )
            val vivid = android.graphics.RenderEffect.createColorFilterEffect(Vibrant)
            val blurred = if (blur > 0f) {
                android.graphics.RenderEffect.createBlurEffect(blur, blur, vivid, Shader.TileMode.CLAMP)
            } else {
                vivid
            }
            val layer = layers[drawn] ?: requireGraphicsContext().createGraphicsLayer().also { layers[drawn] = it }
            drawn++
            // The effect takes the shader as it is now: the next group's is another.
            layer.renderEffect = android.graphics.RenderEffect.createChainEffect(
                android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"),
                blurred,
            ).asComposeRenderEffect()
            layer.record(IntSize(x1 - x0, y1 - y0)) {
                translate(-x0.toFloat(), -y0.toFloat()) {
                    with(backdrop) { drawBackdrop(density, coordinates, null) }
                }
            }
            layer.topLeft = IntOffset(x0, y0)
            drawLayer(layer)
        }
    }
}

private fun Color.into(values: FloatArray, at: Int) {
    values[at] = red
    values[at + 1] = green
    values[at + 2] = blue
    values[at + 3] = alpha
}

/** As many pieces as one sheet takes: the shader's arrays are this long. */
private const val MaxPieces = 8

/** The sheet's layer starts and ends on multiples of this many pixels: the platform sizes a layer's buffer in such steps. */
private const val Grid = 64

/** Round the sheet: the reach of the shadow, below most of all. */
private val Margin = 40.dp

/** The library's vibrancy: half as saturated again. */
private val Vibrant = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })

/**
 * One sheet of glass for up to eight capsules.
 *
 * The shape is the smooth minimum of the pieces' distances, and everything
 * the library draws for one piece is drawn here for that shape: its lens -
 * the same circular profile over the same band, along the gradient of the
 * joined field rather than of one rounded rectangle - the wash or the tint
 * over it, the light along the rim by the angle the rim makes with it, and
 * the shadow, which is the same field a few points lower run through the
 * falloff of a blurred edge. A pixel belongs to each piece by how much nearer
 * it is to it than to the whole, and takes its band, its wash and its
 * presence in that measure: in a neck, a tinted piece shades into a plain one.
 *
 * PIECES is how many of them there are, put in before it is compiled.
 *
 * The rounded rectangle's distance and the lens's profile are the library's
 * own (Kyant0's backdrop, Apache-2.0: its RoundedRectRefractionShader); the
 * smooth minimum is the quadratic one in common use.
 */
private const val UnionGlassShader = """
uniform shader content;
uniform float2 origin;
uniform float4 rects[8];
uniform float4 looks[8];
uniform float4 washes[8];
uniform float4 tints[8];
uniform float reach;
uniform float rim;
uniform float glint;
uniform float4 shade;

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float circleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}

float smin(float a, float b, float k) {
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

float field(float2 p) {
    float d = 1.0e6;
    for (int i = 0; i < PIECES; i++) {
        d = smin(d, sdRoundedRect(p - rects[i].xy, rects[i].zw, looks[i].x), reach);
    }
    return d;
}

float2 slope(float2 p) {
    float2 g = float2(
        field(p + float2(1.0, 0.0)) - field(p - float2(1.0, 0.0)),
        field(p + float2(0.0, 1.0)) - field(p - float2(0.0, 1.0))
    );
    float l = length(g);
    return l > 1.0e-5 ? g / l : float2(0.0);
}

// How much of what is at p is there at all: a piece fading away takes its
// own part of the sheet with it, and none of a piece it lies across.
float presenceAt(float2 p, float sd) {
    float most = 0.0;
    float there = 0.0;
    for (int i = 0; i < PIECES; i++) {
        float di = sdRoundedRect(p - rects[i].xy, rects[i].zw, looks[i].x);
        float w = exp(-max(di - sd, 0.0) * 6.0 / reach);
        most = max(most, w);
        there = max(there, w * looks[i].y);
    }
    return there / max(most, 1.0e-6);
}

float lumOf(half3 c) {
    return dot(c, half3(0.3, 0.59, 0.11));
}

half3 inGamut(half3 c) {
    float l = lumOf(c);
    float n = min(c.r, min(c.g, c.b));
    float x = max(c.r, max(c.g, c.b));
    if (n < 0.0) c = half3(l + (c - l) * l / (l - n));
    if (x > 1.0) c = half3(l + (c - l) * (1.0 - l) / (x - l));
    return c;
}

// The hue of one colour on the saturation and the lightness of another: the Hue blend.
half3 hueOf(half3 src, half3 dst) {
    float n = min(src.r, min(src.g, src.b));
    float x = max(src.r, max(src.g, src.b));
    float s = max(dst.r, max(dst.g, dst.b)) - min(dst.r, min(dst.g, dst.b));
    half3 c = x > n ? half3((src - n) * s / (x - n)) : half3(0.0);
    return inGamut(half3(c + (lumOf(dst) - lumOf(c))));
}

half4 main(float2 coord) {
    float2 p = coord - origin;
    float sd = field(p);
    float cover = clamp(0.5 - sd, 0.0, 1.0);
    half4 result = half4(0.0);

    if (cover > 0.0) {
        float sum = 0.0;
        float band = 0.0;
        float bend = 0.0;
        float4 wash = float4(0.0);
        float washed = 0.0;
        float4 tint = float4(0.0);
        float tinted = 0.0;
        for (int i = 0; i < PIECES; i++) {
            float di = sdRoundedRect(p - rects[i].xy, rects[i].zw, looks[i].x);
            float w = exp(-max(di - sd, 0.0) * 6.0 / reach) * looks[i].y;
            sum += w;
            band += w * looks[i].z;
            bend += w * looks[i].w;
            if (tints[i].a > 0.0) {
                tint += w * tints[i];
                tinted += w;
            } else {
                wash += w * washes[i];
                washed += w;
            }
        }
        sum = max(sum, 1.0e-6);
        band /= sum;
        bend /= sum;

        float depth = -min(sd, 0.0);
        float2 grad = float2(0.0);
        if (depth < max(band, rim + 1.0)) grad = slope(p);

        half4 glass;
        if (depth >= band || bend == 0.0) {
            glass = content.eval(coord);
        } else {
            glass = content.eval(coord + circleMap(1.0 - depth / band) * bend * grad);
        }

        half3 rgb = glass.rgb;
        half3 lit = rgb;
        float coat = 0.0;
        if (washed > 0.0) {
            float4 over = wash / washed;
            lit = mix(rgb, half3(over.rgb), half(over.a));
            coat = over.a * washed / sum;
        }
        if (tinted > 0.0) {
            float4 over = tint / tinted;
            half3 dyed = mix(hueOf(half3(over.rgb), rgb), half3(over.rgb), half(over.a));
            lit = mix(lit, dyed, half(tinted / sum));
            coat += tinted / sum;
        }

        float edge = clamp(rim - depth + 0.5, 0.0, 1.0);
        if (edge > 0.0) {
            lit += half3(half(glint * edge * abs(dot(grad, float2(0.70710678, 0.70710678)))));
        }

        float alpha = mix(float(glass.a), 1.0, clamp(coat, 0.0, 1.0));
        result = half4(min(lit, half3(1.0)), half(alpha)) * half(cover * presenceAt(p, sd));
    }

    if (cover < 1.0 && shade.w > 0.0) {
        float2 q = p - shade.xy;
        float sq = field(q);
        float dark = shade.w / (1.0 + exp(min(1.702 * sq / shade.z, 30.0)));
        result.a += half(dark * presenceAt(q, sq) * (1.0 - cover));
    }
    return result;
}
"""
