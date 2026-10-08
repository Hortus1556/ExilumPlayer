package com.hortus1556.exilum.ui.screens.player.components.views

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hortus1556.exilum.renderer.Player
import com.hortus1556.exilum.ui.liquid.LiquidColors
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The most columns the display is ever given, however wide the window, and the fewest. */
private const val MAX_COLUMNS = 24
private const val MIN_COLUMNS = 10
private const val MIN_ROWS = 12
private const val MAX_ROWS = 32

/** A column and the gap beside it, where there is room for more than the fewest. */
private val COLUMN_PITCH = 26.dp
/** How much of a column's pitch its segments fill, and of a row's. */
private const val SEGMENT_WIDTH = 0.80f
private const val SEGMENT_HEIGHT = 0.62f
/** A row's pitch as a share of a column's at most: segments stay flat lozenges however tall the view. */
private const val ROW_PER_COLUMN = 0.42f

private val SIDE_PADDING = 14.dp
private val LETTERING_HEIGHT = 26.dp
private val LABEL_HEIGHT = 18.dp

/** Whole displays a bar falls through in a second once its sound has gone: the pace of a hi-fi's, not a meter's. */
private const val BAR_FALL_PER_SECOND = 2.0f
/** How long a cap hangs where a bar left it, and how it falls after: from rest, faster and faster. */
private const val CAP_HOLD_SECONDS = 0.5f
private const val CAP_GRAVITY = 3.2f

/**
 * The display turns itself up for quiet music, by this much of its height at
 * most, so that a track mastered low - or an engine that plays low - still
 * fills it. Slowly up, at once down; never below what the sound really is.
 */
private const val MAX_LIFT = 0.30f
private const val LIFT_TARGET = 0.93f
private const val LIFT_RISE_PER_SECOND = 0.12f
private const val LOUDEST_FADE_PER_SECOND = 0.02f

/** Where the colours change up a column: the body, the warning rows, and the top. */
private const val AMBER_FROM = 0.64f
private const val RED_FROM = 0.84f

/** The frequencies a hi-fi printed under its columns. */
private val LABEL_HZ = floatArrayOf(63f, 160f, 400f, 1000f, 2500f, 6300f, 16000f)
private val LABEL_TEXT = arrayOf("63", "160", "400", "1k", "2.5k", "6.3k", "16k")

/**
 * The spectrum of what is playing, as the display of a hi-fi from around
 * 2000 showed it: columns of flat segments, one a band of pitch, lighting
 * from the bottom as far as that band is loud - green-blue, then amber, then
 * red at the top - with a cap on each that hangs where the bar last reached
 * and then drops.
 *
 * Drawn the way the rest of the player is made: the segments are lozenges
 * with the light of a glass bead on them, the dark ones are still there as a
 * faint grid, and where the controls over the view are glass the columns are
 * mirrored below their feet, under that glass, which bends and softens them
 * into a glow that moves with the music.
 *
 * It shows the output itself (see spectrum.h on the native side), so every
 * engine has it, the equalizer shows in it, and the volume does not.
 *
 * @param playing whether sound is being made; when it stops the bars fall
 *   and the caps after them, and then nothing is redrawn
 * @param live whether this is the view on screen, or the one a swipe is
 *   bringing in: all the views stay built, and one that is out of sight asks
 *   for no frames
 * @param bottomInset how much of the bottom of the view is under the controls
 * @param reflect whether to mirror the columns into that part - where the
 *   controls are glass
 */
