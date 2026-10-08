//
// GSF decoder. See decoder-gsf.h.
//
#include "decoder-gsf.h"
#include "psf-song.h"
#include "seek-ticket.h"
#include "visual-state.h"
#include "visual-publisher.h"
#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/gba/core.h>
#include <mgba/internal/gba/gba.h>
#include <mgba-util/vfs.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdarg>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
constexpr const char* TAG = "Exilum GSF";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

constexpr int kVersion = 0x22;
constexpr int kChannels = 6;
const char* const kChannelNames[kChannels] = {
    "Square 1", "Square 2", "Wave", "Noise", "Direct Sound A", "Direct Sound B"};
/**
 * How often the channels are looked at: once each time the console mixes, a
 * rate of its own whatever the resolution the game set.
 */
constexpr int kTapRate = 16384;
/** Looks kept of each channel, for its trace and its level: 125 ms. */
constexpr int kHistory = 2048;
constexpr int kLevelWindowMs = 30;
/** A channel at its loudest alone, as a share of full scale - see tapChannels. */
constexpr float kFullScale = 0.75f;
/** What mGBA may hold between two reads; a frame is under a thousand. */
constexpr size_t kAudioBuffer = 0x2000;
/** Where a program that runs from RAM rather than the cartridge starts. */
constexpr uint32_t kMultibootEntry = 0x02000000;
/** The largest cartridge there is. */
constexpr size_t kMaxRomBytes = 32u << 20;
/** Frames run without a sample coming out, before the program is taken to have stopped. */
constexpr int kSilentFrameLimit = 600;
/** Where the low-pass filter starts to take the top off, and how gently: Butterworth. */
constexpr double kLowpassHz = 8000.0;
constexpr double kLowpassQ = 0.70710678;
/** How fast a pan setting moves to a new value, in percent a sample at the console's usual 32 kHz. */
constexpr float kPanSlew = 0.25f;

std::atomic<int> g_stereoPercent{100};
std::atomic<bool> g_lowpass{false};
std::atomic<int> g_defaultLengthMs{150000};
/** Each channel's pan from where its game puts it, -100 to 100. */
std::atomic<int> g_pan[kChannels] = {};

struct GsfState;

/** What mGBA is handed to report each sample it mixes; the state is reached through it. */
struct Tap {
  mAVStream stream;
  GsfState* owner;
};

/** What mGBA is handed to mix a pan into its output; the state is reached through it. */
struct PanMixer {
  GBAAudioMixer mixer;
  GsfState* owner;
};

struct GsfState {
  std::mutex mutex;
  mCore* core = nullptr;
  Tap tap{};
  /** The program the file and its libraries make, which mGBA runs from. */
  std::vector<uint8_t> image;
  bool multiboot = false;

  bool loaded = false;
  bool ended = false;
  bool loop = false;
  int sampleRate = 48000;
  std::string title;
  std::string comment;
  psf::SongLength length;

  /** Frames handed out since the top of the song. */
  int64_t rendered = 0;
  /** Frames mGBA has made and not yet handed out, interleaved. */
  std::vector<int16_t> pending;
  size_t pendingAt = 0;
  bool skipping = false;

  /** Pitch and tempo, which the console cannot change without each other. */
  ShiftStage stage;
  double pitchSemitones = 0.0;
  double tempoFactor = 1.0;
  std::array<std::vector<float>, 2> out{std::vector<float>(1024), std::vector<float>(1024)};

  /** The low-pass filter: its coefficients at the rate, and each side's two delays. */
  float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
  std::array<std::array<float, 2>, 2> lowpass{};

  std::array<int, kChannels> muted{};
  std::array<std::array<float, kHistory>, kChannels> history{};
  int historyWrite = 0;
  int tapPhase = 0;

  /**
   * The pan: what each channel's setting has moved to so far, which sample
   * of the group mGBA is on, and what has been added to each side so far.
   */
  PanMixer pan{};
  std::array<float, kChannels> panNow{};
  int panSub = 0;
  int panSeen = 0;
  std::array<int, 2> panApplied{};

