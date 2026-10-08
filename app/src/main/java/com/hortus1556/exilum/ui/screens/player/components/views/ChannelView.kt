package com.hortus1556.exilum.ui.screens.player.components.views

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.*
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
import androidx.compose.ui.input.pointer.util.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.tooling.preview.datasource.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.ui.screens.player.PlaybackVisuals
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.renderer.model.MAX_CHANNELS
import com.hortus1556.exilum.renderer.model.PlaybackSnapshot
import com.materialkolor.ktx.darken
import com.materialkolor.ktx.lighten
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

/** Shortest a scope tile is allowed to get before the grid drops a column. */
private val MIN_TILE_WIDTH = 150.dp

/** Tile height as a fraction of its width, in scopes-only mode. */
private const val TILE_ASPECT = 0.62f

/**
 * Where each channel's waveform box sits, for both layouts.
 *
 * Every layer and the tap handler go through this, so the trace, the chrome
 * drawn under it and the channel a tap mutes cannot drift apart - which is
 * the failure mode as soon as two of them compute positions separately.
 */
private class ChannelGeometry(
    val scopesOnly: Boolean,
    val rowHeight: Float,
    val chNumWidth: Float,
    val waveWidth: Float,
    val pad: Float,
    val minTileWidth: Float,
) {
    fun columns(viewWidth: Float): Int =
        if (!scopesOnly) 1 else (viewWidth / minTileWidth).toInt().coerceAtLeast(1)

    fun cellWidth(viewWidth: Float): Float =
        if (!scopesOnly) viewWidth else viewWidth / columns(viewWidth)

    fun cellHeight(viewWidth: Float): Float =
        if (!scopesOnly) rowHeight else cellWidth(viewWidth) * TILE_ASPECT

    fun contentHeight(viewWidth: Float, numChannels: Int): Float =
        if (!scopesOnly) {
            rowHeight * numChannels
        } else {
            cellHeight(viewWidth) * ceil(numChannels / columns(viewWidth).toFloat())
        }

    /** The waveform box for [ch] in view coordinates, scroll already applied. */
    fun box(ch: Int, viewWidth: Float, scrollY: Float): Rect =
        if (!scopesOnly) {
            val top = ch * rowHeight - scrollY + pad
            Rect(chNumWidth, top, chNumWidth + waveWidth, top + rowHeight - pad * 2)
        } else {
            val cols = columns(viewWidth)
            val cw = cellWidth(viewWidth)
            val ch2 = cellHeight(viewWidth)
            val left = (ch % cols) * cw
            val top = (ch / cols) * ch2 - scrollY
            Rect(left + pad, top + pad, left + cw - pad, top + ch2 - pad)
        }

    /** Channel whose box contains the point, or -1. */
    fun channelAt(x: Float, y: Float, viewWidth: Float, scrollY: Float, numChannels: Int): Int {
        if (!scopesOnly) {
            val ch = ((y + scrollY) / rowHeight).toInt()
            val inWave = x >= chNumWidth && x <= chNumWidth + waveWidth
            return if (ch in 0 until numChannels && inWave) ch else -1
        }
        val cols = columns(viewWidth)
        val col = (x / cellWidth(viewWidth)).toInt().coerceIn(0, cols - 1)
        val row = ((y + scrollY) / cellHeight(viewWidth)).toInt()
        val ch = row * cols + col
        return if (ch in 0 until numChannels) ch else -1
    }
}

data class ChannelViewColors(
    val background: Color,
    val channelNumber: Color,
    val rowSeparator: Color,
    val waveformBackground: Color,
    val waveformBorder: Color,
    val waveformBaseline: Color,
    val waveformLine: Color,
    val instrumentText: Color,
    val volumeBarTrack: Color,
    val volumeBar: Color,
    val finalVolumeBar: Color,
    val panRail: Color,
    val panCursor: Color
)

@Composable
fun rememberDefaultChannelViewColors(): ChannelViewColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        ChannelViewColors(
            background = Color.Black.lighten(1.0f),
            channelNumber = Color.White,
            rowSeparator = Color(0xFF1A2030),
            waveformBackground = Color(0xFF0C1018),
            waveformBorder = Color(0xFF303848),
            waveformBaseline = Color(0xFF1E2830),
            waveformLine = Color(0xFF40C878),
            instrumentText = Color(0xFF60D0A0),
            volumeBarTrack = Color.DarkGray.darken(1.5f),
            volumeBar = Color.Blue.lighten(1.1f),
            finalVolumeBar = Color.Gray,
            panRail = Color(0xFF2E3848),
            panCursor = Color(0xFF8898AA),
        )
    }
}

