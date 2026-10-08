#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>

/**
 * CustomMade - Ron Klaren's music from Amiga games: .cm, .rk, .rkb.
 *
 * Played by a port of the replay routine - see custommade/custommade.h - on
 * an emulated Paula, four channels, left and right as on the Amiga. No UADE
 * and no 68000: the routine's tables are found in the module and played with
 * its logic in C++, which was checked against UADE sample by sample.
 *
 * The routine goes round by itself, so the length of a subsong is measured at
 * load as the time until every voice has come back to the start of its track
 * list, the point UADE calls the song's end. Looping starts it again from the
 * top there.
 */

bool cm_decoder_probe(const uint8_t* data, size_t size);
bool cm_decoder_load(const uint8_t* data, size_t size, int sampleRate);
void cm_decoder_release();
bool cm_decoder_loaded();

void cm_decoder_prepare_start(int rate);
void cm_decoder_set_sample_rate(int rate);

const char* cm_decoder_type();

int cm_decoder_duration_ms();
int cm_decoder_subsong_duration_ms(int index);
bool cm_decoder_duration_known();
int cm_decoder_position_ms();
void cm_decoder_seek_ms(int ms);
bool cm_decoder_ended();
void cm_decoder_set_loop(bool loop);

double cm_decoder_render_position_ms();
void cm_decoder_loop_seek_ms(int ms);
void cm_decoder_set_duration_override_ms(int ms);

/** Semitones, -12 to 12: Paula's clock runs faster or slower, the tempo stays. */
void cm_decoder_set_pitch(double semitones);
/** Percent, 10 to 200: the routine's timer runs faster or slower, the pitch stays. */
void cm_decoder_set_tempo(double percent);

int cm_decoder_subsong_count();
bool cm_decoder_start_subsong(int index);
int cm_decoder_current_subsong();

int cm_decoder_channels();
int cm_decoder_instrument_count();
/** "Synth n" or "Sample n": whether the instrument is a waveform the routine rewrites, or a recording. */
const char* cm_decoder_instrument_name(int index);
void cm_decoder_mute(int channel, int muted);
int cm_decoder_muted(int channel);

bool cm_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int cm_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, in decoder-cm-jni.cpp ---
void cm_fillModVars(JNIEnv* env, jobject modVars);
void cm_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean cm_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