  VisualPublisher visuals;

  static GsfState& instance() {
    static GsfState s;
    return s;
  }
};

uint32_t readLe32(const uint8_t* p) {
  return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

/** mGBA talks about everything it does; none of it is for the listener. */
void quiet(mLogger*, int, mLogLevel, const char*, va_list) {}
mLogger g_quietLogger{quiet, nullptr};

GBA* boardOf(const GsfState& state) { return static_cast<GBA*>(state.core->board); }

/**
 * Where a channel sits between the sides given what its game routes it to:
 * -1 the left alone, 0 both, 1 the right alone, moved by the pan and held to
 * the ends. The console has no more places than those three, so the gain each
 * side gets is 1 up to the middle and falls away past it.
 */
float panPosition(bool left, bool right, float percent) {
  const float game = left == right ? 0.0f : left ? -1.0f : 1.0f;
  return std::clamp(game + percent / 100.0f, -1.0f, 1.0f);
}

/**
 * Mixed in by mGBA's own sound mixer hook, which it calls for every sample it
 * makes before the Direct Sound pair is added. What the console makes has each
 * channel routed to a side or not; a pan is that, done over: the difference
 * between where the channel is put and where the game had it goes into the
 * same two streams the console's output does, from the same moment. A pan of
 * 0 changes nothing at all.
 */
void panStep(GBAAudioMixer* mixer) {
  GsfState& state = *reinterpret_cast<PanMixer*>(mixer)->owner;
  GBAAudio& audio = boardOf(state)->audio;

  // Which of the group's samples this is: mGBA holds where the group began
  // until it has made the lot.
  const int perGroup = 2 << GBARegisterSOUNDBIASGetResolution(audio.soundbias);
  if (audio.sampleIndex != state.panSeen) {
    state.panSeen = audio.sampleIndex;
    state.panSub = audio.sampleIndex;
  }
  const int at = state.panSub;
  state.panSub = at + 1 >= perGroup ? 0 : at + 1;

  const float slew = kPanSlew * static_cast<float>(audio.sampleInterval) / 512.0f;
  bool moved = state.panApplied[0] != 0 || state.panApplied[1] != 0;
  for (int c = 0; c < kChannels; c++) {
    const float target = static_cast<float>(g_pan[c].load(std::memory_order_relaxed));
    float& now = state.panNow[c];
    const float gap = target - now;
    now = std::fabs(gap) <= slew ? target : now + std::copysign(slew, gap);
    moved = moved || now != 0.0f;
  }
  if (!moved) return;

  const GBAudio& psg = audio.psg;
  const int psgShift = std::max(0, 4 - audio.volume);
  const int8_t psgSample[4] = {psg.ch1.sample, psg.ch2.sample, psg.ch3.sample, psg.ch4.sample};
  const bool left[kChannels] = {psg.ch1Left, psg.ch2Left, psg.ch3Left, psg.ch4Left, audio.chALeft, audio.chBLeft};
  const bool right[kChannels] = {psg.ch1Right, psg.ch2Right, psg.ch3Right, psg.ch4Right, audio.chARight,
                                 audio.chBRight};
  const bool off[kChannels] = {psg.forceDisableCh[0], psg.forceDisableCh[1], psg.forceDisableCh[2],
                               psg.forceDisableCh[3], audio.forceDisableChA, audio.forceDisableChB};
  const int fifoSample[2] = {audio.chA.samples[at], audio.chB.samples[at]};
  const bool fifoFull[2] = {audio.volumeChA, audio.volumeChB};

  float extra[2] = {0.0f, 0.0f};
  for (int c = 0; c < kChannels; c++) {
    if (state.panNow[c] == 0.0f || off[c] || (!left[c] && !right[c])) continue;
    const float position = panPosition(left[c], right[c], state.panNow[c]);
    const float gain[2] = {std::min(1.0f, 1.0f - position), std::min(1.0f, 1.0f + position)};
    const bool routed[2] = {left[c], right[c]};
    for (int side = 0; side < 2; side++) {
      const int level = side == 0 ? psg.volumeLeft : psg.volumeRight;
      // The channel as that side would hear it, routed there or not.
      const int amount = c < 4 ? ((psgSample[c] * 8 * (1 + level)) >> psgShift)
                               : (fifoSample[c - 4] * 4) >> (fifoFull[c - 4] ? 0 : 1);
      extra[side] += static_cast<float>(amount) * (gain[side] - (routed[side] ? 1.0f : 0.0f));
    }
  }

  // Scaled as the console scales what it makes, by the master level.
  const int when = audio.clock + at * audio.sampleInterval;
  blip_t* sides[2] = {audio.psg.left, audio.psg.right};
  for (int side = 0; side < 2; side++) {
    const int applied =
        static_cast<int>(std::lround(extra[side] * static_cast<float>(audio.masterVolume) * 3.0f / 16.0f));
    if (applied == state.panApplied[side]) continue;
    blip_add_delta(sides[side], when, applied - state.panApplied[side]);
    state.panApplied[side] = applied;
  }
}

bool panEngage(GBAAudioMixer*, uint32_t) { return false; }
void panVblank(GBAAudioMixer*) {}

/** Puts the pan in the console's way. What it hooks stays through a reset. */
void installPanLocked(GsfState& state) {
  state.pan = PanMixer{};
  state.pan.owner = &state;
  state.pan.mixer.p = &boardOf(state)->audio;
  state.pan.mixer.engage = panEngage;
  state.pan.mixer.vblank = panVblank;
  state.pan.mixer.step = panStep;
  boardOf(state)->audio.mixer = &state.pan.mixer;
  for (int c = 0; c < kChannels; c++) state.panNow[c] = static_cast<float>(g_pan[c].load(std::memory_order_relaxed));
}

/**
 * Each channel's part of the mix, as the console weighs it: the Game Boy's
 * four at the PSG volume and the master levels, the Direct Sound pair at
 * their own, all of it three sixteenths of the bias scale - what the output
 * comes to at mGBA's full volume.
 */
void tapChannels(mAVStream* stream, int16_t, int16_t) {
  GsfState& state = *reinterpret_cast<Tap*>(stream)->owner;
  if (state.skipping) return;
  const GBAAudio& audio = boardOf(state)->audio;
  // Called for each sample mixed, 2 << resolution of them a look.
  const int perLook = 2 << GBARegisterSOUNDBIASGetResolution(audio.soundbias);
  if (++state.tapPhase < perLook) return;
  state.tapPhase = 0;

  const GBAudio& psg = audio.psg;
  const int psgShift = std::max(0, 4 - audio.volume);
  const int psgGain = 1 + std::max(psg.volumeLeft, psg.volumeRight);
  const auto fromPsg = [&](int sample, bool routed) { return routed ? (sample * 8 * psgGain) >> psgShift : 0; };
  const auto fromFifo = [](int sample, bool routed, bool full) { return routed ? (sample * 4) >> (full ? 0 : 1) : 0; };
  const int raw[kChannels] = {
      fromPsg(psg.ch1.sample, psg.ch1Left || psg.ch1Right),
      fromPsg(psg.ch2.sample, psg.ch2Left || psg.ch2Right),
      fromPsg(psg.ch3.sample, psg.ch3Left || psg.ch3Right),
      fromPsg(psg.ch4.sample, psg.ch4Left || psg.ch4Right),
      fromFifo(audio.chA.samples[0], audio.chALeft || audio.chARight, audio.volumeChA),
      fromFifo(audio.chB.samples[0], audio.chBLeft || audio.chBRight, audio.volumeChB),
  };
  for (int c = 0; c < kChannels; c++) {
    state.history[c][state.historyWrite] = raw[c] * 48 / 32768.0f;
  }
  state.historyWrite = (state.historyWrite + 1) % kHistory;
}

/** Builds the program from the file and its libraries, in the order they overlay each other. */
bool buildImage(const psf::File& file, std::vector<uint8_t>& image, uint32_t& entry) {
  image.clear();
  bool first = true;
  return psf::loadWithLibraries(file, [&](const psf::File& part) {
    const std::vector<uint8_t>& program = part.program;
    // A file of tags alone adds nothing.
    if (program.size() < 12) return true;
    const uint32_t partEntry = readLe32(program.data());
    const size_t at = readLe32(program.data() + 4) & 0x01FFFFFF;
    const size_t size = std::min<size_t>(readLe32(program.data() + 8), program.size() - 12);
    // The first program laid down - the deepest library - says where to start.
    if (first) {
      entry = partEntry;
      first = false;
    }
    if (at + size > kMaxRomBytes) return false;
    if (image.size() < at + size) image.resize(at + size);
    std::memcpy(image.data() + at, program.data() + 12, size);
    return true;
  });
}

/** The filter's coefficients for the rate: the cookbook's low-pass. */
void designLowpassLocked(GsfState& state) {
  const double w0 = 2.0 * M_PI * std::min(kLowpassHz, state.sampleRate * 0.45) / state.sampleRate;
  const double alpha = std::sin(w0) / (2.0 * kLowpassQ);
  const double a0 = 1.0 + alpha;
  const double cosine = std::cos(w0);
  state.b0 = static_cast<float>((1.0 - cosine) / 2.0 / a0);
  state.b1 = static_cast<float>((1.0 - cosine) / a0);
  state.b2 = state.b0;
  state.a1 = static_cast<float>(-2.0 * cosine / a0);
  state.a2 = static_cast<float>((1.0 - alpha) / a0);
  for (auto& side : state.lowpass) side.fill(0.0f);
}

void applyRatesLocked(GsfState& state) {
  designLowpassLocked(state);
  const int32_t clock = state.core->frequency(state.core);
  blip_set_rates(state.core->getAudioChannel(state.core, 0), clock, state.sampleRate);
  blip_set_rates(state.core->getAudioChannel(state.core, 1), clock, state.sampleRate);
}

void applyMutesLocked(GsfState& state) {
  for (int c = 0; c < kChannels; c++) state.core->enableAudioChannel(state.core, c, state.muted[c] == 0);
}

size_t pendingFrames(const GsfState& state) { return state.pending.size() / 2 - state.pendingAt; }

/** Runs one frame of the console and keeps the samples it made. False when it made none. */
bool runFrameLocked(GsfState& state) {
  state.core->runFrame(state.core);
  blip_t* left = state.core->getAudioChannel(state.core, 0);
  blip_t* right = state.core->getAudioChannel(state.core, 1);
  const int available = blip_samples_avail(left);
  if (state.pendingAt > 0) {
    state.pending.erase(state.pending.begin(), state.pending.begin() + static_cast<ptrdiff_t>(state.pendingAt * 2));
    state.pendingAt = 0;
  }
  if (available <= 0) return false;
  const size_t at = state.pending.size();
  state.pending.resize(at + static_cast<size_t>(available) * 2);
  blip_read_samples(left, state.pending.data() + at, available, 1);
  blip_read_samples(right, state.pending.data() + at + 1, available, 1);
  return true;
}

/** Starts the song again from the top. Caller holds the mutex. */
void restartLocked(GsfState& state) {
  state.core->reset(state.core);
  // Looked for, and skipped once found: the loop a sound driver waits for
  // the screen in, which is most of what it does and most of the cost of
  // running it at speed to a seek.
  boardOf(state)->idleOptimization = IDLE_LOOP_DETECT;
  applyMutesLocked(state);
  blip_clear(state.core->getAudioChannel(state.core, 0));
  blip_clear(state.core->getAudioChannel(state.core, 1));
  state.pending.clear();
  state.pendingAt = 0;
  state.rendered = 0;
  state.ended = false;
  state.tapPhase = 0;
  // What was added to the streams went with them when they were cleared.
  state.panSub = 0;
  state.panSeen = 0;
  state.panApplied.fill(0);
  for (auto& side : state.lowpass) side.fill(0.0f);
}

void clearHistory(GsfState& state) {
  for (auto& h : state.history) h.fill(0.0f);
  state.historyWrite = 0;
}

/**
 * The console has no way to start part-way, so a seek back starts it again
 * and every seek runs it up to the place, as fast as it goes, without
 * looking at the channels on the way.
 */
void seekLocked(GsfState& state, int ms, bool keepVisuals, bool mayGiveUp = false) {
  if (!state.loaded) return;
  const int total = state.length.totalMs();
  const int64_t target = psf::msToFrames(std::clamp(ms, 0, std::max(0, total - 1)), state.sampleRate);
  if (target < state.rendered) restartLocked(state);
  state.skipping = true;
  int silent = 0;
  while (state.rendered < target) {
    // The listener's own seek, overtaken: the song is left where it has got to.
    if (mayGiveUp && engine_seekOvertaken()) break;
    const size_t skip = static_cast<size_t>(
        std::min<int64_t>(static_cast<int64_t>(pendingFrames(state)), target - state.rendered));
    state.pendingAt += skip;
    state.rendered += static_cast<int64_t>(skip);
    if (state.rendered >= target) break;
    if (runFrameLocked(state)) {
      silent = 0;
    } else if (++silent > kSilentFrameLimit) {
      break;
    }
  }
  state.skipping = false;
  state.ended = false;
  state.stage.reset();
  clearHistory(state);
  if (!keepVisuals) state.visuals.reset();
}

/**
 * Up to [frames] frames of the console's output from where the song has got
 * to, faded as its tags say; fewer when it ends. Caller holds the mutex.
 */
int produceLocked(GsfState& state, float* left, float* right, int frames) {
  int done = 0;
  int silent = 0;
  while (done < frames) {
    const int64_t end = state.length.endFrame(state.sampleRate);
    if (state.rendered >= end || silent > kSilentFrameLimit) {
      if (!state.loop) break;
      restartLocked(state);
      silent = 0;
    }
    if (pendingFrames(state) == 0) {
      silent = runFrameLocked(state) ? 0 : silent + 1;
      continue;
    }
    const int chunk = static_cast<int>(std::min<int64_t>(
        {static_cast<int64_t>(frames - done), static_cast<int64_t>(pendingFrames(state)), end - state.rendered}));
    const int16_t* in = state.pending.data() + state.pendingAt * 2;
    const bool lowpass = g_lowpass.load(std::memory_order_relaxed);
    // The sides apart by the setting: as they are at 100, one at 0.
    const float width = g_stereoPercent.load(std::memory_order_relaxed) / 100.0f;
    for (int i = 0; i < chunk; i++) {
      const float gain = state.length.gainAt(state.rendered + i, state.sampleRate) / 32768.0f;
      float sides[2] = {in[i * 2] * gain, in[i * 2 + 1] * gain};
      if (lowpass) {
        for (int c = 0; c < 2; c++) {
          auto& z = state.lowpass[c];
          const float x = sides[c];
          const float y = state.b0 * x + z[0];
          z[0] = state.b1 * x - state.a1 * y + z[1];
          z[1] = state.b2 * x - state.a2 * y;
          sides[c] = y;
        }
      }
      psf::spread(sides[0], sides[1], width);
      left[done + i] = sides[0];
      right[done + i] = sides[1];
    }
    state.pendingAt += static_cast<size_t>(chunk);
    state.rendered += chunk;
    done += chunk;
  }
  return done;
}

void publishVisuals(GsfState& state, int64_t frameStamp) {
  VisualFrame& frame = state.visuals.back();
  frame.clear();
  frame.scalars[kScalarNumChannels] = kChannels;
  frame.scalars[kScalarTimeMs] = static_cast<int32_t>(state.rendered * 1000 / std::max(1, state.sampleRate));
  frame.scalars[kScalarTotalTimeMs] = state.length.totalMs();
  frame.scalars[kScalarGlobalVolume] = kGlobalVolumeUnity;

  const GBAAudio& audio = boardOf(state)->audio;
  const GBAudio& psg = audio.psg;
  const bool left[kChannels] = {psg.ch1Left, psg.ch2Left, psg.ch3Left, psg.ch4Left, audio.chALeft, audio.chBLeft};
  const bool right[kChannels] = {psg.ch1Right, psg.ch2Right, psg.ch3Right, psg.ch4Right, audio.chARight,
                                 audio.chBRight};
  const bool playing[kChannels] = {psg.playingCh1, psg.playingCh2, psg.playingCh3, psg.playingCh4, true, true};
  // The tone the chip is asked for, where it has one: the squares' and the
  // wave's frequency registers. The noise has no note, and Direct Sound is
  // whatever the game mixed into it.
  double hz[kChannels] = {};
  if (psg.ch1.control.frequency < 2048) hz[0] = 131072.0 / (2048 - psg.ch1.control.frequency);
  if (psg.ch2.control.frequency < 2048) hz[1] = 131072.0 / (2048 - psg.ch2.control.frequency);
  if (psg.ch3.rate < 2048) hz[2] = 65536.0 / (2048 - psg.ch3.rate) / (psg.ch3.size ? 2.0 : 1.0);

  const int levelSpan = kTapRate * kLevelWindowMs / 1000;
  int sounding = 0;
  for (int c = 0; c < kChannels; c++) {
    int32_t* f = frame.channels.data() + c * kChannelFields;
    const bool muted = state.muted[c] != 0;
    const auto& h = state.history[c];
    float loudest = 0.0f;
    for (int i = 1; i <= levelSpan; i++) {
      loudest = std::max(loudest, std::fabs(h[(state.historyWrite - i + kHistory) % kHistory]));
    }
    f[kChPeak] = muted ? 0 : std::clamp(static_cast<int32_t>(loudest / kFullScale * kPeakScale), 0, kPeakScale);
    if (f[kChPeak] > 0) sounding++;
    f[kChFinalVolume] = f[kChPeak] * 64 / kPeakScale;
    int volume = 0;
    if (!muted && playing[c]) {
      switch (c) {
        case 0: volume = psg.ch1.envelope.currentVolume * 64 / 15; break;
        case 1: volume = psg.ch2.envelope.currentVolume * 64 / 15; break;
        case 2: volume = psg.ch3.volume > 0 ? 64 : 0; break;
        case 3: volume = psg.ch4.envelope.currentVolume * 64 / 15; break;
        case 4: volume = audio.volumeChA ? 64 : 32; break;
        default: volume = audio.volumeChB ? 64 : 32; break;
      }
    }
    f[kChVolume] = volume;
    // Where the channel is heard: the game's placing of it, moved by the pan.
    const float heard = panPosition(left[c], right[c], static_cast<float>(g_pan[c].load(std::memory_order_relaxed)));
    f[kChPan] = !left[c] && !right[c] ? 128 : static_cast<int32_t>(std::lround((heard + 1.0f) * 127.5f));
    f[kChInstrument] = c;
    f[kChSample] = c + 1;
    if (!muted && f[kChPeak] > 0 && hz[c] > 0.0) {
      const double midi = 69.0 + 12.0 * std::log2(hz[c] / 440.0);
      f[kChNote] = std::clamp(static_cast<int>(std::lround(midi)) - 11, 1, 127);
      f[kChKey] = f[kChNote] - 1;
      f[kChPeriod] = static_cast<int32_t>(std::lround(hz[c]));
      f[kChPitchBend] = static_cast<int32_t>(std::lround((midi - std::round(midi)) * 100.0));
    }

    if (muted) continue;
    // Scaled so the loudest look in the window reaches the edge, as a
    // tracker's scope does: a single voice is a fraction of full scale.
    float span = kFullScale / 8.0f;
    for (int x = 0; x < kScopeColumns; x++) {
      span = std::max(span, std::fabs(h[(state.historyWrite - kScopeColumns + x + kHistory) % kHistory]));
    }
    int8_t* out = frame.scopes.data() + c * kScopeColumns;
    for (int x = 0; x < kScopeColumns; x++) {
      const float sample = h[(state.historyWrite - kScopeColumns + x + kHistory) % kHistory];
      out[x] = static_cast<int8_t>(std::clamp(static_cast<int>(sample / span * 127.0f), -127, 127));
    }
  }
  frame.scalars[kScalarPlayingChannels] = sounding;
  state.visuals.publish(frameStamp);
}

void releaseLocked(GsfState& state) {
  state.loaded = false;
  if (state.core) {
    // The hook is ours, not the console's to tear down.
    boardOf(state)->audio.mixer = nullptr;
    // Its settings are the caller's to give back - the core's own deinit
    // leaves them - and every tune left their tables behind it.
    mCoreConfigDeinit(&state.core->config);
    state.core->deinit(state.core);
    state.core = nullptr;
  }
  state.image.clear();
  state.image.shrink_to_fit();
  state.pending.clear();
  state.pendingAt = 0;
  state.visuals.reset();
}

}  // namespace

