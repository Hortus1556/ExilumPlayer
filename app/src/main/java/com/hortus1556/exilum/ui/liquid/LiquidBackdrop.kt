package com.hortus1556.exilum.ui.liquid

import android.os.Build
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.DefaultCameraDistance
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.ceil
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

/**
 * Whether Liquid Glass bends what is behind it, or only blurs it.
 *
 * The bending - the lens along every rim, the drop that magnifies what it is
 * over - is a shader, a RuntimeShader, which Android has from 13 on. Before
 * that, or with the effect turned off in the settings, the glass is a plain
 * Gaussian blur under the same wash. Read where the glass is drawn, so a
 * change shows on the next frame.
 */
object LiquidRefraction {
    /** Whether this device can bend at all. */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The setting, as the theme last read it. */
    var enabled by mutableStateOf(true)
        internal set

    /** Whether to bend now: wanted, and possible. */
    val on: Boolean get() = supported && enabled
}

/**
 * What the glass on a page bends: the page's own content, captured by the
 * page that provides it. Null where nothing is captured, and glass there is
 * drawn without anything behind it to bend.
 */
val LocalLiquidBackdrop = compositionLocalOf<Backdrop?> { null }

/** The wash over a bar's glass in each mode, as thick as the settings make it. */
val LiquidColors.barGlass: Color
    get() = if (isDark) {
        Color(0xFF121212).copy(alpha = glassWash(glassOpacity))
    } else {
        Color(0xFFFAFAFA).copy(alpha = glassWash(glassOpacity))
    }

/**
 * The wash over a button's glass: the bars' own, as thick as theirs. It was
 * three quarters of it, so that at any setting a button showed more of what
 * was behind it than the bar beside it - and at "frosted", where a bar is
 * all but solid, a button was still a window.
 */
val LiquidColors.buttonGlass: Color
    get() = barGlass

/**
 * The tint of a prominent button's glass - the one coloured all through - as
 * thick as the settings make the rest: what it was, three quarters, where
 * the opacity is as it comes, clear at one end and solid at the other. It
 * was three quarters whatever the setting.
 */
val LiquidColors.tintGlass: Color
    get() = tint.copy(alpha = (0.75f * glassOpacity / 0.4f).coerceIn(0f, 0.95f))

/**
 * The same tint where the page captures nothing for the glass to blur:
 * thicker, since what shows through it there shows sharp, and following the
 * setting as the plain glass beside it does (see glassTint). It was 0.92
 * whatever the setting.
 */
val LiquidColors.tintGlassPlain: Color
    get() = tint.copy(alpha = (0.92f * glassOpacity / 0.4f).coerceIn(0f, 0.95f))

/**
 * How much the glass blurs what is behind it, from the setting: none at all
 * at Clear - only the bending at the rim is left, as iOS's clear glass - the
 * reference amount at the default, a little more towards Frosted.
 */
val LiquidColors.frost: Float
    get() = (glassOpacity / 0.4f).coerceIn(0f, 1.6f)

/**
 * Glass over Now Playing's colours: a clear whitening, as on Music's
 * buttons there, as thick as the settings make it.
 */
val LiquidColors.nowPlayingGlass: Color
    get() = if (isDark) {
        Color.White.copy(alpha = (0.14f * glassOpacity / 0.4f).coerceIn(0.03f, 0.42f))
    } else {
        // White glass on a white page is nothing at all: iOS's gray fill.
        Color(0xFF787880).copy(alpha = (0.16f * glassOpacity / 0.4f).coerceIn(0.05f, 0.45f))
    }

/**
 * The drop that marks the choice in a selector lying on Now Playing's glass -
 * the player's views: lighter than the glass under it by as much at any
 * setting, and never so thin that the choice cannot be seen. [press] is how
 * far it is held, 0 to 1: it is lit a little more while it is. It was one
 * thickness whatever the setting.
 */
fun LiquidColors.nowPlayingDrop(press: Float): Color {
    val setting = glassOpacity / 0.4f
    return if (isDark) {
        Color.White.copy(alpha = ((0.22f + 0.06f * press) * setting).coerceIn(0.1f, 0.5f))
    } else {
        Color.Black.copy(alpha = ((0.07f + 0.05f * press) * setting).coerceIn(0.04f, 0.25f))
    }
}

