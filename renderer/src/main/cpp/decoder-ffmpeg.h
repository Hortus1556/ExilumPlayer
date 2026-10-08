#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

/**
 * Music that is sound and not a program: MP3, FLAC, AAC of every kind, ALAC,
 * Vorbis, Opus, Musepack, WavPack, WAV and AIFF, in the files they come in.
 * FFmpeg's demuxers and decoders read them; this plays what they give.
 *
 * Unlike every other decoder here the file is not read whole and handed
 * over: a record is tens or hundreds of megabytes where a tune is kilobytes.
 * It is read from its descriptor as it plays, by a thread of its own that
 * keeps a couple of seconds decoded ahead - so the audio callback never
 * waits for the storage, and a seek is the storage's work and not the
 * callback's. A provider that hands over a pipe, which cannot be read from
 * anywhere but where it is, is read whole after all.
 *
 * Decoded to two channels at the output's rate. The channels to show and to
 * mute are the left and the right, or the one of a mono file.
 */

/**
 * Whether [head], the first [size] bytes of a file called [name], are the
 * start of one of these - by what they say they are, never by a guess: a
 * tune no other decoder wants is not to be played as noise.
 */
bool ffmpeg_decoder_claims(const uint8_t* head, size_t size, const char* name);

/**
 * What the file behind [fd] is, and its title, read from where it lies. The
 * descriptor is the caller's still; false for one that cannot be read from
 * anywhere (a pipe) or that turns out not to hold sound this plays.
 */
bool ffmpeg_decoder_test_fd(int fd, const char* name, std::string& title, std::string& type);
bool ffmpeg_decoder_test_memory(const uint8_t* data, size_t size, const char* name, std::string& title,
                                std::string& type);

/** Plays from [fd], which stays the caller's: a copy of it is kept until release. */
bool ffmpeg_decoder_load_fd(int fd, const char* name, int sampleRate);
/** Plays from a copy of [data], for a file that could only be read whole. */
bool ffmpeg_decoder_load_memory(const uint8_t* data, size_t size, const char* name, int sampleRate);
void ffmpeg_decoder_release();
bool ffmpeg_decoder_loaded();

void ffmpeg_decoder_prepare_start(int rate);
void ffmpeg_decoder_set_sample_rate(int rate);

/** "FLAC", "MP3", "AAC", "Opus"... */
const char* ffmpeg_decoder_type();
const char* ffmpeg_decoder_title();
/** The artist, the album and the rest of the tags worth showing, then how the sound is kept, one to a line. */
const char* ffmpeg_decoder_comment();
/**
 * The words, as the file carries them: lines with their times in front
 * ("[01:23.45]") where they are timed, plain lines where they are not. Empty
 * for a file with none.
 */
std::string ffmpeg_decoder_lyrics();

int ffmpeg_decoder_duration_ms();
bool ffmpeg_decoder_duration_known();
int ffmpeg_decoder_position_ms();
void ffmpeg_decoder_seek_ms(int ms);
bool ffmpeg_decoder_ended();
void ffmpeg_decoder_set_loop(bool loop);

double ffmpeg_decoder_render_position_ms();
void ffmpeg_decoder_loop_seek_ms(int ms);
void ffmpeg_decoder_set_duration_override_ms(int ms);

/** Semitones, -12 to 12, and percent, 10 to 200, kept apart as for the consoles - see shift-stage.h. */
void ffmpeg_decoder_set_pitch(double semitones);
void ffmpeg_decoder_set_tempo(double percent);

/** The settings' own: see Player.setAudioStereo. Applies to what is playing too. */
void ffmpeg_decoder_set_stereo(int percent);

int ffmpeg_decoder_channels();
const char* ffmpeg_decoder_channel_name(int index);
void ffmpeg_decoder_mute(int channel, int muted);
int ffmpeg_decoder_muted(int channel);

bool ffmpeg_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

int ffmpeg_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, in decoder-ffmpeg-jni.cpp ---
void ffmpeg_fillModVars(JNIEnv* env, jobject modVars);
void ffmpeg_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean ffmpeg_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