bool gsf_decoder_probe(const uint8_t* data, size_t size) { return psf::probe(data, size, kVersion); }

bool gsf_decoder_load(const uint8_t* data, size_t size, int sampleRate) {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
  if (!psf::probe(data, size, kVersion)) return false;
  psf::File file;
  uint32_t entry = 0;
  // A program and its libraries inflate to tens of megabytes; with no room
  // for them this is a file that cannot be played, as it is for the other
  // consoles, not the end of the app.
  bool read = false;
  try {
    read = psf::parse(data, size, file) && buildImage(file, state.image, entry) && !state.image.empty();
  } catch (const std::bad_alloc&) {
    LOG_INFO("no memory for the program");
  }
  if (!read) {
    LOG_INFO("no program: a library the tags name is missing or unreadable");
    state.image.clear();
    state.image.shrink_to_fit();
    return false;
  }
  if (sampleRate > 0) state.sampleRate = sampleRate;
  state.multiboot = entry == kMultibootEntry;

  mLogSetDefaultLogger(&g_quietLogger);
  mCore* core = GBACoreCreate();
  if (!core) return false;
  if (!core->init(core)) {
    free(core);
    return false;
  }
  mCoreInitConfig(core, nullptr);
  core->opts.skipBios = true;
  core->opts.useBios = false;
  core->setAudioBufferSize(core, kAudioBuffer);
  state.tap = Tap{};
  state.tap.stream.postAudioFrame = tapChannels;
  state.tap.owner = &state;
  core->setAVStream(core, &state.tap.stream);
  state.core = core;

  // mGBA keeps the file it is handed, and closes it when done with it.
  VFile* rom = VFileMemChunk(state.image.data(), state.image.size());
  // Which is a copy of its own, and what the console reads from here on:
  // this one was kept beside it for as long as the song played.
  const size_t imageBytes = state.image.size();
  state.image.clear();
  state.image.shrink_to_fit();
  if (!rom) {
    releaseLocked(state);
    return false;
  }
  const bool loaded = state.multiboot ? GBALoadMB(boardOf(state), rom) : core->loadROM(core, rom);
  if (!loaded) {
    rom->close(rom);
    releaseLocked(state);
    return false;
  }
  installPanLocked(state);
  applyRatesLocked(state);
  state.stage.configure(state.sampleRate);
  state.stage.setPitch(state.pitchSemitones);
  state.stage.setTempo(state.tempoFactor);

  state.title = file.tag("title");
  state.comment = psf::describe(file);
  state.length.fromTags(file, g_defaultLengthMs.load(std::memory_order_relaxed));
  state.muted.fill(0);
  restartLocked(state);
  clearHistory(state);
  state.visuals.reset();
  state.loaded = true;
  LOG_INFO("loaded: %zu bytes of program from 0x%08x, %d ms and %d ms of fade", imageBytes, entry,
           state.length.lengthMs, state.length.fadeMs);
  return true;
}

