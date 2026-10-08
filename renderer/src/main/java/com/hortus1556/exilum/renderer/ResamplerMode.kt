package com.hortus1556.exilum.renderer

/**
 * How libopenmpt reads a sample between the points it actually has. Maps to
 * `render.interpolationfilterlength`, or to its Paula emulation for the two
 * Amiga modes.
 *
 * Linear was the fifth of these and is gone: between off and spline it was
 * neither the raw waveform nor a smooth one, and no tracker ever sounded like
 * it. Anyone who had it gets sinc, which is what the app now starts on.
 */
enum class ResamplerMode(val id: Int) {
    /** No interpolation at all: the sample as it is, steps and all. */
    OFF(0),
    CUBIC(2),
    SINC(3), // Player.DEFAULT_INTERPOLATION
    AMIGA_A500(100),
    AMIGA_A1200(101);

    companion object {
        fun fromId(id: Int): ResamplerMode =
            entries.firstOrNull { it.id == id } ?: SINC
    }
}