/**
 * The wash over a window's glass - an alert, a menu, a sheet: the bars' own,
 * so that one slider makes all the glass in the app alike, window and bar.
 */
val LiquidColors.windowGlass: Color
    get() = barGlass

/** And how much a window blurs what is behind it: the same as a bar. */
val LiquidColors.windowFrost: Float
    get() = frost

/** The setting's 0-1, as a wash that never quite hides the glass: at most 0.92. */
private fun glassWash(opacity: Float): Float = (opacity.coerceIn(0f, 1f) * 0.92f)

/**
 * The shadow a piece of glass casts, drawn as the backdrop library draws its
 * own - the same blurred outline with the shape cut out of it, moved and
 * stretched with the glass's own [layerBlock] - once, into a picture, and
 * the picture put down after that. Goes before drawBackdrop, which is told to
 * cast none.
 *
 * The library draws its shadow into a layer, and the outline in that layer
 * is blurred by 24 points - which is worked out on the processor, across the
 * whole of the shadow, each time the layer is rendered. It is rendered each
 * time it is drawn, and glass is drawn again whenever what is behind it
 * moves: every frame a list scrolls under a bar. On a wide window, with the
 * mini player over the list, that was eleven milliseconds of every sixteen
 * while the list moved - measured, and gone with the shadow alone switched
 * off; keeping the layer and not recording it again did not bring them back,
 * and taking the blur out of it did. A shadow has nothing to do with what is
 * behind the glass. Here it is worked out when the glass changes shape or
 * size and at no other time.
 */
private class GlassShadowElement(
    val shape: () -> Shape,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
) : ModifierNodeElement<GlassShadowNode>() {
    override fun create() = GlassShadowNode(shape, layerBlock)

    override fun update(node: GlassShadowNode) {
        node.shape = shape
        node.layerBlock = layerBlock
        node.invalidateDraw()
    }

    override fun equals(other: Any?): Boolean =
        other is GlassShadowElement && other.shape == shape && other.layerBlock == layerBlock

    override fun hashCode(): Int = 31 * shape.hashCode() + (layerBlock?.hashCode() ?: 0)
}

private class GlassShadowNode(
    var shape: () -> Shape,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
) : DrawModifierNode, Modifier.Node() {
    override val shouldAutoInvalidate: Boolean = false

    private val shadow = Shadow.Default
    private val paint = Paint().apply { color = shadow.color }
    private val moved = LayerMove()

    /**
     * The picture, and how much of it is the shadow: it is as big as the
     * biggest shadow drawn into it, so that a bar changing size - folding,
     * unfolding - draws into the one it has and does not make one a frame.
     */
    private var picture: ImageBitmap? = null
    private var room = IntSize.Zero

    /** What it holds: the shape, at the size and the scale it was drawn for. */
    private var drawn: Shape? = null
    private var drawnSize = Size.Unspecified
    private var drawnDensity = 0f
    private var drawnDirection: LayoutDirection? = null

    override fun ContentDrawScope.draw() {
        val radius = shadow.radius.toPx()
        val shape = shape()
        if (shape != drawn || size != drawnSize || density != drawnDensity || layoutDirection != drawnDirection) {
            drawn = shape
            drawnSize = size
            drawnDensity = density
            drawnDirection = layoutDirection
            paint(shape, radius)
        }
        val picture = picture
        if (picture != null) {
            // Where the glass's own layer puts the glass: the library's
            // shadow is drawn inside that layer, and leans and swells with a
            // finger as the glass does.
            val block = layerBlock
            if (block == null) {
                put(picture, radius)
            } else {
                moved.read(size, this, block)
                withTransform({
                    translate(moved.translationX, moved.translationY)
                    scale(
                        moved.scaleX, moved.scaleY,
                        Offset(
                            size.width * moved.transformOrigin.pivotFractionX,
                            size.height * moved.transformOrigin.pivotFractionY,
                        ),
                    )
                }) {
                    put(picture, radius)
                }
            }
        }
        drawContent()
    }

    /** The shadow of [shape], as the library draws it into its layer: the outline blurred, and the shape itself cut out. */
    private fun DrawScope.paint(shape: Shape, radius: Float) {
        val offsetX = shadow.offset.x.toPx()
        val offsetY = shadow.offset.y.toPx()
        room = IntSize(
            ceil(size.width + radius * 4f + offsetX).toInt(),
            ceil(size.height + radius * 4f + offsetY).toInt(),
        )
        if (room.width <= 0 || room.height <= 0) {
            picture = null
            return
        }
        val kept = picture?.takeIf { it.width >= room.width && it.height >= room.height }
        val into = kept ?: ImageBitmap(room.width, room.height)
        if (kept != null) kept.asAndroidBitmap().eraseColor(0)
        picture = into
        val outline = shape.createOutline(size, layoutDirection, this)
        paint.asFrameworkPaint().maskFilter =
            if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
        val canvas = Canvas(into)
        canvas.translate(radius * 2f + offsetX, radius * 2f + offsetY)
        canvas.drawOutline(outline, paint)
        // Nothing of it under the glass itself: glass is seen through.
        canvas.translate(-offsetX, -offsetY)
        canvas.drawOutline(outline, ShadowCutOut)
    }

    private fun DrawScope.put(picture: ImageBitmap, radius: Float) {
        translate(-radius * 2f, -radius * 2f) {
            drawImage(picture, srcSize = room, dstSize = room, alpha = shadow.alpha, blendMode = shadow.blendMode)
        }
    }

    override fun onDetach() {
        picture = null
        drawn = null
    }
}

