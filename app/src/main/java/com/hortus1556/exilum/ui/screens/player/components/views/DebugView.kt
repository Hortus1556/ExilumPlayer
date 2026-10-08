package com.hortus1556.exilum.ui.screens.player.components.views

import androidx.compose.runtime.remember
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.LifecycleStartEffect
import com.hortus1556.exilum.player.PlaybackStatus
import com.hortus1556.exilum.player.PlayerUiState
import com.hortus1556.exilum.player.SoundCheckStatus
import kotlinx.coroutines.delay
import com.hortus1556.exilum.ui.screens.player.PlaybackVisuals
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.renderer.model.ModVars

private val NOTE_NAMES = arrayOf(
    "C-", "C#", "D-", "D#", "E-", "F-",
    "F#", "G-", "G#", "A-", "A#", "B-",
)

/**
 * Names a note the way the pattern does.
 *
 * The field counts from one - libopenmpt's NOTE_MIN is 1 and means C-0 - so
 * the octave and the name both come from note - 1. Reading it as though it
 * counted from zero named every note a semitone above what the pattern showed
 * for the same row.
 */
private fun noteStr(note: Int): String = when {
    note <= 0 -> "---"
    else -> (note - 1).let { n -> "${NOTE_NAMES[n % 12]}${n / 12}" }
}

/**
 * Everything the engine currently reports, for diagnosing playback.
 *
 * Reads the same [PlaybackVisuals] as the other two views, so what it shows is
 * exactly what they are showing - which is the point of a debug view. Like
 * them it is split by change frequency: the scalar sections recompose only
 * when their own values move, and the channel table is a single Canvas keyed
 * to the per-channel tickets rather than a grid of Text composables.
 */
@Composable
fun DebugView(
    modifier: Modifier = Modifier,
    state: PlayerUiState,
    visuals: PlaybackVisuals,
    soundCheckStatus: () -> SoundCheckStatus? = { null },
    /** Whether this is the view being shown, or about to be. */
    live: Boolean = true,
) {
    // Beside the view that is showing, the pager keeps this one built - and
    // built, it went on reading the playback visuals, formatting their text
    // and laying it out on every frame, for nobody: half of all the memory
    // the player churned through while it played, and a garbage collection
    // every few seconds to clear it. Off screen it reads a set of visuals
    // that never moves, and no Sound Check at all.
    val idle = remember { PlaybackVisuals() }
    val shown = if (live) visuals else idle
    val soundCheck = if (live) soundCheckStatus else NoSoundCheck
    Column {
        LazyVerticalStaggeredGrid(
            modifier = modifier.weight(1f),
            columns = StaggeredGridCells.Adaptive(200.dp),
            verticalItemSpacing = 4.dp,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = {
                item { PlaybackInfo(state) }
                item { ModVarsInfo(state) }
                item { PositionInfo(shown, state.modVars.hasPatterns) }
                item { SyncInfo(shown) }
                item { SoundCheckInfo(soundCheck) }
            },
        )
        HorizontalDivider(Modifier.fillMaxWidth())
        ChannelTable(Modifier.weight(1f), state.modVars.chn, shown)
    }
}

/** No Sound Check to show: what the view reads while it is off screen. */
private val NoSoundCheck: () -> SoundCheckStatus? = { null }

