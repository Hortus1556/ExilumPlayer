#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>

/**
 * 2SF - Nintendo DS music: .2sf, and .mini2sf with the .2sflib it names.
 *
 * A 2SF is the game's cartridge, cut down to its sound code and data, which
 * a Nintendo DS emulator runs: vio2sf, the DeSmuME core without its screens.
 * A mini2sf is a few bytes that pick one song, patched into the cartridge of
 * the library it names - see psf.h for how that library is found. The files
 * that start from a saved state instead carry it beside the cartridge.
 *
 * The console has sixteen voices and mixes them itself: each plays a
 * recording, at eight bits, sixteen, or packed to four, and the last eight
 * can play a square wave or noise instead. Each can be muted and moved to a
 * side, inside the mix.
 *
 * The music goes round by itself; how long to play it for is written in the
 * file's tags, and a fade after that. Looping starts it again from the top.
 */

bool twosf_decoder_probe(const uint8_t* data, size_t size);
bool twosf_decoder_load(const uint8_t* data, size_t size, int sampleRate);
void twosf_decoder_release();
bool twosf_decoder_loaded();

void twosf_decoder_prepare_start(int rate);
void twosf_decoder_set_sample_rate(int rate);

const char* twosf_decoder_type();
/** The "title" tag, or "". */
const char* twosf_decoder_title();
/** The game, the artist, the year and the rest of the tags worth showing, one to a line. */
const char* twosf_decoder_comment();

int twosf_decoder_duration_ms();
bool twosf_decoder_duration_known();
int twosf_decoder_position_ms();
void twosf_decoder_seek_ms(int ms);
bool twosf_decoder_ended();
void twosf_decoder_set_loop(bool loop);
/**
 * Where the A-B loop starts, -1 for no loop: the console has to be run from
 * the top to get there, which is made ahead, off the audio thread, so the
 * jump back costs it nothing. The repeat's jump to the top is made ahead
 * too, while loop is set.
 */
void twosf_decoder_expect_loop(int startMs);

double twosf_decoder_render_position_ms();
void twosf_decoder_loop_seek_ms(int ms);
void twosf_decoder_set_duration_override_ms(int ms);

/**
 * Semitones, -12 to 12, and percent, 10 to 200. The console's clock is the
 * song's, so neither moves in it without the other; they are kept apart
 * after it - see shift-stage.h.
 */
void twosf_decoder_set_pitch(double semitones);
void twosf_decoder_set_tempo(double percent);

/** The settings' own: see Player.setTwoSfStereo, setTwoSfInterpolation, setTwoSfPan. Apply to what is playing too. */
void twosf_decoder_set_stereo(int percent);
/** How a voice's recording is brought to the output's rate, 0 to 5: see Player.TWOSF_INTERP_*. */
void twosf_decoder_set_interpolation(int mode);
/** One voice's pan from where its game puts it, -100 to 100; 0 leaves it there. */
void twosf_decoder_set_pan(int channel, int value);
/** How long to play a file whose tags give no length. */
void twosf_decoder_set_default_length_ms(int ms);

int twosf_decoder_channels();
/** The kinds of voice the console has, which the channels are labelled with as they play. */
int twosf_decoder_voice_kinds();
const char* twosf_decoder_voice_kind_name(int index);
void twosf_decoder_mute(int channel, int muted);
int twosf_decoder_muted(int channel);

bool twosf_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int twosf_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, in decoder-2sf-jni.cpp ---
void twosf_fillModVars(JNIEnv* env, jobject modVars);
void twosf_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean twosf_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
