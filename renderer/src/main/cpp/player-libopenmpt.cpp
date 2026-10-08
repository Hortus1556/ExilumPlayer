#include "audio.h"
#include "libopenmpt/libopenmpt.h"
#include "libopenmpt/libopenmpt_ext.h"
#include "player-common.h"
#include "visual-state.h"
#include "player.h"
#include <algorithm>
#include <android/log.h>
#include <array>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

constexpr const char* TAG = "Exilum (libopenmpt) JNI";
#define LOG_DEBUG(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

constexpr int MAX_BUFFER_SIZE = 1024;
constexpr int OPENMPT_SAMPLE_16BIT = 0x01;
constexpr int OPENMPT_SAMPLE_LOOP = 0x02;
constexpr int OPENMPT_SAMPLE_PINGPONG = 0x04;
constexpr int OPENMPT_SAMPLE_STEREO = 0x40;

// Layout of the flat arrays openmpt_readVisualState fills. Mirrored exactly by
// PlaybackSnapshot.kt - flat int arrays rather than a JNI object fill because
// this runs once per display frame and SetIntArrayRegion is one bulk copy,
// where setting ~20 fields individually is ~20 JNI round trips.


/** Full scale for kChPeak, so the level survives the trip as an integer. */

// Player.kt setPlayer/getPlayer parameter ids
constexpr int PARAM_MASTER_VOLUME = 1;      // OPENMPT_MODULE_RENDER_MASTERGAIN_MILLIBEL, value 0-200 (100 = 0dB)
constexpr int PARAM_STEREO_SEPARATION = 2;  // OPENMPT_MODULE_RENDER_STEREOSEPARATION_PERCENT, value 0-200 (100 = normal)
constexpr int PARAM_DITHER = 3;             // dither ctl (the only one of these that is a real ctl key), value 0-3
constexpr int PARAM_VOLUME_RAMPING = 4;     // OPENMPT_MODULE_RENDER_VOLUMERAMPING_STRENGTH, value -1..10 (-1 = recommended default)

struct OpenMptState {
  std::mutex mutex;
  bool initialized = false;
  bool mod_is_loaded = false;
  bool playing = false;
  bool loop = false;
  /**
   * Whether playback has already gone round once. libopenmpt's position
   * counter only ever adds, so past the first pass it reads beyond the
   * song's length and has to be wrapped back into it - and it has to go on
   * being wrapped after repeat is switched off, or the bar jumps to the end
   * and the elapsed time climbs on from there while the song plays out its
   * last pass. Cleared wherever playback starts from a known point.
   */
  bool wrapped = false;
  int actual_rate = 44100;
  int selected_subsong = 0;
  /** The channels the module itself mutes, bit n for channel n: see openmpt_resetMutes. */
  uint64_t file_muted = 0;
  double duration = 0.0;
  /** How long each subsong is, in seconds, where the module has more than one: for the list of them. */
  std::vector<double> subsong_seconds;
  // Playback time of the point the song returns to when it ends and repeats.
  // 0 for the great majority of modules, which restart from the beginning;
  // XM in particular can name a later order. See computeLoopStartSeconds.
  double loop_start_seconds = 0.0;
  openmpt_module* mod = nullptr;
  // The same module through the extended API, which is where pitch lives.
  // mod is obtained from this and must not be destroyed separately.
  openmpt_module_ext* mod_ext = nullptr;
  openmpt_module_ext_interface_interactive interactive{};
  bool has_interactive = false;
  // Whether this module's global volume scales the mix rather than each
  // channel - fixed by its mix levels, so read once at load. See publishSnapshot.
  bool global_volume_to_master = true;
  // Kept here rather than only in the UI: a pitch belongs to playback, not to
  // one module, so it has to be re-applied to whatever loads next.
  double pitch_semitones = 0.0;
  /**
   * Global tempo, as a factor, and the song time it is measured against.
   *
   * libopenmpt counts its position in the time actually rendered, and at any
   * tempo but one that is not the song's own time - the total, the loop
   * point and the seek bar are all in that. So the song position is kept as
   * the point it stood at when the tempo last changed or playback last
   * jumped, plus what has been rendered since, scaled. See songSeconds.
   */
  double tempo_factor = 1.0;
  double anchor_song_seconds = 0.0;
  double anchor_render_seconds = 0.0;
  std::array<int, MAX_CHANNELS> current_sample{};
  // used to derive pitch bend: period when the note last triggered
  std::array<int, MAX_CHANNELS> base_periods{};
  std::array<int, MAX_CHANNELS> prev_keys{};

  static OpenMptState& instance() {
    static OpenMptState s;
    return s;
  }
};

// See OpenMptState::tempo_factor; defined with applyTempo.
double songSeconds(OpenMptState& state);
void anchorSongTime(OpenMptState& state, double songAt);

// ---------------------------------------------------------------------------
// Playback state snapshots
//
// Every tracker that displays a correct, non-jittery playback position does
// the same thing, and this is it: the audio thread stamps a copy of the
// player's state with the stream frame position at which that state becomes
// audible, drops it in a lock-free ring, and the UI - whenever it happens to
// draw - picks the newest entry the speaker has actually reached. OpenMPT
// calls the entry a Notification and stamps it with `timestampSamples`,
// popping in OnTimerNotify while `currenttotalsamples >= timestampSamples`;
// Fasttracker II's clone calls it pattSyncData_t/chSyncData_t on a
// SYNC_QUEUE_LEN ring, popping while `frameTime64 >= getPattQueueTimestamp()`.
// Same structure, same reason.
//
// Two properties matter, and the old design here had neither:
//
// 1. The stamp is arithmetic on the audio timeline, never a clock reading.
//    ft2-clone is emphatic about this - it samples the wall clock exactly
//    once at (re)start and thereafter only *adds* each tick's duration,
//    because an audio callback arrives in jittery bursts and reading a clock
//    inside it injects that jitter straight into the display. Counting output
//    frames sidesteps the problem entirely: frames are what the hardware
//    consumes at a constant rate, so `framesWritten + rendered` is exact by
//    construction with no clock involved at all.
//
// 2. It is compared against what has actually been *heard*, not what has been
//    rendered. The renderer necessarily runs ahead of the speaker by the
//    output buffer's depth; showing the state it just produced means showing
//    the future. ft2-clone can only estimate that offset as one buffer
//    period; Oboe reports it exactly (get_frames_played), so this doesn't
//    have to estimate anything.
//
// The ring is single-producer (audio thread) / single-consumer (UI), so it
// needs no lock - which is the point. The UI reading playback state must
// never block on the audio thread, and previously every such read did:
// renderOpenMpt held the state mutex for a whole callback while getFrameInfo,
// getChannelData and getSampleData all waited on it.
// ---------------------------------------------------------------------------

struct ChannelSnapshot {
  int32_t sample;        // libopenmpt sample index (1-based); 0 = nothing playing
  int32_t instrument;
  int32_t note;
  int32_t key;
  int32_t volume;
  int32_t finalVolume;
  int32_t pan;
  int32_t period;
  int32_t pitchBend;
  // Everything a scope needs to redraw this channel's trace, in libopenmpt's
  // own units: a 32.32 fixed-point position within the sample and the signed
  // 32.32 step it advances per output frame. Walking the instrument's sample
  // data from these is how real trackers draw scopes (ft2-clone's scope_t
  // carries exactly this pair as `position`/`positionFrac` and `delta`); the
  // mixed output buffer is never the source.
  int64_t position;
  int64_t increment;
  // The OPL voice the channel plays on, counted from 1; 0 when it plays a
  // sample or nothing. A voice of the chip has no sample data to walk: its
  // trace is what the chip made of it - see renderScopeTraces.
  int32_t oplVoice;
  // How loud the channel came out over this snapshot's sub-chunk, measured by
  // the mixer (output_level), in kPeakScale units - and the loudest of that
  // over the last kLevelWindowMs, which is what the views are given. See
  // publishSnapshot.
  int32_t measuredLevel;
  int32_t level;
};

/**
 * How far back a channel's level looks.
 *
 * A snapshot covers one ~5 ms sub-chunk, less than one cycle of a low note -
 * a 40 Hz bass takes 25 ms - so its peak alone would sink between the crests
 * of the wave and the bar would flicker with the note's pitch rather than
 * follow its volume. The same span the tracker's own meters look over.
 */
constexpr int kLevelWindowMs = 30;

struct PlaybackSnapshot {
  // Stream frame position at which this state becomes audible.
  uint64_t frameStamp;
  int32_t order;
  int32_t pattern;
  int32_t row;
  int32_t numRows;
  int32_t speed;
  int32_t bpm;
  int32_t timeMs;
  int32_t playingChannels;
  int32_t numChannels;
  int32_t sequence;
  int32_t globalVolume;  // kGlobalVolumeUnity for full
  // Frames the module's OPL chip had mixed when this was taken: where its
  // voices' traces for this moment are. 0 for a module without one.
  uint64_t oplFrames;
  ChannelSnapshot ch[MAX_CHANNELS];
};

