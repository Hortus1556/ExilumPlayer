#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>

/**
 * Furnace: .fur, and the DefleMask .dmf it grew out of.
 *
 * A tracker, and a multi-chip one - a song names the sound chips it was
 * written for (up to 32 of them, with every chip Furnace emulates on offer)
 * and each chip brings its own channels. So the pattern view and the channel
 * view both work, one column and one trace per chip channel, and what the
 * other trackers here call a sample can be an FM patch, a wavetable or a
 * recording.
 *
 * Played by Furnace's own engine, headless: no audio backend, no
 * configuration file, audio pulled a block at a time with nextBuf. See
 * furnace/ and patches/furnace-exilum.patch.
 */

bool furnace_decoder_probe(const uint8_t* data, size_t size);
bool furnace_decoder_load(const uint8_t* data, size_t size, const char* filename, int sampleRate);
void furnace_decoder_release();
bool furnace_decoder_loaded();

/** Readies this decoder to be the render source, at [rate]. See hvl_decoder_prepare_start. */
void furnace_decoder_prepare_start(int rate);
void furnace_decoder_set_sample_rate(int rate);

const char* furnace_decoder_title();
const char* furnace_decoder_author();
/** "Furnace", and the chips the song was written for. */
const char* furnace_decoder_type();
/** "Furnace" and its version: ModVars.renderingEngine, which the app keys this decoder's quirks on. */
const char* furnace_decoder_engine_name();

int furnace_decoder_duration_ms();
int furnace_decoder_subsong_duration_ms(int index);
const char* furnace_decoder_subsong_name(int index);
int furnace_decoder_position_ms();
void furnace_decoder_seek_ms(int ms);
bool furnace_decoder_ended();
void furnace_decoder_set_loop(bool loop);

/**
 * Shifts everything by [semitones] without changing the tempo.
 *
 * Through the song's tuning - the frequency of A-4 every chip works its
 * notes out from - so it takes effect on each channel as its next note or
 * pitch change comes.
 */
void furnace_decoder_set_pitch(double semitones);

/** Global tempo in percent, 10 to 200, without changing the pitch. */
void furnace_decoder_set_tempo(double percent);

double furnace_decoder_render_position_ms();
void furnace_decoder_loop_seek_ms(int ms);
void furnace_decoder_set_duration_override_ms(int ms);
bool furnace_decoder_duration_known();

int furnace_decoder_subsong_count();
bool furnace_decoder_start_subsong(int index);
int furnace_decoder_current_subsong();

int furnace_decoder_channels();
int furnace_decoder_instrument_count();
const char* furnace_decoder_instrument_name(int index);
int furnace_decoder_sample_count();
const char* furnace_decoder_sample_name(int index);
void furnace_decoder_mute(int channel, int muted);
int furnace_decoder_muted(int channel);

/** Rows in a pattern, and the orders - what PatternView draws, one order at a time. */
int furnace_decoder_pattern_rows();
int furnace_decoder_order_count();

/**
 * One order's rows, in PatternView's layout.
 *
 * Furnace's effects are two hex digits, which the one-character effect
 * column the other formats share cannot hold, so this decoder fills that
 * column its own way - and the app reads it that way for a module whose
 * rendering engine is Furnace (see PlayerEngine.getPatternData):
 * [fxType] holds the effect number as an unsigned byte and [fxParm] its
 * value; [fx2Type] is a set of flags rather than an effect, 1 when the row has
 * an effect and 2 when it has a volume, and [fx2Parm] is the volume.
 */
void furnace_decoder_pattern_data(
    int order, int numRows, int8_t* notes, int8_t* instruments,
    int8_t* fxType, int8_t* fxParm, int8_t* fx2Type, int8_t* fx2Parm);

bool furnace_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int furnace_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, in decoder-furnace-jni.cpp ---
void furnace_fillModVars(JNIEnv* env, jobject modVars);
void furnace_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean furnace_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
void furnace_fillPatternData(
    JNIEnv* env, jint pat, jint numRows, jbyteArray notes, jbyteArray instruments,
    jbyteArray fxType, jbyteArray fxParm, jbyteArray fx2Type, jbyteArray fx2Parm);