@Composable
private fun ChannelTable(
    modifier: Modifier = Modifier,
    numChannels: Int,
    visuals: PlaybackVisuals
) {
    val density = LocalDensity.current
    val fontPx = with(density) { 12.sp.toPx() }
    val textPaint = remember(fontPx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = fontPx
        }
    }
    val charWidth = remember(fontPx) { textPaint.measureText("0") }
    val cellHeight = fontPx * 1.45f
    val headerColor = MaterialTheme.colorScheme.primary.toArgb()
    val cellColor = MaterialTheme.colorScheme.onSurface.toArgb()

    val tableWidth = remember(charWidth) { COLUMN_CHARS.sum() * charWidth }
    val tableHeight = cellHeight * (numChannels + 1)

    // How far the table is scrolled and how much of it shows, for the pass
    // below to draw the rows in sight and no others.
    val scroll = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewportHeight = it.height }
            .verticalScroll(scroll)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (numChannels > 0) {
            DebugHeader("Channel Info")

            // One Canvas for the whole grid rather than a Text per cell. At 64
            // channels this table would be 576 Text composables, each
            // re-measured and re-laid-out on every update, with a String
            // allocation behind every one. Drawing it is a single pass with no
            // composition, measurement or layout involved at all.
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Canvas(
                    modifier = Modifier.size(
                        width = with(density) { tableWidth.toDp() },
                        height = with(density) { tableHeight.toDp() },
                    )
                ) {
                    // Read both in the draw phase: this pass re-runs exactly
                    // when a value it shows has actually changed, and never
                    // just because a frame went by.
                    @Suppress("UNUSED_EXPRESSION") visuals.voiceTick
                    @Suppress("UNUSED_EXPRESSION") visuals.levelTick
                    val snapshot = visuals.snapshot
                    // Only the channels in sight. The level ticket moves on
                    // nearly every frame, for the eased bars this table does
                    // not even show, so this pass runs at the display's rate -
                    // and it used to draw every row of the table each time,
                    // nine pieces of text apiece, when a dozen of a module's
                    // sixty-four fit on screen. The canvas starts a little
                    // way down what is scrolled, under its heading, which is
                    // what TABLE_SLACK_ROWS allows for.
                    val scrolled = scroll.value
                    val firstRow = ((scrolled / cellHeight).toInt() - TABLE_SLACK_ROWS).coerceAtLeast(0)
                    val endRow = (((scrolled + viewportHeight) / cellHeight).toInt() + 1).coerceAtMost(numChannels)
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val baseline = -textPaint.fontMetrics.ascent

                        textPaint.color = headerColor
                        var x = 0f
                        for (col in COLUMN_HEADERS.indices) {
                            native.drawText(COLUMN_HEADERS[col], x, baseline, textPaint)
                            x += COLUMN_CHARS[col] * charWidth
                        }

                        textPaint.color = cellColor
                        for (i in firstRow until endRow) {
                            val y = baseline + (i + 1) * cellHeight
                            var cx = 0f
                            cx = drawColumn(native, textPaint, (i + 1).toString(), cx, y, 0, charWidth)
                            cx = drawColumn(native, textPaint, noteStr(snapshot.note(i)), cx, y, 1, charWidth)
                            cx = drawColumn(native, textPaint, snapshot.instrument(i).toString(), cx, y, 2, charWidth)
                            cx = drawColumn(native, textPaint, snapshot.sample(i).toString(), cx, y, 3, charWidth)
                            cx = drawColumn(native, textPaint, snapshot.volume(i).toString(), cx, y, 4, charWidth)
                            cx = drawColumn(native, textPaint, snapshot.finalVolume(i).toString(), cx, y, 5, charWidth)
                            cx = drawColumn(native, textPaint, snapshot.pan(i).toString(), cx, y, 6, charWidth)
                            cx = drawColumn(native, textPaint, snapshot.period(i).toString(), cx, y, 7, charWidth)
                            drawColumn(native, textPaint, snapshot.pitchBend(i).toString(), cx, y, 8, charWidth)
                        }
                    }
                }
            }
        }
    }
}

private val COLUMN_HEADERS =
    arrayOf("Ch", "Note", "Ins", "Smp", "Vol", "FVol", "Pan", "Period", "Bend")

/** Width of each column in monospace character cells, including its trailing gap. */
private val COLUMN_CHARS = intArrayOf(4, 5, 5, 5, 5, 6, 5, 8, 7)

/**
 * Rows drawn above the first one worked out to be in sight. The table sits
 * below its heading inside what scrolls, so its rows are that much further
 * down than the scroll position alone says; a few rows of margin cover it.
 */
private const val TABLE_SLACK_ROWS = 4

/**
 * Draws [text] right-aligned within column [col] (leaving one character of gap
 * before the next one) and returns the next column's left edge.
 */
private fun drawColumn(
    canvas: android.graphics.Canvas,
    paint: Paint,
    text: String,
    x: Float,
    baselineY: Float,
    col: Int,
    charWidth: Float
): Float {
    val width = COLUMN_CHARS[col] * charWidth
    val textWidth = text.length * charWidth
    canvas.drawText(text, x + (width - charWidth - textWidth).coerceAtLeast(0f), baselineY, paint)
    return x + width
}

/** The four displayed lines, combined so derivedStateOf has one value to compare. */
private data class PositionLines(
    val posPatRow: String,
    val speedBpm: String,
    val timeTotal: String,
    val channels: String,
)