// Power of two so the index wraps with a mask. Sized to comfortably outlast
// the deepest output latency this can face: snapshots are published per
// render sub-chunk (~5ms), so 256 entries is ~1.2s of history against a
// buffer that is at most a few hundred ms deep. Deliberately generous -
// ft2-clone sizes its own queues far past need for the same reason, because
// wrapping past an entry the consumer is still reading is the one genuinely
// unsafe event here.
constexpr int kSnapshotRingSize = 256;
constexpr int kSnapshotRingMask = kSnapshotRingSize - 1;

static PlaybackSnapshot g_snapshotRing[kSnapshotRingSize];
static std::atomic<uint64_t> g_snapshotWrite{0};

// Called on the audio thread, holding state.mutex, right after a sub-chunk of
// [frameStamp] total frames has been rendered.
static void publishSnapshot(OpenMptState& state, uint64_t frameStamp) {
  const uint64_t seq = g_snapshotWrite.load(std::memory_order_relaxed);
  PlaybackSnapshot& s = g_snapshotRing[seq & kSnapshotRingMask];

  s.frameStamp = frameStamp;
  const int pattern = openmpt_module_get_current_pattern(state.mod);
  s.order = openmpt_module_get_current_order(state.mod);
  s.pattern = pattern;
  s.row = openmpt_module_get_current_row(state.mod);
  s.numRows = openmpt_module_get_pattern_num_rows(state.mod, pattern);
  s.speed = openmpt_module_get_current_speed(state.mod);
  s.bpm = static_cast<int32_t>(std::lround(openmpt_module_get_current_tempo2(state.mod)));

  // Wrapped back into the song's own length while repeating. libopenmpt keeps
  // accumulating past the end rather than rewinding when it repeats (see
  // computeLoopStartSeconds), so without this the progress bar sticks at the
  // end and the elapsed time climbs forever. Only while looping: if the
  // duration estimate were ever a shade short, wrapping during ordinary
  // playback would snap the bar back to zero just before the track ends.
  double positionSeconds = songSeconds(state);
  if (positionSeconds >= state.duration && (state.loop || state.wrapped)) {
    const double loopLength = state.duration - state.loop_start_seconds;
    if (loopLength > 0.0) {
      // Once it has gone round, it stays wrapped: switching repeat off in the
      // middle of a later pass leaves the song playing that pass out, and the
      // bar has to go on showing where in it playback is.
      state.wrapped = true;
      positionSeconds = state.loop_start_seconds +
        std::fmod(positionSeconds - state.duration, loopLength);
    }
  }
  s.timeMs = static_cast<int32_t>(std::lround(positionSeconds * 1000.0));
  s.playingChannels = openmpt_module_get_current_playing_channels(state.mod);
  s.sequence = state.selected_subsong;

  // The song's master volume as it stands now, Vxx slides included.
  //
  // With the mix levels nearly every module loads with, libopenmpt applies it
  // to the finished mix, and a channel's volume and VU leave it out - so the
  // level bars stayed full through a global fade-out, and the traces with
  // them. Folded into each channel's final volume here when that is the case;
  // with the old ModPlug mix levels it is in there already. Published either
  // way, because the traces are fitted to their box and need it on its own.
  const double globalVolume =
      (state.has_interactive && state.interactive.get_global_volume)
          ? std::clamp(state.interactive.get_global_volume(state.mod_ext), 0.0, 1.0)
          : 1.0;
  s.globalVolume = static_cast<int32_t>(std::lround(globalVolume * kGlobalVolumeUnity));

  s.oplFrames = openmpt_module_get_opl_frames(state.mod);

  const int channels = std::min<int>(MAX_CHANNELS, openmpt_module_get_num_channels(state.mod));
  s.numChannels = channels;
  for (int i = 0; i < channels; i++) {
    ChannelSnapshot& c = s.ch[i];
    openmpt_module_current_channel_state chs{};
    if (openmpt_module_get_current_channel_state(state.mod, i, &chs) == 0) {
      c = ChannelSnapshot{};
      continue;
    }
    c.sample = chs.sample;
    c.instrument = chs.instrument;
    c.note = chs.note;
    c.key = chs.key;
    c.volume = chs.volume;
    // The volume the channel is mixed at: its volume, envelopes, fade-out and
    // global volumes, as 0..64. This was libopenmpt's VU (final_volume), which
    // is not a volume at all: it jumps to full on every new note and sinks to
    // half over the next ticks, and it reads louder for a channel panned to
    // one side - so the backdrop pulsed with every note and a steady channel
    // drew at half its volume.
    const double mixGlobal = state.global_volume_to_master ? globalVolume : 1.0;
    c.finalVolume = static_cast<int32_t>(std::lround(chs.real_volume * 64.0 / 16384.0 * mixGlobal));

    // What really came out, measured by the mixer as it added the channel to
    // the output - resampling, filters, loops, sample ends and background
    // (NNA) voices all included - on the same scale: a full-scale sample at
    // full volume is kPeakScale. The trace below is a picture of the sample
    // data; this is the sound. See ModChannel::exilumOutputLevel in the
    // libopenmpt patch.
    c.measuredLevel = static_cast<int32_t>(std::clamp<long>(
        std::lround(chs.output_level / 16384.0 * mixGlobal * kPeakScale), 0L, static_cast<long>(kPeakScale)));
    c.level = c.measuredLevel;
    c.pan = chs.pan;
    c.period = chs.period;
    // chs.pitchbend is MIDI-only (always 8192 for MOD/XM/S3M/IT), so derive
    // the bend from how far the period has moved since the note triggered.
    // Period is inverse to pitch, hence the subtraction order.
    if (chs.key != state.prev_keys[i]) {
      state.base_periods[i] = chs.period;
      state.prev_keys[i] = chs.key;
    }
    c.pitchBend = (state.base_periods[i] > 0 && chs.period > 0)
      ? state.base_periods[i] - chs.period
      : 0;
    c.position = static_cast<int64_t>(chs.position) << 32;
    c.increment = chs.increment;
    c.oplVoice = chs.opl_voice + 1;
    state.current_sample[i] = chs.sample;
  }

  // Each channel's level held over the last kLevelWindowMs of snapshots - see
  // kLevelWindowMs. Only this thread writes the ring, so the entries before
  // this one are safe to read here; a stamp not strictly before this one's
  // belongs to a timeline since restarted and ends the look back.
  const uint64_t holdFrames = static_cast<uint64_t>(state.actual_rate) * kLevelWindowMs / 1000;
  for (uint64_t back = 1; back <= seq && back < kSnapshotRingSize; back++) {
    const PlaybackSnapshot& prev = g_snapshotRing[(seq - back) & kSnapshotRingMask];
    if (prev.frameStamp >= frameStamp || frameStamp - prev.frameStamp > holdFrames) break;
    const int prevChannels = std::min(channels, prev.numChannels);
    for (int i = 0; i < prevChannels; i++) {
      s.ch[i].level = std::max(s.ch[i].level, prev.ch[i].measuredLevel);
    }
  }

  // Release: everything written above must be visible to a consumer before
  // the index that publishes it is.
  g_snapshotWrite.store(seq + 1, std::memory_order_release);
}

/**
 * Copies out the newest snapshot the device has actually played up to.
 * Lock-free: callable from the UI thread every display frame without ever
 * waiting on the audio thread.
 *
 * If playback is still ahead of every entry (right after starting, before the
 * first buffer has been consumed), the oldest available entry is used rather
 * than failing - the display shows the start of the song instead of nothing.
 */
static bool pickSnapshot(int64_t framesPlayed, PlaybackSnapshot& out) {
  const uint64_t writeSeq = g_snapshotWrite.load(std::memory_order_acquire);
  if (writeSeq == 0) return false;

  // One slot of headroom, deliberately. The producer is filling the entry at
  // writeSeq right now, and once the ring has wrapped that slot is also the
  // one at writeSeq - kSnapshotRingSize - so treating the full ring as
  // readable lets the oldest entry be read field by field while it is being
  // overwritten, mixing two different moments into one snapshot.
  const uint64_t count = std::min<uint64_t>(writeSeq, kSnapshotRingSize - 1);
  const uint64_t oldest = writeSeq - count;
  const uint64_t played = framesPlayed < 0 ? 0 : static_cast<uint64_t>(framesPlayed);

  uint64_t chosen = oldest;
  for (uint64_t seq = writeSeq; seq > oldest;) {
    --seq;
    if (g_snapshotRing[seq & kSnapshotRingMask].frameStamp <= played) {
      chosen = seq;
      break;
    }
  }

  out = g_snapshotRing[chosen & kSnapshotRingMask];

  // The producer may have lapped the entry mid-copy. At ~200 writes/sec
  // against a 256-entry ring that needs a >1s stall, but a torn snapshot
  // would show as a visibly wrong frame, so verify rather than assume.
  const uint64_t writeNow = g_snapshotWrite.load(std::memory_order_acquire);
  return (writeNow - chosen) < kSnapshotRingSize;
}

