//
// GameCube streams. See decoder-dsp.h.
//
#include "decoder-dsp.h"
#include "gc-stream.h"
#include "psf-song.h"
#include "sinc-resampler.h"
#include "visual-publisher.h"
#include "visual-state.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
constexpr const char* TAG = "Exilum DSP";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

using gc::kMaxChannels;

/** The most samples one frame of any of the codecs gives. */
constexpr int kMaxFrameSamples = 32;
/** Frames between two places the decoder can start again from: at most a few milliseconds of decoding to get anywhere. */
constexpr int64_t kCheckpointFrames = 4096;
/** How long a song that loops fades for once it has been through. */
constexpr int kFadeMs = 500;

const char* const kStereoNames[2] = {"Left", "Right"};
const char* const kMonoName = "Mono";
/** For streams of more than two channels: pairs, a left and a right each. */
const char* const kPairNames[kMaxChannels] = {"Left 1", "Right 1", "Left 2", "Right 2",
                                               "Left 3", "Right 3", "Left 4", "Right 4"};

/** Output frames kept of each channel, for its trace and its level. */
constexpr uint32_t kTapFrames = 16384;
/** How often a channel is looked at for its trace and its level. */
constexpr int kLookHz = 16000;
constexpr int kLevelWindowMs = 30;
/** Native samples kept around the place being interpolated: a power of two, at least twice the kernel. */
constexpr int kWindow = 64;

std::atomic<int> g_stereoPercent{100};

/** The stream, and each channel's history at the start of every kCheckpointFrames-th frame. */
struct Song {
  gc::Stream stream;
  std::vector<std::array<gc::Hist, kMaxChannels>> checkpoints;
};

/** The stream's samples in order from any place: a checkpoint, then decoding on from it. */
struct Reader {
  int64_t frame = -1;  // the frame in [block]
  int16_t block[kMaxChannels][kMaxFrameSamples] = {};
  std::array<gc::Hist, kMaxChannels> hist{};  // after [frame]
  std::array<size_t, kMaxChannels> run{};     // where each channel's frames were last found
  int64_t next = 0;                           // the stream sample read() gives next

  void seek(const Song& song, int64_t sample) {
    const gc::Stream& s = song.stream;
    const int64_t target = sample / s.frameSamples;
    const int64_t checkpoint =
        std::min<int64_t>(target / kCheckpointFrames, static_cast<int64_t>(song.checkpoints.size()) - 1);
    int16_t scratch[kMaxFrameSamples];
    for (int c = 0; c < s.channels; c++) {
      hist[static_cast<size_t>(c)] = song.checkpoints[static_cast<size_t>(checkpoint)][static_cast<size_t>(c)];
      for (int64_t f = checkpoint * kCheckpointFrames; f < target; f++) {
        gc::decodeFrame(s, c, f, hist[static_cast<size_t>(c)], scratch, run[static_cast<size_t>(c)]);
      }
    }
    frame = target - 1;
    next = sample;
  }

  /**
   * On from where it is to [sample], ahead of it, decoding the frames between
   * with the history it carries - not a checkpoint's, which the first time
   * through left.
   */
  void skipTo(const Song& song, int64_t sample) {
    const gc::Stream& s = song.stream;
    const int64_t target = sample / s.frameSamples;
    if (target > frame + 1) {
      int16_t scratch[kMaxFrameSamples];
      for (int c = 0; c < s.channels; c++) {
        for (int64_t f = frame + 1; f < target; f++) {
          gc::decodeFrame(s, c, f, hist[static_cast<size_t>(c)], scratch, run[static_cast<size_t>(c)]);
        }
      }
      frame = target - 1;
    }
    next = sample;
  }