@Composable
fun SpectrumView(
    playing: Boolean,
    live: Boolean,
    bottomInset: Dp,
    reflect: Boolean,
    modifier: Modifier = Modifier,
) {
    val liquidColors = LocalLiquidColors.current
    val colors = remember(liquidColors) { spectrumColors(liquidColors) }
    val meter = remember { SpectrumMeter() }
    val layout = remember { GridCache() }
    val sprites = remember { SpriteCache() }

    // Coming back into view it starts dark. The loop below stops where it is
    // when the view goes out of sight, half way through the slide, and what
    // the bars were then is not what is playing now - or anything, if it has
    // stopped since: they stood there lit, and fell for no sound at all.
    LaunchedEffect(live) {
        if (live) meter.clear()
    }

    // Its own frames, and only while there is something to move: playing, or
    // bars and caps still on their way down after it stopped.
    LaunchedEffect(live, playing) {
        if (!live) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            // A frame a long time after the last - the app was away - is not
            // a long fall to be made in one step.
            val seconds = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            if (!meter.advance(seconds, playing) && !playing) break
        }
    }

    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT } }
    val letteringPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.22f
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        // ---- Layer 1: what does not move ---------------------------------
        // Every segment unlit, the lettering and the frequencies. Drawn when
        // the size or the colours change and never otherwise: it is most of
        // the shapes on screen, and none of them depends on the sound. In a
        // layer of its own, which is what makes that so - without one, both
        // canvases are recorded into the box's, and every move of the light
        // had all of this recorded again with it.
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
            val grid = layout.of(this, bottomInset)
            if (!grid.valid) return@Canvas
            meter.columns = grid.columns

            for (column in 0 until grid.columns) {
                val x = grid.segmentLeft(column)
                for (row in 0 until grid.rows) {
                    val y = grid.segmentTop(row)
                    drawRoundRect(colors.unlit, Offset(x, y), grid.segment, grid.corner)
                    // The edge the light catches, even on a dark one.
                    drawRoundRect(
                        colors.unlitEdge, Offset(x + grid.corner.x * 0.5f, y),
                        Size(grid.segment.width - grid.corner.x, max(1f, grid.segment.height * 0.10f)),
                        grid.corner,
                    )
                }
            }

            drawIntoCanvas { canvas ->
                // Over the display's top corner, as it was printed on the glass.
                letteringPaint.textSize = 10.sp.toPx()
                letteringPaint.color = colors.lettering.toArgb()
                canvas.nativeCanvas.drawText(
                    "SPECTRUM ANALYZER", grid.left + grid.gapX / 2f, grid.top - 9.dp.toPx(), letteringPaint,
                )

                labelPaint.textSize = 10.sp.toPx()
                labelPaint.color = colors.label.toArgb()
                labelPaint.textAlign = Paint.Align.CENTER
                val baseline = grid.bottom + LABEL_HEIGHT.toPx() * 0.78f
                // Each printed frequency under the column nearest it in
                // pitch, and none on top of the one before.
                var clearFrom = Float.NEGATIVE_INFINITY
                for (i in LABEL_HZ.indices) {
                    val column = grid.labelColumns[i]
                    val centre = grid.segmentLeft(column) + grid.segment.width / 2f
                    val half = labelPaint.measureText(LABEL_TEXT[i]) / 2f
                    if (centre - half < clearFrom) continue
                    canvas.nativeCanvas.drawText(LABEL_TEXT[i], centre, baseline, labelPaint)
                    clearFrom = centre + half + 4.dp.toPx()
                }
            }
        }

        // ---- Layer 2: the light -------------------------------------------
        // The lit segments, the caps, and the columns' mirror under the
        // controls. Redrawn when the meter says something moved - and a
        // handful of copies a column when it is: a whole column lit is drawn
        // once, into a picture, and each frame shows as much of it as is lit.
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
            @Suppress("UNUSED_EXPRESSION") meter.tick
            val grid = layout.of(this, bottomInset)
            if (!grid.valid) return@Canvas

            // How many rows of mirror fit under the controls, and where it starts.
            val mirrorTop = (size.height - bottomInset.toPx() + 2.dp.toPx()).roundToInt()
            val mirrorRows = if (reflect) min(grid.rows, ((size.height - mirrorTop) / grid.rowPitch).toInt()) else 0
            val art = sprites.of(this, grid, colors, mirrorRows)
            val row = grid.rowPitch.toInt()
            val bottom = grid.bottom.toInt()

            for (column in 0 until min(grid.columns, meter.columns)) {
                val left = grid.columnLeft(column)
                val shown = meter.level[column].coerceIn(0f, 1f) * grid.rows
                val full = min(shown.toInt(), grid.rows)
                // The segment the bar is passing through, lit by how far
                // into it the bar is: a bar falls a row at a time, as they
                // did, but each row goes out like a phosphor and not a switch.
                val passing = if (full < grid.rows) shown - full else 0f

                if (shown > 0.05f) {
                    // The light the column throws around itself: one soft
                    // shape, stretched to the height of what is lit.
                    val height = (shown * grid.rowPitch).toInt()
                    drawImage(
                        image = art.glow,
                        dstOffset = IntOffset(left - art.glowMargin, bottom - height - art.glowMargin),
                        dstSize = IntSize(art.width + art.glowMargin * 2, height + art.glowMargin * 2),
                        alpha = min(1f, shown / 3f),
                    )
                }
                if (full > 0) {
                    drawImage(
                        image = art.column,
                        srcOffset = IntOffset(0, (grid.rows - full) * row),
                        srcSize = IntSize(art.width, full * row),
                        dstOffset = IntOffset(left, bottom - full * row),
                        dstSize = IntSize(art.width, full * row),
                    )
                }
                if (passing > 0.01f) {
                    drawImage(
                        image = art.column,
                        srcOffset = IntOffset(0, (grid.rows - full - 1) * row),
                        srcSize = IntSize(art.width, row),
                        dstOffset = IntOffset(left, bottom - (full + 1) * row),
                        dstSize = IntSize(art.width, row),
                        alpha = passing,
                    )
                }
                val mirror = art.mirror
                if (mirror != null) {
                    val rows = min(full, mirrorRows)
                    if (rows > 0) {
                        drawImage(
                            image = mirror,
                            srcSize = IntSize(art.width, rows * row),
                            dstOffset = IntOffset(left, mirrorTop),
                            dstSize = IntSize(art.width, rows * row),
                        )
                    }
                    if (passing > 0.01f && full < mirrorRows) {
                        drawImage(
                            image = mirror,
                            srcOffset = IntOffset(0, full * row),
                            srcSize = IntSize(art.width, row),
                            dstOffset = IntOffset(left, mirrorTop + full * row),
                            dstSize = IntSize(art.width, row),
                            alpha = passing,
                        )
                    }
                }

                val cap = meter.cap[column].coerceIn(0f, 1f)
                if (cap > 0.01f) {
                    val capHeight = max(2f, grid.segment.height * 0.46f)
                    val y = (grid.bottom - cap * grid.rows * grid.rowPitch - capHeight).coerceAtLeast(grid.top - capHeight)
                    drawRoundRect(
                        colors.cap, Offset(grid.segmentLeft(column), y), Size(grid.segment.width, capHeight),
                        CornerRadius(capHeight / 2f),
                    )
                }
            }
        }
    }
}