/** Drops all history - after a seek, a module change or a stream restart, no
 *  previously stamped frame position describes the new timeline. */
static void resetSnapshots() {
  g_snapshotWrite.store(0, std::memory_order_release);
}

/**
 * Playback time of the order/row the song returns to when it ends and
 * repeats, so a looping module's position can be wrapped back into range.
 *
 * libopenmpt's own position counter is a plain accumulator - it only ever
 * adds the frames it rendered (m_currentPositionSeconds += count / rate) and
 * is never rewound when repeat_count sends playback back to the restart
 * point. Left alone it therefore runs past the song's duration forever, which
 * is why a repeating track showed a progress bar pinned at the end and an
 * elapsed time that kept climbing.
 *
 * Determining this costs a seek, and a seek runs a full playback simulation
 * (module_impl::set_position_* calls GetLength), so it must never happen on
 * the audio thread. Done once here instead, while the module is loaded and
 * nothing is playing from it yet, leaving only arithmetic at display time.
 * The module is left rewound to the start.
 */
static double computeLoopStartSeconds(openmpt_module* mod, int subsong) {
  const int32_t order = openmpt_module_get_restart_order(mod, subsong);
  const int32_t row = openmpt_module_get_restart_row(mod, subsong);
  // -1 means the subsong index was out of range; 0/0 is the common case of
  // restarting from the very beginning, which needs no seek to establish.
  if (order <= 0 && row <= 0) return 0.0;
  if (order < 0) return 0.0;

  const double seconds =
    openmpt_module_set_position_order_row(mod, order, row < 0 ? 0 : row);
  openmpt_module_set_position_seconds(mod, 0.0);
  return seconds > 0.0 ? seconds : 0.0;
}

std::string openmptString(const char* value) {
  if (!value) return "";
  std::string result(value);
  openmpt_free_string(value);
  return result;
}

std::string metadata(openmpt_module* mod, const char* key) {
  return openmptString(openmpt_module_get_metadata(mod, key));
}

// Renders [want] frames starting at frame offset [frameOffset] within
// [audioData], converting from libopenmpt's native interleaved-stereo-int16
// output to whatever format/channel-count the stream actually negotiated.
// Identical conversion logic to what a single whole-buffer render used, just
// parameterized so renderOpenMpt can call it repeatedly at different offsets
// instead of once for the whole callback - see renderOpenMpt's doc for why.
static size_t renderChunk(OpenMptState& state, void* audioData, int32_t frameOffset,
    int32_t want, int32_t numChannels, int32_t bytesPerSample) {
  if (bytesPerSample == 2 && numChannels == 2) {
    auto* out = static_cast<int16_t*>(audioData) + static_cast<size_t>(frameOffset) * 2;
    return openmpt_module_read_interleaved_stereo(state.mod, state.actual_rate, want, out);
  }
  std::vector<int16_t> stereo(static_cast<size_t>(want) * 2);
  size_t frames = openmpt_module_read_interleaved_stereo(state.mod, state.actual_rate, want, stereo.data());
  if (bytesPerSample == 2 && numChannels == 1) {
    auto* out = static_cast<int16_t*>(audioData) + frameOffset;
    for (size_t i = 0; i < frames; i++) out[i] = static_cast<int16_t>((stereo[i * 2] + stereo[i * 2 + 1]) / 2);
  } else if (bytesPerSample == 4) {
    auto* out = static_cast<int32_t*>(audioData) + static_cast<size_t>(frameOffset) * numChannels;
    for (size_t i = 0; i < frames; i++) {
      int32_t left = stereo[i * 2] << 16;
      int32_t right = stereo[i * 2 + 1] << 16;
      if (numChannels == 1) {
        out[i] = (left + right) / 2;
      } else {
        out[i * 2] = left;
        out[i * 2 + 1] = right;
      }
    }
  }
  return frames;
}

/** Silences frames [from, numFrames) of the output buffer. */
static void silenceTail(void* audioData, int32_t from, int32_t numFrames,
    int32_t numChannels, int32_t bytesPerSample) {
  if (from >= numFrames) return;
  const size_t offset = static_cast<size_t>(from) * numChannels * bytesPerSample;
  const size_t bytes = static_cast<size_t>(numFrames - from) * numChannels * bytesPerSample;
  memset(static_cast<char*>(audioData) + offset, 0, bytes);
}

int renderOpenMpt(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  OpenMptState& state = OpenMptState::instance();

  // Stream frame position of this buffer's first frame. Valid to read here
  // because oboe.cpp only advances the counter after this returns, and it is
  // what every snapshot published below is stamped against - an exact
  // arithmetic position on the audio timeline, never a clock reading.
  const uint64_t bufferBase = static_cast<uint64_t>(get_frames_written());

  static int render_count = 0;
  if (++render_count % 5000 == 0) {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (state.mod) {
      LOG_DEBUG("renderOpenMpt: count=%d pos=%.2fs / %.2fs loop=%d repeat_count=%d",
        render_count, openmpt_module_get_position_seconds(state.mod), state.duration,
        state.loop ? 1 : 0, openmpt_module_get_repeat_count(state.mod));
    }
  }

  // Rendered in small sub-chunks, not one read call for the whole callback
  // buffer - what actual tracker players do (libopenmpt's own example
  // renderer reads in 480-frame/~10ms chunks; ft2-clone subdivides its output
  // buffer at replayer tick boundaries for exactly this reason).
  // openmpt_module_read_interleaved_stereo() freely advances through as many
  // rows as fit in however many frames it is asked for, and nothing about its
  // intermediate state is ever queryable - only what is true when it returns.
  // Asking for less each time is what makes every individual row transition
  // observable, and gives each one a distinct frame stamp.
  //
  // ~220 frames (~5ms at 44.1/48kHz) is comfortably below the shortest row
  // duration the classic tempo formula (2500*speed/bpm) can produce at its
  // fastest standard setting (bpm 255, speed 1 -> ~9.8ms), so this doesn't
  // just improve the odds of catching a row change - for any tempo/speed a
  // classic-mode module can specify, it cannot be missed.
  constexpr int32_t kSubChunkFrames = 220;
  // A module that yields nothing but still restarts would otherwise spin this
  // callback forever; no real module loops more than a handful of times
  // inside one buffer.
  constexpr int kMaxLoopRestarts = 4;

  int32_t rendered = 0;
  int loopRestarts = 0;
  bool ended = false;
  bool moduleGone = false;

  while (rendered < numFrames) {
    const int32_t want = std::min(kSubChunkFrames, numFrames - rendered);
    size_t got = 0;
    bool restarted = false;

    // Locked per sub-chunk rather than for the whole callback. That single
    // change is what stops the UI stalling: this mutex is also taken by every
    // playback-state read, and holding it across a full buffer meant any such
    // read could block for the whole render - tens of milliseconds, on the
    // main thread, which is a dropped frame every time it happened.
    // (The state reads no longer take it at all now, see pickSnapshot, but
    // pattern data and mute toggles still do.)
    {
      std::lock_guard<std::mutex> lock(state.mutex);
      if (!state.mod || !state.playing) {
        moduleGone = true;
      } else {
        got = renderChunk(state, audioData, rendered, want, numChannels, bytesPerSample);
        if (got > 0) {
          publishSnapshot(state, bufferBase + static_cast<uint64_t>(rendered) + got);
        }
        if (got < static_cast<size_t>(want)) {
          LOG_INFO("renderOpenMpt: short read got=%zu want=%d loop=%d repeat_count=%d",
            got, want, state.loop ? 1 : 0, openmpt_module_get_repeat_count(state.mod));
          if (state.loop && loopRestarts < kMaxLoopRestarts) {
            // Keep filling the rest of the buffer from the looped position
            // rather than leaving it untouched: a loop point can land
            // anywhere inside a callback, and stopping here would leave the
            // remainder as whatever was already in that memory - an audible
            // click or a burst of garbage at every loop.
            //
            // Snapshots are deliberately NOT reset here. The frames already
            // in the output buffer are still going to be heard, and their
            // pre-loop stamps are what correctly keeps showing the end of the
            // song until the speaker actually reaches the loop point.
            openmpt_module_set_position_seconds(state.mod, 0.0);
            anchorSongTime(state, 0.0);
            restarted = true;
          } else {
            ended = true;
          }
        }
      }
    }

    rendered += static_cast<int32_t>(got);
    if (moduleGone) break;
    if (ended) {
      LOG_INFO("renderOpenMpt: signalling module_ended");
      silenceTail(audioData, rendered, numFrames, numChannels, bytesPerSample);
      return -1;
    }
    if (restarted) {
      loopRestarts++;
      continue;
    }
    // Rendered nothing and neither looped nor ended: nothing more will come
    // from this module on this pass, so stop instead of spinning.
    if (got == 0) break;
  }

  // Anything not filled above (module released mid-callback, or a stalled
  // read) must be silence, not whatever the previous buffer left behind.
  silenceTail(audioData, rendered, numFrames, numChannels, bytesPerSample);
  return 0;
}

