#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <string>

/**
 * Game Music Emu: NSF, NSFE, SPC, GBS, HES, KSS, SAP, AY, VGM, VGZ, GYM.
 *
 * Not trackers. Each of these is either code for a sound chip's host CPU,
 * emulated instruction by instruction, or a log of writes to that chip's
 * registers replayed against an emulation of it. There is no pattern to show
 * and usually no sample to draw - what there is, is voices, which is what the
 * channel view gets.
 */

/**
 * Re-opens at [rate] if that is not what is already playing.
 *
 * GME fixes the sample rate when the emulator is created, but the output
 * stream is opened after the file is loaded, so the rate is not known yet at
 * that point. The file bytes are kept for exactly this: re-opening is cheap
 * and happens before anything has been heard.
 */
void gme_decoder_set_sample_rate(int rate);

/**
 * Readies this decoder to be the render source, at [rate].
 *
 * The counterpart of openmpt_prepareStart: starting the stream restarts the
 * device's frame counters, so any frame stamp already published describes a
 * timeline that no longer exists.
 */
void gme_decoder_prepare_start(int rate);

/**
 * Whether GME recognises these bytes, without loading anything.
 *
 * Returns the format name it saw ("NSF", "SPC"...) or an empty string. Used
 * by indexing, which runs over every file in a folder while something else is
 * playing and must not disturb it.
 */
const char* gme_decoder_probe(const uint8_t* data, size_t size);

/** True if GME recognises the bytes and takes ownership of playback. */
bool gme_decoder_load(const uint8_t* data, size_t size);

/** Releases whatever is loaded; safe to call when nothing is. */
void gme_decoder_release();

bool gme_decoder_loaded();

/** Track title and system name, for ModInfo. */
const char* gme_decoder_title();
const char* gme_decoder_system();

int gme_decoder_duration_ms();

/**
 * Tracks in the file.
 *
 * These formats routinely hold a whole game's soundtrack in one file - an NSF
 * is a ROM with an entry point per tune - so this is usually well above one,
 * and maps onto what the player already calls subsongs.
 */
int gme_decoder_track_count();
int gme_decoder_current_track();
int gme_decoder_track_duration_ms(int track);
std::string gme_decoder_track_name(int track);
bool gme_decoder_start_track(int track);
int gme_decoder_position_ms();
void gme_decoder_seek_ms(int ms);
bool gme_decoder_ended();
void gme_decoder_set_loop(bool loop);

/**
 * How long to play a track whose file does not say, in milliseconds.
 *
 * A plain NSF or a GBS is a ROM and an entry point: the console would go round
 * for ever and nothing in the file suggests when to stop. Applied to whatever
 * is playing as well as to what is loaded next, since the reason to change it
 * is a tune repeating itself right now.
 */
void gme_decoder_set_default_length_ms(int ms);

/**
 * Master volume, stereo width and per-voice panning, per console.
 *
 * Kept apart per machine because they are different machines: an NES mixes to
 * one output and has no stereo of its own to widen, a Game Boy pans its four
 * voices in hardware, and the SNES does both in the DSP. console is one of
 * Player.kt's GmeConsole values.
 */
void gme_decoder_set_console_volume(int console, int percent);
void gme_decoder_set_console_stereo(int console, int percent);
void gme_decoder_set_console_pan(int console, int voice, int pan);

/**
 * The SNES DSP's echo, and how its voices are read.
 *
 * Both live inside the chip rather than in the mix: the echo is a delay line
 * the tune writes into and reads back, and the interpolation decides what a
 * voice's samples are between the points the sample actually has. Mode is 0
 * none, 1 the SNES's own gaussian table, 2 windowed sinc.
 */
void gme_decoder_set_snes_echo(bool enabled);
void gme_decoder_set_snes_interpolation(int mode);

/**
 * Shifts everything by [semitones] without changing the tempo.
 *
 * The emulator is told how fast its machine runs, which moves pitch and tempo
 * together, and handed a tempo divided by the same amount to take the second
 * half back out - see gme_set_pitch.
 */
void gme_decoder_set_pitch(double semitones);

/** Global tempo in percent, 10 to 200, without changing the pitch. */
void gme_decoder_set_tempo(double percent);

/** Song time at the end of what has been rendered, in milliseconds. */
double gme_decoder_render_position_ms();
/** A seek for the custom loop: no fade in, and the traces kept. */
void gme_decoder_loop_seek_ms(int ms);
/** The track's length as the listener set it; 0 or less goes back to the file's or the default. */
void gme_decoder_set_duration_override_ms(int ms);
/** Whether the track's length came out of the file. */
bool gme_decoder_duration_known();

/** Voices this chip has, and their names - the channel view's rows. */
int gme_decoder_voice_count();
const char* gme_decoder_voice_name(int index);
void gme_decoder_mute(int voice, int muted);
int gme_decoder_muted(int voice);

/**
 * Fills the shared visual buffers - see visual-state.h.
 *
 * Scope traces come from the per-voice audio this decoder renders, when the
 * emulator supports splitting it. Where it does not (SPC), the voices are
 * still listed and still mute, they just have no trace.
 */
/** False when nothing has been rendered yet, which is what `valid` reports. */
bool gme_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);

/** Registered with the audio output while GME owns playback. */
int gme_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

// --- JNI glue, implemented in decoder-gme-jni.cpp ---
void gme_fillModVars(JNIEnv* env, jobject modVars);
void gme_fillFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean gme_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
