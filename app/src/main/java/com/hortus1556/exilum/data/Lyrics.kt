package com.hortus1556.exilum.data

import androidx.compose.runtime.Immutable

/** One word of a line whose words have times of their own, from [startMs] to [endMs]. */
@Immutable
data class LyricWord(val text: String, val startMs: Int, val endMs: Int)

/**
 * One line of the words. [startMs] is when it is sung, [endMs] when it is
 * over - the next line's start, or sooner where the file says so - and both
 * are -1 in words that are not timed. [words] is empty unless the file times
 * each word.
 */
@Immutable
data class LyricLine(
    val text: String,
    val startMs: Int = -1,
    val endMs: Int = -1,
    val words: List<LyricWord> = emptyList(),
)

/**
 * The words of a song, as a file carries them or as a .lrc beside it does.
 * [synced] when its lines are timed; otherwise they are only lines to read.
 */
@Immutable
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {

    /** The line being sung at [ms], or -1 before the first; the last one stays until the end. */
    fun lineAt(ms: Int): Int {
        if (!synced || lines.isEmpty() || ms < lines[0].startMs) return -1
        var low = 0
        var high = lines.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (lines[middle].startMs <= ms) low = middle else high = middle - 1
        }
        return low
    }

    companion object {
        /** A time in front of a line - "[01:23.45]", "[1:23]", "[01:23:45]" - or one inside it, in angle brackets. */
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

        /** What a .lrc says about itself rather than sings: "[ar:...]", "[offset:+250]". */
        private val TAG = Regex("""^\[([A-Za-z#]+):(.*)]\s*$""")

        /** Whose line it is, in files made for two singers. */
        private val SINGER = Regex("""^[FMD]:\s*""")

        /** A silence this long between two lines is shown as one: an instrumental. */
        const val GAP_MS = 5_000

        private fun stampMs(match: MatchResult): Int {
            val minutes = match.groupValues[1].toInt()
            val seconds = match.groupValues[2].toInt()
            val fraction = match.groupValues[3]
            // Hundredths are the custom, but a file may give tenths or thousandths.
            val millis = when (fraction.length) {
                0 -> 0
                1 -> fraction.toInt() * 100
                2 -> fraction.toInt() * 10
                else -> fraction.take(3).toInt()
            }
            return minutes * 60_000 + seconds * 1000 + millis
        }

        /**
         * Reads [text] as LRC where any line of it is timed, and as plain
         * lines otherwise. Null when there is nothing in it to show.
         */
        fun parse(text: String): Lyrics? {
            val raw = text.replace("\r\n", "\n").replace('\r', '\n').trim('﻿', ' ', '\n', '\t')
            if (raw.isEmpty()) return null
            val timed = ArrayList<LyricLine>()
            // Lines timed and empty: where the one before them ends.
            val ends = ArrayList<Int>()
            var offset = 0
            for (row in raw.split('\n')) {
                val line = row.trim()
                TAG.matchEntire(line)?.let { tag ->
                    if (tag.groupValues[1].equals("offset", ignoreCase = true)) {
                        // The file's own word on how far out it is: sooner, when positive.
                        offset = tag.groupValues[2].trim().toIntOrNull() ?: 0
                    }
                }
                var rest = line
                val starts = ArrayList<Int>()
                while (true) {
                    val stamp = STAMP.find(rest)?.takeIf { it.range.first == 0 } ?: break
                    starts += stampMs(stamp)
                    rest = rest.substring(stamp.range.last + 1).trimStart()
                }
                if (starts.isEmpty()) continue
                rest = SINGER.replace(rest, "")
                val words = wordsOf(rest)
                val shown = WORD_STAMP.replace(rest, "").replace(Regex("""\s+"""), " ").trim()
                for (start in starts) {
                    if (shown.isEmpty()) ends += start else timed += LyricLine(shown, start, words = words)
                }
            }
            if (timed.isEmpty()) {
                // No times anywhere: lines to read. A blank one is kept, once, as the space between two verses.
                val plain = ArrayList<LyricLine>()
                for (row in raw.split('\n')) {
                    val line = row.trim()
                    if (TAG.matches(line)) continue
                    if (line.isEmpty() && (plain.isEmpty() || plain.last().text.isEmpty())) continue
                    plain += LyricLine(line)
                }
                while (plain.isNotEmpty() && plain.last().text.isEmpty()) plain.removeAt(plain.lastIndex)
                return if (plain.isEmpty()) null else Lyrics(plain, synced = false)
            }

            timed.sortBy { it.startMs }
            ends.sort()
            val lines = timed.mapIndexed { index, line ->
                val start = (line.startMs - offset).coerceAtLeast(0)
                val next = timed.getOrNull(index + 1)?.startMs
                // Over where the file says so, when that is before the next
                // line. A .lrc seldom does: then at the next line - unless
                // that is so far off that the line cannot be what fills the
                // time, and what follows it is an instrumental.
                val said = ends.firstOrNull { it > line.startMs && (next == null || it <= next) }
                val sung = line.startMs + singingMs(line.text)
                val guessed = next?.let { if (it - sung >= GUESSED_GAP_MS) sung else it }
                val end = ((said ?: guessed ?: (line.startMs + LAST_LINE_MS)) - offset).coerceAtLeast(start)
                val words = line.words.mapIndexed { w, word ->
                    val wordStart = (word.startMs - offset).coerceIn(start, end)
                    val wordEnd = ((line.words.getOrNull(w + 1)?.startMs ?: (end + offset)) - offset).coerceIn(wordStart, end)
                    LyricWord(word.text, wordStart, wordEnd)
                }
                LyricLine(line.text, start, end, words)
            }
            return Lyrics(lines, synced = true)
        }

        /** The words of a line that times them: "<00:01.00>one <00:01.50>two". Ends are filled in by the caller. */
        private fun wordsOf(rest: String): List<LyricWord> {
            val stamps = WORD_STAMP.findAll(rest).toList()
            if (stamps.isEmpty()) return emptyList()
            val words = ArrayList<LyricWord>()
            for ((index, stamp) in stamps.withIndex()) {
                val until = stamps.getOrNull(index + 1)?.range?.first ?: rest.length
                val word = rest.substring(stamp.range.last + 1, until).replace(Regex("""\s+"""), " ").trim()
                if (word.isNotEmpty()) words += LyricWord(word, stampMs(stamp), -1)
            }
            return words
        }

        /** How long the last line is taken to last, where nothing says. */
        private const val LAST_LINE_MS = 8_000

        /**
         * About how long a line takes to sing, for a file that says when
         * lines start and not when they end: a second and a half, and a
         * tenth of a second a letter, within reason.
         */
        private fun singingMs(text: String): Int = (1_500 + text.length * 100).coerceIn(2_500, 10_000)

        /** A line guessed to be over leaves an instrumental only when this much is left after it: longer than one known to be. */
        private const val GUESSED_GAP_MS = 7_000
    }
}
