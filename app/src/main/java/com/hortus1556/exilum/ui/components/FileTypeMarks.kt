package com.hortus1556.exilum.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.entity.ModuleEntity

/**
 * What stands at the head of a row for the kind of file it is: the logo of
 * the machine, the program or the format the music is from, as its owner
 * draws it and in its own colours - or, where there is no logo to be had,
 * what the file's name ends in.
 */
@Immutable
sealed interface FileTypeMark {

    /**
     * A logo, a drawable sized as it stands in the slot.
     *
     * @param dark the drawable for a dark theme, where the logo has black
     *   among its colours and that black has to be white there.
     * @param mono it is one colour throughout, and on a dark theme is drawn
     *   in the colour of the text instead.
     * @param size how large to draw it, for one that is a picture and has
     *   no size of its own in points.
     */
    data class Logo(
        @param:DrawableRes val light: Int,
        @param:DrawableRes val dark: Int = light,
        val mono: Boolean = false,
        val size: DpSize = DpSize.Unspecified,
    ) : FileTypeMark

    /** No logo: the ending of the file's name, in capitals. */
    data class Sigla(val text: String) : FileTypeMark
}

/**
 * The mark for each kind of file the app plays.
 *
 * The logos are their owners' trademarks, here to say what a file is; where
 * each is from, and under what terms, is in NOTICE. They were cut to what
 * they draw and sized to much the same area each, within the slot.
 */
object FileTypeMarks {

    /** The slot every mark is drawn in the middle of, so that the rows' text lines up. */
    val Slot = DpSize(64.dp, 40.dp)

    /**
     * Where the text starts in a row that leads with the slot, 16 points in
     * and 12 before its text: where the line between two such rows starts.
     */
    val RowTextStart = 16.dp + Slot.width + 12.dp

    private val Amiga = FileTypeMark.Logo(R.drawable.logo_amiga, mono = true)
    private val Furnace = FileTypeMark.Logo(R.drawable.logo_furnace, size = DpSize(37.dp, 29.3.dp))
    private val Commodore64 = FileTypeMark.Logo(R.drawable.logo_c64, mono = true)
    private val Nes = FileTypeMark.Logo(R.drawable.logo_nes, dark = R.drawable.logo_nes_dark)
    private val SuperNintendo = FileTypeMark.Logo(R.drawable.logo_snes)
    private val GameBoy = FileTypeMark.Logo(R.drawable.logo_gameboy, mono = true)
    private val GameBoyAdvance = FileTypeMark.Logo(R.drawable.logo_gba)
    private val NintendoDs = FileTypeMark.Logo(R.drawable.logo_nds, dark = R.drawable.logo_nds_dark)
    private val Nintendo64 = FileTypeMark.Logo(R.drawable.logo_n64, dark = R.drawable.logo_n64_dark)
    private val PlayStation = FileTypeMark.Logo(R.drawable.logo_playstation)
    private val GameCube = FileTypeMark.Logo(R.drawable.logo_gamecube)
    private val MegaDrive = FileTypeMark.Logo(R.drawable.logo_megadrive)
    private val Sega = FileTypeMark.Logo(R.drawable.logo_sega)
    private val PcEngine = FileTypeMark.Logo(R.drawable.logo_pcengine)
    private val Msx = FileTypeMark.Logo(R.drawable.logo_msx)
    private val Atari = FileTypeMark.Logo(R.drawable.logo_atari)
    private val Spectrum = FileTypeMark.Logo(R.drawable.logo_spectrum)
    private val Pc98 = FileTypeMark.Logo(R.drawable.logo_pc98, mono = true)
    private val AdLib = FileTypeMark.Logo(R.drawable.logo_adlib, dark = R.drawable.logo_adlib_dark)
    private val Flac = FileTypeMark.Logo(R.drawable.logo_flac)
    private val Mp3 = FileTypeMark.Logo(R.drawable.logo_mp3, mono = true)
    private val Ogg = FileTypeMark.Logo(R.drawable.logo_ogg)
    private val Opus = FileTypeMark.Logo(R.drawable.logo_opus, dark = R.drawable.logo_opus_dark)
    private val Aac = FileTypeMark.Logo(R.drawable.logo_aac, mono = true)
    private val Alac = FileTypeMark.Logo(R.drawable.logo_alac, mono = true)
    private val WavPack = FileTypeMark.Logo(R.drawable.logo_wavpack, mono = true)