/** An octave either way, which is as far as a tracker sample stays itself. */
constexpr int kMaxPitchSemitones = 12;

/**
 * Pushes state.pitch_semitones into the loaded module.
 *
 * Semitones rather than a factor because that is what a musician means by
 * pitch, and because the ends of the range then sit exactly an octave either
 * side. libopenmpt wants the factor, which is 2^(n/12) - the same relation a
 * fretboard has.
 *
 * Caller holds state.mutex.
 */
void applyPitch(OpenMptState& state) {
  if (!state.has_interactive || !state.mod_ext || !state.interactive.set_pitch_factor) return;
  const double factor = std::pow(2.0, state.pitch_semitones / 12.0);
  state.interactive.set_pitch_factor(state.mod_ext, factor);
}

/** Pushes state.tempo_factor into the loaded module. Caller holds state.mutex. */
void applyTempo(OpenMptState& state) {
  if (!state.has_interactive || !state.mod_ext || !state.interactive.set_tempo_factor) return;
  state.interactive.set_tempo_factor(state.mod_ext, state.tempo_factor);
}

/** The position in the song's own time - see OpenMptState::tempo_factor. Caller holds state.mutex. */
double songSeconds(OpenMptState& state) {
  const double rendered = openmpt_module_get_position_seconds(state.mod);
  return state.anchor_song_seconds +
         (rendered - state.anchor_render_seconds) * state.tempo_factor;
}

/**
 * Pins song time to where libopenmpt now stands, after anything that moved
 * playback: [songAt] is where that was in the song. Caller holds state.mutex.
 */
void anchorSongTime(OpenMptState& state, double songAt) {
  state.anchor_song_seconds = songAt;
  state.anchor_render_seconds = state.mod ? openmpt_module_get_position_seconds(state.mod) : 0.0;
}

void destroyModule(OpenMptState& state) {
  // Before anything else: every stamped position in the ring describes a
  // module that is about to stop existing, and a consumer must not be handed
  // one of those for the next module.
  resetSnapshots();
  state.wrapped = false;
  if (state.mod_ext) {
    // Destroys the underlying module too - mod is a borrowed pointer into it.
    openmpt_module_ext_destroy(state.mod_ext);
    state.mod_ext = nullptr;
    state.mod = nullptr;
  }
  state.has_interactive = false;
  state.mod_is_loaded = false;
  state.playing = false;
  state.duration = 0.0;
  state.subsong_seconds.clear();
  state.loop_start_seconds = 0.0;
  state.selected_subsong = 0;
  state.file_muted = 0;
  state.current_sample.fill(0);
  state.base_periods.fill(0);
  state.prev_keys.fill(-1);
}

static jbyte readSampleByte(const openmpt_module_sample_state& sample, int frame) {
  if (!sample.data || frame < 0 || frame >= sample.length) return 0;
  int sampleChannels = std::max(1, sample.channels);
  int index = frame * sampleChannels;
  if ((sample.flags & OPENMPT_SAMPLE_STEREO) != 0 && sampleChannels >= 2) {
    if (sample.bits_per_sample == 16) {
      const auto* data = static_cast<const int16_t*>(sample.data);
      return static_cast<jbyte>(((static_cast<int>(data[index]) + static_cast<int>(data[index + 1])) / 2) >> 8);
    }
    const auto* data = static_cast<const int8_t*>(sample.data);
    return static_cast<jbyte>((static_cast<int>(data[index]) + static_cast<int>(data[index + 1])) / 2);
  }
  if (sample.bits_per_sample == 16) {
    const auto* data = static_cast<const int16_t*>(sample.data);
    return static_cast<jbyte>(data[index] >> 8);
  }
  const auto* data = static_cast<const int8_t*>(sample.data);
  return static_cast<jbyte>(data[index]);
}

static int64_t wrapSamplePosition(int64_t pos, const openmpt_module_sample_state& sample) {
  const int64_t loopStart = static_cast<int64_t>(sample.loop_start) << 32;
  const int64_t loopEnd = static_cast<int64_t>(sample.loop_end) << 32;
  const int64_t sampleEnd = static_cast<int64_t>(sample.length) << 32;
  if ((sample.flags & OPENMPT_SAMPLE_LOOP) != 0 && loopEnd > loopStart) {
    // Only what has run past the loop's end goes back round it. What is
    // before its start is the attack, played once on the way in: folded
    // into the loop as well, the first of every note was traced as its
    // sustain rather than as what was sounding.
    if (pos < 0) return -1;
    const int64_t loopLength = loopEnd - loopStart;
    if (pos >= loopEnd) pos = loopStart + ((pos - loopEnd) % loopLength);
    return pos;
  }
  return (pos >= 0 && pos < sampleEnd) ? pos : -1;
}

jboolean openmpt_hasEnded(JNIEnv* env) {
  return has_module_ended() ? JNI_TRUE : JNI_FALSE;
}

// The disconnect flag is otherwise only cleared by opening a fresh stream, so
// a recovery path that does not reach that point leaves it set and every
// later start aborts immediately on a disconnect already dealt with.
void openmpt_clearAudioDisconnected(JNIEnv* env) {
  clear_audio_disconnected();
}

void openmpt_notifyInit(jint actualRate) {
  OpenMptState& state = OpenMptState::instance();
  state.actual_rate = actualRate;
  state.initialized = true;
}

void openmpt_prepareStart(jint rate) {
  OpenMptState& state = OpenMptState::instance();
  state.actual_rate = rate > 0 ? rate : state.actual_rate;
  // Starting the stream restarts the device's frame counters (see
  // restart_audio), so any stamp already in the ring refers to a timeline
  // that no longer exists.
  resetSnapshots();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.playing = true;
}

void openmpt_notifyStop() {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.playing = false;
}

void openmpt_shutdown() {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  destroyModule(state);
  state.initialized = false;
}

jboolean openmpt_setSequence(JNIEnv* env, jint seq) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return JNI_FALSE;
  if (openmpt_module_select_subsong(state.mod, seq) == 0) return JNI_FALSE;
  state.selected_subsong = seq;
  state.duration = openmpt_module_get_duration_seconds(state.mod);
  // Measured by seeking, and libopenmpt times a seek at the tempo set - so
  // back into the song's own time, where the duration already is.
  state.loop_start_seconds = computeLoopStartSeconds(state.mod, seq) * state.tempo_factor;
  anchorSongTime(state, 0.0);
  // Another subsong, from its own start: no passes behind it.
  state.wrapped = false;
  set_playing(1); // reset module_ended
  return JNI_TRUE;
}

jboolean openmpt_testData(JNIEnv* env, const unsigned char* bytes, size_t size, jobject modInfo) {
  initModInfoFields(env);
  if (!bytes || size == 0) return JNI_FALSE;

  // Its header first, which says no in a few microseconds to everything that
  // is not a module - every chip tune, every stream, every stray file of a
  // library - where opening it to find out was a full attempt at loading.
  // "Cannot tell from this much" goes on to the attempt.
  const size_t head = std::min(size, openmpt_probe_file_header_get_recommended_size());
  if (openmpt_probe_file_header(OPENMPT_PROBE_FILE_HEADER_FLAGS_DEFAULT, bytes, head, size, nullptr, nullptr, nullptr,
                                nullptr, nullptr, nullptr) == OPENMPT_PROBE_FILE_HEADER_RESULT_FAILURE) {
    return JNI_FALSE;
  }

  int error = OPENMPT_ERROR_OK;
  const char* error_message = nullptr;
  // Only the title and the type are wanted here, so the samples are left
  // unread - an MO3's are MP3 or Vorbis, and decoding every one of them for
  // each file in a directory being indexed would make MO3s slow to list. Nor
  // the patterns, the plugins, or the play-through of every subsong that
  // works out how long each is: none of it is in a title, and for a library
  // of thousands it was most of what the first scan did.
  static const openmpt_module_initial_ctl kIndexCtls[] = {
      {"load.skip_samples", "1"},
      {"load.skip_patterns", "1"},
      {"load.skip_plugins", "1"},
      {"load.skip_subsongs_init", "1"},
      {nullptr, nullptr},
  };
  openmpt_module* mod = openmpt_module_create_from_memory2(bytes, size, nullptr, nullptr, nullptr, nullptr, &error, &error_message, kIndexCtls);

  if (!mod) {
    openmpt_free_string(error_message);
    return JNI_FALSE;
  }

  std::string title = metadata(mod, "title");
  std::string type_long = metadata(mod, "type_long");
  jstring name = newJString(env, title.c_str());
  jstring typeStr = newJString(env, type_long.c_str());
  env->SetObjectField(modInfo, g_modInfo.name, name);
  env->SetObjectField(modInfo, g_modInfo.type, typeStr);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(typeStr);
  openmpt_module_destroy(mod);
  return JNI_TRUE;
}





