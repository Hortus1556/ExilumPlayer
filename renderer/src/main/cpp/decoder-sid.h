#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>

/**
 * Commodore 64 music: .sid, in both of its forms - PSID and RSID.
 *
 * Not a data format. A SID tune is a program for the C64, and playing one
 * means running it: libsidplayfp emulates the machine (6510 CPU, CIA timers,
 * VIC interrupts) and reSIDfp the sound chip, cycle by cycle. So there is no
 * pattern to show and no instrument list, and no length either - the program
 * runs until something stops it, and the length used is the one set for
 * files that have none (see sid_decoder_set_default_length_ms).
 *
 * Each SID chip has three voices, and a tune may address up to three chips,
 * so up to nine channels. Each voice's own output is tapped from inside the
 * chip emulation - see patches/libresidfp-exilum.patch - for its trace and
 * its level.
 *
 * No ROMs: the KERNAL and BASIC images are Commodore's and cannot ship.
 * libsidplayfp plays PSID tunes without them, and most RSID tunes too.
 */

bool sid_decoder_probe(const uint8_t* data, size_t size);
bool sid_decoder_load(const uint8_t* data, size_t size, int sampleRate);
void sid_decoder_release();
bool sid_decoder_loaded();

void sid_decoder_prepare_start(int rate);
void sid_decoder_set_sample_rate(int rate);

const char* sid_decoder_title();
const char* sid_decoder_author();
/** The copyright line: year and publisher, as the file gives it. */
const char* sid_decoder_released();
/** "PSID" or "RSID", the SID model and the video standard. */
const char* sid_decoder_type();

/** The length the subsong plays for: the listener's own for it, else the default. */
int sid_decoder_duration_ms();
/** The length a subsong has before the listener sets one - the default, for every one. */
int sid_decoder_native_duration_ms();
int sid_decoder_position_ms();
void sid_decoder_seek_ms(int ms);
/** Whether a seek asked for has not landed yet: the tune is being run to it on a thread of its own. */
bool sid_decoder_seeking();
bool sid_decoder_ended();
void sid_decoder_set_loop(bool loop);
/** How long a tune plays when nothing says otherwise - the same setting as GME's. */
void sid_decoder_set_default_length_ms(int ms);

double sid_decoder_render_position_ms();
void sid_decoder_loop_seek_ms(int ms);
void sid_decoder_set_duration_override_ms(int ms);

int sid_decoder_subsong_count();
bool sid_decoder_start_subsong(int index);
int sid_decoder_current_subsong();

int sid_decoder_channels();
void sid_decoder_mute(int channel, int muted);
int sid_decoder_muted(int channel);

bool sid_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int sid_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, in decoder-sid-jni.cpp ---
void sid_fillModVars(JNIEnv* env, jobject modVars);
void sid_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean sid_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
