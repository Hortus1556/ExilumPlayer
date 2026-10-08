#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>

/**
 * GSF - Game Boy Advance music: .gsf, and .minigsf with the .gsflib it names.
 *
 * A GSF is the game itself, cut down to its sound code and data, which a
 * Game Boy Advance emulator runs: mGBA's, without its screen. A minigsf is a
 * few bytes that pick one song, patched into the program of the library it
 * names - see psf.h for how that library is found.
 *
 * The console has six voices, all of them mixed inside it: the four of the
 * Game Boy's sound chip, and the two Direct Sound channels most games play
 * all their music through, mixed in software by the game. Each can be muted,
 * which mGBA does inside the mix.
 *
 * The music goes round by itself; how long to play it for is written in the
 * file's tags, and a fade after that. Looping starts it again from the top.
 */

bool gsf_decoder_probe(const uint8_t* data, size_t size);
bool gsf_decoder_load(const uint8_t* data, size_t size, int sampleRate);
void gsf_decoder_release();
bool gsf_decoder_loaded();

void gsf_decoder_prepare_start(int rate);
void gsf_decoder_set_sample_rate(int rate);

const char* gsf_decoder_type();
/** The "title" tag, or "". */
const char* gsf_decoder_title();
/** The game, the artist, the year and the rest of the tags worth showing, one to a line. */
const char* gsf_decoder_comment();

int gsf_decoder_duration_ms();
bool gsf_decoder_duration_known();
int gsf_decoder_position_ms();
void gsf_decoder_seek_ms(int ms);
bool gsf_decoder_ended();
void gsf_decoder_set_loop(bool loop);

double gsf_decoder_render_position_ms();
void gsf_decoder_loop_seek_ms(int ms);
void gsf_decoder_set_duration_override_ms(int ms);

/**
 * Semitones, -12 to 12, and percent, 10 to 200. The console's clock is the
 * song's, so neither moves in it without the other; they are kept apart
 * after it - see shift-stage.h.
 */
void gsf_decoder_set_pitch(double semitones);
void gsf_decoder_set_tempo(double percent);

/** The settings' own: see Player.setGsfStereo, setGsfLowpass, setGsfPan. Apply to what is playing too. */
void gsf_decoder_set_stereo(int percent);
void gsf_decoder_set_lowpass(bool on);
/** One channel's pan from where its game puts it, -100 to 100; 0 leaves it there. */
void gsf_decoder_set_pan(int channel, int value);
/** How long to play a file whose tags give no length. */
void gsf_decoder_set_default_length_ms(int ms);

int gsf_decoder_channels();
const char* gsf_decoder_channel_name(int index);
void gsf_decoder_mute(int channel, int muted);
int gsf_decoder_muted(int channel);

bool gsf_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int gsf_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, in decoder-gsf-jni.cpp ---
void gsf_fillModVars(JNIEnv* env, jobject modVars);
void gsf_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean gsf_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
