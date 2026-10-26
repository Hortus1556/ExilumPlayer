package com.hortus1556.exilum.ui.screens.player.components.views

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hortus1556.exilum.data.LyricLine
import com.hortus1556.exilum.data.Lyrics
import com.hortus1556.exilum.ui.screens.player.PlaybackVisuals
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/** How far down the view the line being sung is held: a quarter of what shows between the heading and the controls. */
private const val FOCUS = 0.16f

/** How dim a line not being sung is, and how dim while the listener is scrolling and reading. */
private const val REST_ALPHA = 0.34f
private const val READ_ALPHA = 0.6f

/** How much smaller a line not being sung is, about its leading edge. */
private const val REST_SCALE = 0.96f

/** How long the list is left where the listener scrolled it before it goes back to the line being sung. */
private const val RESUME_MS = 3_000L

/** The wave a change of line sends down the lines: each a little after the one above it. */
private const val WAVE_STEP_MS = 30f
private const val WAVE_STIFFNESS = 13f

/** The dots that stand for an instrumental. */
private val DotSize = 11.dp
private val DotGap = 8.dp

/** What the list shows: a line of the words, or the dots of a silence between two. */
private sealed interface Entry {
    val startMs: Int
    val endMs: Int

    data class Words(val line: LyricLine) : Entry {
        override val startMs get() = line.startMs
        override val endMs get() = line.endMs
    }

    data class Rest(override val startMs: Int, override val endMs: Int) : Entry
}

/** The lines, with a rest wherever nothing is sung for a while - before the first line as well. */
private fun entriesOf(lyrics: Lyrics): List<Entry> {
    val entries = ArrayList<Entry>()
    var sungUntil = 0
    for (line in lyrics.lines) {
        if (line.startMs - sungUntil >= Lyrics.GAP_MS) entries += Entry.Rest(sungUntil, line.startMs)
        entries += Entry.Words(line)
        sungUntil = line.endMs
    }
    return entries
}

/** The entry on at [ms]: the last one begun, or -1 before any. */
private fun List<Entry>.at(ms: Int): Int {
    var low = -1
    var high = lastIndex
    while (low < high) {
        val middle = (low + high + 1) ushr 1
        if (this[middle].startMs <= ms) low = middle else high = middle - 1
    }
    return low
}

/**
 * The wave: when the line changes the list is moved at once, each row is
 * put back where it stood by an offset of its own, and the offsets are let
 * go one after another from the changed line down - so the lines follow
 * each other up the screen rather than move as one sheet.
 */
@Stable
private class Wave {
    var distance by mutableFloatStateOf(0f)
    var origin by mutableIntStateOf(0)
    var startNanos by mutableLongStateOf(0L)
    var nowNanos by mutableLongStateOf(0L)
    var running by mutableStateOf(false)

    /** How far row [index] still is from where the list has put it. */
    fun offsetOf(index: Int): Float {
        if (!running) return 0f
        val wait = (index - origin).coerceAtLeast(0) * WAVE_STEP_MS
        val seconds = ((nowNanos - startNanos) / 1_000_000f - wait) / 1000f
        if (seconds <= 0f) return distance
        // Critically damped: gets there without going past.
        val x = WAVE_STIFFNESS * seconds
        return distance * (1f + x) * exp(-x)
    }
}

/**
 * The words of the song playing, as Apple Music shows them: large lines,
 * the one being sung bright and held a quarter of the way down, the others
 * dim and softer the further they are from it, each change of line sending
 * the rest up in a wave. A tap on a line plays from it. Scrolled by hand the
 * list stays where it is put, every line readable, and goes back to the line
 * being sung a few seconds after it is let go. A silence between two lines
 * is three dots that fill as it passes. Lines whose words are timed light
 * up word by word.
 *
 * Words that are not timed are only text to scroll.
 */
@Composable
fun LyricsView(
    lyrics: Lyrics,
    visuals: PlaybackVisuals,
    live: () -> Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = LocalContentColor.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Larger on a wide window, as on a tablet.
        val fontSize = (maxWidth.value / 13f).coerceIn(26f, 38f).sp
        val style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, lineHeight = 1.18.em, color = color)
        val focus = maxHeight * FOCUS
        val edge = Modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // Lines come in and go out through a fade at either end, not across an edge.
                val height = this.size.height.coerceAtLeast(1f)
                val fade = (56.dp.toPx() / height).coerceAtMost(0.2f)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        fade to Color.Black,
                        1f - fade to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        if (!lyrics.synced) {
            PlainWords(lyrics, style, edge)
        } else {
            TimedWords(lyrics, visuals, live, style, focus, maxHeight - focus, onSeek, edge)
        }
    }
}

