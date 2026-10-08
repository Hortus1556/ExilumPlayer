#pragma once
#include <cstdint>

/**
 * The spectrum of what is being played, for a display of bars like a hi-fi's:
 * how loud the sound is in each of a row of frequency bands, low to high.
 *
 * It is taken from the output itself - after the effects, before the volume -
 * so every engine has it without knowing, the equalizer shows in it, and
 * turning the volume down does not shrink it. The audio thread only keeps the
 * last second and a bit of what it played; the bands are worked out by
 * whoever asks, from the stretch around the frame being heard at that moment,
 * so the bars move with the sound and not with the buffer ahead of it.
 *
 * A band's level is the power of the left and of the right, not of the two
 * added together: what is on one side only, or out of phase between them, is
 * shown as loud as it is heard.
 */

/** The most bands a read can ask for. */
constexpr int kSpectrumMaxBands = 64;

/**
 * Keeps [frames] frames of the output, 16-bit and interleaved, the first of
 * them stream frame [firstFrame] at [rate] Hz. Audio thread; silence
 * included, so that the bars fall when the music stops.
 */
void spectrum_write(const int16_t* samples, int32_t frames, int32_t channels, int64_t firstFrame, int rate);

/**
 * Forgets what was kept. For when the stream's frames start again from 0 -
 * a stream started anew, or closed: what is kept is filed under the old
 * stream's numbers, and until the new one had written over it a read took it
 * for what is playing now. Not while a callback may be running.
 */
void spectrum_reset();

/**
 * The level of each of [count] bands, spaced evenly by pitch from the lowest
 * note a speaker makes much of to the top of hearing, on a scale of decibels
 * that is the display's height: 0 at its bottom, where a sound is the last of
 * a note dying away, and 1 at its top, which is as loud as music gets in one
 * band. Not held to that: a band under the bottom is below 0, down to -1,
 * and one over the top above 1 - for a display that turns itself up or down.
 * False, and every band -1, when nothing has been played yet. Any thread
 * but the audio one: it takes a lock and a transform.
 */
bool spectrum_read(float* bands, int count);

/** The frequency in the middle of band [index] of [count], in Hz, for its label. */
float spectrum_band_hz(int index, int count);
