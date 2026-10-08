#ifndef OPENMPT_JNI_AUDIO_H
#define OPENMPT_JNI_AUDIO_H

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

struct AudioStats {
  int32_t xrun_count;
  int32_t underrun_count;
  int32_t frames_per_burst;
  int32_t buffer_capacity;
  int32_t buffer_size;
  int sample_rate;
  const char* audio_api;
  const char* sharing_mode;
  const char* perf_mode;
  const char* audio_format;
};

typedef int (*AudioRenderCallback)(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

/**
 * Frames handed to the audio device since the stream started, counting the
 * silence written while stopped so this stays on the same timeline as
 * get_frames_played(). Read from inside the render callback it gives the
 * stream position of the buffer currently being filled, because the counter
 * is only advanced after the callback returns - that is what lets the
 * renderer stamp its state snapshots with the exact stream frame at which
 * each one becomes audible.
 */
int64_t get_frames_written(void);

/**
 * Frames into the current render call's buffer that the next render starts
 * at. Set by the loop in decoder.cpp around each piece it renders, because a
 * buffer can be rendered in several pieces and a decoder stamps its visuals
 * with get_frames_written() - which counts only whole callbacks. Audio
 * thread only; nothing else sees it.
 */
void set_render_frame_offset(int64_t frames);

/**
 * Gain on everything the stream plays, in decibels, -99 to +99, clipped at
 * full scale - the per-file volume boost. On top of the master volume.
 */
void set_output_boost_db(int db);

/**
 * Frames the audio device has actually played out since the stream started.
 * Always <= get_frames_written(); the difference is the output latency, i.e.
 * how far ahead of the speaker the renderer currently is. Comparing a
 * snapshot's frame stamp against this is what makes the UI show the state
 * being *heard* rather than the state already rendered into the buffer.
 */
int64_t get_frames_played(void);

int get_audio_stats(struct AudioStats* stats);

int get_effective_format_flags(void);

int open_audio(int, int, int, int, int, int);

int play_audio(void);

int restart_audio(void);

int stop_audio(void);

void close_audio(void);

int has_audio_disconnected(void); // returns 1 after ErrorDisconnected from Oboe

void clear_audio_disconnected(void);

int has_module_ended(void); // returns 1 when the render callback signals end-of-module

void reset_render_callback(void);

void set_render_callback(AudioRenderCallback callback);

void set_loop_mode(int loop_flag); // 0 = loop forever (repeat-one), 1 = play once

int get_loop_mode(void);

void set_playing(int val); // 0 = silence+sync, 1 = play; waits for in-flight callback on stop

/** Waits out a render callback in flight, for no longer than a pause does. */
void wait_for_render(void);

/** Whether the stream is playing: 1 while the render callback is being called for sound, 0 otherwise. */
int get_playing(void);

/**
 * Master volume, as a percentage, applied to the stream itself.
 *
 * Not to a decoder. It used to be libopenmpt's own master gain, which meant
 * it did nothing at all to anything libopenmpt was not playing - and the
 * ducking the audio focus does goes through here too, so a chip tune kept
 * playing at full volume over a notification.
 */
void set_output_gain(int percent);
int get_output_gain(void);

/**
 * Fades the output up from silence over the next [ms] milliseconds.
 *
 * For seeking. Every decoder here reaches a new position by putting its chips
 * or its replayer into a state that has nothing to do with the sample just
 * played, so the first sample after a seek starts wherever it starts - and a
 * step in the waveform is a click in the speaker. The bigger the step the
 * louder it is, which is why it is worst on the loud parts of a tune.
 *
 * Applied to the finished buffer, like the gain, so no decoder has to know
 * about it and none of them can forget to do it. Ten milliseconds is inaudible
 * as a fade and long enough that nothing gets through as a click.
 */
void ramp_output_in(int ms);

/** The rate the stream actually opened at, or 0 when it is not open. */
int get_stream_rate(void);

#ifdef __cplusplus
}
#endif

#endif
