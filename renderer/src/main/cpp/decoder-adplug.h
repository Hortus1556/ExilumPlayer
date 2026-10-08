#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

/**
 * AdPlug: the AdLib formats. rad, hsc, lds, cmf, dro, imf, d00, a2m, sng and
 * some forty more, all of them ways of driving a Yamaha OPL2 or OPL3.
 *
 * These are trackers in origin, but AdPlug's interface stops at the current
 * position, pattern, row and speed - there is no way to ask a player for the
 * note in a given cell, and most of its forty-odd players hold their pattern
 * data in a shape private to the format. So the pattern view is not shown for
 * these, and what is shown instead is real rather than approximated: the OPL
 * chip is emulated here, so every channel's note, level and waveform come from
 * the registers the player actually wrote and the samples the chip actually
 * produced.
 *
 * [filename] matters. Several AdPlug players decide whether a file is theirs
 * partly by its extension, so the name has to travel with the bytes.
 */
/**
 * Whether [filename] carries an extension one of AdPlug's players claims.
 *
 * Asked first because the real probe is expensive - AdPlug has no magic
 * number its formats share, so the only way to find out is to hand the bytes
 * to each of its forty-odd players until one accepts them, and doing that for
 * every file in a folder scan would be felt.
 */
bool adplug_decoder_claims_extension(const char* filename);

bool adplug_decoder_probe(const uint8_t* data, size_t size, const char* filename);

/** Every extension AdPlug's players claim, lowercased and without repeats. */
void adplug_decoder_extensions(std::vector<std::string>& out);
bool adplug_decoder_load(const uint8_t* data, size_t size, const char* filename, int sampleRate);
void adplug_decoder_release();
bool adplug_decoder_loaded();

const char* adplug_decoder_title();
const char* adplug_decoder_type();
const char* adplug_decoder_author();
int adplug_decoder_channels();

/**
 * Whether the loaded player can hand over its pattern grid.
 *
 * Most AdPlug formats are trackers and most of its players hold a grid, but
 * the interface never exposed one - so the accessors this asks for are local
 * additions to the library. See adplug/ANDROID-CHANGES.md. A player without
 * them, and the register-log formats that have no grid at all, answer no.
 */
bool adplug_decoder_has_patterns();
int adplug_decoder_pattern_count();
int adplug_decoder_pattern_rows();
int adplug_decoder_order_count();

/** One pattern, row-major with a fixed MAX_CHANNELS stride. */
void adplug_decoder_pattern_data(
    int pattern, int numRows, int8_t* notes, int8_t* instruments, int8_t* fxType,
    int8_t* fxParm, int8_t* fx2Type, int8_t* fx2Parm);
int adplug_decoder_instrument_count();
const char* adplug_decoder_instrument_name(int index);

/**
 * The fixed list of patch names channels are labelled with.
 *
 * Not the tune's instruments - see the note on the channel fields. These
 * describe how a channel is wired, which is the one thing about an OPL voice
 * that can be read back reliably.
 */
int adplug_decoder_patch_count();
const char* adplug_decoder_patch_name(int index);

int adplug_decoder_duration_ms();
int adplug_decoder_position_ms();
void adplug_decoder_seek_ms(int ms);

int adplug_decoder_subsong_count();
int adplug_decoder_current_subsong();
bool adplug_decoder_start_subsong(int index);
int adplug_decoder_subsong_duration_ms(int index);

bool adplug_decoder_ended();
void adplug_decoder_set_loop(bool loop);

void adplug_decoder_mute(int channel, int muted);
int adplug_decoder_muted(int channel);

/**
 * Which OPL emulator is heard: 0 Nuked OPL3, 1 DOSBox, 2 MAME (OPL2), 3 Ken
 * Silverman's AdLibEmu (OPL2). Nuked keeps running for the views either way.
 * Changing it while a tune plays replays the tune on the new core up to where
 * it was.
 */
void adplug_decoder_set_core(int core);

/** Scales the mix. 100 leaves it alone. */
void adplug_decoder_set_gain(int percent);

/**
 * Shifts everything by [semitones] without changing the tempo.
 *
 * The OPL is told what to play as an f-number and a block - a frequency and
 * its octave - so this is a multiplication applied as those registers go past.
 */
void adplug_decoder_set_pitch(double semitones);

/** Global tempo in percent, 10 to 200, without changing the pitch. */
void adplug_decoder_set_tempo(double percent);

/** Song time at the end of what has been rendered, in milliseconds. */
double adplug_decoder_render_position_ms();
/** A seek for the custom loop, keeping the views' history. */
void adplug_decoder_loop_seek_ms(int ms);
/** The subsong's length as the listener set it; 0 or less goes back to the measured one. */
void adplug_decoder_set_duration_override_ms(int ms);
/** Whether the subsong's length was measured to its end. */
bool adplug_decoder_duration_known();

void adplug_decoder_set_sample_rate(int rate);
void adplug_decoder_prepare_start(int rate);

/** False when nothing has been rendered yet, which is what `valid` reports. */
bool adplug_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int adplug_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels,
                          int32_t bytesPerSample);

// ---------------------------------------------------------------------------
// JNI glue, in decoder-adplug-jni.cpp
// ---------------------------------------------------------------------------

#include <jni.h>

void adplug_fillModVars(JNIEnv* env, jobject modVars);
void adplug_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean adplug_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
void adplug_fillPatternData(
    JNIEnv* env, jint pat, jint numRows, jbyteArray notes, jbyteArray instruments,
    jbyteArray fxType, jbyteArray fxParm, jbyteArray fx2Type, jbyteArray fx2Parm);