private val ShadowCutOut = Paint().apply { blendMode = BlendMode.Clear }

/**
 * What a layer block does to where its layer is: run against this, it says
 * how far the layer is moved and stretched, and how far faded. The glass's
 * blocks do nothing else - a lean after the finger, a swell under it - and
 * the rest of what a layer can be told is taken and left unused.
 */
internal class LayerMove : GraphicsLayerScope {
    override var size: Size = Size.Unspecified
    override var density: Float = 1f
    override var fontScale: Float = 1f

    override var scaleX: Float = 1f
    override var scaleY: Float = 1f
    override var alpha: Float = 1f
    override var translationX: Float = 0f
    override var translationY: Float = 0f
    override var shadowElevation: Float = 0f
    override var ambientShadowColor: Color = DefaultShadowColor
    override var spotShadowColor: Color = DefaultShadowColor
    override var rotationX: Float = 0f
    override var rotationY: Float = 0f
    override var rotationZ: Float = 0f
    override var cameraDistance: Float = DefaultCameraDistance
    override var transformOrigin: TransformOrigin = TransformOrigin.Center
    override var shape: Shape = RectangleShape
    override var clip: Boolean = false
    override var renderEffect: RenderEffect? = null
    override var blendMode: BlendMode = BlendMode.SrcOver
    override var colorFilter: ColorFilter? = null
    override var compositingStrategy: CompositingStrategy = CompositingStrategy.Auto

    fun read(size: Size, density: Density, block: GraphicsLayerScope.() -> Unit) {
        this.size = size
        this.density = density.density
        fontScale = density.fontScale
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        translationX = 0f
        translationY = 0f
        transformOrigin = TransformOrigin.Center
        block()
    }
}

/**
 * A bar of iOS 26's glass over [backdrop]: what is behind it saturated,
 * blurred a little and bent round its edge like a thick lens, with the light
 * along its rim - the tab bar, its accessory, the search button.
 *
 * Given a [union], it draws none of that itself: it is a capsule in the one
 * sheet of glass the union draws, and runs together with the others in it
 * (see [LiquidGlassUnion]); [carried] is for a piece that something above it
 * moves and fades (see [LiquidGlassCarrier]).
 */
fun Modifier.liquidBarGlass(
    backdrop: Backdrop,
    surface: Color,
    shape: () -> Shape = { Capsule() },
    frost: Float = 1f,
    union: LiquidGlassUnion? = null,
    carried: (() -> Float)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier = if (union != null) {
    liquidGlassPiece(union, surface, Color.Unspecified, GlassKind.Bar, carried, layerBlock)
} else {
    this.then(GlassShadowElement(shape, layerBlock)).drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        // Its own, kept: see GlassShadowElement.
        shadow = null,
        effects = { glassOf(GlassKind.Bar, frost) },
        layerBlock = layerBlock,
        onDrawSurface = { drawRect(surface) },
    )
}