  /**
   * At the loop's start going round again, for a stream whose loop starts
   * with the history its end left: the loop's first frame decoded on from
   * the loop's start with [end], each channel's last two samples before the
   * loop's end. Called on the reader at the loop's start the first time.
   */
  void restartLoop(const Song& song, const std::array<gc::Hist, kMaxChannels>& end) {
    const gc::Stream& s = song.stream;
    const int64_t first = s.loopStart / s.frameSamples;
    const int from = static_cast<int>(s.loopStart % s.frameSamples);
    for (int c = 0; c < s.channels; c++) {
      hist[static_cast<size_t>(c)] = end[static_cast<size_t>(c)];
      gc::decodeFrame(s, c, first, hist[static_cast<size_t>(c)], block[c], run[static_cast<size_t>(c)], from);
    }
    frame = first;
    next = s.loopStart;
  }

  /** The next sample of each channel; past the end, silence. */
  void read(const Song& song, int16_t* out) {
    const gc::Stream& s = song.stream;
    const int64_t f = next / s.frameSamples;
    if (f >= s.frames) {
      std::fill(out, out + s.channels, int16_t{0});
      next++;
      return;
    }
    if (f != frame) {
      if (f != frame + 1) seek(song, next);
      for (int c = 0; c < s.channels; c++) {
        gc::decodeFrame(s, c, f, hist[static_cast<size_t>(c)], block[c], run[static_cast<size_t>(c)]);
      }
      frame = f;
    }
    const int at = static_cast<int>(next % s.frameSamples);
    for (int c = 0; c < s.channels; c++) out[c] = block[c][at];
    next++;
  }
};

struct DspState {
  std::mutex mutex;
  Song song;
  Reader reader;
  /** The reader at the loop's start, put back each time the song goes round. */
  Reader atLoop;
  /**
   * For a stream whose loop starts with the history its end left
   * (gc::Stream::loopKeepsHistory): the reader there the second time round,
   * and every time after; and the stream sample from which the second time
   * plays as the first did - the history's difference died away - before
   * which a place in it is decoded on from the loop's start, not found from
   * a checkpoint. For any other stream, atLoop, and the loop's start.
   */
  Reader atLoopAgain;
  Reader atLoopLater;
  int64_t settles = 0;
  SincKernel kernel;

  bool loaded = false;
  bool ended = false;
  bool loop = false;
  int sampleRate = 48000;
  std::string type;
  std::string comment;
  psf::SongLength length;

  /** Output frames handed out since the top of the song. */
  int64_t rendered = 0;
  /** The stream's samples around the place being interpolated, by their place in the song, and the next to read. */
  int16_t window[kMaxChannels][kWindow] = {};
  int64_t songNext = 0;

  ShiftStage stage;
  double pitchSemitones = 0.0;
  double tempoFactor = 1.0;
  std::array<std::vector<float>, 2> out{std::vector<float>(1024), std::vector<float>(1024)};

  std::array<int, kMaxChannels> muted{};
  std::vector<int16_t> tap = std::vector<int16_t>(static_cast<size_t>(kMaxChannels) * kTapFrames);

  VisualPublisher visuals;

  static DspState& instance() {
    static DspState s;
    return s;
  }
};

/** The stream's sample at a place in the song: through the loop as often as it goes round, or -1 past the end of one that does not. */
int64_t fileSample(const gc::Stream& s, int64_t song) {
  if (song < s.samples) return song;
  if (!s.loops) return -1;
  return s.loopStart + (song - s.loopStart) % (s.samples - s.loopStart);
}

/** Which time through a place in the song is: 0 the first, 1 the second time round the loop, 2 any after. */
int timeRound(const gc::Stream& s, int64_t song) {
  if (!s.loops || song < s.samples) return 0;
  return song - s.samples < s.samples - s.loopStart ? 1 : 2;
}