jint openmpt_getPatternRows(JNIEnv* env, jint pat) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;
  return openmpt_module_get_pattern_num_rows(state.mod, pat);
}

jint openmpt_getPlayer(JNIEnv* env, jint parm) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;
  int32_t rp = 0;
  switch (parm) {
    case PARAM_STEREO_SEPARATION:
      openmpt_module_get_render_param(state.mod, OPENMPT_MODULE_RENDER_STEREOSEPARATION_PERCENT, &rp);
      return static_cast<jint>(rp);
    case PARAM_MASTER_VOLUME:
      openmpt_module_get_render_param(state.mod, OPENMPT_MODULE_RENDER_MASTERGAIN_MILLIBEL, &rp);
      return static_cast<jint>(rp / 60) + 100;
    case PARAM_DITHER:
      // "dither" is a real ctl key (unlike the render.* string keys below, which
      // don't exist in this libopenmpt's ctl table and silently no-op) — see
      // libopenmpt_impl.cpp's ctl_infos[] for the authoritative list.
      return static_cast<jint>(openmpt_module_ctl_get_integer(state.mod, "dither"));
    case PARAM_VOLUME_RAMPING:
      openmpt_module_get_render_param(state.mod, OPENMPT_MODULE_RENDER_VOLUMERAMPING_STRENGTH, &rp);
      return static_cast<jint>(rp);
    default:
      return 0;
  }
}

jint openmpt_loadFd(JNIEnv* env, jint fd, jobject modInfo) {
  std::vector<unsigned char> data = readFd(fd);
  close(fd);
  return openmpt_loadData(env, data.data(), data.size(), modInfo);
}

jint openmpt_loadData(JNIEnv* env, const unsigned char* bytes, size_t size, jobject modInfo) {
  initModInfoFields(env);
  // Read where it lies: libopenmpt takes what it needs out of it and keeps
  // none of it, and a copy made here first was the whole file a second
  // time - a hundred megabytes of it, for the largest that are tried.
  if (bytes == nullptr || size == 0) return -1;

  int error = OPENMPT_ERROR_OK;
  const char* error_message = nullptr;
  // The extended handle, not the plain one: everything the plain API does is
  // reachable from it via openmpt_module_ext_get_module, and it is the only
  // way to the interactive interface that owns pitch.
  openmpt_module_ext* mod_ext = openmpt_module_ext_create_from_memory(bytes, size, nullptr, nullptr, nullptr, nullptr, &error, &error_message, nullptr);

  if (!mod_ext) {
    LOG_WARN("loadModuleFd failed: %d %s", error, error_message ? error_message : "");
    openmpt_free_string(error_message);
    return error != 0 ? error : -2;
  }

  openmpt_module* mod = openmpt_module_ext_get_module(mod_ext);
  if (!mod) {
    openmpt_module_ext_destroy(mod_ext);
    openmpt_free_string(error_message);
    return -2;
  }

  openmpt_module_ext_interface_interactive interactive{};
  const bool hasInteractive = openmpt_module_ext_get_interface(
      mod_ext, LIBOPENMPT_EXT_C_INTERFACE_INTERACTIVE, &interactive, sizeof(interactive)) != 0;
  if (!hasInteractive) LOG_WARN("loadFd: interactive interface unavailable, pitch disabled");

  // play once by default; setLoopMode overrides to -1 for repeat-one
  int rc_result = openmpt_module_set_repeat_count(mod, 0);
  LOG_INFO("loadFd: set repeat_count=0 result=%d actual=%d", rc_result, openmpt_module_get_repeat_count(mod));

  // Computed before the module is handed to the state, i.e. before the audio
  // callback can reach it: this seeks, and a seek is far too expensive to
  // ever run on the audio thread.
  // The channels the file itself has switched off: a module is saved with its
  // mutes, and a tracker's author leaves channels off on purpose - sketches,
  // the scratch channels of an S3M. They are the file's, to be shown as muted
  // and brought back by whoever wants to hear them.
  uint64_t fileMuted = 0;
  for (int c = 0; c < std::min(64, openmpt_module_get_num_channels(mod)); c++) {
    if (openmpt_module_channel_mute(mod, c, -1) == 1) fileMuted |= uint64_t{1} << c;
  }

  // Each subsong's own length, while nothing is playing the module yet: the
  // list of them gave every one the length of whichever was playing, and
  // changed them all when another was picked. Asked by selecting each in
  // turn, which only moves to its start - the lengths were worked out when
  // the module was opened - and the selection it came with is put back as
  // it was, "all of them, one after another" included.
  std::vector<double> subsongSeconds;
  const int subsongCount = openmpt_module_get_num_subsongs(mod);
  if (subsongCount > 1) {
    const int cameWith = openmpt_module_get_selected_subsong(mod);
    subsongSeconds.reserve(static_cast<size_t>(subsongCount));
    for (int i = 0; i < subsongCount; i++) {
      openmpt_module_select_subsong(mod, i);
      subsongSeconds.push_back(openmpt_module_get_duration_seconds(mod));
    }
    openmpt_module_select_subsong(mod, cameWith);
  }

  const int selectedSubsong = std::max(0, openmpt_module_get_selected_subsong(mod));
  const double duration = openmpt_module_get_duration_seconds(mod);
  const double loopStart = computeLoopStartSeconds(mod, selectedSubsong);

  OpenMptState& state = OpenMptState::instance();
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    destroyModule(state);
    state.mod = mod;
    state.mod_ext = mod_ext;
    state.interactive = interactive;
    state.has_interactive = hasInteractive;
    // Re-applied here because the factor is a property of the module object,
    // so a new module always starts at unity no matter what the user chose.
    applyPitch(state);
    applyTempo(state);
    anchorSongTime(state, 0.0);
    state.mod_is_loaded = true;
    state.playing = false;
    state.selected_subsong = selectedSubsong;
    state.file_muted = fileMuted;
    state.duration = duration;
    state.subsong_seconds = std::move(subsongSeconds);
    state.loop_start_seconds = loopStart;
    // A local ctl - see libopenmpt_impl.cpp. Fixed by the module's mix levels.
    state.global_volume_to_master =
        openmpt_module_ctl_get_boolean(mod, "play.global_volume_applies_to_master") != 0;
  }

  std::string title = metadata(mod, "title");
  std::string type = metadata(mod, "type");
  jstring name = newJString(env, title.c_str());
  jstring typeStr = newJString(env, type.c_str());
  env->SetObjectField(modInfo, g_modInfo.name, name);
  env->SetObjectField(modInfo, g_modInfo.type, typeStr);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(typeStr);
  return 0;
}

/**
 * The status that mutes a channel as the file itself has it muted, rather
 * than as a listener who does not want to hear it: the same silence, but a
 * Scream Tracker module skips the effects of a channel it has switched off,
 * and plays to another length when they are run. See the libopenmpt patch.
 */
constexpr int kMutedByFile = 2;

jlong openmpt_resetMutes(JNIEnv* env) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;
  for (int c = 0; c < 64; c++) {
    openmpt_module_channel_mute(state.mod, c, (state.file_muted >> c) & 1 ? kMutedByFile : 0);
  }
  return static_cast<jlong>(state.file_muted);
}

jint openmpt_mute(JNIEnv* env, jint chn, jint status) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return -1;
  // A channel the file mutes, muted again after the listener had brought it
  // back, is the file's mute again and not a listener's: see kMutedByFile.
  const bool files = chn >= 0 && chn < 64 && ((state.file_muted >> chn) & 1) != 0;
  return openmpt_module_channel_mute(state.mod, chn, status > 0 && files ? kMutedByFile : status);
}

jint openmpt_nextPosition(JNIEnv* env) {
  return JNI_FALSE;
}



jint openmpt_prevPosition(JNIEnv* env) {
  return JNI_FALSE;
}

jint openmpt_releaseModule(JNIEnv* env) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  destroyModule(state);
  return 0;
}

jint openmpt_restartModule(JNIEnv* env) {
  return JNI_FALSE;
}