/**
 * One lit segment: a bead of coloured glass. Its colour, lighter above and
 * deeper below as a thick piece is; a band of light across its upper part,
 * where a round surface shows the sky; a bright line along the very top and
 * a shade along the bottom.
 */
private fun DrawScope.drawBead(colors: SpectrumColors, grid: Grid, x: Float, y: Float, body: Color, strength: Float) {
    val segment = grid.segment
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to lerp(body, Color.White, colors.lift),
            0.55f to body,
            1f to lerp(body, Color.Black, colors.shade),
            startY = y, endY = y + segment.height,
        ),
        topLeft = Offset(x, y), size = segment, cornerRadius = grid.corner, alpha = strength * colors.body,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = colors.sheen),
            1f to Color.White.copy(alpha = 0f),
            startY = y + segment.height * 0.08f, endY = y + segment.height * 0.52f,
        ),
        topLeft = Offset(x + segment.height * 0.20f, y + segment.height * 0.08f),
        size = Size(segment.width - segment.height * 0.40f, segment.height * 0.44f),
        cornerRadius = CornerRadius(segment.height * 0.22f),
        alpha = strength,
    )
    drawRoundRect(
        Color.White.copy(alpha = strength * colors.edge),
        Offset(x + grid.corner.x * 0.7f, y + 0.5f),
        Size(segment.width - grid.corner.x * 1.4f, max(1f, segment.height * 0.07f)),
        grid.corner,
    )
}

/** The column a frequency falls in, the bands being even steps of pitch from the lowest to the highest. */
private fun nearestColumn(hz: Float, columns: Int): Int {
    var best = 0
    var bestDistance = Float.MAX_VALUE
    for (column in 0 until columns) {
        val distance = abs(ln(Player.spectrumBandHz(column, columns) / hz))
        if (distance < bestDistance) {
            best = column
            bestDistance = distance
        }
    }
    return best
}

/** The grid last worked out, kept while the view is the size it was: it is asked for on every frame. */
private class GridCache {
    private var width = -1f
    private var height = -1f
    private var inset = -1f
    private var grid: Grid? = null

