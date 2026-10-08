#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>

/**
 * VGM and VGZ: a log of what was written to the sound chips, and when.
 *
 * Not a tune and not a program. A VGM is the register traffic a real machine
 * produced, captured while the music played, so nothing here interprets a
 * score or runs a CPU - it replays writes against emulated chips at the times
 * they originally happened. There is no pattern, no order list and no
 * instrument, and the file does not know what a note is.
 *
 * Which machine it is depends entirely on which chips the header declares a
 * clock for. What is emulated here is the Master System, the Game Gear and the
 * Mega Drive - the PSG, the FM chip beside it, and the OPLL the Japanese
 * Master System had - because those are the machines whose chips can be
 * emulated under a licence this app can take. A file wanting a chip that is
 * not here still plays, with that chip silent, rather than being refused.
 */

bool vgm_decoder_probe(const uint8_t* data, size_t size);
bool vgm_decoder_load(const uint8_t* data, size_t size, int sampleRate);
void vgm_decoder_release();
bool vgm_decoder_loaded();

void vgm_decoder_set_sample_rate(int rate);

/** Readies this decoder to be the render source, at [rate]. See hvl_decoder_prepare_start. */
void vgm_decoder_prepare_start(int rate);

const char* vgm_decoder_title();

/** The machine, from the GD3 tag or from which chips the header clocks. */
const char* vgm_decoder_system();

int vgm_decoder_duration_ms();
int vgm_decoder_position_ms();
void vgm_decoder_seek_ms(int ms);
bool vgm_decoder_ended();
void vgm_decoder_set_loop(bool loop);

/**
 * Every voice this can actually draw, across every chip the file uses.
 *
 * Chips whose emulation cannot hand back their voices separately are not
 * counted: they contribute sound but no channels. A file made entirely of
 * those reports none, and the channel view is dropped from the player.
 */
int vgm_decoder_voice_count();

/** Whether there is a channel view worth showing at all. */
bool vgm_decoder_has_channels();
const char* vgm_decoder_voice_name(int index);

void vgm_decoder_mute(int voice, int muted);
int vgm_decoder_muted(int voice);

/** Shifts everything by [semitones] without changing the tempo. */
void vgm_decoder_set_pitch(double semitones);

/** Global tempo in percent, 10 to 200, without changing the pitch. */
void vgm_decoder_set_tempo(double percent);

/** Song time at the end of what has been rendered, in milliseconds. */
double vgm_decoder_render_position_ms();
/** A seek for the custom loop, keeping the views' history. */
void vgm_decoder_loop_seek_ms(int ms);

int vgm_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels,
                       int32_t bytesPerSample);

bool vgm_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

// JNI glue, kept in decoder-vgm-jni.cpp so the decoder itself has no opinion
// about Java.
void vgm_fillModVars(JNIEnv* env, jobject modVars);
void vgm_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean vgm_fillVisualState(JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes,
                             jint scopeWidth);