/** The window filled up to the song's sample [last], reading on. Caller holds the mutex. */
void fillTo(DspState& state, int64_t last) {
  const gc::Stream& s = state.song.stream;
  while (state.songNext <= last) {
    int16_t frame[kMaxChannels] = {};
    const int64_t at = fileSample(s, state.songNext);
    if (at >= 0) {
      if (state.reader.next != at) {
        const int round = timeRound(s, state.songNext);
        const Reader& loopStart = round == 0 ? state.atLoop : round == 1 ? state.atLoopAgain : state.atLoopLater;
        if (s.loops && at == s.loopStart) {
          // Round the loop: from where it starts, ready - not decoded up to
          // again from a checkpoint, which for a loop of a few samples would
          // be every few samples.
          state.reader = loopStart;
        } else if (round > 0 && at < state.settles) {
          // Where going round still differs from the first time: on from the loop's start.
          state.reader = loopStart;
          state.reader.skipTo(state.song, at);
        } else {
          state.reader.seek(state.song, at);
        }
      }
      state.reader.read(state.song, frame);
    }
    const int slot = static_cast<int>(state.songNext & (kWindow - 1));
    for (int c = 0; c < s.channels; c++) state.window[c][slot] = frame[c];
    state.songNext++;
  }
}

/** The song's place, in its own samples, for output frame [frame]: whole, and how far on to the next. */
void place(const DspState& state, int64_t frame, int64_t& whole, double& fraction) {
  const int64_t scaled = frame * state.song.stream.rate;
  whole = scaled / state.sampleRate;
  fraction = static_cast<double>(scaled % state.sampleRate) / state.sampleRate;
}

/** Ready to hand out output frame [frame]: the window emptied and read up to just before it. Caller holds the mutex. */
void moveTo(DspState& state, int64_t frame) {
  state.rendered = frame;
  int64_t whole = 0;
  double fraction = 0.0;
  place(state, frame, whole, fraction);
  std::memset(state.window, 0, sizeof(state.window));
  state.songNext = std::max<int64_t>(0, whole - (SincKernel::kTaps / 2 - 1));
  // Placed again before it reads: it may be at the right sample of the wrong time round.
  state.reader.next = -1;
  state.ended = false;
}

/** Going round the stream's own loop for ever: repeat on, a loop in the stream, and no length of the listener's own. */
bool forever(const DspState& state) { return state.loop && state.song.stream.loops && state.length.overrideMs <= 0; }

/**
 * Back from going round for ever, which counts its frames on past the end
 * of the first time through: the same place in the stream, as a frame of
 * that first time, so the song plays on from there to its end and fades.
 * Caller holds the mutex.
 */
void foldLocked(DspState& state) {
  if (!state.loaded || !forever(state)) return;
  const gc::Stream& s = state.song.stream;
  int64_t whole = 0;
  double fraction = 0.0;
  place(state, state.rendered, whole, fraction);
  if (whole < s.samples) return;
  const int64_t at = fileSample(s, whole);
  // The first output frame at or after that place, as close as the rates allow.
  moveTo(state, (at * state.sampleRate + s.rate - 1) / s.rate);
}

/** Where a song played through once ends, in output frames; none while it repeats and loops by itself. */
int64_t endFrame(const DspState& state) { return state.length.endFrame(state.sampleRate); }

/**
 * Up to [frames] frames from where the song has got to; fewer when it ends.
 * One channel is both sides; two are the left and the right; more are
 * pairs, the lefts mixed into the left and the rights into the right.
 * Caller holds the mutex.
 */