    fun of(scope: DrawScope, bottomInset: Dp): Grid {
        val insetPx = with(scope) { bottomInset.toPx() }
        val kept = grid
        if (kept != null && scope.size.width == width && scope.size.height == height && insetPx == inset) return kept
        width = scope.size.width
        height = scope.size.height
        inset = insetPx
        return Grid.of(scope, bottomInset).also { grid = it }
    }
}

/**
 * Where everything is, for a view of one size. The pitches are whole pixels,
 * so that a column drawn once into a picture lands on the same pixels
 * wherever it is copied to.
 */
private class Grid(
    val valid: Boolean,
    val columns: Int,
    val rows: Int,
    val left: Float,
    val top: Float,
    val bottom: Float,
    val columnPitch: Float,
    val rowPitch: Float,
    val segment: Size,
    val corner: CornerRadius,
) {
    val gapX: Float get() = columnPitch - segment.width

    /** The column each printed frequency goes under, worked out once: it asks the player for every band's pitch. */
    val labelColumns: IntArray = IntArray(LABEL_HZ.size) { if (columns > 0) nearestColumn(LABEL_HZ[it], columns) else 0 }

    /** Where a column's pitch starts, and where its segments do. */
    fun columnLeft(column: Int): Int = (left + column * columnPitch).toInt()
    fun segmentLeft(column: Int): Float = columnLeft(column) + gapX / 2f

    /** How far down its row's pitch a segment starts. */
    val inRow: Float get() = (rowPitch - segment.height) / 2f

    /** Row 0 is the bottom one. */
    fun segmentTop(row: Int): Float = bottom - (row + 1) * rowPitch + inRow

    /** How far up the display a row is, 0 the bottom one and 1 the top, for its colour. */
    fun rowHeight(row: Int): Float = if (rows > 1) row / (rows - 1f) else 0f

    companion object {
        private val None = Grid(false, 0, 0, 0f, 0f, 0f, 0f, 0f, Size.Zero, CornerRadius.Zero)

        fun of(scope: DrawScope, bottomInset: Dp): Grid = with(scope) {
            val side = SIDE_PADDING.toPx()
            val width = size.width - side * 2f
            val top = LETTERING_HEIGHT.toPx()
            val bottom = floor(size.height - bottomInset.toPx() - LABEL_HEIGHT.toPx() - 6.dp.toPx())
            val height = bottom - top
            if (width <= 0f || height <= 0f) return None

            val columns = (width / COLUMN_PITCH.toPx()).roundToInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
            val columnPitch = floor(width / columns)
            // As many rows as fit at the flattest a segment may be, and when
            // the view is short, fewer rows squeezed rather than none.
            val rows = floor(height / (columnPitch * ROW_PER_COLUMN)).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
            val rowPitch = floor(min(columnPitch * ROW_PER_COLUMN, height / rows))
            if (columnPitch < 4f || rowPitch < 3f) return None
            val segment = Size(floor(columnPitch * SEGMENT_WIDTH), max(2f, floor(rowPitch * SEGMENT_HEIGHT)))
            Grid(
                valid = true, columns = columns, rows = rows,
                // What the whole pixels left over, shared between the sides.
                left = floor((size.width - columns * columnPitch) / 2f),
                top = bottom - rows * rowPitch, bottom = bottom,
                columnPitch = columnPitch, rowPitch = rowPitch, segment = segment,
                corner = CornerRadius(segment.height * 0.48f),
            )
        }
    }
}

/**
 * The pictures the light is made of: a column with every row lit, the soft
 * glow one throws, and the column again upside down and fading, for the
 * mirror. Made when the grid or the colours change, which is seldom; copied
 * from, every frame.
 */
private class Sprites(
    val column: ImageBitmap,
    val glow: ImageBitmap,
    val mirror: ImageBitmap?,
    /** The column's width, which is a column's pitch. */
    val width: Int,
    /** How far the glow's picture reaches beyond the column it is for, on every side. */
    val glowMargin: Int,
)

private class SpriteCache {
    /**
     * What the pictures are made from. Not the grid itself: while the
     * controls fold away the view grows a little every frame, a new grid each
     * time with the same segments in it, and the same pictures were being
     * painted again for each.
     */
    private data class Key(
        val columnPitch: Float,
        val rowPitch: Float,
        val rows: Int,
        val segment: Size,
        val corner: CornerRadius,
        val colors: SpectrumColors,
        val density: Float,
    )

