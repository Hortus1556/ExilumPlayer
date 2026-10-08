package com.hortus1556.exilum.ui.screens.preferences

import androidx.annotation.StringRes
import com.hortus1556.exilum.R

/**
 * The renderer's decoders, in the order a file is offered to them, as the
 * formats screen presents them. [id] is the renderer's name for each (see
 * Player.getFormatGroups), [library] its entry in the About screen's list.
 */
internal enum class NativeEngine(
    val id: String,
    val title: String,
    val licence: String,
    val library: String,
    @StringRes val description: Int,
) {
    Ffmpeg("ffmpeg", "FFmpeg", "LGPL-2.1-or-later", "native.ffmpeg", R.string.engine_ffmpeg_desc),
    LibOpenMpt("libopenmpt", "libopenmpt", "BSD-3-Clause", "native.libopenmpt", R.string.engine_libopenmpt_desc),
    Hively("hively", "HivelyTracker", "BSD-3-Clause", "native.hively", R.string.engine_hively_desc),
    Furnace("furnace", "Furnace", "GPL-2.0-or-later", "native.furnace", R.string.engine_furnace_desc),
    Sid("libsidplayfp", "libsidplayfp", "GPL-2.0-or-later", "native.libsidplayfp", R.string.engine_sid_desc),
    CustomMade("custommade", "CustomMade", "No licence stated", "native.custommade", R.string.engine_custommade_desc),
    Mgba("mgba", "mGBA", "MPL-2.0", "native.mgba", R.string.engine_mgba_desc),
    Vio2sf("vio2sf", "vio2sf", "GPL-2.0-or-later", "native.vio2sf", R.string.engine_vio2sf_desc),
    Lazyusf2("lazyusf2", "lazyusf2", "GPL-2.0-or-later", "native.lazyusf2", R.string.engine_lazyusf2_desc),
    Aopsf("aopsf", "aopsf", "BSD-3-Clause and GPL-3.0", "native.aopsf", R.string.engine_aopsf_desc),
    Dsp("dsp", "GameCube streams", "GPL-3.0-or-later, from vgmstream (ISC)", "native.vgmstream", R.string.engine_dsp_desc),
    Vgm("libvgm", "libvgm", "Per-core licences", "native.libvgm", R.string.engine_vgm_desc),
    Gme("gme", "Game_Music_Emu", "LGPL-2.1-or-later", "native.gme", R.string.engine_gme_desc),
    AdPlug("adplug", "AdPlug", "LGPL-2.1-or-later", "native.adplug", R.string.engine_adplug_desc),
    ;

    companion object {
        fun of(id: String): NativeEngine? = entries.firstOrNull { it.id == id }
    }
}