int produceLocked(DspState& state, float* left, float* right, int frames) {
  const gc::Stream& s = state.song.stream;
  const bool forever = ::forever(state);
  const float width = g_stereoPercent.load(std::memory_order_relaxed) / 100.0f;
  const int pairs = (s.channels + 1) / 2;
  float keep[kMaxChannels];
  for (int c = 0; c < kMaxChannels; c++) keep[c] = state.muted[static_cast<size_t>(c)] ? 0.0f : 1.0f / pairs;
  float weights[SincKernel::kTaps];
  int done = 0;
  while (done < frames) {
    if (!forever && state.rendered >= endFrame(state)) {
      if (!state.loop) break;
      // Round again from the top.
      moveTo(state, 0);
    }
    const int64_t k = state.rendered;
    int64_t whole = 0;
    double fraction = 0.0;
    place(state, k, whole, fraction);
    fillTo(state, whole + SincKernel::kTaps / 2);
    state.kernel.weights(fraction, weights);
    const int64_t first = whole - (SincKernel::kTaps / 2 - 1);
    const uint32_t at = static_cast<uint32_t>(k) & (kTapFrames - 1);
    float sides[2] = {0.0f, 0.0f};
    for (int c = 0; c < s.channels; c++) {
      float value = 0.0f;
      for (int t = 0; t < SincKernel::kTaps; t++) {
        value += weights[t] * state.window[c][static_cast<int>((first + t) & (kWindow - 1))];
      }
      state.tap[static_cast<size_t>(c) * kTapFrames + at] =
          static_cast<int16_t>(std::clamp(std::lround(value), -32768L, 32767L));
      if (s.channels == 1) {
        sides[0] = sides[1] = value * keep[0];
      } else {
        sides[c & 1] += value * keep[c];
      }
    }
    const float gain = (forever ? 1.0f : state.length.gainAt(k, state.sampleRate)) / 32768.0f;
    sides[0] *= gain;
    sides[1] *= gain;
    psf::spread(sides[0], sides[1], width);
    left[done] = sides[0];
    right[done] = sides[1];
    state.rendered++;
    done++;
  }
  return done;
}

void publishVisuals(DspState& state, int64_t frameStamp) {
  VisualFrame& frame = state.visuals.back();
  frame.clear();
  const gc::Stream& s = state.song.stream;
  const int channels = s.channels;
  frame.scalars[kScalarNumChannels] = channels;
  // The place in the song; going round its loop for ever, the place in the
  // stream, through the loop as often as it has gone round.
  int64_t at = state.rendered * 1000 / std::max(1, state.sampleRate);
  if (forever(state)) {
    int64_t whole = 0;
    double fraction = 0.0;
    place(state, state.rendered, whole, fraction);
    at = std::max<int64_t>(0, fileSample(s, whole)) * 1000 / std::max(1, s.rate);
  }
  frame.scalars[kScalarTimeMs] = static_cast<int32_t>(at);
  frame.scalars[kScalarTotalTimeMs] = state.length.totalMs();
  frame.scalars[kScalarGlobalVolume] = kGlobalVolumeUnity;

  const uint32_t heard = static_cast<uint32_t>(state.rendered);
  const uint32_t step = static_cast<uint32_t>(std::max(1, state.sampleRate / kLookHz));
  const int levelLooks = kLookHz * kLevelWindowMs / 1000;
  int sounding = 0;
  for (int c = 0; c < channels; c++) {
    int32_t* f = frame.channels.data() + c * kChannelFields;
    const bool muted = state.muted[static_cast<size_t>(c)] != 0;
    const int16_t* ring = state.tap.data() + static_cast<size_t>(c) * kTapFrames;
    const auto look = [&](uint32_t back) { return ring[(heard - 1 - back * step) & (kTapFrames - 1)] / 32768.0f; };

    float loudest = 0.0f;
    for (int i = 0; i < levelLooks; i++) loudest = std::max(loudest, std::fabs(look(static_cast<uint32_t>(i))));
    f[kChPeak] = muted ? 0 : std::clamp(static_cast<int32_t>(loudest * kPeakScale), 0, kPeakScale);
    if (f[kChPeak] > 0) sounding++;
    f[kChFinalVolume] = f[kChPeak] * 64 / kPeakScale;
    f[kChVolume] = muted ? 0 : 64;
    f[kChPan] = channels == 1 ? 128 : ((c & 1) == 0 ? 0 : 255);
    f[kChInstrument] = c;
    f[kChSample] = 0;

    if (muted) continue;
    float span = 1.0f / 64.0f;
    for (int x = 0; x < kScopeColumns; x++) span = std::max(span, std::fabs(look(static_cast<uint32_t>(x))));
    int8_t* out = frame.scopes.data() + c * kScopeColumns;
    for (int x = 0; x < kScopeColumns; x++) {
      const float sample = look(static_cast<uint32_t>(kScopeColumns - 1 - x));
      out[x] = static_cast<int8_t>(std::clamp(static_cast<int>(sample / span * 127.0f), -127, 127));
    }
  }
  frame.scalars[kScalarPlayingChannels] = sounding;
  state.visuals.publish(frameStamp);
}

