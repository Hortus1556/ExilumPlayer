package com.hortus1556.exilum.ui.liquid

import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.ui.oneui.Backdrop
import kotlin.math.ceil

/**
 * Liquid Glass, as a material a surface can be made of.
 *
 * What is behind the surface is taken from a [Backdrop], blurred, and bent
 * at the edges the way a thick lens bends light: near the rim the glass
 * shows what lies just outside it, pulled in and a little split into its
 * colours, so the shape reads as a solid piece of glass rather than a
 * frosted window. Over that goes a wash of [tint], a bright specular edge
 * catching the light from the top, and a faint shade at the bottom.
 *
 * The lens is a RuntimeShader, so it needs Android 13; Android 12 gets the
 * blur and the edge without the bending, and older versions a translucent
 * fill with the edge.
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: Backdrop?,
    shape: Shape,
    cornerRadius: Dp,
    tint: Color,
    blur: Dp = 10.dp,
    refraction: Dp = 14.dp,
    rim: Dp = 18.dp,
    highlight: Float = 1f,
): Modifier {
    val colors = LocalLiquidColors.current
    val canBlur = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blurred = if (canBlur) rememberGraphicsLayer() else null
    val lens = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LensShader.create() else null }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // The last effect built, rebuilt only when what it depends on changes.
    val effectCache = remember { EffectCache() }

    return this
        .then(if (canBlur) Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier)
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = Path().apply { addOutline(outline) }

            if (blurred != null && backdrop != null) {
                // Room around the glass for the blur to draw from and for the
                // rim to reach out and pull in what lies beyond the edge.
                val margin = ceil((blur + refraction).toPx()).toInt()
                val layerSize = IntSize(size.width.toInt() + margin * 2, size.height.toInt() + margin * 2)
                blurred.renderEffect = effectCache.effect(
                    lens = lens,
                    size = size,
                    margin = margin.toFloat(),
                    radius = cornerRadius.toPx().coerceAtMost(size.minDimension / 2f),
                    blur = blur.toPx(),
                    refraction = refraction.toPx(),
                    rim = rim.toPx(),
                    base = colors.background,
                )
                blurred.record(size = layerSize) {
                    val source = backdrop.origin
                    translate(source.x - origin.x + margin, source.y - origin.y + margin) {
                        drawLayer(backdrop.layer)
                    }
                }
                blurred.topLeft = IntOffset(-margin, -margin)
                clipPath(clip) { drawLayer(blurred) }
            }

            clipPath(clip) {
                drawRect(tint)
                // A faint shade towards the bottom: the glass has depth.
                drawRect(
                    brush = Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (colors.isDark) 0.10f else 0.04f),
                    ),
                )
            }
            drawSpecularEdge(outline, colors.isDark, highlight)
        }
}

/**
 * The light on the glass: a bright rim, strongest along the top and fading
 * down the sides, and a softer glint along the bottom where light passing
 * through comes out again.
 */
internal fun DrawScope.drawSpecularEdge(
    outline: androidx.compose.ui.graphics.Outline,
    dark: Boolean,
    strength: Float = 1f,
) {
    val top = (if (dark) 0.34f else 0.85f) * strength
    val bottom = (if (dark) 0.14f else 0.45f) * strength
    drawOutline(
        outline = outline,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = top),
            0.35f to Color.White.copy(alpha = top * 0.25f),
            0.7f to Color.White.copy(alpha = bottom * 0.2f),
            1f to Color.White.copy(alpha = bottom),
        ),
        style = Stroke(width = 1.2.dp.toPx()),
    )
}

/**
 * The soft shadow under something with continuous corners.
 *
 * Not [androidx.compose.ui.draw.shadow] with the shape itself: a continuous
 * corner is a path, and Android cannot cast a shadow from a path - it falls
 * back to the box the thing was laid out in and draws a square one. On a
 * solid pane nobody notices, but a pane that fades in is see-through while
 * it does, and what is seen first is that square. Every window in the app
 * opened as a square that rounded afterwards, and this was why.
 *
 * A plain rounded rectangle of the same radius, then, which Android can cast
 * - the shadow is blurred by [elevation] anyway, and no eye can tell the two
 * curves apart through that. Clipping is left alone: the shape itself is
 * what clips.
 */
fun Modifier.liquidShadow(elevation: Dp, corner: Dp, alpha: Float = 0.25f): Modifier = shadow(
    elevation = elevation,
    shape = RoundedCornerShape(corner),
    clip = false,
    ambientColor = Color.Black.copy(alpha = alpha),
    spotColor = Color.Black.copy(alpha = alpha),
)

/**
 * Glass with nothing behind it to bend - over a plain background, or where a
 * surface has no backdrop to take: a clear-ish tinted body with the same
 * light on its edge.
 */
