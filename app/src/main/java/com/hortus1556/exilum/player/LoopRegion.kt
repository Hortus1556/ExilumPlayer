package com.hortus1556.exilum.player

import androidx.compose.runtime.Immutable

/**
 * The custom loop playing, in song milliseconds: playback reaching [endMs]
 * goes straight back to [startMs].
 *
 * @param presetId the saved loop preset this is, or null for one being tried
 *   out in the overrides sheet and not saved yet.
 */
@Immutable
data class LoopRegion(
    val startMs: Int,
    val endMs: Int,
    val presetId: Int? = null,
)