    private var key: Key? = null
    private var mirrorRows = -1
    private var sprites: Sprites? = null

    fun of(scope: DrawScope, grid: Grid, colors: SpectrumColors, mirrorRows: Int): Sprites {
        val key = Key(grid.columnPitch, grid.rowPitch, grid.rows, grid.segment, grid.corner, colors, scope.density)
        val kept = sprites?.takeIf { key == this.key }
        if (kept != null && mirrorRows == this.mirrorRows) return kept
        val density = Density(scope.density, scope.fontScale)
        val margin = ceil(grid.columnPitch * 0.55f).toInt()
        // The mirror alone, where only how much of it shows has changed.
        val made = Sprites(
            column = kept?.column ?: column(density, grid, colors),
            glow = kept?.glow ?: glow(density, grid, colors, margin),
            mirror = if (mirrorRows > 0) mirror(density, grid, colors, mirrorRows) else null,
            width = grid.columnPitch.toInt(),
            glowMargin = margin,
        )
        this.key = key
        this.mirrorRows = mirrorRows
        sprites = made
        return made
    }

    private fun column(density: Density, grid: Grid, colors: SpectrumColors): ImageBitmap {
        val row = grid.rowPitch.toInt()
        return picture(density, grid.columnPitch.toInt(), grid.rows * row) {
            for (r in 0 until grid.rows) {
                drawBead(colors, grid, grid.gapX / 2f, (grid.rows - 1 - r) * row + grid.inRow, colors.rowColor(grid.rowHeight(r)), 1f)
            }
        }
    }

    /** Upside down - the feet at the top - and fainter the further from them, as on a polished shelf. */
    private fun mirror(density: Density, grid: Grid, colors: SpectrumColors, rows: Int): ImageBitmap {
        val row = grid.rowPitch.toInt()
        return picture(density, grid.columnPitch.toInt(), rows * row) {
            for (r in 0 until rows) {
                val depth = 1f - r / rows.toFloat()
                drawBead(
                    colors, grid, grid.gapX / 2f, r * row + grid.inRow, colors.rowColor(grid.rowHeight(r)),
                    colors.mirror * depth * depth,
                )
            }
        }
    }

    /** One blurred bar, as tall as it is wide: stretched to the height of what is lit, the soft edge stays soft. */
    private fun glow(density: Density, grid: Grid, colors: SpectrumColors, margin: Int): ImageBitmap {
        val width = grid.columnPitch.toInt()
        val inset = grid.gapX / 2f
        return picture(density, width + margin * 2, width + margin * 2) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.rowColor(0f).copy(alpha = colors.glow).toArgb()
                maskFilter = BlurMaskFilter(margin * 0.5f, BlurMaskFilter.Blur.NORMAL)
            }
            drawIntoCanvas {
                it.nativeCanvas.drawRoundRect(
                    margin + inset, margin.toFloat(), margin + inset + grid.segment.width, (margin + width).toFloat(),
                    grid.corner.x, grid.corner.y, paint,
                )
            }
        }
    }

    private inline fun picture(density: Density, width: Int, height: Int, crossinline draw: DrawScope.() -> Unit): ImageBitmap {
        val bitmap = ImageBitmap(max(1, width), max(1, height))
        CanvasDrawScope().draw(
            density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap),
            Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
        ) { draw() }
        return bitmap
    }
}

/**
 * The bars and their caps, as they stand: what the sound last was, and how
 * the display has moved towards it. Plain arrays, moved once a frame by
 * [advance]; drawing reads [tick] to be told when to look.
 */
private class SpectrumMeter {
    /** How many columns are drawn, set by whoever lays them out. */
    var columns = MIN_COLUMNS

    val level = FloatArray(MAX_COLUMNS)
    val cap = FloatArray(MAX_COLUMNS)

    var tick by mutableIntStateOf(0)
        private set

    private val heard = FloatArray(MAX_COLUMNS)
    private val capHeld = FloatArray(MAX_COLUMNS)
    private val capSpeed = FloatArray(MAX_COLUMNS)

    /** The loudest any band has been lately, and how far the display is turned up for it. */
    private var loudest = LIFT_TARGET
    private var lift = 0f

    /** Whether anything was lit after the last move: the move that puts the last of it out is still one to draw. */
    private var wasAlive = false