void releaseLocked(DspState& state) {
  state.loaded = false;
  state.song = Song();
  state.reader = Reader();
  state.atLoop = Reader();
  state.atLoopAgain = Reader();
  state.atLoopLater = Reader();
  state.visuals.reset();
}

/** The stream read, and decoded once through for where to start from again anywhere. */
bool parseLocked(DspState& state, const uint8_t* data, size_t size, const std::string& name) {
  Song song;
  if (!gc::parse(data, size, name, song.stream)) return false;
  const gc::Stream& s = song.stream;
  if (s.frameSamples > kMaxFrameSamples) return false;
  song.checkpoints.reserve(static_cast<size_t>(s.frames / kCheckpointFrames + 1));
  std::array<gc::Hist, kMaxChannels> hist = s.start;
  std::array<size_t, kMaxChannels> run{};
  int16_t scratch[kMaxFrameSamples];
  for (int64_t f = 0; f < s.frames; f++) {
    if (f % kCheckpointFrames == 0) song.checkpoints.push_back(hist);
    for (int c = 0; c < s.channels; c++) {
      gc::decodeFrame(s, c, f, hist[static_cast<size_t>(c)], scratch, run[static_cast<size_t>(c)]);
    }
  }
  if (song.checkpoints.empty()) song.checkpoints.push_back(hist);
  state.song = std::move(song);
  return true;
}

/** The history a reader at the second last of the stream's samples leaves at its end: the last two. */
std::array<gc::Hist, kMaxChannels> historyAtEnd(const Song& song, Reader reader) {
  int16_t before[kMaxChannels] = {};
  int16_t last[kMaxChannels] = {};
  reader.read(song, before);
  reader.read(song, last);
  std::array<gc::Hist, kMaxChannels> end{};
  for (int c = 0; c < song.stream.channels; c++) end[static_cast<size_t>(c)] = {last[c], before[c]};
  return end;
}

/**
 * The loop's start the second time round and after; and where the second
 * time comes to play as the first did. Only a stream whose loop starts with
 * the history its end left differs: the first time round, the loop starts
 * with the samples before it, then with the last two of the time before.
 * The third time onward starts as the second unless the loop is so short
 * the difference never dies away, when it starts with the second's end -
 * and later times are taken to be the third. Caller holds the mutex.
 */
void prepareRoundsLocked(DspState& state) {
  const Song& song = state.song;
  const gc::Stream& s = song.stream;
  state.atLoopAgain = state.atLoop;
  state.atLoopLater = state.atLoop;
  state.settles = s.loopStart;
  if (!s.loops || !s.loopKeepsHistory || s.samples - s.loopStart < 2) return;
  Reader once;
  once.seek(song, s.samples - 2);
  const std::array<gc::Hist, kMaxChannels> endOnce = historyAtEnd(song, once);
  state.atLoopAgain.restartLoop(song, endOnce);

  // Frame by frame from the loop's first, the second time against the first,
  // until each channel's history after a frame is the same: the frames after
  // it then decode alike.
  const int64_t first = s.loopStart / s.frameSamples;
  const int64_t last = (s.samples - 1) / s.frameSamples;
  std::array<gc::Hist, kMaxChannels> before = state.atLoop.hist;
  std::array<gc::Hist, kMaxChannels> again = state.atLoopAgain.hist;
  std::array<size_t, kMaxChannels> beforeRun = state.atLoop.run;
  std::array<size_t, kMaxChannels> againRun = state.atLoopAgain.run;
  int16_t scratch[kMaxFrameSamples];
  for (int c = 0; c < s.channels; c++) {
    gc::decodeFrame(s, c, first, before[static_cast<size_t>(c)], scratch, beforeRun[static_cast<size_t>(c)]);
  }
  const auto alike = [&] {
    for (int c = 0; c < s.channels; c++) {
      const gc::Hist& a = before[static_cast<size_t>(c)];
      const gc::Hist& b = again[static_cast<size_t>(c)];
      if (a.h1 != b.h1 || a.h2 != b.h2) return false;
    }
    return true;
  };
  int64_t f = first;
  while (!alike() && f < last) {
    f++;
    for (int c = 0; c < s.channels; c++) {
      gc::decodeFrame(s, c, f, before[static_cast<size_t>(c)], scratch, beforeRun[static_cast<size_t>(c)]);
      gc::decodeFrame(s, c, f, again[static_cast<size_t>(c)], scratch, againRun[static_cast<size_t>(c)]);
    }
  }
  state.settles = alike() ? std::min(s.samples, (f + 1) * s.frameSamples) : s.samples;

  std::array<gc::Hist, kMaxChannels> endAgain = endOnce;
  if (state.settles > s.samples - 2) {
    Reader second = state.atLoopAgain;
    second.skipTo(song, s.samples - 2);
    endAgain = historyAtEnd(song, second);
  }
  state.atLoopLater.restartLoop(song, endAgain);
}

