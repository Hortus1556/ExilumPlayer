#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>

/**
 * Nintendo GameCube streams: the recorded music the console's games play,
 * in whichever of their containers - Nintendo's DSP ADPCM with a standard
 * header or a game's own, HAL's HPS, CRI's ADX, Nintendo's AFC and AST, the
 * DVD drive's DTK streams, Hudson's PDT splits. What each is and how it is
 * laid out is gc-stream.h's; this plays it.
 *
 * Decoded at the stream's rate and brought to the output's with a windowed
 * sinc (see sinc-resampler.h). A song plays once through, and one that
 * loops goes round into its loop for a moment while it fades; with repeat
 * on it goes round for ever. Its channels are the ones to show and to mute:
 * one, the left and the right, or pairs of them mixed together.
 *
 * Some streams are told by their name - the ones with no header - and some
 * come in two files, the left and the right, found among the companion files.
 */

/** What the file is - "ADX", "HPS"... - when it is one of these; null otherwise. Name and all, as for load. */
const char* dsp_decoder_probe(const uint8_t* data, size_t size, const char* name);
bool dsp_decoder_load(const uint8_t* data, size_t size, const char* name, int sampleRate);
void dsp_decoder_release();
bool dsp_decoder_loaded();

void dsp_decoder_prepare_start(int rate);
void dsp_decoder_set_sample_rate(int rate);

const char* dsp_decoder_type();
/** No title: the file's name is its only one. */
const char* dsp_decoder_title();
/** The machine, the format and how the stream is laid out, one to a line. */
const char* dsp_decoder_comment();

int dsp_decoder_duration_ms();
bool dsp_decoder_duration_known();
int dsp_decoder_position_ms();
void dsp_decoder_seek_ms(int ms);
bool dsp_decoder_ended();
void dsp_decoder_set_loop(bool loop);

double dsp_decoder_render_position_ms();
void dsp_decoder_loop_seek_ms(int ms);
void dsp_decoder_set_duration_override_ms(int ms);

/** Semitones, -12 to 12, and percent, 10 to 200, kept apart as for the consoles - see shift-stage.h. */
void dsp_decoder_set_pitch(double semitones);
void dsp_decoder_set_tempo(double percent);

/** The settings' own: see Player.setDspStereo. Applies to what is playing too. */
void dsp_decoder_set_stereo(int percent);

int dsp_decoder_channels();
const char* dsp_decoder_channel_name(int index);
void dsp_decoder_mute(int channel, int muted);
int dsp_decoder_muted(int channel);

bool dsp_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int dsp_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, in decoder-dsp-jni.cpp ---
void dsp_fillModVars(JNIEnv* env, jobject modVars);
void dsp_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean dsp_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