/**
 * Per-channel scopes, levels and pan.
 *
 * **Where the traces come from.** Not from the mixed output buffer - from each
 * channel's own instrument sample, walked from that channel's exact playback
 * position. That is how real trackers draw scopes: FT2's clone keeps a shadow
 * voice per channel (sample pointer, 32.32 position, per-pixel step) and its
 * whole draw loop is "fetch the sample at the read position, plot it, advance,
 * wrap at the loop point". The walk itself happens natively here, from a
 * frame-stamped snapshot, so the window advances in exact lockstep with the
 * audio being heard. The previous version extrapolated the position by
 * elapsed wall-clock time on every call, which handed consecutive frames
 * windows offset by an arbitrary amount - a moving window sampled at an
 * unrelated rate, which is textbook stroboscopic aliasing and is what made
 * the trace look doubled.
 *
 * **When anything redraws.** Three canvases, split by how often their contents
 * actually change, so no pass ever re-issues work that did not change:
 *
 *  - chrome (numbers, boxes, borders, instrument names) - only when a channel
 *    changes what it plays, or mute/scroll moves;
 *  - traces - only when there is genuinely new waveform, which by definition
 *    means at least one channel is audible. Silence redraws nothing;
 *  - levels (volume bars, pan cursor) - cheap shapes, on level changes.
 *
 * No timer, no fixed interval, no refresh-rate coupling anywhere: everything
 * is driven by [PlaybackVisuals], which reads once per display frame and
 * reports what actually moved.
 */