std::string describeLocked(const gc::Stream& s) {
  char layout[32];
  if (s.channels == 1) {
    std::snprintf(layout, sizeof layout, "mono");
  } else if (s.channels == 2) {
    std::snprintf(layout, sizeof layout, "stereo");
  } else {
    std::snprintf(layout, sizeof layout, "%d channels", s.channels);
  }
  char text[256];
  std::snprintf(text, sizeof text, "Nintendo GameCube\n%s, %d Hz, %s%s", s.detail.c_str(), s.rate, layout,
                s.loops ? ", looped" : "");
  return text;
}

void seekLocked(DspState& state, int ms, bool keepVisuals) {
  if (!state.loaded) return;
  const int total = state.length.totalMs();
  moveTo(state, psf::msToFrames(std::clamp(ms, 0, std::max(0, total - 1)), state.sampleRate));
  state.stage.reset();
  if (!keepVisuals) state.visuals.reset();
}

}  // namespace

const char* dsp_decoder_probe(const uint8_t* data, size_t size, const char* name) {
  // The type, kept for the caller to copy; per thread, as indexing probes off the audio thread.
  thread_local std::string type;
  return gc::probe(data, size, name ? name : "", &type) ? type.c_str() : nullptr;
}

bool dsp_decoder_load(const uint8_t* data, size_t size, const char* name, int sampleRate) {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
  bool parsed = false;
  try {
    parsed = parseLocked(state, data, size, name ? name : "");
  } catch (const std::bad_alloc&) {
    LOG_INFO("no memory for the file");
  }
  if (!parsed) {
    releaseLocked(state);
    return false;
  }
  if (sampleRate > 0) state.sampleRate = sampleRate;
  const gc::Stream& s = state.song.stream;
  state.kernel.design(s.rate, state.sampleRate);
  state.stage.configure(state.sampleRate);
  state.stage.setPitch(state.pitchSemitones);
  state.stage.setTempo(state.tempoFactor);

  state.type = s.type;
  state.comment = describeLocked(s);
  state.length = psf::SongLength();
  state.length.lengthMs = static_cast<int>(s.samples * 1000 / s.rate);
  state.length.fadeMs = s.loops ? kFadeMs : 0;
  state.length.known = true;
  state.muted.fill(0);
  std::fill(state.tap.begin(), state.tap.end(), int16_t{0});
  state.reader = Reader();
  state.reader.seek(state.song, 0);
  state.atLoop = Reader();
  if (s.loops) state.atLoop.seek(state.song, s.loopStart);
  prepareRoundsLocked(state);
  moveTo(state, 0);
  state.visuals.reset();
  state.loaded = true;
  LOG_INFO("loaded %s: %d Hz, %d channels, %lld samples%s", s.type.c_str(), s.rate, s.channels,
           static_cast<long long>(s.samples), s.loops ? ", looped" : "");
  return true;
}

