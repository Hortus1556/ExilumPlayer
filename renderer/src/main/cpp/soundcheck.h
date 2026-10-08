#pragma once
#include <cstdint>

/**
 * Sound Check: every track, whatever library plays it, at the same loudness.
 *
 * Loudness as ITU-R BS.1770 measures it (see loudness.h) - what Apple's Sound
 * Check and ReplayGain 2 are built on - brought to one target by a fixed gain
 * per track, applied in the output stage so it is the same for a MOD, a SID
 * and a VGM, and none of the decoders has to know.
 *
 * Like Apple's, the gain comes from measuring the track before it is heard:
 * the player has each file analysed apart from playback (see
 * engine_analyzeLoudness) and hands the result over as the track starts, so
 * the level is right from the first sample and does not move. Only for a
 * track that could not be analysed in time does the gain start from the
 * average of the tracks measured so far - and change once, gliding, when the
 * analysis arrives (sound_check_update), or, failing that, follow what the
 * output stage measures itself.
 *
 * A peak limiter follows the gain, for quiet tracks brought up to the target
 * whose peaks would otherwise clip.
 */

/** The loudness every track is brought to, in LUFS - about where Apple's Sound Check lands. */
constexpr float kSoundCheckTargetLufs = -16.0f;

/** Reported for "nothing measured yet". */
constexpr float kSoundCheckUnknown = -100.0f;

void sound_check_set_enabled(bool enabled);
bool sound_check_enabled();

/**
 * A new track. [knownLufs] is its measured loudness, or kSoundCheckUnknown;
 * [priorLufs] the average loudness of the tracks measured so far, which an
 * unknown track starts from.
 */
void sound_check_begin(float knownLufs, float priorLufs);

/**
 * The playing track's loudness has become known after it started: the gain
 * glides to it over about a second and stays.
 */
void sound_check_update(float knownLufs);

/** Integrated loudness of this track as the output stage has measured it so far, or kSoundCheckUnknown. */
float sound_check_loudness();
/** How much of this track has counted towards that, in seconds - the gated blocks. */
float sound_check_measured_seconds();
/** The gain being applied, in dB. */
float sound_check_gain_db();

/** Fields of sound_check_stats, for diagnosing it. */
enum SoundCheckStat {
  kStatEnabled = 0,       // 1 while it is on
  kStatProcessedSeconds,  // audio it has been handed since the app started
  kStatLoudness,          // integrated LUFS of this track so far
  kStatMeasuredSeconds,   // how much of the track that counts
  kStatGainDb,            // gain it is aiming at
  kStatLimiterDb,         // the most the limiter took off in the last 100 ms
  kStatFixed,             // 1 when the gain comes from a known loudness
  kStatBegins,            // tracks started
  kStatCount
};
void sound_check_stats(float* out);

/**
 * The audio thread's part: measures [data] and applies the gain and the
 * limiter to it, in place. 16-bit when [bytesPerSample] is 2, float when 4,
 * as the output stage treats them.
 */
void sound_check_process(void* data, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample, int rate);

/**
 * The same in two halves, for the output effects to go between (see
 * effects.h), in place on stereo floats of full scale 1. The first measures
 * the music as it comes - as the analysis of the file heard none of the
 * effects - and brings it to its level, before them: what still rings in
 * them from the track before keeps the level it went in at. The second is
 * the limiter, after them, which then catches the peaks they add.
 */
void sound_check_level(float* left, float* right, int32_t frames, int rate);
void sound_check_limit(float* left, float* right, int32_t frames, int rate);