void gsf_decoder_release() {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
}

bool gsf_decoder_loaded() { return GsfState::instance().loaded; }
const char* gsf_decoder_type() { return "GSF"; }
const char* gsf_decoder_title() { return GsfState::instance().title.c_str(); }
const char* gsf_decoder_comment() { return GsfState::instance().comment.c_str(); }

void gsf_decoder_prepare_start(int rate) {
  gsf_decoder_set_sample_rate(rate);
  GsfState::instance().visuals.reset();
}

void gsf_decoder_set_sample_rate(int rate) {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate) return;
  // The place kept in time, not in frames, which change with the rate.
  const int64_t atMs = state.rendered * 1000 / std::max(1, state.sampleRate);
  state.sampleRate = rate;
  state.stage.configure(rate);
  if (!state.core) return;
  applyRatesLocked(state);
  state.pending.clear();
  state.pendingAt = 0;
  state.rendered = psf::msToFrames(atMs, rate);
}

int gsf_decoder_duration_ms() { return GsfState::instance().length.totalMs(); }
bool gsf_decoder_duration_known() { return GsfState::instance().length.known; }

int gsf_decoder_position_ms() {
  GsfState& state = GsfState::instance();
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : 0;
}

void gsf_decoder_seek_ms(int ms) {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, false, true);
}