void dsp_decoder_release() {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
}

bool dsp_decoder_loaded() { return DspState::instance().loaded; }
const char* dsp_decoder_type() { return DspState::instance().type.c_str(); }
const char* dsp_decoder_title() { return ""; }
const char* dsp_decoder_comment() { return DspState::instance().comment.c_str(); }

void dsp_decoder_prepare_start(int rate) {
  dsp_decoder_set_sample_rate(rate);
  DspState::instance().visuals.reset();
}

void dsp_decoder_set_sample_rate(int rate) {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate) return;
  foldLocked(state);
  const int atMs = static_cast<int>(state.rendered * 1000 / std::max(1, state.sampleRate));
  state.sampleRate = rate;
  state.stage.configure(rate);
  if (!state.loaded) return;
  state.kernel.design(state.song.stream.rate, rate);
  seekLocked(state, atMs, true);
}

int dsp_decoder_duration_ms() { return DspState::instance().length.totalMs(); }
bool dsp_decoder_duration_known() { return true; }

int dsp_decoder_position_ms() {
  DspState& state = DspState::instance();
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : 0;
}

void dsp_decoder_seek_ms(int ms) {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, false);
}

bool dsp_decoder_ended() { return DspState::instance().ended; }

void dsp_decoder_set_loop(bool loop) {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!loop) foldLocked(state);
  state.loop = loop;
}

double dsp_decoder_render_position_ms() {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return (state.rendered - state.stage.latencyFrames()) * 1000.0 / std::max(1, state.sampleRate);
}

void dsp_decoder_loop_seek_ms(int ms) {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, true);
}

void dsp_decoder_set_duration_override_ms(int ms) {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (ms > 0) foldLocked(state);
  state.length.overrideMs = ms > 0 ? ms : 0;
}

void dsp_decoder_set_pitch(double semitones) {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitchSemitones = std::clamp(semitones, -12.0, 12.0);
  state.stage.setPitch(state.pitchSemitones);
}

void dsp_decoder_set_tempo(double percent) {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
  state.stage.setTempo(state.tempoFactor);
}

void dsp_decoder_set_stereo(int percent) { g_stereoPercent.store(std::clamp(percent, 0, 200), std::memory_order_relaxed); }

int dsp_decoder_channels() {
  const DspState& state = DspState::instance();
  return state.loaded ? state.song.stream.channels : 0;
}

const char* dsp_decoder_channel_name(int index) {
  const DspState& state = DspState::instance();
  const int channels = state.song.stream.channels;
  if (index < 0 || index >= channels) return "";
  if (channels == 1) return kMonoName;
  if (channels == 2) return kStereoNames[index];
  return kPairNames[index];
}

void dsp_decoder_mute(int channel, int muted) {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (channel < 0 || channel >= kMaxChannels) return;
  state.muted[static_cast<size_t>(channel)] = muted ? 1 : 0;
}

int dsp_decoder_muted(int channel) {
  const DspState& state = DspState::instance();
  return channel >= 0 && channel < kMaxChannels ? state.muted[static_cast<size_t>(channel)] : 0;
}

bool dsp_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  return DspState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int dsp_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  DspState& state = DspState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  if (!state.loaded || state.ended) {
    std::memset(audioData, 0, static_cast<size_t>(numFrames) * numChannels * bytesPerSample);
    return state.loaded ? 1 : 0;
  }

  const int64_t base = get_frames_written();
  const ShiftStage::Source source = [&state](float* left, float* right, int frames) {
    return produceLocked(state, left, right, frames);
  };
  const auto publish = [&state, base](int32_t written) {
    const double delay = state.stage.latencyFrames() / std::max(state.tempoFactor, 0.1);
    publishVisuals(state, base + written + static_cast<int64_t>(delay));
  };
  if (psf::renderSong(state.stage, state.out, state.sampleRate, source, publish, audioData, numFrames, numChannels,
                      bytesPerSample)) {
    state.ended = true;
    return 1;
  }
  return 0;
}
