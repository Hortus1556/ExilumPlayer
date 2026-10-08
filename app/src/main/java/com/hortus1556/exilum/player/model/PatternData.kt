package com.hortus1556.exilum.player.model

import androidx.compose.runtime.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class PatternData(
    val cells: ImmutableList<ImmutableList<NoteCell>> = persistentListOf(),
    val numChannels: Int = 0,
    val numRows: Int = 0,
    val patternIndex: Int = -1
)

private val NOTE_NAMES = arrayOf(
    "C-", "C#", "D-", "D#", "E-", "F-",
    "F#", "G-", "G#", "A-", "A#", "B-",
)

private val HEX2 = Array(256) { "%02X".format(it) }

/**
 * An effect number as the character a tracker shows it by.
 *
 * Digits and then letters, the MOD way, for 0 to 35. Past that, AdLib Tracker
 * II's own glyphs - its effect column runs on after Z through & % ! @ = # $ ~ ^
 * ` > < - and 48 and 49, which its replay code substitutes for > and < with a
 * fix applied, show as the effects they stand in for.
 */
private const val EXTRA_EFFECT_GLYPHS = "&%!@=#$~^`><"

private fun effectChar(effect: Int): Char = when (effect) {
    in 0..9 -> '0' + effect
    in 10..35 -> 'A' + (effect - 10)
    in 36..47 -> EXTRA_EFFECT_GLYPHS[effect - 36]
    48 -> '>'
    49 -> '<'
    else -> '?'
}

@Immutable
data class NoteCell(
    val fxParam: Int,
    val fxType: Int,
    val instrument: Int,
    val note: Int,
    val fx2Param: Int = -1, // secondary effect: f2t/f2p (libxmp), volume column (openmpt)
    val fx2Type: Int = -1,
    /** The effect number is two hex digits rather than one character - Furnace's effects. */
    val wideEffect: Boolean = false,
) {
    val isEmpty: Boolean get() = note == 0 && instrument == 0 && fxType < 0 && fx2Type < 0

    val noteStr: String = when (note) {
        0 -> "---"
        // libopenmpt's raw ModCommand::NOTE special values (Snd_defs.h / modcommand.h):
        // NOTE_KEYOFF=0xFF, NOTE_NOTECUT=0xFE, NOTE_FADE=0xFD. These flow through
        // unchanged from openmpt_getPatternRow's jbyte cast (sign-extended, then
        // masked back to unsigned in PlayerEngine.getPatternData), so they arrive
        // here as 255/254/253 — not 0x80/0x81, which openmpt never actually emits.
        0xFF -> "=="  // Note Off
        0xFE -> "^^"  // Note Cut
        0xFD -> "~~"  // Note Fade
        in 1..128 -> (note - 1).let { n -> "${NOTE_NAMES[n % 12]}${n / 12}" }
        else -> "???" // NOTE_PC (0xFC) / NOTE_PCS (0xFB) plugin param-control "notes"
    }

    val instrumentStr: String = if (instrument > 0) HEX2[instrument and 0xFF] else ".."

    val effectTypeStr: String = when {
        fxType < 0 -> "."
        wideEffect -> HEX2[fxType and 0xFF]
        else -> effectChar(fxType).toString()
    }

    val effectParamStr: String = if (fxType >= 0) HEX2[fxParam and 0xFF] else ".."

    val effect2TypeStr: String = if (fx2Type >= 0) effectChar(fx2Type).toString() else "."

    val effect2ParamStr: String = if (fx2Type >= 0) HEX2[fx2Param and 0xFF] else ".."
}