@Composable
fun ChannelView(
    numChannels: Int,
    instrumentNames: ImmutableList<String>,
    sampleNames: ImmutableList<String>,
    visuals: PlaybackVisuals,
    scopesOnly: Boolean,
    modifier: Modifier = Modifier,
    colors: ChannelViewColors = rememberDefaultChannelViewColors(),
    mutes: () -> Long = { 0L },
    /**
     * Whether any of this view is on screen, or about to be. Read inside the
     * draw passes, like [mutes]: beside the view that is showing the pager
     * keeps this one built, and its layers went on being recorded for nobody.
     */
    live: () -> Boolean = { true },
    onToggleMute: (channel: Int) -> Unit = {},
    onSolo: (channel: Int, numChannels: Int) -> Unit = { _, _ -> },
) {
    // The engine's mutes, bit n for channel n - the same copy PatternView draws
    // from. [mutes] is read inside the draw passes below, so a mute redraws
    // the rows without recomposing the view.
    //
    // This used to be an array of its own, read back from the engine only when
    // the channel count changed. PatternView kept another; with both built side
    // by side in the pager a mute made there never reached here, and a track
    // with the same number of channels as the last one kept showing that
    // track's mutes over a new one the engine had unmuted.
    fun isMuted(ch: Int) = ch in 0 until 64 && (mutes() and (1L shl ch)) != 0L
    val toggleMute by rememberUpdatedState(onToggleMute)
    val solo by rememberUpdatedState(onSolo)

    // Per-channel gain the trace layer eases towards. Kept out of Compose
    // state on purpose: it changes every frame and nothing but the trace
    // layer reads it, so making it observable would recompose the view sixty
    // times a second to redraw the same thing.
    val traceGain = remember { FloatArray(MAX_CHANNELS) { 1f } }

    // The time base each channel is drawn at, the value waiting to replace it,
    // and how many frames running that value has agreed with itself. See the
    // note where they are used.
    val traceSpan = remember { IntArray(MAX_CHANNELS) }
    val spanCandidate = remember { IntArray(MAX_CHANNELS) }
    val spanAgreed = remember { IntArray(MAX_CHANNELS) }

    var zoom by remember { mutableFloatStateOf(1f) }
    // Deliberately NOT read in the composable body. A fling runs this
    // Animatable at the display's frame rate, and reading it during
    // composition makes every one of those frames recompose the entire view -
    // measure and layout included - when all that changes is where the rows
    // are painted. Each Canvas reads it inside its own draw scope instead, so
    // a scroll costs a redraw and nothing more.
    val scrollYAnim = remember { Animatable(0f) }
    val decay = remember { exponentialDecay<Float>() }
    val coroutineScope = rememberCoroutineScope()

    // Same as PatternView: the zoom is the user's choice of how to read this,
    // and carries over. Scroll does not - a different module has a different
    // number of channels - and solo cannot, since starting a module unmutes
    // every channel natively.
    //
    // A module's channels can also grow as it plays - the Nintendo 64's
    // voices appear as the game first uses them - which only adds rows below:
    // the place is kept then. A load always goes through no channels at all.
    var shownChannels by remember { mutableIntStateOf(0) }
    LaunchedEffect(numChannels) {
        if (shownChannels == 0 || numChannels < shownChannels) scrollYAnim.snapTo(0f)
        shownChannels = numChannels
    }
    // Read by the gesture handlers, so a channel appearing mid-gesture does not
    // restart them and drop the finger.
    val channelCount by rememberUpdatedState(numChannels)

    val density = LocalDensity.current
    val baseRowHeight = with(density) { 52.dp.toPx() }
    val baseChNumWidth = with(density) { 26.dp.toPx() }
    val baseWaveWidth = with(density) { 96.dp.toPx() }
    val basePad = with(density) { 4.dp.toPx() }

    val fontSizePx = with(density) { 11.sp.toPx() } * zoom

    // Raw android.graphics.Paint rather than Compose's TextMeasurer: measured
    // at up to 10.5ms per draw pass with TextLayoutResult. See PatternView.kt
    // for the full rationale - same fix, same reasoning.
    val textPaint = remember(fontSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = fontSizePx
        }
    }
    val panLLabelWidth = remember(fontSizePx) { textPaint.measureText("L [") }
    val panRLabelWidth = remember(fontSizePx) { textPaint.measureText("] R") }

    // Shared layout, computed once per composition for all three canvases.
    val rowHeight = baseRowHeight * zoom
    val pad = basePad * zoom
    val chNumWidth = baseChNumWidth * zoom
    val waveWidth = baseWaveWidth * zoom
    val infoX = chNumWidth + waveWidth + pad * 2
    val lineH = rowHeight / 3f
    val minTileWidth = with(density) { MIN_TILE_WIDTH.toPx() } * zoom

    // Every layer and the tap handler position channels through this one
    // object, so what is drawn and what a tap hits cannot disagree.
    val geometry = remember(scopesOnly, rowHeight, chNumWidth, waveWidth, pad, minTileWidth) {
        ChannelGeometry(
            scopesOnly = scopesOnly,
            rowHeight = rowHeight,
            chNumWidth = chNumWidth,
            waveWidth = waveWidth,
            pad = pad,
            minTileWidth = minTileWidth,
        )
    }
    // Read by the gesture handlers, which run outside composition and so must
    // not close over a value that a later recomposition replaces.
    val geometryRef = remember { mutableStateOf(geometry) }
    geometryRef.value = geometry

    // Whatever changes how tall the channels stand in all - rows for tiles, a
    // pinch, the window itself - can leave the scroll past the new end, and
    // only the next drag used to bring it back. Going from rows to tiles on a
    // wide window while scrolled to the bottom put every box above the top of
    // the view, and the view stayed empty until it was touched.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(geometry, numChannels, viewSize) {
        if (viewSize == IntSize.Zero) return@LaunchedEffect
        val limit = (geometry.contentHeight(viewSize.width.toFloat(), numChannels) - viewSize.height)
            .coerceAtLeast(0f)
        if (scrollYAnim.value > limit) scrollYAnim.snapTo(limit)
    }

    val tracePath = remember { Path() }

    Box(modifier = modifier.onSizeChanged { viewSize = it }) {
        // ---- Layer 1: chrome -------------------------------------------------
        // Everything that does not change with a volume envelope: the frame of
        // the view, the channel numbers, the scope boxes and the instrument
        // names. Carries the gestures for the whole view; the layers above it
        // are pure paint and take no input.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                // One detector for scroll, fling, pinch, tap and long press.
                // The scroll loop this replaces consumed the pointer on any
                // movement at all, including a single pixel, which cancelled
                // the tap detector stacked on top of it - so muting a channel
                // here only worked if the finger did not move even slightly.
                .pointerInput(Unit) {
                    fun maxScroll(): Float =
                        (geometryRef.value.contentHeight(size.width.toFloat(), channelCount) - size.height)
                            .coerceAtLeast(0f)

                    fun channelAt(offset: Offset): Int = geometryRef.value.channelAt(
                        offset.x, offset.y, size.width.toFloat(), scrollYAnim.value, channelCount,
                    )

                    detectViewGestures(
                        onGestureStart = { coroutineScope.launch { scrollYAnim.stop() } },
                        onTap = { offset ->
                            val ch = channelAt(offset)
                            if (ch >= 0) toggleMute(ch)
                        },
                        onLongPress = { offset ->
                            val ch = channelAt(offset)
                            if (ch >= 0) {
                                solo(ch, channelCount)
                            } else {
                                zoom = 1f
                                coroutineScope.launch { scrollYAnim.snapTo(0f) }
                            }
                        },
                        onTransform = { pan, gestureZoom ->
                            if (gestureZoom != 1f) {
                                zoom = (zoom * gestureZoom).coerceIn(0.5f, 3f)
                            }
                            coroutineScope.launch {
                                scrollYAnim.snapTo((scrollYAnim.value - pan.y).coerceIn(0f, maxScroll()))
                            }
                        },
                        onFling = { velocity ->
                            coroutineScope.launch {
                                scrollYAnim.updateBounds(0f, maxScroll())
                                // Lifted again however the fling ends. A touch
                                // stops it by cancelling it, which used to
                                // skip the line that did this - and the old
                                // limit then held every scroll after it, so
                                // rows added since by a pinch, or by tiles
                                // going back to rows, could not be reached
                                // until another fling had run its course.
                                try {
                                    scrollYAnim.animateDecay(-velocity.y, decay)
                                } finally {
                                    scrollYAnim.updateBounds(null, null)
                                }
                            }
                        },
                    )
                }
        ) {
            // Read in the draw phase so this pass re-runs on a mute toggle or
            // when a channel switches instrument - and on nothing else. Not
            // even on that while the view is off screen: see the levels below.
            @Suppress("UNUSED_EXPRESSION") mutes()
            if (live()) {
                @Suppress("UNUSED_EXPRESSION") visuals.voiceTick
            }
            if (numChannels == 0) return@Canvas
            val scrollY = scrollYAnim.value
            val snapshot = visuals.snapshot

            drawRect(color = colors.background, size = size)

            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                for (ch in 0 until numChannels) {
                    val box = geometry.box(ch, size.width, scrollY)
                    if (box.bottom < 0f || box.top > size.height) continue

                    val muted = isMuted(ch)
                    val alpha = if (muted) 0.3f else 1f

                    if (!scopesOnly && ch > 0) {
                        val separatorY = ch * rowHeight - scrollY
                        drawLine(
                            color = colors.rowSeparator,
                            start = Offset(0f, separatorY),
                            end = Offset(size.width, separatorY),
                            strokeWidth = 1f
                        )
                    }

                    val chText = (ch + 1).toString()
                    val chTextWidth = textPaint.measureText(chText)
                    textPaint.color = colors.channelNumber.copy(alpha = alpha).toArgb()
                    // Row layout only: the number lives in the gutter to the
                    // left of the box, so nothing later paints over it. A tile
                    // has no gutter and has to wait until after the box is
                    // filled - see below.
                    if (!scopesOnly) {
                        nativeCanvas.drawText(
                            chText,
                            (chNumWidth - chTextWidth) / 2f,
                            centerBaselineY(textPaint, box.top + box.height / 2f),
                            textPaint
                        )
                    }

                    drawRect(
                        color = colors.waveformBackground,
                        topLeft = Offset(box.left, box.top),
                        size = Size(box.width, box.height)
                    )
                    drawRect(
                        color = colors.waveformBorder,
                        topLeft = Offset(box.left, box.top),
                        size = Size(box.width, box.height),
                        style = Stroke(1f)
                    )
                    val midY = box.top + box.height / 2f
                    drawLine(
                        color = colors.waveformBaseline,
                        start = Offset(box.left + 1f, midY),
                        end = Offset(box.right - 1f, midY),
                        strokeWidth = 1f
                    )

                    if (scopesOnly) {
                        // Inside the box's top-left corner, and drawn after the
                        // fill rather than before it - painted first it was
                        // simply covered by the box's own background, which is
                        // why tiles looked unnumbered.
                        val labelY = box.top + pad - textPaint.fontMetrics.ascent
                        nativeCanvas.drawText(chText, box.left + pad * 2, labelY, textPaint)

                        // What it is playing, next to the number, ellipsised to
                        // whatever the tile leaves after it.
                        val nameX = box.left + pad * 2 + chTextWidth + pad * 2
                        val room = box.right - pad * 2 - nameX
                        if (room > 0f) {
                            val name = if (muted) {
                                "Muted"
                            } else {
                                voiceName(snapshot, ch, instrumentNames, sampleNames)
                            }
                            textPaint.color = if (muted) {
                                Color.Red.toArgb()
                            } else {
                                colors.instrumentText.toArgb()
                            }
                            nativeCanvas.drawText(ellipsise(name, textPaint, room), nameX, labelY, textPaint)
                        }
                    }

                    // A tile shows the trace and nothing else.
                    if (scopesOnly) continue

                    val infoWidth = (size.width - infoX - pad).coerceAtLeast(0f)
                    if (infoWidth < 16f) continue

                    // Row-relative, not box-relative: the info column is three
                    // lines tall (name, level, pan) and the level layer places
                    // its own two off exactly this origin. box is inset by pad
                    // for the trace, so using it here would drift the name
                    // away from the bars beside it.
                    val rowTop = ch * rowHeight - scrollY

                    val rawName = voiceName(snapshot, ch, instrumentNames, sampleNames)
                    val insText = if (muted) "${rawName.take(2)} Muted" else rawName
                    textPaint.color = (if (muted) Color.Red else colors.instrumentText).toArgb()
                    nativeCanvas.drawText(
                        insText,
                        infoX,
                        centerBaselineY(textPaint, rowTop + lineH / 2f),
                        textPaint
                    )
                }
            }
        }

        // ---- Layer 2: scope traces ------------------------------------------
        // Only the polylines. Redraws when there is new waveform to show and
        // stops entirely during silence - see PlaybackVisuals.scopeTick.
        Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
            @Suppress("UNUSED_EXPRESSION") visuals.scopeTick
            @Suppress("UNUSED_EXPRESSION") mutes()
            if (numChannels == 0) return@Canvas
            val scrollY = scrollYAnim.value
            val snapshot = visuals.snapshot
            val width = PlaybackSnapshot.SCOPE_WIDTH
            // Hoisted: identical for every channel in a given pass, so
            // building it once here instead of once per channel avoids up to
            // 64 redundant allocations on the hot path.
            val traceStroke = Stroke((1.5f * zoom).coerceAtLeast(1f), cap = StrokeCap.Butt)

            for (ch in 0 until numChannels) {
                val box = geometry.box(ch, size.width, scrollY)
                if (box.bottom < 0f || box.top > size.height) continue
                if (!snapshot.isAudible(ch)) continue

                val midY = box.top + box.height / 2f
                val base = ch * width

                // How the trace is drawn is decided from the wave itself
                // rather than from the channel volume: it is fitted to the box
                // and its time base comes from its own pitch. A channel fading
                // out keeps its shape instead of shrinking to a line, and a
                // high note stops reading as a solid band. See traceWindow.
                val peak = tracePeak(snapshot.scopes, base, width)
                val targetGain = if (peak > SCOPE_SILENCE) SCOPE_FULL / peak else 1f
                // The gain falls fast and rises slowly. Falling is the wave
                // getting louder, and being late there means a frame of
                // clipping; rising is it fading, where being late costs
                // nothing and being quick would make the view pump.
                val ease = if (targetGain < traceGain[ch]) GAIN_FALL else GAIN_RISE
                traceGain[ch] += (targetGain - traceGain[ch]) * ease
                val gain = traceGain[ch].coerceIn(1f, MAX_TRACE_GAIN)

                val window = traceWindow(snapshot.scopes, base, width, peak)

                // A new time base has to say the same thing several frames
                // running before it is drawn.
                //
                // Counting zero crossings finds a period, but on anything with
                // a strong harmonic it finds the harmonic's about as often as
                // the fundamental's - so the measurement alternates between a
                // value and half of it, frame after frame, and the trace is
                // redrawn at twice the zoom every other frame. That is not a
                // scale changing, it is two scales interleaved, and it is what
                // a sampled instrument looks like through a crossing counter.
                //
                // A value that alternates never agrees with itself twice
                // running, so it never gets adopted. A real change of note
                // does, within a few frames.
                val measured = window and 0xffff
                var span = traceSpan[ch]
                if (span == 0) {
                    span = measured
                    traceSpan[ch] = measured
                } else if (abs(measured - span) * SPAN_HOLD > span) {
                    val candidate = spanCandidate[ch]
                    if (candidate > 0 && abs(measured - candidate) * SPAN_HOLD <= candidate) {
                        spanAgreed[ch]++
                    } else {
                        spanCandidate[ch] = measured
                        spanAgreed[ch] = 1
                    }
                    if (spanAgreed[ch] >= SPAN_SETTLE) {
                        span = measured
                        traceSpan[ch] = measured
                        spanAgreed[ch] = 0
                    }
                } else {
                    spanAgreed[ch] = 0
                }
                val start = (window ushr 16).coerceIn(0, (width - span).coerceAtLeast(0))
                // Scaled by the song's master volume. The trace is fitted to its
                // box, so on its own it keeps its size through a global fade -
                // a Vxx slide down to silence drew full-height waves to the end.
                val amplitude = (box.height / 2f) * 0.88f * snapshot.globalVolume
                val xStep = (box.width - 2f) / (span - 1).coerceAtLeast(1)

                // A muted channel is drawn as the silence it is. Not every
                // library stops working out a muted channel's wave - libopenmpt
                // and the chip emulators keep producing it and only leave it
                // out of the mix - and drawn faintly it read as still playing,
                // most of all with one channel soloed and the rest behind it.
                val muted = isMuted(ch)
                tracePath.reset()
                if (muted) {
                    tracePath.moveTo(box.left + 1f, midY)
                    tracePath.lineTo(box.right - 1f, midY)
                } else {
                    for (i in 0 until span) {
                        val x = box.left + 1f + i * xStep
                        val sample = snapshot.scopes[base + start + i].toFloat() * gain
                        val y = midY - (sample / 128f).coerceIn(-1f, 1f) * amplitude
                        if (i == 0) tracePath.moveTo(x, y) else tracePath.lineTo(x, y)
                    }
                }
                val alpha = if (muted) 0.3f else 1f
                drawPath(
                    path = tracePath,
                    color = colors.waveformLine.copy(alpha = alpha),
                    style = traceStroke
                )
            }
        }

        // ---- Layer 3: levels -------------------------------------------------
        // Volume bars and pan cursor: cheap rectangles and lines, so these can
        // afford to follow every level change. Drawn from the eased values
        // rather than the raw ones - the underlying numbers step once per
        // audio tick and unevenly, which reads as twitching, while the eased
        // ones rise and fall like a real level meter. levelTick keeps bumping
        // while that easing is still in motion and stops once it settles.
        //
        // Followed only while the view is on screen. A layer that reads a
        // ticket is recorded again whenever the ticket moves, whether or not
        // anything goes on to draw it - so with the pattern or the spectrum
        // showing, this pass still ran on every frame the music played. Not
        // reading the ticket is what stops it, and live() changing is itself
        // what redraws this, up to date, the moment a swipe heads here.
        Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
            if (live()) {
                @Suppress("UNUSED_EXPRESSION") visuals.levelTick
            }
            @Suppress("UNUSED_EXPRESSION") mutes()
            if (numChannels == 0 || scopesOnly) return@Canvas
            val infoWidth = (size.width - infoX - pad).coerceAtLeast(0f)
            if (infoWidth < 16f) return@Canvas
            val scrollY = scrollYAnim.value

            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                for (ch in 0 until numChannels) {
                    val rowTop = ch * rowHeight - scrollY
                    if (rowTop + rowHeight < 0f || rowTop > size.height) continue

                    val muted = isMuted(ch)
                    val alpha = if (muted) 0.3f else 1f

                    // volume
                    val barH = lineH * 0.5f
                    val barTop = rowTop + lineH + (lineH - barH) / 2f
                    // Backdrop = the volume the tracker is commanding for
                    // this channel; bar over it = what the channel is actually
                    // putting out, measured from the sample data being heard.
                    // Those differ whenever a note is held through a quiet part
                    // of its sample, or a quiet sample plays at full volume -
                    // cases where the commanded volume alone says "loud" and
                    // nothing much is audible.
                    // Nothing for a muted channel, for the same reason as its trace.
                    val nominal = if (muted) 0f else visuals.smoothedNominal[ch].coerceIn(0f, 1f)
                    val level = if (muted) 0f else visuals.smoothedLevel[ch].coerceIn(0f, 1f)
                    drawRect(
                        color = colors.volumeBarTrack,
                        topLeft = Offset(infoX, barTop),
                        size = Size(infoWidth, barH)
                    )
                    if (nominal > 0f) {
                        drawRect(
                            color = colors.finalVolumeBar.copy(alpha = 0.35f * alpha),
                            topLeft = Offset(infoX, barTop),
                            size = Size(infoWidth * nominal, barH)
                        )
                    }
                    if (level > 0f) {
                        drawRect(
                            color = colors.volumeBar.copy(alpha = alpha),
                            topLeft = Offset(infoX, barTop),
                            size = Size(infoWidth * level, barH)
                        )
                    }

                    // pan
                    val panCenterY = rowTop + lineH * 2.5f
                    val panFrac = visuals.smoothedPan[ch].coerceIn(0f, 1f)
                    val gap = pad * 0.5f
                    val railX0 = infoX + panLLabelWidth + gap
                    val railX1 = infoX + infoWidth - panRLabelWidth - gap
                    val railW = (railX1 - railX0).coerceAtLeast(0f)
                    val cursorW = (railW * 0.06f).coerceIn(
                        minOf(4f * zoom, railW),
                        minOf(14f * zoom, railW)
                    )
                    val cursorH = lineH * 0.45f
                    val cursorX = (railX0 + railW * panFrac - cursorW / 2f)
                        .coerceIn(railX0, (railX1 - cursorW).coerceAtLeast(railX0))

                    textPaint.color = colors.panRail.toArgb()
                    nativeCanvas.drawText("L [", infoX, centerBaselineY(textPaint, panCenterY), textPaint)
                    nativeCanvas.drawText(
                        "] R",
                        infoX + infoWidth - panRLabelWidth,
                        centerBaselineY(textPaint, panCenterY),
                        textPaint
                    )
                    drawLine(
                        color = colors.panRail,
                        start = Offset(railX0, panCenterY),
                        end = Offset(railX1, panCenterY),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = colors.panRail,
                        start = Offset(railX0 + railW / 2f, panCenterY - cursorH / 2f),
                        end = Offset(railX0 + railW / 2f, panCenterY + cursorH / 2f),
                        strokeWidth = 1f
                    )
                    drawRect(
                        color = colors.panCursor.copy(alpha = alpha),
                        topLeft = Offset(cursorX, panCenterY - cursorH / 2f),
                        size = Size(cursorW, cursorH)
                    )
                }
            }
        }
    }
}