@Composable
private fun PositionInfo(visuals: PlaybackVisuals, hasPatterns: Boolean) {
    val lines by remember(visuals) {
        derivedStateOf {
            @Suppress("UNUSED_EXPRESSION") visuals.positionTick
            @Suppress("UNUSED_EXPRESSION") visuals.infoTick
            val s = visuals.snapshot
            PositionLines(
                posPatRow = "${s.order} / ${s.pattern} / ${s.row}/${s.numRows}",
                speedBpm = "${s.speed} / ${s.bpm}",
                timeTotal = "${s.timeMs} / ${s.totalTimeMs}",
                channels = "${s.playingChannels} / ${s.numChannels}",
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        DebugHeader("Position")
        // A chip log has no order list, no pattern and no tracker speed, and
        // its decoder leaves those at zero. Printing three rows of zeroes says
        // nothing except that the row exists.
        if (hasPatterns) {
            DebugRow("Pos/Pat/Row", lines.posPatRow)
            DebugRow("Speed/BPM", lines.speedBpm)
        }
        DebugRow("Time/Total", lines.timeTotal)
        DebugRow("Chn used", lines.channels)
    }
}

@Composable
private fun SyncInfo(visuals: PlaybackVisuals) {
    // The audio/video sync figures this whole architecture exists to get
    // right: how far ahead of the speaker the renderer is, and therefore how
    // much of a correction is being applied to everything shown above.
    val lines by remember(visuals) {
        derivedStateOf {
            @Suppress("UNUSED_EXPRESSION") visuals.infoTick
            val frames = visuals.snapshot.latencyFrames
            listOf(
                "Latency" to "$frames frames",
                "Seq" to visuals.snapshot.sequence.toString(),
                "Valid" to visuals.snapshot.valid.toString(),
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        DebugHeader("Sync")
        lines.forEach { (key, value) -> DebugRow(key, value) }
    }
}

/**
 * Sound Check, read from the output stage twice a second: whether it is on
 * there as well as in the settings, whether audio is going through it, and
 * what it measures and does.
 */
@Composable
private fun SoundCheckInfo(read: () -> SoundCheckStatus?) {
    var status by remember { mutableStateOf(read()) }
    // Only while the app is in front of someone. Left open on this view and
    // sent to the background, it went on asking the output stage twice a
    // second, on the main thread, for figures nobody was there to read.
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        started = true
        onStopOrDispose { started = false }
    }
    LaunchedEffect(read, started) {
        if (!started) return@LaunchedEffect
        while (true) {
            status = read()
            delay(500)
        }
    }
    val s = status ?: return
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        DebugHeader("Sound Check")
        DebugRow("Setting/Audio", "${s.settingOn} / ${s.nativeOn}")
        DebugRow("Processed", "%.0f s".format(s.processedSeconds))
        DebugRow("Loudness", "${decibels(s.loudness)} LUFS / %.0f s".format(s.measuredSeconds))
        DebugRow("Gain", "${decibels(s.gainDb)} dB" + if (s.fixed) " fixed" else " adapting")
        DebugRow("Limiter", "${decibels(s.limiterDb)} dB")
        DebugRow(
            "Analysed",
            s.stored?.let { "${decibels(it.lufs)} LUFS / %.0f s".format(it.seconds) + if (it.complete) " whole" else " part" } ?: "-",
        )
        DebugRow("Average", "${decibels(s.prior)} LUFS")
        DebugRow("Library", "${s.library.done} / ${s.library.total}" + if (s.library.running) " running" else "")
        DebugRow("Tracks", s.tracksStarted.toString())
    }
}

/** A level to one decimal, or a dash for the -100 that means none. */
private fun decibels(value: Float): String = if (value <= -99f) "-" else "%.1f".format(value)

@Composable
private fun PlaybackInfo(state: PlayerUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        DebugHeader("Playback")
        DebugRow("Status", state.status.name)
        DebugRow(
            "Module",
            state.currentModule?.let { it.moduleName.ifBlank { it.filename } } ?: "—"
        )
        DebugRow("Queue", "${state.currentQueueIndex + 1} / ${state.queue.size}")
        DebugRow("Sequence", state.currentSequence.toString())
        DebugRow("Repeat", state.repeatMode.toString())
        DebugRow("Shuffle", state.isShuffle.toString())
        DebugRow("All seqs", state.playAllSequences.toString())
    }
}

@Composable
private fun ModVarsInfo(state: PlayerUiState) {
    val mv = state.modVars
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        DebugHeader("ModVars")
        DebugRow("Name", mv.modName.ifBlank { "—" })
        DebugRow("Type", mv.modType.ifBlank { "—" })
        if (mv.hasPatterns) {
            DebugRow("Pat/Chn/Ins", "${mv.pat} / ${mv.chn} / ${mv.ins}")
            DebugRow("Smp/Spd/BPM", "${mv.smp} / ${mv.spd} / ${mv.bpm}")
            DebugRow("Len/Rst/Gvl", "${mv.len} / ${mv.rst} / ${mv.gvl}")
        } else {
            DebugRow("Voices", mv.chn.toString())
        }
        DebugRow(if (mv.hasPatterns) "Sequences" else "Tracks", mv.miNumSequences.toString())
        DebugRow("Renderer", mv.renderingEngine)
        if (mv.miComment.isNotBlank()) DebugRow("Comment", mv.miComment.take(32))
    }
}

@Composable
private fun DebugHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DebugRow(key: String, value: String) {
    Row {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.width(80.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Preview(showBackground = true, widthDp = 480, heightDp = 640)
@Composable
private fun Preview() {
    val state = PlayerUiState(
        status = PlaybackStatus.PLAYING,
        modVars = ModVars(
            name = "Space Debris",
            type = "Extended Module",
            pat = 24, chn = 8, ins = 18, smp = 32,
            bpm = 125, spd = 6, len = 24, rst = 0, gvl = 64,
            miNumSequences = 2,
            miComment = "Made with FastTracker II",
        ),
        currentSequence = 0,
        currentQueueIndex = 2,
        isShuffle = true,
    )

    AppTheme {
        DebugView(
            modifier = Modifier.fillMaxSize(),
            state = state,
            visuals = remember { PlaybackVisuals() },
        )
    }
}