/** What either kind of glass does to what is behind it: saturates it, blurs it, bends it along the rim. */
private fun BackdropEffectScope.glassOf(kind: GlassKind, frost: Float) {
    vibrancy()
    val bends = LiquidRefraction.on
    // Without the lens the blur is all the glass there is, so there is
    // always some of it: clear glass with nothing to bend is no glass.
    val radius = kind.blur.toPx() * frost
    val blurred = if (bends) radius else maxOf(radius, kind.leastBlur.toPx())
    if (blurred > 0f) blur(blurred)
    // The rim bends only a rim's worth: on a small circle the full band
    // would be most of it, and drag in what lies well outside.
    if (bends) {
        val side = size.minDimension
        lens(minOf(kind.rim.toPx(), side * kind.rimOfSide), minOf(GlassBend.toPx(), side * GlassBendOfSide))
    }
}

/**
 * A glass button over [backdrop]: less blur than a bar, so what is behind
 * reads through it, and a tighter lens for its size. [tint] makes it the
 * prominent kind, tinted all through, as thickly as its alpha says - see
 * [tintGlass]. Given a [union] it joins it, as a bar does.
 */
fun Modifier.liquidButtonGlass(
    backdrop: Backdrop,
    surface: Color,
    shape: () -> Shape = { Capsule() },
    tint: Color = Color.Unspecified,
    frost: Float = 1f,
    union: LiquidGlassUnion? = null,
    carried: (() -> Float)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier = if (union != null) {
    liquidGlassPiece(union, surface, tint, GlassKind.Button, carried, layerBlock)
} else {
    this.then(GlassShadowElement(shape, layerBlock)).drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        shadow = null,
        effects = { glassOf(GlassKind.Button, frost) },
        layerBlock = layerBlock,
        onDrawSurface = {
            if (tint.isSpecified) {
                drawRect(tint.copy(alpha = 1f), blendMode = androidx.compose.ui.graphics.BlendMode.Hue)
                drawRect(tint)
            } else {
                drawRect(surface)
            }
        },
    )
}

private val Color.isSpecified: Boolean get() = this != Color.Unspecified

/**
 * The search that lives in the tab bar, as iOS 26 puts it: a button of its
 * own beside the tabs that opens into a field along the bottom. The pages
 * read [field]; [active] is whether the field is open.
 */
@Stable
class LiquidSearchState(val field: TextFieldState) {
    var active by mutableStateOf(false)
}

/** The tab bar's search, for the page it searches; null where there is none. */
val LocalLiquidSearch = compositionLocalOf<LiquidSearchState?> { null }

/**
 * The tab bar's state, shared with the pages under it: how folded the bars
 * are, how far the search is open, and where a page's own floating action
 * goes - above the bars while they are open, in the folded row once they
 * fold - so it can fold along with them.
 */
@Stable
class LiquidBarsState {
    val fold = androidx.compose.animation.core.Animatable(0f)
    val open = androidx.compose.animation.core.Animatable(0f)

    /** In root coordinates. */
    /**
     * The one sheet of glass the bars and the pages' floating actions are
     * pieces of, so that they run together where they meet; null where the
     * device cannot draw one.
     */
    val union: LiquidGlassUnion? = if (LiquidRefraction.supported) LiquidGlassUnion() else null

    var actionArea by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    var foldedActionArea by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)

    /**
     * The widths of the floating actions of the pages being shown, in px:
     * folded, one sits in the folded row beside the search, and the
     * accessory there makes room for it.
     */
    val actionWidths = androidx.compose.runtime.mutableStateMapOf<Any, Int>()

    /** How much of the folded row a page's action takes, in px; 0 for none. */
    val actionWidth: Int get() = actionWidths.values.maxOrNull() ?: 0

    /**
     * How far the accessory has been pushed from where it sits, in px: to
     * the left as it is slid away, up as it is lifted. The glass goes with
     * what is on it, rather than the mini player sliding out of a capsule
     * that stays behind.
     */
    var accessoryShift by mutableStateOf(Offset.Zero)
}

/** The tab bar's state, for the pages under it; null where there is no Liquid tab bar. */
val LocalLiquidBars = compositionLocalOf<LiquidBarsState?> { null }

/**
 * Places a page's floating action where [bars] say it goes, and folds it
 * along with them: laid out wherever its slot puts it, then moved there -
 * above the bars, sliding into the folded row as they fold, gone while the
 * search is open.
 */