/** Baseline Y to vertically center text at a given point (e.g. row midpoint). */
/**
 * Name for whatever [ch] is playing.
 *
 * The instrument field cannot be trusted on its own. libopenmpt fills it from
 * the channel's triggered instrument, but when a channel has none it falls
 * back to the sample index instead:
 *
 *     state.instrument = chn.nNewIns > 0 ? chn.nNewIns - 1 : -1;
 *     ...
 *     if (state.instrument < 0) state.instrument = i - 1;   // i = sample index
 *
 * so on a module with an instrument layer it quietly hands back a *sample*
 * number, which then names the wrong entry in the instrument list. That was
 * the "still wrong in some cases" - the earlier fix only covered modules with
 * no instrument layer at all.
 *
 * The fallback is detectable: it can only ever produce instrument == sample-1.
 * When the two disagree the field is a real instrument index and is used.
 * When they agree, the sample index is the one reading that is certainly
 * right, so that wins - and if it really was an instrument of the same
 * number, it is playing that sample anyway.
 */
/** [text] cut down to [maxWidth], with a trailing dot when something was dropped. */
private fun ellipsise(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    val kept = paint.breakText(text, true, maxWidth - paint.measureText("."), null)
    return text.take(kept).trimEnd() + "."
}

private fun voiceName(
    snapshot: PlaybackSnapshot,
    ch: Int,
    instrumentNames: ImmutableList<String>,
    sampleNames: ImmutableList<String>,
): String {
    val instrument = snapshot.instrument(ch)
    val sample = snapshot.sample(ch)
    val insName = instrumentNames.getOrNull(instrument)
    val smpName = sampleNames.getOrNull(sample - 1)
    return when {
        instrument >= 0 && instrument != sample - 1 -> insName ?: smpName ?: ".."
        sample > 0 -> smpName ?: insName ?: ".."
        instrument >= 0 -> insName ?: ".."
        else -> ".."
    }
}

