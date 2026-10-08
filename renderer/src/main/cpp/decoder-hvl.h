#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <string>

/**
 * AHX and Hively: .ahx, .thx, .hvl.
 *
 * These are trackers, unlike the emulator formats - Amiga ones, with an order
 * list, patterns and per-channel tracks - so the pattern view works and is
 * kept. What they do not have is samples: every voice runs a small synthesis
 * program instead, which is why the instrument list reads as names of
 * programs rather than of recordings.
 */

bool hvl_decoder_probe(const uint8_t* data, size_t size);
bool hvl_decoder_load(const uint8_t* data, size_t size, int sampleRate);
void hvl_decoder_release();
bool hvl_decoder_loaded();

void hvl_decoder_set_sample_rate(int rate);

/**
 * Readies this decoder to be the render source, at [rate].
 *
 * The counterpart of openmpt_prepareStart: starting the stream restarts the
 * device's frame counters, so any frame stamp already published describes a
 * timeline that no longer exists.
 */
void hvl_decoder_prepare_start(int rate);

const char* hvl_decoder_title();
int hvl_decoder_duration_ms();

/** Nominal length of one subsong, so the list can show them apart. */
int hvl_decoder_subsong_duration_ms(int index);
int hvl_decoder_position_ms();
void hvl_decoder_seek_ms(int ms);
bool hvl_decoder_ended();
void hvl_decoder_set_loop(bool loop);

/**
 * Scales the tune's own stereo spread, on libopenmpt's scale: 0 is mono, 100
 * half of it, 200 all of it.
 *
 * Half by default because all of it is the Amiga's hard panning - two voices
 * entirely left, two entirely right - which on headphones is a voice in each
 * ear and nothing in the middle, and is not what anyone hears from a player
 * that has a separation setting at all. The same scale as libopenmpt's
 * setting, so one number means one width wherever a tune is played from.
 *
 * AHX takes its spread from a five-step table and HVL carries one in the
 * file; this scales that rather than replacing it, so a tune that was mixed
 * narrow stays narrow.
 */
void hvl_decoder_set_stereo_separation(int percent);

/** Multiplies the mix gain the tune loaded with. 100 leaves it alone. */
void hvl_decoder_set_gain(int percent);

/**
 * Shifts everything by [semitones] without changing the tempo.
 *
 * Scales the step each voice reads its wavetable at: the interrupts still
 * come at the same rate, the wave is just read faster or slower.
 */
void hvl_decoder_set_pitch(double semitones);

/** Global tempo in percent, 10 to 200, without changing the pitch. */
void hvl_decoder_set_tempo(double percent);

/** Song time at the end of what has been handed to the output, in milliseconds. */
double hvl_decoder_render_position_ms();
/** A seek for the custom loop, keeping the views' history. */
void hvl_decoder_loop_seek_ms(int ms);
/** The subsong's length as the listener set it; 0 or less goes back to the measured one. */
void hvl_decoder_set_duration_override_ms(int ms);
/** Whether the subsong's length was measured to its end rather than estimated. */
bool hvl_decoder_duration_known();

int hvl_decoder_subsong_count();
bool hvl_decoder_start_subsong(int index);
int hvl_decoder_current_subsong();

int hvl_decoder_channels();
int hvl_decoder_instrument_count();
std::string hvl_decoder_instrument_name(int index);
void hvl_decoder_mute(int channel, int muted);
int hvl_decoder_muted(int channel);

/** Rows in a position, and the notes in one - what PatternView draws. */
int hvl_decoder_pattern_rows();
int hvl_decoder_position_count();
void hvl_decoder_pattern_data(
    int position, int numRows, int8_t* notes, int8_t* instruments,
    int8_t* fxType, int8_t* fxParm, int8_t* fx2Type, int8_t* fx2Parm);

/** False when nothing has been rendered yet, which is what `valid` reports. */
bool hvl_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int hvl_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, in decoder-hvl-jni.cpp ---
void hvl_fillModVars(JNIEnv* env, jobject modVars);
void hvl_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean hvl_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
void hvl_fillPatternData(
    JNIEnv* env, jint pat, jint numRows, jbyteArray notes, jbyteArray instruments,
    jbyteArray fxType, jbyteArray fxParm, jbyteArray fx2Type, jbyteArray fx2Parm);