    /**
     * The mark for a file called something ending in [extension] that the
     * index says is [type]. By the name first - it says which machine - and
     * the type where one name covers two kinds (an .m4a is AAC or ALAC).
     */
    fun of(extension: String, type: String = ""): FileTypeMark {
        val ending = extension.lowercase()
        fun says(word: String) = type.contains(word, ignoreCase = true)
        return when (ending) {
            // The Amiga's own: the first modules, and the players written for it.
            "mod", "ahx", "thx", "hvl", "cm", "rk", "rkb" -> Amiga
            "fur" -> Furnace
            "sid", "psid", "rsid" -> Commodore64
            "nsf", "nsfe" -> Nes
            "spc" -> SuperNintendo
            "gbs" -> GameBoy
            "gsf", "minigsf" -> GameBoyAdvance
            "2sf", "mini2sf" -> NintendoDs
            "usf", "miniusf" -> Nintendo64
            "psf", "minipsf" -> PlayStation
            "dsp", "adx", "sfa", "hps", "ast", "afc", "stx", "adp", "dtk", "trk", "lstm", "idsp", "mdsp", "mpdsp",
            "ste", "mss", "gcm", "ddsp", "itl", "ds2", "iadp", "ymf", "cfn", "gcub", "str", "pdt", "rsm", "adpcm", "lwav",
            "ldat" -> GameCube
            // Scream Tracker's modules had the name first.
            "stm" -> if (says("Scream") || type.isBlank()) FileTypeMark.Sigla("STM") else GameCube
            // What was logged from one machine's chips. A .vgm or an .s98 from
            // NEC's is from whichever machine it was, and says so only inside.
            "gym" -> MegaDrive
            "sgc" -> Sega
            "hes" -> PcEngine
            "kss" -> Msx
            "sap" -> Atari
            "ay" -> Spectrum
            "s98" -> Pc98
            "flac" -> Flac
            "alac" -> Alac
            "wv" -> WavPack
            "mp3", "mp2" -> Mp3
            "aac" -> Aac
            "opus" -> Opus
            // Ogg is the box: what is in it is usually Vorbis, and need not be.
            "ogg", "oga" -> when {
                says("Opus") -> Opus
                says("FLAC") -> Flac
                else -> Ogg
            }
            "m4a", "m4b", "mp4" -> if (says("ALAC")) Alac else Aac
            "caf" -> when {
                says("ALAC") -> Alac
                says("AAC") -> Aac
                else -> FileTypeMark.Sigla("CAF")
            }
            else -> when {
                // The names AdPlug opens are forty: told by what the index calls them.
                says("AdLib") || says("OPL") || ending in ADLIB -> AdLib
                else -> FileTypeMark.Sigla(ending.uppercase())
            }
        }
    }

    fun of(module: ModuleEntity): FileTypeMark =
        of(module.fileExtension.ifBlank { module.filename.substringAfterLast('.', "") }, module.moduleType)

    /** The commoner of AdPlug's names, for a file not yet tested and so not yet called anything. */
    private val ADLIB = setOf(
        "rad", "a2m", "a2t", "amd", "d00", "hsc", "hsp", "dro", "imf", "wlf", "cmf", "rol", "ksm", "laa", "lds", "sng",
        "sa2", "raw", "bam", "dmo", "dtm", "got", "jbm", "mad", "mkj", "msc", "mtk", "sat", "xad", "xsm", "adl", "edl",
    )
}

/**
 * A row's leading slot with [mark] in the middle of it, and nothing under
 * it: the logo alone, as it is.
 */
@Composable
fun FileTypeSlot(mark: FileTypeMark, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(FileTypeMarks.Slot), contentAlignment = Alignment.Center) {
        when (mark) {
            is FileTypeMark.Logo -> {
                val ink = MaterialTheme.colorScheme.onSurface
                val onDark = ink.luminance() > 0.5f
                Image(
                    painter = painterResource(if (onDark) mark.dark else mark.light),
                    contentDescription = null,
                    modifier = if (mark.size.isSpecified) Modifier.size(mark.size) else Modifier,
                    // A logo that is black, or one dark colour, would be
                    // lost on a dark row: there it is the text's colour.
                    colorFilter = if (onDark && mark.mono) ColorFilter.tint(ink) else null,
                )
            }

            is FileTypeMark.Sigla -> Text(
                text = mark.text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (mark.text.length > 5) 10.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