/** Below this the window is treated as silence and left alone. */
private const val SCOPE_SILENCE = 3f

/** What a trace is fitted to: just inside the byte range, so peaks do not clip. */
private const val SCOPE_FULL = 120f

/** Ceiling on the fit, so noise in a near-silent channel is not magnified. */
private const val MAX_TRACE_GAIN = 24f

/** How fast the fit follows a wave getting louder, and quieter. */
private const val GAIN_FALL = 0.5f
private const val GAIN_RISE = 0.08f

/** Cycles of the wave a trace aims to show. */
private const val CYCLES_SHOWN = 2.5f

/**
 * How far a new time base must sit from the drawn one to count as a change,
 * as one part in this many.
 */
private const val SPAN_HOLD = 4

/** Frames a new time base must agree with itself before it is drawn. */
private const val SPAN_SETTLE = 3

/** Fewest columns of the trace that may be stretched across a box. */
private const val MIN_SPAN = 32

/**
 * Shortest period worth narrowing the window for, in columns.
 *
 * Below this there is nothing to see anyway: a handful of columns stretched
 * across the box is a shape made of four points.
 */
private const val MIN_PERIOD = 6f

/**
 * How much the gaps between crossings may scatter before the wave is treated
 * as having no pitch at all, as a fraction of their average.
 *
 * A square or a triangle crosses zero like clockwork; a snare, a cymbal or a
 * noise channel does not. Both produce plenty of crossings, so counting them
 * is not enough to tell the two apart - a noise burst counted as a very high
 * note, which pulled the window down to nothing and drew four points of
 * static. The regularity of the crossings is what separates them.
 */
