package com.hortus1556.exilum.renderer.model

import androidx.compose.runtime.Stable

/**
 * @see [com.hortus1556.exilum.renderer.Player.getModVars]
 */
@Stable
data class ModVars(
    val name: String = "",  /* Module title */
    val type: String = "",  /* Module format */
    val pat: Int = 0,   /* Number of patterns */
    val trk: Int = 0,   /* Number of tracks */
    val chn: Int = 0,   /* Tracks per pattern */
    val ins: Int = 0,   /* Number of instruments */
    val smp: Int = 0,   /* Number of samples */
    val spd: Int = 0,   /* Initial speed */
    val bpm: Int = 0,   /* Initial BPM */
    val len: Int = 0,   /* Module length in patterns */
    val rst: Int = 0,   /* Restart position */
    val gvl: Int = 0,   /* Global volume */
    val miNumSequences: Int = 0,    /* Number of valid sequences */
    val miComment: String = "",     /* Comment text, if any */
    val seqData: Array<Sequence> = emptyArray(),
    val instruments: Array<String> = emptyArray(),
    val samples: Array<String> = emptyArray(),
    /**
     * Whether this format has patterns to show.
     *
     * False for the chip-log and CPU-dump formats, where PatternView is left
     * out of the player rather than shown with nothing in it. Reported by the
     * decoder rather than guessed from the pattern count, because a decoder
     * that has patterns but has not counted them yet is not the same thing as
     * one that has none.
     */
    val hasPatterns: Boolean = true,
    /**
     * Whether this format has per-channel output to draw.
     *
     * False where the decoder can only produce a finished mix - a chip whose
     * emulation has not been taught to hand back each voice on its own. The
     * channel view is left out of the player rather than shown with a row of
     * flat lines per voice, which reads as a fault rather than as an absence.
     *
     * True by default, because every decoder here manages it except for the
     * less-played chips behind libvgm.
     */
    val hasChannels: Boolean = true,
    /**
     * Whether the file says how long it is.
     *
     * False where the length is a guess - a GME track with no length tag, an
     * AdPlug or AHX tune that was measured until the measuring gave up - which
     * is when the listener is offered a length of their own for it.
     */
    val durationKnown: Boolean = true,
    val renderingEngine: String = "",
) {
    val modName: String get() = name.trim()
    val modType: String get() = type.trim()

    fun sequenceDuration(idx: Int): Int = seqData.getOrNull(idx)?.duration ?: 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ModVars

        if (pat != other.pat) return false
        if (trk != other.trk) return false
        if (chn != other.chn) return false
        if (ins != other.ins) return false
        if (smp != other.smp) return false
        if (spd != other.spd) return false
        if (bpm != other.bpm) return false
        if (len != other.len) return false
        if (rst != other.rst) return false
        if (gvl != other.gvl) return false
        if (miNumSequences != other.miNumSequences) return false
        if (name != other.name) return false
        if (type != other.type) return false
        if (miComment != other.miComment) return false
        if (!seqData.contentEquals(other.seqData)) return false
        if (!instruments.contentEquals(other.instruments)) return false
        if (!samples.contentEquals(other.samples)) return false
        if (hasPatterns != other.hasPatterns) return false
        if (hasChannels != other.hasChannels) return false
        if (durationKnown != other.durationKnown) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pat
        result = 31 * result + trk
        result = 31 * result + chn
        result = 31 * result + ins
        result = 31 * result + smp
        result = 31 * result + spd
        result = 31 * result + bpm
        result = 31 * result + len
        result = 31 * result + rst
        result = 31 * result + gvl
        result = 31 * result + miNumSequences
        result = 31 * result + name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + miComment.hashCode()
        result = 31 * result + seqData.contentHashCode()
        result = 31 * result + instruments.contentHashCode()
        result = 31 * result + samples.contentHashCode()
        result = 31 * result + hasPatterns.hashCode()
        result = 31 * result + hasChannels.hashCode()
        result = 31 * result + durationKnown.hashCode()
        return result
    }
}
