package com.hortus1556.exilum.ui.screens.player.components.views

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.player.model.NoteCell
import com.hortus1556.exilum.player.model.PatternData
import com.hortus1556.exilum.ui.screens.player.PlaybackVisuals
import com.hortus1556.exilum.ui.theme.AppTheme
import com.materialkolor.ktx.lighten
import kotlin.math.ceil
import kotlinx.collections.immutable.toImmutableList

/**
 * How many rows beyond the visible ones the scrolling layers hold. The grid is
 * recorded once and then only translated, so this is the distance the view can
 * scroll before the content has to be recorded again - at a typical 64 or 128
 * row pattern this covers the whole thing, meaning one recording per pattern.
 */
private const val WINDOW_PAD_ROWS = 96

/** Row granularity at which the recorded window is allowed to move, so that
 * scrolling past its edge re-records in one step instead of every row. */
private const val WINDOW_QUANTUM_ROWS = 32

/**
 * The same two for the columns: how many channels beyond the visible ones the
 * cell layer holds on either side, and the step its window moves in. Far
 * smaller than the rows' - a channel is ten characters of text on every row
 * recorded, so each one held off screen costs a column of drawText calls
 * whenever the layer is recorded - but enough that a pan across a wide module
 * re-records every couple of channels rather than every frame. The pad must
 * not be less than the step minus one, or the window could stop short of the
 * last visible column.
 */
private const val WINDOW_PAD_COLUMNS = 2
private const val WINDOW_QUANTUM_COLUMNS = 2

/** Characters a note takes in its cell; the instrument starts after them. */
private const val NOTE_CHARS = 3

data class PatternColors(
    val rowNumberBackground: Color,
    val mutedScrim: Color,
    val rowNumber: Color,
    val note: Color,
    val instrument: Color,
    val effectType: Color,
    val effectParam: Color,
    val empty: Color,
    val beat: Color,
    val currentRow: Color,
    val background: Color,
    val headerBackground: Color,
    val headerText: Color
)

@Composable
fun rememberDefaultPatternColors(): PatternColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        PatternColors(
            rowNumberBackground = Color(0xFF181C24),
            mutedScrim = Color(0xFF0B0E14).copy(alpha = 0.66f),
            rowNumber = Color(0xFF6080A0),
            note = Color(0xFFE0E8F0),
            instrument = Color(0xFF60D0A0),
            effectType = Color(0xFFE08060),
            effectParam = Color(0xFFC0A0E0),
            empty = Color(0xFF303848),
            beat = Color(0xFF1C2030).copy(alpha = 0.40f),
            currentRow = Color(0xFF4060A0),
            background = Color.Black.lighten(1.0f),
            headerBackground = Color(0xFF20283A),
            headerText = Color(0xFFA0C0E0),
        )
    }
}

private class PatternRenderState(
    val rowTexts: Array<String>,
    val headers: Array<String>
)

/**
 * The row the grid last stood on, and the pattern that row was read in - see
 * shownRow in [PatternView]. Plain fields rather than state: it is written
 * while drawing, and must not be what makes anything draw.
 */
private class HeldRow {
    var row = 0
    var pattern = -1
}

private fun buildRenderState(pattern: PatternData) = PatternRenderState(
    rowTexts = Array(pattern.numRows) { "%02X".format(it) },
    headers = Array(pattern.numChannels) { (it + 1).toString() },
)

/**
 * The pattern grid.
 *
 * **Position.** The playing row is whatever [PlaybackVisuals] reports, drawn
 * as a hard cut - row 1 is on screen until the row being *heard* becomes row
 * 2, at which point row 2 is on screen. There is no smoothing, no velocity
 * estimation and no extrapolation anywhere in this file, and that is not a
 * simplification: it is what every reference tracker does. Fasttracker II's
 * clone compares the queued row against the last drawn one and redraws only
 * on a difference; ProTracker 2's clone does the same; neither interpolates
 * between rows. Smoothing only ever made sense here as a workaround for a
 * position that arrived late and jittery, and that is fixed at the source now
 * - the row comes from a frame-stamped snapshot matched against what the
 * speaker has actually emitted, so it is exact and arrives on time.
 *
 * **Cost.** Split into layers by *how often each one changes*, rather than
 * drawn as one pass per frame. The grid's appearance only changes when the
 * pattern or the zoom changes; what changes continuously is only where it
 * sits, down the rows as it plays and across the channels under a finger. So
 * the scrolling parts are recorded into their own layers and the per-row
 * update is a translation of those layers - a transform on an already
 * recorded display list, not hundreds of drawText calls re-issued from
 * scratch. The expensive cell-text layer is keyed to [windowStart], which
 * moves in WINDOW_QUANTUM_ROWS steps, so it re-records roughly once per
 * pattern rather than once per row - and to a window of columns the same
 * way, so a pan sideways moves it rather than recording it again.
 *
 * Layer order matters and is why these are separate: beat tints sit under the
 * current-row highlight, which sits under the text, and the header sits over
 * everything so rows can scroll up underneath it.
 */