bool gsf_decoder_ended() { return GsfState::instance().ended; }

void gsf_decoder_set_loop(bool loop) {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loop = loop;
}

double gsf_decoder_render_position_ms() {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return (state.rendered - state.stage.latencyFrames()) * 1000.0 / std::max(1, state.sampleRate);
}

void gsf_decoder_loop_seek_ms(int ms) {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, true);
}

void gsf_decoder_set_duration_override_ms(int ms) {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.length.overrideMs = ms > 0 ? ms : 0;
}

void gsf_decoder_set_pitch(double semitones) {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitchSemitones = std::clamp(semitones, -12.0, 12.0);
  state.stage.setPitch(state.pitchSemitones);
}

void gsf_decoder_set_tempo(double percent) {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
  state.stage.setTempo(state.tempoFactor);
}

void gsf_decoder_set_stereo(int percent) {
  g_stereoPercent.store(std::clamp(percent, 0, 200), std::memory_order_relaxed);
}

void gsf_decoder_set_lowpass(bool on) { g_lowpass.store(on, std::memory_order_relaxed); }

void gsf_decoder_set_pan(int channel, int value) {
  if (channel < 0 || channel >= kChannels) return;
  g_pan[channel].store(std::clamp(value, -100, 100), std::memory_order_relaxed);
}

