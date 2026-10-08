#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>

/**
 * USF - Nintendo 64 music: .usf, and .miniusf with the .usflib it names.
 *
 * A USF is the game itself, cut down to its sound code and data, with a
 * saved state of the console to start it from, which a Nintendo 64 emulator
 * runs: lazyusf2, the Mupen64Plus core without its screen. A miniusf is a
 * few bytes that pick one song, patched into the state of the library it
 * names - see psf.h for how that library is found.
 *
 * The console has no sound chip. The game's own sound code runs on its
 * signal processor and mixes everything it plays into one stereo pair. That
 * code is either run as it is (LLE: exact, and slow) or done over by the
 * emulator's own version of it (HLE: several times faster, and very nearly
 * the same) - a setting.
 *
 * Done over, the mixing can be watched a voice at a time, when the sound
 * code is one whose voices can be told apart - the libultra kinds (ABI1 and
 * naudio), which most games use. Each voice is then a channel to show and to
 * mute: what it puts into the mix is what the buses hold after it less what
 * they held before, and muting it is putting back what they held. The
 * channels are the voices the song has used so far, in the order it first
 * used them, so there are more as it plays. Otherwise - run as it is, or a
 * sound code of another kind - what there is to show and to mute is the
 * stereo pair: two channels, the left and the right.
 *
 * The music goes round by itself; how long to play it for is written in the
 * file's tags, and a fade after that. Looping starts it again from the top.
 */

bool usf_decoder_probe(const uint8_t* data, size_t size);
bool usf_decoder_load(const uint8_t* data, size_t size, int sampleRate);
void usf_decoder_release();
bool usf_decoder_loaded();

void usf_decoder_prepare_start(int rate);
void usf_decoder_set_sample_rate(int rate);

const char* usf_decoder_type();
/** The "title" tag, or "". */
const char* usf_decoder_title();
/** The game, the artist, the year and the rest of the tags worth showing, one to a line. */
const char* usf_decoder_comment();

int usf_decoder_duration_ms();
bool usf_decoder_duration_known();
int usf_decoder_position_ms();
void usf_decoder_seek_ms(int ms);
bool usf_decoder_ended();
void usf_decoder_set_loop(bool loop);
/**
 * Where the A-B loop starts, -1 for no loop: the console has to be run from
 * the top to get there, which is made ahead, off the audio thread, so the
 * jump back costs it nothing. The repeat's jump to the top is made ahead
 * too, while loop is set.
 */
void usf_decoder_expect_loop(int startMs);

double usf_decoder_render_position_ms();
void usf_decoder_loop_seek_ms(int ms);
void usf_decoder_set_duration_override_ms(int ms);

/**
 * Semitones, -12 to 12, and percent, 10 to 200. The console's clock is the
 * song's, so neither moves in it without the other; they are kept apart
 * after it - see shift-stage.h.
 */
void usf_decoder_set_pitch(double semitones);
void usf_decoder_set_tempo(double percent);

/** The settings' own: see Player.setUsfStereo, setUsfHle. Apply to what is playing too. */
void usf_decoder_set_stereo(int percent);
/** Whether the sound code is done over by the emulator (HLE) rather than run as it is (LLE). */
void usf_decoder_set_hle(bool hle);
/** How long to play a file whose tags give no length. */
void usf_decoder_set_default_length_ms(int ms);

/** The voices so far, or the two sides: see above. */
int usf_decoder_channels();
/** What the channels are labelled with, by the instrument each shows: the sides' names, or a voice's. */
int usf_decoder_channel_names();
const char* usf_decoder_channel_name(int index);
/** Bumped whenever the channels change - another voice, or the sides in their place - for the app to ask again. */
int usf_decoder_channel_layout();
void usf_decoder_mute(int channel, int muted);
int usf_decoder_muted(int channel);

bool usf_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int usf_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, in decoder-usf-jni.cpp ---
void usf_fillModVars(JNIEnv* env, jobject modVars);
void usf_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean usf_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