jint openmpt_seek(JNIEnv* env, jint time) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return -1;
  // libopenmpt reads the target at the tempo set, so a point in the song is
  // asked for as the time it takes to get there at that tempo.
  const double song = std::max(0, static_cast<int>(time)) / 1000.0;
  openmpt_module_set_position_seconds(state.mod, song / state.tempo_factor);
  anchorSongTime(state, song);
  // A point in the song, named: whatever passes had gone by are not where
  // playback is any more.
  state.wrapped = false;
  return 0;
}

jint openmpt_setPlayer(JNIEnv* env, jint parm, jint value) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;
  switch (parm) {
    case PARAM_STEREO_SEPARATION:
      // value 0-200, 100 = normal stereo width
      openmpt_module_set_render_param(state.mod, OPENMPT_MODULE_RENDER_STEREOSEPARATION_PERCENT, value);
      break;
    case PARAM_MASTER_VOLUME:
      // value 0-200, 100 = unity gain (0 millibel)
      openmpt_module_set_render_param(state.mod, OPENMPT_MODULE_RENDER_MASTERGAIN_MILLIBEL, (value - 100) * 60);
      break;
    case PARAM_DITHER:
      openmpt_module_ctl_set_integer(state.mod, "dither", value);
      break;
    case PARAM_VOLUME_RAMPING:
      // value -1..10, -1 = libopenmpt recommended default
      openmpt_module_set_render_param(state.mod, OPENMPT_MODULE_RENDER_VOLUMERAMPING_STRENGTH, value);
      break;
    default:
      break;
  }
  return 0;
}

jint openmpt_setPosition(JNIEnv* env, jint n) {
  return JNI_FALSE;
}

jint openmpt_setResampler(JNIEnv* env, jint mode) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return 0;
  // Where the song is when the setting lands: past its start means it was
  // already playing on the library's own default.
  LOG_INFO("setResampler: mode %d with the song at %.3f s", mode, openmpt_module_get_position_seconds(state.mod));

  if (mode == 100 || mode == 101) {
    openmpt_module_ctl_set_boolean(state.mod, "render.resampler.emulate_amiga", 1);
    openmpt_module_ctl_set_text(state.mod, "render.resampler.emulate_amiga_type", mode == 100 ? "a500" : "a1200");
  } else {
    // ResamplerMode.NEAREST=0, LINEAR=1, CUBIC=2, SINC=3 → mpt filter lengths 1, 2, 4, 8
    static const int kFilterLen[] = {1, 2, 4, 8};
    int filter_len = (mode >= 0 && mode <= 3) ? kFilterLen[mode] : 2;
    openmpt_module_ctl_set_boolean(state.mod, "render.resampler.emulate_amiga", 0);
    openmpt_module_set_render_param(state.mod, OPENMPT_MODULE_RENDER_INTERPOLATIONFILTER_LENGTH, filter_len);
  }
  return 0;
}


jint openmpt_stopModule(JNIEnv* env) {
  return JNI_FALSE;
}

jint openmpt_time(JNIEnv* env) {
  return JNI_FALSE;
}

std::vector<std::string> openmpt_formatList() {
  // Just the list. Turning it into what the app reports it supports is the
  // decoder layer's business, because libopenmpt is no longer all of it.
  std::string extensions = openmptString(openmpt_get_supported_extensions());
  std::vector<std::string> items;
  size_t start = 0;
  while (start <= extensions.size()) {
    size_t end = extensions.find(';', start);
    std::string item = extensions.substr(start, end == std::string::npos ? std::string::npos : end - start);
    if (!item.empty()) items.push_back(item);
    if (end == std::string::npos) break;
    start = end + 1;
  }
  return items;
}

jstring openmpt_getVersion(JNIEnv* env) {
  const char* version = openmpt_get_string("library_version");
  jstring result = newJString(env, version ? version : "");
  openmpt_free_string(version);
  return result;
}

void openmpt_getAudioStats(JNIEnv* env, jobject info) {
  initAudioStatsFields(env);
  struct AudioStats stats{};
  get_audio_stats(&stats);
  env->SetIntField(info, g_audioStats.xrunCount, stats.xrun_count);
  env->SetIntField(info, g_audioStats.underrunCount, stats.underrun_count);
  env->SetIntField(info, g_audioStats.framesPerBurst, stats.frames_per_burst);
  env->SetIntField(info, g_audioStats.bufferCapacity, stats.buffer_capacity);
  env->SetIntField(info, g_audioStats.bufferSize, stats.buffer_size);
  env->SetIntField(info, g_audioStats.sampleRate, stats.sample_rate);
  jstring apiStr = newJString(env, stats.audio_api ? stats.audio_api : "");
  jstring modeStr = newJString(env, stats.sharing_mode ? stats.sharing_mode : "");
  jstring perfStr = newJString(env, stats.perf_mode ? stats.perf_mode : "");
  jstring formatStr = newJString(env, stats.audio_format ? stats.audio_format : "");
  env->SetObjectField(info, g_audioStats.audioApi, apiStr);
  env->SetObjectField(info, g_audioStats.sharingMode, modeStr);
  env->SetObjectField(info, g_audioStats.perfMode, perfStr);
  env->SetObjectField(info, g_audioStats.audioFormat, formatStr);
  env->DeleteLocalRef(apiStr);
  env->DeleteLocalRef(modeStr);
  env->DeleteLocalRef(perfStr);
  env->DeleteLocalRef(formatStr);
}

/**
 * Draws each channel's scope trace by walking the instrument's sample data
 * from the position the snapshot captured - the way every real tracker does
 * it. ft2-clone's per-pixel scope loop is the same three steps this inner
 * loop performs: fetch the sample at the current fixed-point read position,
 * plot it, advance by the channel's step, apply the loop wrap.
 *
 * Note what is NOT here: nothing reads the mixed output buffer, and nothing
 * extrapolates the position by elapsed wall-clock time. The old version did
 * the latter, which meant two calls a millisecond apart got windows offset by
 * an arbitrary amount - a moving window sampled at an unrelated rate, i.e.
 * textbook stroboscopic aliasing, which is what made the trace look doubled
 * and unstable. The position now comes from the snapshot, so it advances in
 * exact lockstep with the audio being heard.
 *
 * The horizontal time base is the channel's own increment scaled by
 * kScopeFramesPerPixel, so one scope width always covers the same span of
 * *output* time regardless of pitch; a higher note simply traverses more
 * sample data and shows more cycles. ft2-clone's scope does exactly this -
 * its drawDelta is derived from the channel's period but works out to a fixed
 * number of screen pixels per second of output.
 *
 * Must be called with state.mutex held (it touches sample data owned by the
 * module).
 */
static void renderScopeTraces(OpenMptState& state, const PlaybackSnapshot& snap,
    int numChannels, int width, jbyte* out) {
  // Output frames each pixel column advances by. This sets how much time one
  // scope width spans, and it is what decides whether the trace reads as
  // flowing or flickering: the window has to be comfortably *wider* than the
  // distance it travels between two display frames, or consecutive frames
  // show non-overlapping stretches of waveform and the eye sees noise rather
  // than motion. One output frame per pixel - what this used to do - covered
  // barely 6ms at 44.1kHz, a third of a 60Hz frame's worth of audio, so every
  // frame drew a completely different piece of the wave.
  //
  // Twice ft2-clone's own time base (its scope spans ~30ms; this spans
  // ~15ms across 256 columns), so a single cycle is drawn twice as wide and
  // the shape of the waveform is actually readable rather than a dense band.
  //
  // Past the point where consecutive frames still overlap, deliberately: at
  // 60Hz the window travels 16.7ms between frames, so at this span they sit
  // edge to edge. That matters less than the overlap argument suggests,
  // because what the eye tracks on a sustained note is not the window's
  // absolute position but the waveform's phase, and the per-frame phase shift
  // is a small fraction of one cycle's width either way. The cost lands on
  // transients and noise-like samples, which have no phase to track and so
  // look busier here than they did at the wider span.
  constexpr double kScopePixelsPerSecond = 16726.0;
  const double framesPerPixel =
    static_cast<double>(state.actual_rate) / kScopePixelsPerSecond;

  for (int chn = 0; chn < numChannels; chn++) {
    const ChannelSnapshot& c = snap.ch[chn];
    // A voice of the OPL chip: an instrument that is four numbers for an
    // oscillator, with no sample data to walk. The chip's own output for the
    // voice is drawn instead, from the moment the snapshot was taken on - the
    // same stretch of time a sample's trace covers. The library keeps the
    // last second of it (see OPL::Mix in the libopenmpt patch); a snapshot
    // older than that leaves the box empty.
    if (c.oplVoice > 0) {
      openmpt_module_get_opl_scope(state.mod, c.oplVoice - 1, snap.oplFrames, framesPerPixel, width,
                                   reinterpret_cast<int8_t*>(out + static_cast<size_t>(chn) * width));
      continue;
    }
    if (c.sample <= 0 || c.increment == 0) continue;

    openmpt_module_sample_state sample{};
    if (openmpt_module_get_sample_state(state.mod, c.sample, &sample) == 0 ||
        sample.length <= 0 || !sample.data) {
      continue;
    }

    int64_t pos = c.position;
    int64_t step = static_cast<int64_t>(
      std::llround(static_cast<double>(c.increment) * framesPerPixel));
    if (step < 0) step = -step;
    if (step == 0) step = 1;

    jbyte* row = out + static_cast<size_t>(chn) * width;
    for (int i = 0; i < width; i++) {
      const int64_t wrapped = wrapSamplePosition(pos, sample);
      if (wrapped < 0) break;
      row[i] = readSampleByte(sample, static_cast<int>(wrapped >> 32));
      pos += step;
    }
    // No level from here any more. The loudest byte of this walk times
    // libopenmpt's VU was what the volume bar showed, and it missed anything
    // the sample data does not say by itself - filters, background voices,
    // the true volume - and read only 15 ms, which is less than a low note's
    // cycle. The mixer measures the level now; see ChannelSnapshot::level.
  }
}