@Composable
private fun PlainWords(lyrics: Lyrics, style: TextStyle, modifier: Modifier) {
    val smaller = style.copy(fontSize = style.fontSize * 0.74f, fontWeight = FontWeight.SemiBold, lineHeight = 1.3.em)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 48.dp),
    ) {
        itemsIndexed(lyrics.lines) { _, line ->
            if (line.text.isEmpty()) Box(Modifier.height(22.dp)) else Text(line.text, style = smaller, modifier = Modifier.padding(vertical = 5.dp))
        }
    }
}

@Composable
private fun TimedWords(
    lyrics: Lyrics,
    visuals: PlaybackVisuals,
    live: () -> Boolean,
    style: TextStyle,
    focus: Dp,
    below: Dp,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val entries = remember(lyrics) { entriesOf(lyrics) }
    val list = rememberLazyListState()
    val wave = remember { Wave() }

    // The time is read every frame, but this only says so when the entry
    // changes; off screen it reads nothing and keeps the entry it had.
    val last = remember(lyrics) { intArrayOf(-1) }
    val on by remember(lyrics) {
        derivedStateOf {
            if (live()) {
                visuals.infoTick
                last[0] = entries.at(visuals.snapshot.timeMs)
            }
            last[0]
        }
    }

    // Reading: from a drag of the listener's until a little after it is let go.
    var reading by remember { mutableStateOf(false) }
    var letGo by remember { mutableIntStateOf(0) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> reading = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> letGo++
            }
        }
    }
    LaunchedEffect(letGo) {
        if (letGo == 0) return@LaunchedEffect
        delay(RESUME_MS)
        reading = false
    }

    // To the line being sung: at once, with the wave making the move.
    LaunchedEffect(on, reading) {
        if (reading) return@LaunchedEffect
        val target = on.coerceAtLeast(0)
        follow(list, wave, target)
    }
    // The clock the wave runs on, for as long as one is running.
    LaunchedEffect(wave.running) {
        while (wave.running) {
            withFrameNanos { now ->
                wave.nowNanos = now
                val slowest = (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: wave.origin) - wave.origin
                val over = (now - wave.startNanos) / 1_000_000f > slowest.coerceAtLeast(0) * WAVE_STEP_MS + 900f
                if (over) wave.running = false
            }
        }
    }

    LazyColumn(
        state = list,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = focus, bottom = below),
    ) {
        itemsIndexed(entries) { index, entry ->
            val active = index == on
            val lit by animateFloatAsState(if (active) 1f else 0f, tween(280), label = "lyric_lit")
            val rest by animateFloatAsState(if (reading) READ_ALPHA else REST_ALPHA, tween(250), label = "lyric_rest")
            // Softer the further from the line being sung; sharp while the listener reads.
            val distance = if (on < 0) index + 1 else abs(index - on)
            val soft by animateDpAsState(
                if (reading || active) 0.dp else (distance * 1.1f).coerceAtMost(5f).dp,
                tween(300),
                label = "lyric_soft",
            )
            val row = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = wave.offsetOf(index)
                    alpha = rest + (1f - rest) * lit
                    val scale = REST_SCALE + (1f - REST_SCALE) * lit
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && soft > 0.2.dp) {
                        Modifier.blur(soft, BlurredEdgeTreatment.Unbounded)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 24.dp, vertical = 9.dp)
            when (entry) {
                is Entry.Words -> WordsRow(
                    line = entry.line,
                    style = style,
                    active = active,
                    time = { visuals.infoTick; visuals.snapshot.timeMs },
                    modifier = row.clickable(remember { MutableInteractionSource() }, indication = null) {
                        reading = false
                        onSeek(entry.line.startMs.toLong())
                    },
                )

                is Entry.Rest -> RestRow(
                    entry = entry,
                    color = style.color,
                    active = active,
                    time = { visuals.infoTick; visuals.snapshot.timeMs },
                    modifier = row,
                )
            }
        }
    }
}