private const val JITTER_TOLERANCE = 0.35f

/** Loudest sample in one channel's window. */
private fun tracePeak(scopes: ByteArray, base: Int, width: Int): Float {
    var peak = 0
    for (i in 0 until width) {
        val v = scopes[base + i].toInt()
        val magnitude = if (v < 0) -v else v
        if (magnitude > peak) peak = magnitude
    }
    return peak.toFloat()
}

/**
 * Where in the window to start drawing, and how much of it to draw. Packed as
 * start in the high half and span in the low half: this runs per channel per
 * frame, and returning a pair here would allocate on that path.
 *
 * Two things, both out of the same scan.
 *
 * The span is the time base. The decoders hand over a fixed stretch of time,
 * which is the right thing for them to do - it is the same stretch for every
 * channel, so the traces are comparable - but it is the wrong thing to look
 * at: a bass note fills it with a third of a cycle and a lead fills it with
 * thirty, and the second reads as a solid band. A few cycles is what is
 * legible, so the pitch decides how much of the window is shown.
 *
 * The start is the trigger, the one a bench oscilloscope has: begin at an
 * upward crossing, so the wave is drawn from the same point in its cycle
 * every frame and holds still. Without it a narrowed window slides sideways,
 * because the stretch a decoder hands over advances by its own amount between
 * frames and knows nothing about where a cycle begins.
 *
 * The pitch is measured off the wave rather than read from the note, because
 * not every decoder here reports one and the wave is always there. Counting
 * upward crossings of zero with a threshold either side - so a harmonic
 * dipping back through the middle is not counted twice - is enough; this only
 * has to be right to within a cycle to pick a readable width.
 *
 * Counting them is not enough on its own, though. A snare, a cymbal or a
 * noise channel crosses zero constantly, which reads as a very high note and
 * pulls the window down to a few columns of static. So the gaps between
 * crossings are measured too: regular gaps are a pitch, scattered ones are
 * noise, and noise gets the widest window there is.
 *
 * Only ever narrows. Widening would need sample history the view does not
 * have, so a note too low to finish one cycle in the window is drawn as the
 * part of a cycle that fits, which is what any oscilloscope with a fixed time
 * base shows.
 */