@Composable
fun PatternView(
    module: ModuleEntity,
    pattern: PatternData,
    visuals: PlaybackVisuals,
    modifier: Modifier = Modifier,
    colors: PatternColors = rememberDefaultPatternColors(),
    mutes: () -> Long = { 0L },
    /**
     * Whether any of this view is on screen, or about to be. Read where the
     * position is, never in this body - see shownRow below.
     */
    live: () -> Boolean = { true },
    onToggleMute: (channel: Int) -> Unit = {},
    onSolo: (channel: Int, numChannels: Int) -> Unit = { _, _ -> },
) {
    // The engine's mutes, bit n for channel n - the same copy ChannelView draws
    // from. [mutes] is read inside the draw passes below, so a mute redraws the
    // header and the scrim without recomposing the grid.
    //
    // This used to be an array of its own, read back from the engine only when
    // the module changed. ChannelView kept another, and with both views built
    // side by side in the pager a mute made in one never reached the other.
    fun isMuted(ch: Int) = ch in 0 until 64 && (mutes() and (1L shl ch)) != 0L
    val toggleMute by rememberUpdatedState(onToggleMute)
    val solo by rememberUpdatedState(onSolo)

    var zoom by remember { mutableFloatStateOf(1f) }
    // Split rather than one Offset: the vertical component is consumed only by
    // the layer transforms (free to change), while the horizontal one is also
    // read where the header and the muted columns are drawn (changing it
    // records those again). Keeping them in one state object would make a
    // vertical drag invalidate that content too.
    //
    // The cells used to be among what the horizontal one was baked into, and
    // nothing locks a drag to one axis: on any module wider than the view, a
    // finger going up or down wobbles sideways by a pixel or two on its way,
    // and each of those pixels recorded the whole window of cell text again -
    // well over a thousand drawText calls on every frame of the drag. They
    // are moved by their layer now, like the rows.
    var userOffsetX by remember { mutableFloatStateOf(0f) }
    var userOffsetY by remember { mutableFloatStateOf(0f) }

    // Zoom deliberately survives: it is how the user has chosen to read a
    // pattern, not something about this particular module. Only the pan is
    // reset, because a position inside the previous pattern means nothing in
    // the next one.
    LaunchedEffect(module) {
        userOffsetX = 0f
        userOffsetY = 0f
    }

    val renderState = remember(pattern.patternIndex, pattern.numRows, pattern.numChannels) {
        buildRenderState(pattern)
    }

    // Read through this rather than captured: windowStart below is remembered,
    // and would go on asking the lambda it was first built with.
    val isLive by rememberUpdatedState(live)

    // The row the grid stands on: the one playing, but for two cases.
    //
    // Off screen it is not followed at all. The pager keeps every view built
    // beside the one showing, and a layer that reads a ticket is recorded
    // again whenever the ticket moves, whether or not anything goes on to
    // draw it - so with the channels or the spectrum showing, this view still
    // re-issued its row numbers on every row. Not reading the ticket is what
    // stops that, and live() changing is itself what brings the grid up to
    // date the moment a swipe heads here.
    //
    // And across a change of pattern it stays on the last row of the old one.
    // The position moves a frame or two before the cells do - they are
    // fetched off the main thread, once the screen has seen the new pattern's
    // number - and following it meanwhile drew the old pattern's rows at the
    // new pattern's row: a flash of the wrong cells, highlighted, at every
    // boundary. Held only for the pattern the row was read in, so cells that
    // belong neither to what is playing nor to what was last drawn - or no
    // cells at all - cannot leave the grid standing still.
    val held = remember { HeldRow() }
    fun shownRow(): Int {
        if (!isLive()) return held.row
        @Suppress("UNUSED_EXPRESSION") visuals.positionTick
        val snapshot = visuals.snapshot
        if (snapshot.pattern == pattern.patternIndex) {
            held.row = snapshot.row
            held.pattern = snapshot.pattern
        } else if (pattern.numRows == 0 || held.pattern != pattern.patternIndex) {
            return snapshot.row
        }
        return held.row
    }

    val density = LocalDensity.current
    val baseRowHeight = with(density) { 14.sp.toPx() }
    val baseColumnGap = with(density) { 6.dp.toPx() }
    val rowNumberWidth = with(density) { 26.dp.toPx() }

    val fontSizePx = with(density) { 12.sp.toPx() } * zoom
    val rowHeight = baseRowHeight * zoom
    val columnGap = baseColumnGap * zoom
    val scaledRowNumWidth = rowNumberWidth * zoom
    val headerHeight = rowHeight * 1.5f

    // Raw android.graphics.Paint instead of Compose's TextMeasurer/TextLayoutResult:
    // measured at up to 16ms per Canvas draw pass with the TextLayoutResult approach
    // (hundreds of drawText(TextLayoutResult) calls per frame for a full channel grid,
    // each carrying real per-call overhead even with pre-measured/cached layouts).
    // Every string here is short, single-style, monospace text - exactly the case
    // where plain Canvas.drawText() is dramatically cheaper. Widths are derived from
    // a single measured character cell rather than measuring each string, since
    // monospace guarantees width = text.length * charWidth.
    val textPaint = remember(fontSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = fontSizePx
        }
    }
    val headerPaint = remember(fontSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = fontSizePx
        }
    }
    val charWidth = remember(fontSizePx) { textPaint.measureText("0") }
    val channelWidth = charWidth * 10 + columnGap // "C-3 01 F00" is 10 chars

    // How far the grid may be pushed up or down.
    //
    // Set from the viewport below, since only that knows how tall the grid is.
    // Without a limit the pattern could be dragged clean off the screen and
    // there was nothing left to grab to bring it back - a long press reset it,
    // but only if you knew that.
    val maxOffsetYRef = remember { mutableFloatStateOf(0f) }

    val channelWidthRef = remember { mutableFloatStateOf(channelWidth) }
    val rowNumWidthRef = remember { mutableFloatStateOf(scaledRowNumWidth) }
    channelWidthRef.floatValue = channelWidth
    rowNumWidthRef.floatValue = scaledRowNumWidth

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            // One detector for all three gestures - see detectViewGestures for
            // why they cannot be two.
            .pointerInput(pattern.numChannels) {
                // The whole column, header included: a channel is a vertical
                // strip here, so that is the target.
                fun channelAt(offset: Offset): Int {
                    val cw = channelWidthRef.floatValue
                    val rnw = rowNumWidthRef.floatValue
                    if (offset.x < rnw || cw <= 0f) return -1
                    val ch = ((offset.x - rnw - userOffsetX) / cw).toInt()
                    return if (ch in 0 until pattern.numChannels) ch else -1
                }

                fun minOffsetX(): Float {
                    val cw = channelWidthRef.floatValue
                    val rnw = rowNumWidthRef.floatValue
                    return ((size.width - rnw) - pattern.numChannels * cw).coerceAtMost(0f)
                }

                detectViewGestures(
                    onTap = { offset ->
                        val ch = channelAt(offset)
                        if (ch >= 0) toggleMute(ch)
                    },
                    // On a channel it solos; on the row-number gutter, where
                    // there is no channel to solo, it still resets the view.
                    onLongPress = { offset ->
                        val ch = channelAt(offset)
                        if (ch >= 0) {
                            solo(ch, pattern.numChannels)
                        } else {
                            zoom = 1f
                            userOffsetX = 0f
                            userOffsetY = 0f
                        }
                    },
                    onTransform = { pan, gestureZoom ->
                        zoom = (zoom * gestureZoom).coerceIn(0.5f, 3f)
                        userOffsetX = (userOffsetX + pan.x).coerceIn(minOffsetX(), 0f)
                        val limitY = maxOffsetYRef.floatValue
                        userOffsetY = (userOffsetY + pan.y).coerceIn(-limitY, limitY)
                    },
                )
            }
    ) {
        val viewHeight = with(density) { maxHeight.toPx() }
        val gridHeight = (viewHeight - headerHeight).coerceAtLeast(0f)

        // Enough to put the playing row anywhere from the top of the grid to
        // the bottom of it, and no further: past that the row it is all
        // anchored to would be off screen.
        val maxOffsetY = (gridHeight / 2f - rowHeight / 2f).coerceAtLeast(0f)
        maxOffsetYRef.floatValue = maxOffsetY

        // Zooming in shortens the grid's half-height, so an offset that was
        // legal at the previous zoom can be past the new limit.
        LaunchedEffect(maxOffsetY) {
            userOffsetY = userOffsetY.coerceIn(-maxOffsetY, maxOffsetY)
        }
        val numRows = pattern.numRows
        val numChannels = pattern.numChannels

        val visibleRows = if (rowHeight > 0f) ceil(gridHeight / rowHeight).toInt() + 2 else 0
        val windowRows = (visibleRows + WINDOW_PAD_ROWS).coerceAtMost(numRows).coerceAtLeast(0)

        // derivedStateOf, so the expensive layers below are only invalidated
        // when the recorded window actually moves. positionTick changes every
        // row; this changes every WINDOW_QUANTUM_ROWS rows at most, and not at
        // all for a pattern that fits in the window entirely (the common
        // 64/128-row case).
        //
        // Keyed on the pattern as well as its size: shownRow asks which
        // pattern the cells are, and this would go on asking about the one it
        // was first built for.
        val windowStart by remember(visuals, windowRows, numRows, pattern.patternIndex) {
            derivedStateOf {
                val maxStart = (numRows - windowRows).coerceAtLeast(0)
                val desired = (shownRow() - windowRows / 2).coerceAtLeast(0)
                ((desired / WINDOW_QUANTUM_ROWS) * WINDOW_QUANTUM_ROWS).coerceIn(0, maxStart)
            }
        }

        // The same for the channels: which of them the cell layer holds. As
        // many as fit across the grid and a few more either side, starting
        // where the pan has got to, in steps - so a pan is a translation of
        // that layer until it has gone a couple of channels, and only then a
        // recording of it. A module no wider than the window is recorded once.
        val viewWidth = with(density) { maxWidth.toPx() }
        val gridWidth = (viewWidth - scaledRowNumWidth).coerceAtLeast(0f)
        val visibleColumns = if (channelWidth > 0f) ceil(gridWidth / channelWidth).toInt() + 1 else 0
        val windowColumns = (visibleColumns + WINDOW_PAD_COLUMNS * 2).coerceAtMost(numChannels).coerceAtLeast(0)
        val columnStart by remember(windowColumns, numChannels, channelWidth) {
            derivedStateOf {
                val maxStart = (numChannels - windowColumns).coerceAtLeast(0)
                val first = if (channelWidth > 0f) (-userOffsetX / channelWidth).toInt() else 0
                val desired = (first - WINDOW_PAD_COLUMNS).coerceAtLeast(0)
                ((desired / WINDOW_QUANTUM_COLUMNS) * WINDOW_QUANTUM_COLUMNS).coerceIn(0, maxStart)
            }
        }

        // Shared by every scrolling layer. Reading state here rather than in a
        // draw scope is the whole point: a read inside a graphicsLayer block
        // re-runs only the block, leaving the recorded content untouched - so
        // a row change costs one float computation and a layer transform, not
        // a redraw of the grid.
        //
        // No clip = true here, deliberately. A layer's clip lives in the
        // layer's own coordinate space, so it travels with translationY
        // instead of staying put over the viewport: the further the grid
        // scrolled, the smaller the painted region became, which showed up as
        // most of the pattern simply not being drawn. Clipping to the viewport
        // is the parent's job - hence clipToBounds() on the box above.
        val scrollTransform: GraphicsLayerScope.() -> Unit = {
            val row = shownRow()
            translationY = -(row * rowHeight - gridHeight / 2f + rowHeight / 2f) +
                userOffsetY + headerHeight + windowStart * rowHeight
        }

        // 1. Static ground: page background and the row-number column's strip.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = colors.background, size = size)
            drawRect(
                color = colors.rowNumberBackground,
                topLeft = Offset(0f, headerHeight),
                size = Size(scaledRowNumWidth, (size.height - headerHeight).coerceAtLeast(0f))
            )
        }

        // 2. Beat tints - scrolls, and must stay under the highlight, so it
        // can't share a layer with the cells above it.
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(scrollTransform)) {
            if (numRows == 0 || numChannels == 0) return@Canvas
            val start = windowStart
            val end = (start + windowRows).coerceAtMost(numRows)
            for (row in start until end) {
                if (row % 4 != 0) continue
                drawRect(
                    color = colors.beat,
                    topLeft = Offset(scaledRowNumWidth, (row - start) * rowHeight),
                    size = Size((size.width - scaledRowNumWidth).coerceAtLeast(0f), rowHeight)
                )
            }
        }

        // 3. Current-row highlight. Fixed on screen: the scroll offset is
        // defined so the current row always lands here, so the row scrolls
        // under a stationary band rather than the band chasing the row - the
        // same fixed-centre-row layout FT2 and ProTracker both use.
        Canvas(modifier = Modifier.fillMaxSize()) {
            clipRect(top = headerHeight) {
                drawRect(
                    color = colors.currentRow,
                    topLeft = Offset(0f, gridHeight / 2f - rowHeight / 2f + userOffsetY + headerHeight),
                    size = Size(size.width, rowHeight)
                )
            }
        }

        // 4. Cell text - the expensive layer, and the one that benefits most
        // from being recorded once and then only translated. Note it reads
        // windowStart and columnStart, and nothing that moves per row or per
        // pixel of a pan: where it sits, both ways, is its layer's transform.
        //
        // The box around it is there to keep the cells out of the row-number
        // gutter. That was a clip inside the layer while the layer only ever
        // moved up and down; moving sideways too, it would carry the clip
        // along with it - the same thing the note on scrollTransform says of
        // the viewport. So the edge is held here, by what does not move.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(left = scaledRowNumWidth) { this@drawWithContent.drawContent() }
                }
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scrollTransform()
                    translationX = userOffsetX
                }
            ) {
                if (numRows == 0 || numChannels == 0) return@Canvas
                val start = windowStart
                val end = (start + windowRows).coerceAtMost(numRows)
                val firstColumn = columnStart
                val endColumn = (firstColumn + windowColumns).coerceAtMost(numChannels)
                val topAlignOffset = topAlignedBaselineY(textPaint)
                // Bounds set to the recorded content's own extent rather than
                // the view's: these coordinates are pre-translation, so
                // clipping to the view's size here would cut off whatever part
                // of the window currently sits below or beside the viewport.
                clipRect(
                    left = scaledRowNumWidth + firstColumn * channelWidth,
                    top = 0f,
                    right = scaledRowNumWidth + endColumn * channelWidth,
                    bottom = windowRows * rowHeight
                ) {
                    drawIntoCanvas { canvas ->
                        for (row in start until end) {
                            val rowY = (row - start) * rowHeight
                            val rowCells = pattern.cells[row]
                            for (ch in firstColumn until endColumn) {
                                drawCell(
                                    canvas = canvas.nativeCanvas,
                                    paint = textPaint,
                                    charWidth = charWidth,
                                    cell = rowCells[ch],
                                    colors = colors,
                                    x = scaledRowNumWidth + ch * channelWidth,
                                    baselineY = rowY + topAlignOffset
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Row numbers - separate from the cells because the active row is
        // drawn in a different colour, so this has to be re-recorded once per
        // row. One drawText per row is cheap; re-recording the whole cell grid
        // that often would not be.
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(scrollTransform)) {
            if (numRows == 0) return@Canvas
            val current = shownRow()
            val start = windowStart
            val end = (start + windowRows).coerceAtMost(numRows)
            val rowTexts = renderState.rowTexts
            clipRect(
                left = 0f,
                top = 0f,
                right = scaledRowNumWidth,
                bottom = windowRows * rowHeight
            ) {
                drawIntoCanvas { canvas ->
                    for (row in start until end) {
                        val text = rowTexts[row]
                        val textWidth = text.length * charWidth
                        textPaint.color =
                            if (row == current) Color.White.toArgb() else colors.rowNumber.toArgb()
                        canvas.nativeCanvas.drawText(
                            text,
                            (scaledRowNumWidth - textWidth) / 2f,
                            rowCenterBaselineY(textPaint, (row - start) * rowHeight, rowHeight),
                            textPaint
                        )
                    }
                }
            }
        }

        // 6. Header, drawn last so scrolling rows pass underneath it - and
        // with it the muted-channel scrim, which has to sit above the grid to
        // read as "this column is off".
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (numChannels == 0) return@Canvas
            // Read once for the pass, not once for every channel in it.
            val offsetX = userOffsetX

            clipRect(left = scaledRowNumWidth, top = 0f, right = size.width, bottom = size.height) {
                for (ch in 0 until numChannels) {
                    if (!isMuted(ch)) continue
                    val cellX = scaledRowNumWidth + ch * channelWidth + offsetX
                    if (cellX + channelWidth < 0 || cellX > size.width) continue
                    drawRect(
                        color = colors.mutedScrim,
                        topLeft = Offset(cellX, 0f),
                        size = Size(channelWidth, size.height)
                    )
                }
            }

            drawRect(
                color = colors.headerBackground,
                topLeft = Offset.Zero,
                size = Size(size.width, headerHeight)
            )
            clipRect(left = scaledRowNumWidth, top = 0f, right = size.width, bottom = headerHeight) {
                val baselineY = headerY(headerPaint, headerHeight)
                drawIntoCanvas { canvas ->
                    for (ch in 0 until numChannels) {
                        val cellX = scaledRowNumWidth + ch * channelWidth + offsetX
                        if (cellX + channelWidth < 0 || cellX > size.width) continue
                        headerPaint.color = if (isMuted(ch)) {
                            colors.empty.toArgb()
                        } else {
                            colors.headerText.toArgb()
                        }
                        val text = renderState.headers[ch]
                        val textWidth = text.length * charWidth
                        canvas.nativeCanvas.drawText(
                            text,
                            cellX + (channelWidth - columnGap - textWidth) / 2f,
                            baselineY,
                            headerPaint
                        )
                    }
                }
            }
        }
    }
}

/** Baseline Y to vertically center text within a [0, slotHeight] slot. */
private fun headerY(paint: Paint, slotHeight: Float): Float {
    val fm = paint.fontMetrics
    val textHeight = fm.descent - fm.ascent
    return (slotHeight - textHeight) / 2f - fm.ascent
}

/** Baseline Y to vertically center text within a row starting at [rowY]. */
private fun rowCenterBaselineY(paint: Paint, rowY: Float, rowHeight: Float): Float {
    val fm = paint.fontMetrics
    val textHeight = fm.descent - fm.ascent
    return rowY + (rowHeight - textHeight) / 2f - fm.ascent
}

/** Baseline Y offset so text's top edge lands at the row's top. */
private fun topAlignedBaselineY(paint: Paint): Float = -paint.fontMetrics.ascent

private fun drawCell(
    canvas: android.graphics.Canvas,
    paint: Paint,
    charWidth: Float,
    cell: NoteCell,
    colors: PatternColors,
    x: Float,
    baselineY: Float
) {
    val gap = paint.textSize * 0.25f

    // note
    paint.color = if (cell.note == 0) colors.empty.toArgb() else colors.note.toArgb()
    canvas.drawText(cell.noteStr, x, baselineY, paint)

    // instrument - a note's width on from it, whatever this note is. A key
    // off, a cut and a fade are written with two characters where a note has
    // three, and measured from the text itself the instrument and the effect
    // on those rows sat a character to the left of the rows around them.
    val insX = x + maxOf(cell.noteStr.length, NOTE_CHARS) * charWidth + gap
    paint.color = if (cell.instrument == 0) colors.empty.toArgb() else colors.instrument.toArgb()
    canvas.drawText(cell.instrumentStr, insX, baselineY, paint)

    // effect - fall back to the secondary slot (volume column) when the primary is empty
    val fxX = insX + cell.instrumentStr.length * charWidth + gap
    val showSecondary = cell.fxType < 0 && cell.fx2Type >= 0
    if (cell.fxType < 0 && !showSecondary) {
        paint.color = colors.empty.toArgb()
        canvas.drawText("...", fxX, baselineY, paint)
    } else {
        val typeStr = if (showSecondary) cell.effect2TypeStr else cell.effectTypeStr
        val paramStr = if (showSecondary) cell.effect2ParamStr else cell.effectParamStr
        paint.color = colors.effectType.toArgb()
        canvas.drawText(typeStr, fxX, baselineY, paint)
        paint.color = colors.effectParam.toArgb()
        canvas.drawText(paramStr, fxX + typeStr.length * charWidth, baselineY, paint)
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 600)
@Composable
private fun PreviewPatternView() {
    val sample = PatternData(
        patternIndex = 0,
        numRows = 64,
        numChannels = 8,
        cells = List(64) { row ->
            List(8) { ch ->
                if (row % 2 == 0 && ch < 4) {
                    NoteCell(
                        note = 37 + (row / 4) % 12,
                        instrument = ch + 1,
                        fxType = if (ch == 0) 0 else -1,
                        fxParam = if (ch == 0) row else -1,
                    )
                } else {
                    NoteCell(fxParam = 0, fxType = 0, instrument = -1, note = -1)
                }
            }.toImmutableList()
        }.toImmutableList(),
    )
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            PatternView(
                module = ModuleEntity(),
                pattern = sample,
                visuals = remember { PlaybackVisuals() },
            )
        }
    }
}