void gsf_decoder_set_default_length_ms(int ms) {
  g_defaultLengthMs.store(std::max(1000, ms), std::memory_order_relaxed);
  // To what is playing as well, when its file gave it no length.
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (state.loaded && !state.length.known) {
    state.length.lengthMs = g_defaultLengthMs.load(std::memory_order_relaxed);
  }
}

int gsf_decoder_channels() { return GsfState::instance().loaded ? kChannels : 0; }

const char* gsf_decoder_channel_name(int index) {
  return index >= 0 && index < kChannels ? kChannelNames[index] : "";
}

void gsf_decoder_mute(int channel, int muted) {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (channel < 0 || channel >= kChannels) return;
  state.muted[channel] = muted ? 1 : 0;
  if (state.core) state.core->enableAudioChannel(state.core, channel, !muted);
}

int gsf_decoder_muted(int channel) {
  const GsfState& state = GsfState::instance();
  return channel >= 0 && channel < kChannels ? state.muted[channel] : 0;
}

bool gsf_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  return GsfState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int gsf_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  GsfState& state = GsfState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  if (!state.loaded || state.ended) {
    std::memset(audioData, 0, static_cast<size_t>(numFrames) * numChannels * bytesPerSample);
    return state.loaded ? 1 : 0;
  }

  const int64_t base = get_frames_written();
  const ShiftStage::Source source = [&state](float* left, float* right, int frames) {
    return produceLocked(state, left, right, frames);
  };
  // What the console is doing now is heard once the stage has let it
  // through, and is shown then.
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
