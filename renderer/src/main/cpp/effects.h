#pragma once
#include <cstdint>

/**
 * The output effects: an equalizer, a chorus, a reverb and a virtual
 * surround, on everything played, whatever engine plays it - applied in the
 * output stage, as Sound Check is, so none of the decoders has to know.
 *
 * They run in that order: the equalizer shapes the music, the chorus
 * thickens it, the reverb puts it in a room, and the surround places all of
 * that around the listener. Each is adapted from OpenMPT's code (see the
 * fx-*.h files), on a float stereo signal, with Sound Check measuring the
 * music and bringing it to its level before them - as the analysis of the
 * file heard none of them - and its limiter after, catching the peaks they
 * add. With Sound Check off a limiter of the same kind runs instead, while
 * any effect is on.
 *
 * The settings are handed over at any time from any thread, and taken up by
 * the audio thread at its next callback: every change glides, so moving a
 * slider never clicks, and a reverb's room is changed under a short fade.
 * An effect turned off fades out and then costs nothing; with all of them off
 * the output is the same, sample for sample, as without this stage at all.
 * Nothing is cleared at a seek or between tracks: what is ringing dies away,
 * as it would in a room, while the new music fades in.
 */

constexpr int kEffectsEqBands = 10;

/** The reverb: on or off, the room - see ReverbType in fx-reverb.h - and how much of it, 0 to 100. */
void effects_set_reverb(bool enabled, int type, int mixPercent);
/** The chorus: its rate in tenths of a hertz, 1 to 50; its spread, 0 to 100; how much of it, 0 to 100. */
void effects_set_chorus(bool enabled, int rateTenthsHz, int spreadPercent, int mixPercent);
/** The virtual surround: how much of it, 0 to 100. */
void effects_set_surround(bool enabled, int mixPercent);
/** The equalizer: [count] band gains in dB, -12 to 12, from 31 Hz up; bands past those given are flat. */
void effects_set_equalizer(bool enabled, const int32_t* bandsDb, int count);

/** Everything ringing in the effects silenced, at the audio thread's next callback: for a stream started anew. */
void effects_request_reset();

/**
 * The audio thread's part, in place of sound_check_process: Sound Check and
 * the effects on [data]. Stereo 16-bit only; anything else - and every
 * callback while all the effects are off - goes through Sound Check alone,
 * as before.
 */
void effects_process_output(void* data, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample, int rate);
