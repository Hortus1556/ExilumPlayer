#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>

/**
 * PSF - PlayStation music: .psf, and .minipsf with the .psflib it names.
 *
 * A PSF is the game's program, cut down to its sound code and data, which a
 * PlayStation emulator runs: aopsf, Audio Overload's console as Christopher
 * Snowhill keeps it - the BIOS done over by the emulator, so that no file of
 * Sony's is needed - with a processor of this player's own in it (see
 * aopsf/ANDROID-CHANGES.md). A minipsf is a few bytes that pick one song,
 * laid over the program of the library it names - see psf.h for how that
 * library is found.
 *
 * The console's sound unit has twenty-four voices and mixes them itself,
 * each playing a recording packed to four bits, or noise, through an
 * envelope, and a reverb over those sent to it. Each voice is a channel: its
 * own sound is taken out of the mix as it is made, and a muted voice is left
 * out of it and runs on, so unmuting and seeking stay exact.
 *
 * The sound unit makes its sound at 44100 Hz, which is brought to the
 * output's rate by a windowed sinc (sinc-resampler.h).
 *
 * The music goes round by itself; how long to play it for is written in the
 * file's tags, and a fade after that. Looping starts it again from the top.
 */

bool psf1_decoder_probe(const uint8_t* data, size_t size);
bool psf1_decoder_load(const uint8_t* data, size_t size, int sampleRate);
void psf1_decoder_release();
bool psf1_decoder_loaded();

void psf1_decoder_prepare_start(int rate);
void psf1_decoder_set_sample_rate(int rate);

const char* psf1_decoder_type();
/** The "title" tag, or "". */
const char* psf1_decoder_title();
/** The game, the artist, the year and the rest of the tags worth showing, one to a line. */
const char* psf1_decoder_comment();

int psf1_decoder_duration_ms();
bool psf1_decoder_duration_known();
int psf1_decoder_position_ms();
void psf1_decoder_seek_ms(int ms);
bool psf1_decoder_ended();
void psf1_decoder_set_loop(bool loop);
/**
 * Where the A-B loop starts, -1 for no loop: the console has to be run from
 * the top to get there, which is made ahead, off the audio thread, so the
 * jump back costs it nothing. The repeat's jump to the top is made ahead
 * too, while loop is set.
 */
void psf1_decoder_expect_loop(int startMs);

double psf1_decoder_render_position_ms();
void psf1_decoder_loop_seek_ms(int ms);
void psf1_decoder_set_duration_override_ms(int ms);

/**
 * Semitones, -12 to 12, and percent, 10 to 200. The console's clock is the
 * song's, so neither moves in it without the other; they are kept apart
 * after it - see shift-stage.h.
 */
void psf1_decoder_set_pitch(double semitones);
void psf1_decoder_set_tempo(double percent);

/** The settings' own: see Player.setPsfStereo, setPsfReverb. Apply to what is playing too. */
void psf1_decoder_set_stereo(int percent);
/** Whether the sound unit's reverb is heard. It runs either way, so it is the same reverb when it is heard again. */
void psf1_decoder_set_reverb(bool heard);
/** How long to play a file whose tags give no length. */
void psf1_decoder_set_default_length_ms(int ms);

int psf1_decoder_channels();
/** What a voice can be playing, which the channels are labelled with as they play. */
int psf1_decoder_voice_kinds();
const char* psf1_decoder_voice_kind_name(int index);
void psf1_decoder_mute(int channel, int muted);
int psf1_decoder_muted(int channel);

bool psf1_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int psf1_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, in decoder-psf-jni.cpp ---
void psf1_fillModVars(JNIEnv* env, jobject modVars);
void psf1_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean psf1_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