fun Modifier.liquidBarsAnchored(
    bars: LiquidBarsState,
    /** The pop to follow; its own, on the page it is on, when nothing is given. */
    presence: State<Float>? = null,
): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Pages do not carry their floating buttons along as they slide: the
    // one leaving pops away where it is, and the one arriving pops up after
    // it has gone, rather than the two crossing in the same corner.
    val presence = presence ?: rememberLiquidPresence(enterDelayMillis = 110)
    // While its page is shown, it tells the bars how wide it is, so the
    // accessory in the folded row can make room for it.
    val present = LocalLiquidPageMotion.current?.present ?: true
    val key = remember { Any() }
    var width by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Where it last stood while it was the one being shown. The bars' top
    // rises by the whole height of the accessory as the mini player arrives,
    // and a button on its way out must not ride up with it: it pops where it
    // stands, like every other thing that pops.
    val held = remember { floatArrayOf(Float.NaN, Float.NaN) }
    androidx.compose.runtime.DisposableEffect(bars, present, width) {
        if (present && width > 0) bars.actionWidths[key] = width else bars.actionWidths.remove(key)
        onDispose { bars.actionWidths.remove(key) }
    }
    this
        .onSizeChanged { width = it.width }
        .onPlaced { origin = it.positionInRoot() }
        // The move first, the pop inside it. The other way round the pop's
        // own layer - which has an effect on it, and so is drawn into a
        // buffer its own size - cuts off whatever the move took outside the
        // box the button was laid out in: half a button, mid-animation.
        .graphicsLayer {
            val open = bars.actionArea
            val folded = bars.foldedActionArea
            if (open.isEmpty) return@graphicsLayer
            val f = bars.fold.value
            val right = androidx.compose.ui.util.lerp(open.right, folded.right, f)
            val centerY = androidx.compose.ui.util.lerp(open.bottom - size.height / 2f, folded.center.y, f)
            if (present || held[0].isNaN()) {
                held[0] = right - size.width - origin.x
                held[1] = centerY - size.height / 2f - origin.y
            }
            translationX = held[0]
            translationY = held[1]
            alpha = (1f - bars.open.value * 2f).coerceIn(0f, 1f)
        }
        .liquidPop(presence)
}

/** Keeps a composable's dependency on [LocalLiquidBackdrop] readable at the call site. */
@Composable
fun currentLiquidBackdrop(): Backdrop? = LocalLiquidBackdrop.current

/**
 * A page's own floating action, in the corner the bars name.
 *
 * Declared here and drawn by [LiquidActionOverlay] over every page, rather
 * than inside the page: the pager clips a page to its slot, and a button
 * anchored to the bars stands still while its page slides out from under it,
 * so the page's edge would cut it in half on the way - a wipe. It pops in
 * and out with its page and with [visible].
 */
@Composable
fun LiquidFloatingAction(
    visible: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    val present = (LocalLiquidPageMotion.current?.present ?: true) && visible
    val presence = remember { Animatable(if (present) 1f else 0f) }
    LaunchedEffect(present) {
        // What leaves goes first; what arrives follows it into the same corner.
        if (present) delay(110)
        presence.animateTo(if (present) 1f else 0f, LiquidPopSpring)
    }
    val state = presence.asState()
    val host = LocalLiquidActionHost.current
    val bars = LocalLiquidBars.current
    if (host != null) {
        val backdrop = LocalLiquidBackdrop.current
        val entry = remember(host) {
            LiquidActionEntry(
                order = host.nextOrder(),
                presence = state,
                present = mutableStateOf(present),
                content = mutableStateOf(content),
                backdrop = mutableStateOf(backdrop),
            )
        }
        SideEffect {
            (entry.present as MutableState).value = present
            (entry.content as MutableState).value = content
            (entry.backdrop as MutableState).value = backdrop
        }
        val id = remember { Any() }
        DisposableEffect(host, id) {
            host.entries[id] = entry
            onDispose { host.entries.remove(id) }
        }
        return
    }
    // Without a host - a screen that draws its own bars - it stays where it
    // was put, since nothing slides it away.
    if (present || presence.value > 0.002f) {
        content(if (bars != null) Modifier.liquidBarsAnchored(bars, state) else Modifier.liquidPop(state))
    }
}
