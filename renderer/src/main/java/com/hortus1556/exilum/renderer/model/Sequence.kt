package com.hortus1556.exilum.renderer.model

import androidx.compose.runtime.Immutable

@Immutable
data class Sequence(
    val entryPoint: Int = 0,
    val duration: Int = 0,
    /**
     * What this subsong is called, when the format says.
     *
     * Blank for the tracker formats, where a subsong is a position in one
     * song and has no name to give. The chip formats are the opposite case:
     * an NSF holds a game's whole soundtrack and names the tracks, and
     * "Sub Song 7" throws that away.
     */
    val name: String = "",
)