/**
 * The single per-display-frame read of everything the visualisers show.
 *
 * Replaces getFrameInfo + getChannelData + getSampleData, which were three
 * separate JNI calls each taking the mutex the audio callback held for a full
 * buffer. This one is lock-free for the state itself (pickSnapshot touches
 * only the ring) and takes the mutex only to walk sample data for the scopes,
 * and even then with try_lock: if the audio thread happens to hold it, the
 * scope simply keeps the previous frame's trace rather than stalling the UI.
 * A UI thread must never wait on an audio thread.
 *
 * @param scalars   out, kScalarCount ints - see the kScalar* indices
 * @param chData    out, numChannels * kChannelFields ints, channel-major
 * @param scopes    out, numChannels * scopeWidth bytes; null to skip scopes
 * @return false when no snapshot exists yet (nothing has been rendered)
 */
jboolean openmpt_readVisualState(JNIEnv* env, jintArray scalars, jintArray chData,
    jbyteArray scopes, jint scopeWidth) {
  PlaybackSnapshot snap;
  if (!pickSnapshot(get_frames_played(), snap)) return JNI_FALSE;

  OpenMptState& state = OpenMptState::instance();
  const int channels = std::clamp(snap.numChannels, 0, MAX_CHANNELS);

  if (scalars) {
    int32_t values[kScalarCount] = {};
    values[kScalarOrder] = snap.order;
    values[kScalarPattern] = snap.pattern;
    values[kScalarRow] = snap.row;
    values[kScalarNumRows] = snap.numRows;
    values[kScalarSpeed] = snap.speed;
    values[kScalarBpm] = snap.bpm;
    values[kScalarTimeMs] = snap.timeMs;
    values[kScalarPlayingChannels] = snap.playingChannels;
    values[kScalarGlobalVolume] = snap.globalVolume;
    values[kScalarNumChannels] = channels;
    values[kScalarSequence] = snap.sequence;
    values[kScalarTotalTimeMs] = static_cast<int32_t>(std::lround(state.duration * 1000.0));
    values[kScalarLatencyFrames] =
      static_cast<int32_t>(std::min<int64_t>(get_frames_written() - get_frames_played(), INT32_MAX));
    const jsize len = std::min<jsize>(env->GetArrayLength(scalars), kScalarCount);
    env->SetIntArrayRegion(scalars, 0, len, values);
  }

  if (scopes && scopeWidth > 0 && channels > 0) {
    const int width = std::min<int>(scopeWidth, MAX_BUFFER_SIZE);
    const size_t total = static_cast<size_t>(channels) * width;
    // Reused across calls rather than allocated per frame; this runs on the
    // UI thread once per display frame, where an allocation is a GC problem
    // rather than a correctness one.
    static thread_local std::vector<jbyte> scratch;
    scratch.assign(total, 0);

    std::unique_lock<std::mutex> lock(state.mutex, std::try_to_lock);
    if (lock.owns_lock() && state.mod) {
      renderScopeTraces(state, snap, channels, width, scratch.data());
      const jsize len = std::min<jsize>(env->GetArrayLength(scopes), static_cast<jsize>(total));
      env->SetByteArrayRegion(scopes, 0, len, scratch.data());
    }
  }

  if (chData && channels > 0) {
    int32_t fields[MAX_CHANNELS * kChannelFields] = {};
    for (int i = 0; i < channels; i++) {
      const ChannelSnapshot& c = snap.ch[i];
      int32_t* f = fields + static_cast<size_t>(i) * kChannelFields;
      f[kChSample] = c.sample;
      f[kChInstrument] = c.instrument;
      f[kChNote] = c.note;
      f[kChKey] = c.key;
      f[kChVolume] = c.volume;
      f[kChFinalVolume] = c.finalVolume;
      f[kChPan] = c.pan;
      f[kChPeriod] = c.period;
      f[kChPitchBend] = c.pitchBend;
      f[kChPeak] = c.level;
    }
    const jsize len = std::min<jsize>(env->GetArrayLength(chData), channels * kChannelFields);
    env->SetIntArrayRegion(chData, 0, len, fields);
  }

  return JNI_TRUE;
}

void openmpt_getFrameInfo(JNIEnv* env, jobject frameInfo) {
  initFrameInfoFields(env);
  PlaybackSnapshot snap;
  if (!pickSnapshot(get_frames_played(), snap)) return;
  OpenMptState& state = OpenMptState::instance();

  env->SetIntField(frameInfo, g_frameInfo.pos, snap.order);
  env->SetIntField(frameInfo, g_frameInfo.pattern, snap.pattern);
  env->SetIntField(frameInfo, g_frameInfo.row, snap.row);
  env->SetIntField(frameInfo, g_frameInfo.numRows, snap.numRows);
  env->SetIntField(frameInfo, g_frameInfo.speed, snap.speed);
  env->SetIntField(frameInfo, g_frameInfo.bpm, snap.bpm);
  env->SetIntField(frameInfo, g_frameInfo.time, snap.timeMs);
  env->SetIntField(frameInfo, g_frameInfo.totalTime,
    static_cast<int>(std::lround(state.duration * 1000.0)));
  env->SetIntField(frameInfo, g_frameInfo.virtChannels, snap.numChannels);
  env->SetIntField(frameInfo, g_frameInfo.virtUsed, snap.playingChannels);
  env->SetIntField(frameInfo, g_frameInfo.sequence, snap.sequence);
}

// "01 Bass", "10 Kick" - the index the tracker shows, then the name.
//
// Padded on a decimal boundary. This used to test i + 1 < 16, a leftover from
// numbering in hex, which padded 10 through 15 to three digits ("010") while
// 16 upwards got two.
static std::string numberedName(int index, const std::string& raw) {
  const int shown = index + 1;
  return (shown < 10 ? "0" : "") + std::to_string(shown) + " " + raw;
}

