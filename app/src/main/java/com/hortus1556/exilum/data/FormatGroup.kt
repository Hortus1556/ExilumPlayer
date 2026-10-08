package com.hortus1556.exilum.data

import kotlinx.collections.immutable.ImmutableList

/**
 * One of the renderer's decoders, by the id it goes by there (libopenmpt,
 * hively, furnace, libsidplayfp, custommade, libvgm, gme, adplug), and the
 * extensions it opens.
 */
data class FormatGroup(
    val id: String,
    val extensions: ImmutableList<String>,
)