private fun traceWindow(scopes: ByteArray, base: Int, width: Int, peak: Float): Int {
    if (peak <= SCOPE_SILENCE) return width

    // Half the peak rather than a quarter of it. The threshold is what
    // decides whether a harmonic riding on the wave counts as a crossing of
    // its own, and counting those is what produces a period an octave out.
    val high = (peak / 2f).toInt().coerceAtLeast(1)
    val low = -high
    var crossings = 0
    var first = -1
    var last = -1
    var previous = -1
    var gaps = 0
    var gapSum = 0
    var gapSquares = 0
    var above = false
    for (i in 0 until width) {
        val v = scopes[base + i].toInt()
        if (!above && v > high) {
            above = true
            if (first < 0) {
                first = i
            } else {
                val gap = i - previous
                gapSum += gap
                gapSquares += gap * gap
                gaps++
            }
            previous = i
            last = i
            crossings++
        } else if (above && v < low) {
            above = false
        }
    }
    if (crossings < 3 || last <= first) return width

    // Regular gaps mean a pitch; scattered ones mean noise, and noise has no
    // period to build a time base out of. The widest window is the right
    // answer there - it is what an oscilloscope shows a noise source.
    if (gaps >= 2) {
        val mean = gapSum.toFloat() / gaps
        val spread = gapSquares.toFloat() / gaps - mean * mean
        val allowed = mean * JITTER_TOLERANCE
        if (spread > allowed * allowed) return width
    }

    val period = (last - first).toFloat() / (crossings - 1)
    if (period < MIN_PERIOD) return width

    val span = (period * CYCLES_SHOWN).toInt().coerceIn(MIN_SPAN, width)
    // Triggered on the first crossing that still leaves a whole span behind it.
    val start = if (first + span <= width) first else width - span
    return (start.coerceAtLeast(0) shl 16) or span
}

private fun centerBaselineY(paint: Paint, centerY: Float): Float {
    val fm = paint.fontMetrics
    return centerY - (fm.ascent + fm.descent) / 2f
}

@Preview(showBackground = true, widthDp = 420, heightDp = 520)
@Composable
private fun PreviewChannelView() {
    AppTheme {
        ChannelView(
            numChannels = 10,
            instrumentNames = LoremIpsum(4).values.toPersistentList(),
            sampleNames = LoremIpsum(4).values.toPersistentList(),
            visuals = remember { PlaybackVisuals() },
            scopesOnly = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