void openmpt_getModVars(JNIEnv* env, jobject modVars) {
  initModVarsFields(env);
  initSequenceFields(env);
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.mod) return;

  std::string title = metadata(state.mod, "title");
  std::string type = metadata(state.mod, "type");
  std::string message = metadata(state.mod, "message");
  // Clamped, because this count is what every consumer sizes its loops by, and
  // everything this layer reports per channel stops at MAX_CHANNELS: the
  // ChannelInfo arrays are fixed at that size and getPatternData uses it as a
  // fixed column stride. Formats that allow more (MPTM goes to 127) would
  // otherwise have the UI index straight off the end of those arrays.
  int channels = std::min<int>(MAX_CHANNELS, openmpt_module_get_num_channels(state.mod));
  int subsongs = std::max(1, openmpt_module_get_num_subsongs(state.mod));
  int durationMs = static_cast<int>(std::round(state.duration * 1000.0));
  int instruments = openmpt_module_get_num_instruments(state.mod);
  int samples = openmpt_module_get_num_samples(state.mod);
  int displayInstruments = instruments > 0 ? instruments : samples;

  jstring titleStr = newJString(env, title.c_str());
  jstring typeStr = newJString(env, type.c_str());
  jstring messageStr = newJString(env, message.c_str());
  env->SetObjectField(modVars, g_modVars.name, titleStr);
  env->SetObjectField(modVars, g_modVars.type, typeStr);
  env->SetObjectField(modVars, g_modVars.miComment, messageStr);
  env->SetIntField(modVars, g_modVars.pat, openmpt_module_get_num_patterns(state.mod));
  env->SetIntField(modVars, g_modVars.trk, openmpt_module_get_num_orders(state.mod));
  env->SetIntField(modVars, g_modVars.chn, channels);
  env->SetIntField(modVars, g_modVars.ins, instruments);
  env->SetIntField(modVars, g_modVars.smp, samples);
  env->SetIntField(modVars, g_modVars.spd, openmpt_module_get_current_speed(state.mod));
  env->SetIntField(modVars, g_modVars.bpm, static_cast<int>(std::round(openmpt_module_get_current_tempo2(state.mod))));
  env->SetIntField(modVars, g_modVars.len, openmpt_module_get_num_orders(state.mod));
  env->SetIntField(modVars, g_modVars.gvl, 64);
  env->SetIntField(modVars, g_modVars.miNumSequences, subsongs);

  jclass seqCls = env->FindClass("com/hortus1556/exilum/renderer/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(subsongs, seqCls, nullptr);
  for (int i = 0; i < subsongs; i++) {
    jobject seqObj = env->AllocObject(seqCls);
    env->SetIntField(seqObj, g_sequence.entryPoint, 0);
    // Its own length; the module's where there is only the one.
    const int ownMs = static_cast<size_t>(i) < state.subsong_seconds.size()
                          ? static_cast<int>(std::lround(state.subsong_seconds[static_cast<size_t>(i)] * 1000.0))
                          : durationMs;
    env->SetIntField(seqObj, g_sequence.duration, ownMs);
    // Set even when the module names nothing. AllocObject runs no constructor,
    // so a field left alone is null - and Sequence.name is a non-null String to
    // Kotlin, which found out the moment the subsong sheet asked it isBlank.
    // That was the crash on "Show Subsong", and only here: every other decoder
    // already set it.
    jstring seqName = newJString(env, openmptString(openmpt_module_get_subsong_name(state.mod, i)).c_str());
    env->SetObjectField(seqObj, g_sequence.name, seqName);
    env->DeleteLocalRef(seqName);
    env->SetObjectArrayElement(seqArray, i, seqObj);
    env->DeleteLocalRef(seqObj);
  }
  env->SetObjectField(modVars, g_modVars.seqData, seqArray);

  // Both lists, not one picked by format. A channel reports its sample and
  // its instrument separately and the two are numbered differently, so the
  // consumer has to be able to look either one up - see ChannelView for why
  // it cannot simply trust the instrument field.
  jclass stringCls = env->FindClass("java/lang/String");
  jobjectArray insArray = env->NewObjectArray(displayInstruments, stringCls, nullptr);
  for (int i = 0; i < displayInstruments; i++) {
    std::string raw = instruments > 0 ? openmptString(openmpt_module_get_instrument_name(state.mod, i)) : openmptString(openmpt_module_get_sample_name(state.mod, i));
    jstring s = newJString(env, numberedName(i, raw).c_str());
    env->SetObjectArrayElement(insArray, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetBooleanField(modVars, g_modVars.hasPatterns, JNI_TRUE);
  env->SetObjectField(modVars, g_modVars.instruments, insArray);

  jobjectArray smpArray = env->NewObjectArray(samples, stringCls, nullptr);
  for (int i = 0; i < samples; i++) {
    std::string raw = openmptString(openmpt_module_get_sample_name(state.mod, i));
    jstring s = newJString(env, numberedName(i, raw).c_str());
    env->SetObjectArrayElement(smpArray, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, g_modVars.samples, smpArray);

  jstring renderingStr = newJString(env, "libopenmpt");
  env->SetObjectField(modVars, g_modVars.renderingEngine, renderingStr);

  env->DeleteLocalRef(titleStr);
  env->DeleteLocalRef(typeStr);
  env->DeleteLocalRef(messageStr);
  env->DeleteLocalRef(seqCls);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(stringCls);
  env->DeleteLocalRef(insArray);
  env->DeleteLocalRef(smpArray);
  env->DeleteLocalRef(renderingStr);
}

// Fills every row of [pat] in one call, under a single mutex acquisition — the
// caller (PatternView, via PlayerEngine.getPatternData) used to call
// openmpt_getPatternRow once per row (up to 64 separate lock/unlock cycles per
// pattern fetch). Each output array is row-major with a fixed 64-column
// stride (index = row*64 + ch), matching the per-row layout the old function
// used, so a module with fewer channels just leaves the unused columns unset.
void openmpt_getPatternData(JNIEnv* env, jint pat, jint numRows, jbyteArray allNotes, jbyteArray allInstruments, jbyteArray allFxType, jbyteArray allFxParm, jbyteArray allFx2Type, jbyteArray allFx2Parm) {
  OpenMptState& state = OpenMptState::instance();
  std::vector<jbyte> notes;
  std::vector<jbyte> instruments;
  std::vector<jbyte> fxType;
  std::vector<jbyte> fxParam;
  std::vector<jbyte> fx2Type;
  std::vector<jbyte> fx2Param;
  size_t total = 0;

  // Scoped so the mutex is released before the JNI array writes below: those
  // don't touch player state, and this is the longest critical section any
  // UI-facing call takes (numRows * channels * 6 libopenmpt calls), competing
  // with the audio render callback for the same mutex.
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.mod) return;
    const int channels = std::min<int>(64, openmpt_module_get_num_channels(state.mod));
    numRows = std::min(numRows, openmpt_module_get_pattern_num_rows(state.mod, pat));
    if (numRows <= 0) return;

    total = static_cast<size_t>(numRows) * 64;
    notes.assign(total, 0);
    instruments.assign(total, 0);
    fxType.assign(total, -1);
    fxParam.assign(total, -1);
    fx2Type.assign(total, -1);
    fx2Param.assign(total, -1);

    for (int row = 0; row < numRows; row++) {
      for (int ch = 0; ch < channels; ch++) {
        const size_t idx = static_cast<size_t>(row) * 64 + ch;
        notes[idx] = static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_NOTE));
        instruments[idx] = static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_INSTRUMENT));
        // Internal command 0 is CMD_NONE / VOLCMD_NONE, so type 0 means an empty slot.
        int effect = openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_EFFECT);
        fxType[idx] = static_cast<jbyte>(effect > 0 ? effect : -1);
        fxParam[idx] = effect > 0 ? static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_PARAMETER)) : -1;
        int volEffect = openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_VOLUMEEFFECT);
        fx2Type[idx] = static_cast<jbyte>(volEffect > 0 ? volEffect : -1);
        fx2Param[idx] = volEffect > 0 ? static_cast<jbyte>(openmpt_module_get_pattern_row_channel_command(state.mod, pat, row, ch, OPENMPT_MODULE_COMMAND_VOLUME)) : -1;
      }
    }
  }

  env->SetByteArrayRegion(allNotes, 0, static_cast<jsize>(total), notes.data());
  env->SetByteArrayRegion(allInstruments, 0, static_cast<jsize>(total), instruments.data());
  env->SetByteArrayRegion(allFxType, 0, static_cast<jsize>(total), fxType.data());
  env->SetByteArrayRegion(allFxParm, 0, static_cast<jsize>(total), fxParam.data());
  env->SetByteArrayRegion(allFx2Type, 0, static_cast<jsize>(total), fx2Type.data());
  env->SetByteArrayRegion(allFx2Parm, 0, static_cast<jsize>(total), fx2Param.data());
}

void openmpt_setLoopMode(JNIEnv* env, jboolean loop) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loop = loop == JNI_TRUE;
  LOG_INFO("setLoopMode: loop=%d", state.loop ? 1 : 0);
  if (state.mod) {
    int32_t count = state.loop ? -1 : 0;
    int rc_result = openmpt_module_set_repeat_count(state.mod, count);
    LOG_INFO("setLoopMode: set repeat_count=%d result=%d actual=%d",
      count, rc_result, openmpt_module_get_repeat_count(state.mod));
  }
}

// Called a buffer at a time while playback glides from one pitch or tempo to
// another - see glideStep in decoder.cpp - so neither says anything.
void openmpt_setGlobalPitch(JNIEnv* env, double semitones) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitch_semitones = std::clamp<double>(semitones, -kMaxPitchSemitones, kMaxPitchSemitones);
  applyPitch(state);
}

void openmpt_setGlobalTempo(JNIEnv* env, double percent) {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  const double factor = std::clamp(percent, 10.0, 200.0) / 100.0;
  if (factor == state.tempo_factor) return;
  if (state.mod) anchorSongTime(state, songSeconds(state));
  state.tempo_factor = factor;
  applyTempo(state);
}

void openmpt_setPlaying(JNIEnv* env, jboolean value) {
  OpenMptState& state = OpenMptState::instance();
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    state.playing = value == JNI_TRUE;
  }
  set_playing(value == JNI_TRUE ? 1 : 0);
}

void openmpt_freeContext(JNIEnv* env) {
  LOG_INFO("openmpt_freeContext() - stub, not yet implemented");
}

double openmpt_render_position_ms() {
  OpenMptState& state = OpenMptState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.mod ? songSeconds(state) * 1000.0 : 0.0;
}