@Composable
fun Modifier.liquidGlassPlain(shape: Shape, tint: Color, highlight: Float = 1f): Modifier {
    val colors = LocalLiquidColors.current
    return this.drawWithContent {
        val outline = shape.createOutline(size, layoutDirection, this)
        val clip = Path().apply { addOutline(outline) }
        clipPath(clip) {
            drawRect(tint)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (colors.isDark) 0.05f else 0.25f),
                    0.5f to Color.Transparent,
                ),
            )
        }
        drawContent()
        drawSpecularEdge(outline, colors.isDark, highlight)
    }
}

/** The tint glass takes in each mode: clear, lightly whitened or darkened. */
fun LiquidColors.glassTint(opacity: Float = 1f): Color {
    // Scaled by the setting, 0.4 being where these were tuned.
    val setting = glassOpacity / 0.4f
    return if (isDark) {
        Color(0xFF1C1C1E).copy(alpha = (0.42f * opacity * setting).coerceIn(0f, 0.95f))
    } else {
        Color.White.copy(alpha = (0.50f * opacity * setting).coerceIn(0f, 0.95f))
    }
}

/** Builds the glass's render effect, and keeps it until its inputs change. */
private class EffectCache {
    private var key: List<Float>? = null
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    fun effect(
        lens: LensShader?,
        size: androidx.compose.ui.geometry.Size,
        margin: Float,
        radius: Float,
        blur: Float,
        refraction: Float,
        rim: Float,
        base: Color,
    ): androidx.compose.ui.graphics.RenderEffect? {
        val k = listOf(size.width, size.height, margin, radius, blur, refraction, rim, base.red, base.green, base.blue)
        if (k == key) return cached
        key = k
        val blurEffect = if (blur > 0f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null
        val effect = if (lens != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val bent = lens.effect(size.width, size.height, margin, radius, refraction, rim, base)
            if (blurEffect != null) RenderEffect.createChainEffect(bent, blurEffect) else bent
        } else {
            blurEffect
        }
        cached = effect?.asComposeRenderEffect()
        return cached
    }
}

/**
 * The lens: moves each pixel near the rim outwards along the edge's normal,
 * by an amount that climbs steeply towards the edge the way the thickness of
 * a rounded slab of glass falls away, and splits red and blue a little apart
 * there - so the rim shows what lies just past it, magnified and faintly
 * rainbowed.
 */
private class LensShader private constructor(private val shader: Any) {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun effect(width: Float, height: Float, margin: Float, radius: Float, refraction: Float, rim: Float, base: Color): RenderEffect {
        val s = shader as RuntimeShader
        s.setFloatUniform("size", width, height)
        s.setFloatUniform("margin", margin, margin)
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("refraction", refraction)
        s.setFloatUniform("rim", rim)
        s.setFloatUniform("dispersion", 0.18f)
        s.setFloatUniform("base", base.red, base.green, base.blue, 1f)
        return RenderEffect.createRuntimeShaderEffect(s, "content")
    }

    companion object {
        fun create(): LensShader? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return try {
                LensShader(RuntimeShader(SOURCE))
            } catch (broken: IllegalArgumentException) {
                // A GPU that cannot compile it keeps the blur alone.
                null
            }
        }

        private const val SOURCE = """
uniform shader content;
uniform float2 size;
uniform float2 margin;
uniform float radius;
uniform float refraction;
uniform float rim;
uniform float dispersion;
uniform half4 base;

// What is on screen at c: the page, over the window's background where the
// page draws nothing of its own. Reading the page alone, a channel could come
// from a spot the page leaves clear while another came from one it covers,
// and the edge showed a colored line where they disagreed.
half4 behind(float2 c) {
    half4 v = content.eval(c);
    return v + base * (1.0 - v.a);
}

float roundRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - margin - halfSize;
    float d = roundRect(p, halfSize, radius);
    if (d > 0.0) {
        return behind(coord);
    }
    // 1 at the edge, 0 once a rim's width inside it.
    float t = clamp(1.0 + d / rim, 0.0, 1.0);
    if (t <= 0.0) {
        return behind(coord);
    }
    float2 dx = float2(1.0, 0.0);
    float2 dy = float2(0.0, 1.0);
    float2 normal = normalize(float2(
        roundRect(p + dx, halfSize, radius) - roundRect(p - dx, halfSize, radius),
        roundRect(p + dy, halfSize, radius) - roundRect(p - dy, halfSize, radius)
    ) + float2(0.00001));
    // The slab's edge is round: its surface falls away like a quarter
    // circle, so the bend is slight inside and steep at the very edge.
    float bend = refraction * (1.0 - sqrt(max(1.0 - t * t, 0.0)));
    float2 shift = normal * bend;
    // Red bent the furthest and blue the least, none of them past the full
    // refraction, and every read kept inside the recorded layer.
    float2 lo = float2(0.5);
    float2 hi = size + margin * 2.0 - float2(0.5);
    half4 g = behind(clamp(coord + shift * (1.0 - dispersion * 0.5), lo, hi));
    half r = behind(clamp(coord + shift, lo, hi)).r;
    half b = behind(clamp(coord + shift * (1.0 - dispersion), lo, hi)).b;
    return half4(r, g.g, b, 1.0);
}
"""
    }
}