/** Brings row [target] to the top of the list's content - the focus - and starts the wave that shows it going there. */
private suspend fun follow(list: LazyListState, wave: Wave, target: Int) {
    val shown = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
    if (shown == null) {
        // Too far to have been on screen: no wave to make of it.
        list.animateScrollToItem(target)
        return
    }
    val distance = shown.offset.toFloat()
    if (abs(distance) < 1f) return
    val moved = list.scrollBy(distance)
    if (abs(moved) < 1f) return
    wave.distance = moved
    wave.origin = target
    wave.startNanos = withFrameNanos { it }
    wave.nowNanos = wave.startNanos
    wave.running = true
}

@Composable
private fun WordsRow(line: LyricLine, style: TextStyle, active: Boolean, time: () -> Int, modifier: Modifier) {
    if (line.words.isEmpty()) {
        Text(line.text, style = style, modifier = modifier)
        return
    }
    // Its words lit as they are sung: the line drawn dim, and over it the
    // same line bright, as far as the singing has got.
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val spans = remember(line) { wordSpans(line) }
    val dim = style.color.copy(alpha = style.color.alpha * 0.42f)
    Text(
        text = line.text,
        style = style.copy(color = if (active) dim else style.color),
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val measured = layout ?: return@drawWithContent
            if (!active) return@drawWithContent
            val now = time()
            val sung = Path()
            for ((index, word) in line.words.withIndex()) {
                if (now < word.startMs) break
                val (from, to) = spans.getOrNull(index) ?: continue
                val part = if (word.endMs > word.startMs) ((now - word.startMs).toFloat() / (word.endMs - word.startMs)).coerceIn(0f, 1f) else 1f
                // A word broken over two lines of text has a box on each.
                var at = from
                while (at < to) {
                    val textLine = measured.getLineForOffset(at)
                    val until = min(to, measured.getLineEnd(textLine, visibleEnd = true)).coerceAtLeast(at + 1)
                    val left = measured.getHorizontalPosition(at, usePrimaryDirection = true)
                    val right = measured.getHorizontalPosition(until, usePrimaryDirection = true)
                    val whole = (to - from).coerceAtLeast(1)
                    val shareEnd = ((until - from).toFloat() / whole)
                    val shareStart = ((at - from).toFloat() / whole)
                    val reach = ((part - shareStart) / (shareEnd - shareStart).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
                    if (reach > 0f) {
                        sung.addRect(
                            Rect(
                                left = min(left, right),
                                top = measured.getLineTop(textLine),
                                right = min(left, right) + abs(right - left) * reach,
                                bottom = measured.getLineBottom(textLine),
                            )
                        )
                    }
                    at = until
                }
            }
            clipPath(sung) { drawText(measured, color = style.color) }
        },
    )
}

/** Where each of a line's words is in its text, as [start, end): found in order, so a word said twice is found twice. */
private fun wordSpans(line: LyricLine): List<Pair<Int, Int>> {
    val spans = ArrayList<Pair<Int, Int>>()
    var from = 0
    for (word in line.words) {
        val at = line.text.indexOf(word.text, from).takeIf { it >= 0 } ?: from
        val end = (at + word.text.length).coerceAtMost(line.text.length)
        spans += at to end
        from = end
    }
    return spans
}

/** Three dots for a silence, filling from the first to the last as it passes, and swelling a little while it does. */
@Composable
private fun RestRow(entry: Entry.Rest, color: Color, active: Boolean, time: () -> Int, modifier: Modifier) {
    val width = DotSize * 3 + DotGap * 2 + 8.dp
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Canvas(Modifier.width(width).height(DotSize + 14.dp)) {
            val now = time()
            val span = (entry.endMs - entry.startMs).coerceAtLeast(1)
            val through = if (active) ((now - entry.startMs).toFloat() / span).coerceIn(0f, 1f) else 0f
            // A slow breath, by the song's own clock - still when the song is.
            val breath = if (active) 1f + 0.12f * kotlin.math.sin(now / 1000f * 2f * Math.PI.toFloat() / 2.4f) else 1f
            val radius = DotSize.toPx() / 2f
            for (dot in 0 until 3) {
                val lit = (through * 3f - dot).coerceIn(0f, 1f)
                val centre = Offset(4.dp.toPx() + radius + dot * (DotSize.toPx() + DotGap.toPx()), size.height / 2f)
                drawCircle(color.copy(alpha = color.alpha * (0.3f + 0.7f * lit)), radius * breath, centre)
            }
        }
    }
}