    /** Everything dark, at once: for a display coming back into view, which is not to show what it last did. */
    fun clear() {
        level.fill(0f)
        cap.fill(0f)
        capHeld.fill(0f)
        capSpeed.fill(0f)
        wasAlive = false
        tick++
    }

    /**
     * Moves the display on by [seconds]. With [sounding], towards what is
     * playing now; without, towards nothing. False once nothing is lit and
     * nothing is moving, which is when to stop asking for frames.
     */
    fun advance(seconds: Float, sounding: Boolean): Boolean {
        val count = columns.coerceIn(1, MAX_COLUMNS)
        val read = sounding && Player.readSpectrum(heard, count)

        if (read) {
            var peak = -1f
            for (i in 0 until count) peak = max(peak, heard[i])
            loudest = max(peak, loudest - LOUDEST_FADE_PER_SECOND * seconds)
            val wanted = (LIFT_TARGET - loudest).coerceIn(0f, MAX_LIFT)
            lift = if (wanted < lift) wanted else min(wanted, lift + LIFT_RISE_PER_SECOND * seconds)
        }

        var alive = false
        for (i in 0 until count) {
            val target = if (read) (heard[i] + lift).coerceIn(0f, 1f) else 0f
            // Up at once, down at the display's own pace.
            val bar = max(target, level[i] - BAR_FALL_PER_SECOND * seconds)
            level[i] = bar

            if (bar >= cap[i]) {
                cap[i] = bar
                capHeld[i] = CAP_HOLD_SECONDS
                capSpeed[i] = 0f
            } else if (capHeld[i] > 0f) {
                capHeld[i] -= seconds
            } else {
                capSpeed[i] += CAP_GRAVITY * seconds
                cap[i] = max(bar, cap[i] - capSpeed[i] * seconds)
            }
            if (bar > 0f || cap[i] > 0f) alive = true
        }
        // Playing silence, nothing moves and nothing is to be drawn again.
        if (alive || wasAlive) tick++
        wasAlive = alive
        return alive
    }
}

/** The display's colours, for the mode the app is in. */
private class SpectrumColors(
    private val low: Color,
    private val lowTop: Color,
    private val amber: Color,
    private val red: Color,
    val unlit: Color,
    val unlitEdge: Color,
    val cap: Color,
    val label: Color,
    val lettering: Color,
    /** How solid a lit segment's colour is, and the strengths of the lights on it. */
    val body: Float,
    val lift: Float,
    val shade: Float,
    val sheen: Float,
    val edge: Float,
    val glow: Float,
    val mirror: Float,
) {
    /** The colour of the row [height] of the way up: 0 the bottom, 1 the top. Fixed to the row, not to the level. */
    fun rowColor(height: Float): Color = when {
        height >= RED_FROM -> red
        height >= AMBER_FROM -> amber
        // A little brighter towards the top of its stretch, as the phosphor was.
        else -> lerp(low, lowTop, height / AMBER_FROM)
    }
}

private fun spectrumColors(colors: LiquidColors): SpectrumColors = if (colors.isDark) {
    SpectrumColors(
        low = colors.teal,
        lowTop = lerp(colors.teal, Color.White, 0.20f),
        amber = colors.orange,
        red = colors.red,
        unlit = Color.White.copy(alpha = 0.065f),
        unlitEdge = Color.White.copy(alpha = 0.07f),
        cap = Color.White.copy(alpha = 0.92f),
        label = colors.secondaryLabel,
        lettering = colors.tertiaryLabel,
        body = 0.96f, lift = 0.22f, shade = 0.30f, sheen = 0.42f, edge = 0.55f, glow = 0.34f, mirror = 0.60f,
    )
} else {
    // On a light page there is no glow to be had: the colours go deeper to
    // stand against it, the lights on them stronger, and what was a glow is
    // the faint colour a bead throws on white.
    SpectrumColors(
        low = lerp(colors.teal, colors.blue, 0.20f),
        lowTop = colors.teal,
        amber = colors.orange,
        red = colors.red,
        unlit = Color.Black.copy(alpha = 0.055f),
        unlitEdge = Color.White.copy(alpha = 0.55f),
        cap = Color.Black.copy(alpha = 0.78f),
        label = colors.secondaryLabel,
        lettering = colors.tertiaryLabel,
        body = 1f, lift = 0.30f, shade = 0.16f, sheen = 0.55f, edge = 0.75f, glow = 0.20f, mirror = 0.45f,
    )
}
