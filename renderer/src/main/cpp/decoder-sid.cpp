//
// C64 SID decoder. See decoder-sid.h.
//
#include "decoder-sid.h"
#include "visual-state.h"
#include "visual-publisher.h"
#include "sidplayfp/sidplayfp.h"
#include "sidplayfp/SidConfig.h"
#include "sidplayfp/SidTune.h"
#include "sidplayfp/SidTuneInfo.h"
#include "residfp.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
constexpr const char* TAG = "Exilum SID";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#define LOG_WARN(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

/** Most chips a tune can address, and so a third of the most channels. */
constexpr int kMaxSids = 3;
constexpr int kVoices = 3;

/** Samples of each voice kept for its trace and its level - at 48 kHz, 42 ms. */
constexpr int kHistory = 2048;

/** How far back a voice's level looks: longer than one cycle of a low note. */
constexpr int kLevelWindowMs = 30;

/**
 * Half the swing of a voice's tap at full envelope, measured over the test
 * tunes. The 8580's DACs reach nearly the whole range; the 6581's do not, so
 * its voices would never fill the bar without their own full scale.
 */
constexpr int32_t kFullSwing8580 = 32640;
constexpr int32_t kFullSwing6581 = 24544;

/** PAL and NTSC C64 system clocks, which the SID's 24-bit frequency counts against. */
constexpr double kPalClockHz = 985248.0;
constexpr double kNtscClockHz = 1022727.0;

struct SidState {
  std::mutex mutex;
  /**
   * A seek asked for and not yet reached, in ms, or -1 for none; and whether
   * a worker is running one.
   *
   * A SID tune is a program, and the only way to a point in it is to run it
   * there: silently, but at the machine's own speed, which for minutes in is
   * seconds of work. Done where it was asked from, that froze whatever thread
   * asked - and the audio callback behind it, waiting on the mutex. It is run
   * on a thread of its own instead, and the callback hands back silence while
   * it lasts rather than waiting.
   */
  std::atomic<int> seekTarget{-1};
  std::atomic<bool> seeking{false};
  /**
   * Where the last seek asked for is going, in ms, until the tune has been
   * heard from there; -1 otherwise. The place playback is at comes from what
   * was last rendered, and while a seek runs nothing is: the readout went
   * back to where the tune had been, sat there for as long as the seek took,
   * and showed nought for a moment as it landed.
   */
  std::atomic<int> goingToMs{-1};
  /**
   * How many are waiting for the mutex to take the tune somewhere else - to
   * another subsong, or away. A seek running when one comes stops where it
   * is rather than keep them for as long as it has left.
   */
  std::atomic<int> overtaking{0};
  /** Where the audio handed over had got to when it was last asked - see sid_decoder_render_position_ms. */
  std::atomic<double> renderedMs{0.0};
  std::thread seekThread;
  std::unique_ptr<ReSIDfpBuilder> builder;
  std::unique_ptr<sidplayfp> engine;
  std::unique_ptr<SidTune> tune;

  bool loaded = false;
  bool ended = false;
  bool loop = false;
  /**
   * Whether the tune has already played its length through once. A SID has no
   * end of its own and is never rewound to repeat - it simply plays on, and
   * the position is wrapped back into its length for showing. That wrapping
   * has to outlive repeat being switched off, or the bar jumps to the end and
   * the elapsed time reads the whole of it while the tune plays its last pass.
   */
  bool wrapped = false;
  /**
   * Where playback stops, in the tune's own running time, when repeat is
   * switched off after it has gone round: the end of the pass it is in, not
   * the length - which is already behind it. 0 for the length itself.
   */
  int stopAtMs = 0;
  int sampleRate = 48000;
  int subsong = 0;
  int songs = 1;
  unsigned sids = 1;
  bool stereo = false;
  double clockHz = kPalClockHz;
  /** C64 cycles run per play call: about 5 ms of machine time. */
  unsigned cyclesPerSlice = 4926;

  std::string title, author, released, type;
  /**
   * Which voices the listener has silenced, and whether the engine has been
   * told yet.
   *
   * Written without the mutex: a seek holds that for as long as the tune
   * takes to run to where it is going, and a tap on a voice that had to wait
   * that out was a tap that did nothing for seconds - the screen saying the
   * voice was back while it was still silent. The render loop, which has the
   * mutex anyway, hands them to the engine on its next pass.
   */
  std::array<std::atomic<int>, MAX_CHANNELS> muted{};
  std::atomic<bool> mutesDirty{false};

  int defaultLengthMs = 3 * 60 * 1000;
  int durationOverrideMs = 0;

  /** Audio produced but not yet handed over, interleaved stereo. */
  std::vector<int16_t> spill;
  size_t spillRead = 0;
  std::vector<int16_t> mixed;

  /** Where each voice's own output lands - see sidplayfp::voiceTaps. */
  std::array<std::array<std::vector<int16_t>, kVoices>, kMaxSids> taps;
  /** Each voice's recent output, a ring written as audio is produced. */
  std::array<std::array<int16_t, kHistory>, kMaxSids * kVoices> history{};
  int historyWrite = 0;
  /** Half the swing of a full-scale voice on each chip, by its model - see kFullSwing6581. */
  std::array<int32_t, kMaxSids> fullSwing{};

  VisualPublisher visuals;

  static SidState& instance() {
    static SidState s;
    return s;
  }
};

bool hasMagic(const uint8_t* data, size_t size) {
  return size >= 0x76 && (std::memcmp(data, "PSID", 4) == 0 || std::memcmp(data, "RSID", 4) == 0);
}

int totalMsLocked(const SidState& state) {
  return state.durationOverrideMs > 0 ? state.durationOverrideMs : state.defaultLengthMs;
}

/** Song time reached by the audio handed over so far. */
double renderedMsLocked(const SidState& state) {
  if (!state.engine) return 0.0;
  const size_t waiting = (state.spill.size() - std::min(state.spillRead, state.spill.size())) / 2;
  return static_cast<double>(state.engine->timeMs()) - waiting * 1000.0 / std::max(1, state.sampleRate);
}

/** Taps, mutes: what a (re)loaded engine has to be told again. */
void attachLocked(SidState& state) {
  state.sids = std::min<unsigned>(state.engine->installedSIDs(), kMaxSids);
  // Room for a whole play call's samples - the chips buffer 20 ms.
  const size_t tapSize = static_cast<size_t>(state.sampleRate) / 40 + 64;
  const SidTuneInfo* info = state.tune->getInfo();
  for (unsigned s = 0; s < state.sids; s++) {
    // An extra chip that does not say is the same model as the first.
    const auto model = info->sidModel(s) == SidTuneInfo::SIDMODEL_UNKNOWN ? info->sidModel(0) : info->sidModel(s);
    state.fullSwing[s] = model == SidTuneInfo::SIDMODEL_8580 ? kFullSwing8580 : kFullSwing6581;
    for (auto& tap : state.taps[s]) tap.assign(tapSize, 0);
    state.engine->voiceTaps(s, state.taps[s][0].data(), state.taps[s][1].data(), state.taps[s][2].data());
    for (int v = 0; v < kVoices; v++) {
      state.engine->mute(s, v, state.muted[s * kVoices + v].load(std::memory_order_relaxed) != 0);
    }
  }
  state.stereo = state.sids > 1;
  state.engine->initMixer(state.stereo);
  state.spill.clear();
  state.spillRead = 0;
  for (auto& h : state.history) h.fill(0);
  state.historyWrite = 0;
}

/** Starts subsong [index] from the top. Caller holds the mutex. */
bool startLocked(SidState& state, int index) {
  state.tune->selectSong(static_cast<unsigned>(index + 1));
  if (!state.engine->load(state.tune.get())) {
    LOG_WARN("engine load failed: %s", state.engine->error());
    return false;
  }
  attachLocked(state);
  state.subsong = index;
  state.ended = false;
  state.wrapped = false;
  state.stopAtMs = 0;
  return true;
}

/** Builds the engine for the current rate and starts [subsong]. Caller holds the mutex. */
bool openLocked(SidState& state, int subsong) {
  state.engine = std::make_unique<sidplayfp>();
  state.builder = std::make_unique<ReSIDfpBuilder>("reSIDfp");
  SidConfig config;
  config.frequency = static_cast<uint_least32_t>(state.sampleRate);
  config.samplingMethod = SidConfig::RESAMPLE_INTERPOLATE;
  // For tunes that do not say. The library's own default is the 8580, but a
  // file that does not say is an old one - PSID v1, from before the flag -
  // and written on the 6581, whose filter sounds nothing like the 8580's.
  config.defaultSidModel = SidConfig::MOS6581;
  // The builder makes as many chips as the tune asks for when it is loaded.
  config.sidEmulation = state.builder.get();
  if (!state.engine->config(config)) {
    LOG_WARN("engine config failed: %s", state.engine->error());
    return false;
  }
  return startLocked(state, subsong);
}

void seekLocked(SidState& state, int ms, bool keepVisuals, bool mayGiveUp = false) {
  if (!state.loaded) return;
  const int target = std::clamp(ms, 0, std::max(0, totalMsLocked(state) - 1));
  // A tune is a running program with no way back, so anything before where
  // it is means starting it again. Forward is run silently: the machine and
  // what the tune can read back of the chips carry on, and no audio is made.
  if (target < static_cast<int>(state.engine->timeMs())) {
    startLocked(state, state.subsong);
  }
  state.engine->silent(true);
  int guard = 0;
  while (static_cast<int>(state.engine->timeMs()) < target && guard++ < 1000000) {
    // The listener's own seek, with a later one posted behind it or the tune
    // on its way elsewhere: not run to the end for nobody.
    if (mayGiveUp && (state.seekTarget.load(std::memory_order_acquire) >= 0 ||
                      state.overtaking.load(std::memory_order_acquire) > 0)) {
      break;
    }
    if (state.engine->play(20000) < 0) break;
  }
  state.engine->silent(false);
  state.spill.clear();
  state.spillRead = 0;
  state.ended = false;
  // A point in the tune, named: whatever passes had gone by are not where
  // playback is any more.
  state.wrapped = false;
  state.stopAtMs = 0;
  if (!keepVisuals) state.visuals.reset();
}

std::string modelName(const SidTuneInfo* info) {
  std::string text = info->formatString();
  if (text.find("RSID") != std::string::npos) {
    text = "RSID";
  } else if (text.find("PSID") != std::string::npos) {
    text = "PSID";
  }
  // The model played as, which for a tune that does not say is the default.
  text += info->sidModel(0) == SidTuneInfo::SIDMODEL_8580 ? ", 8580" : ", 6581";
  if (info->sidChips() > 1) text += " x" + std::to_string(info->sidChips());
  text += info->clockSpeed() == SidTuneInfo::CLOCK_NTSC ? ", NTSC" : ", PAL";
  return "C64 SID (" + text + ")";
}

/** Where the mixer puts chip [s]: its left and right weights, as a 0..255 pan. */
int panOf(unsigned s, unsigned sids) {
  if (sids <= 1) return 128;
  if (sids == 2) return s == 0 ? 85 : 170;
  return s == 0 ? 85 : (s == 1 ? 128 : 170);
}

void publishVisuals(SidState& state, int64_t frameStamp) {
  VisualFrame& frame = state.visuals.back();
  frame.clear();
  const int channels = static_cast<int>(state.sids) * kVoices;
  const int total = totalMsLocked(state);
  const int elapsed = static_cast<int>(renderedMsLocked(state));
  frame.scalars[kScalarNumChannels] = channels;
  if (total > 0 && elapsed >= total && (state.loop || state.wrapped)) {
    // Gone round at least once, and it stays wrapped from here: switching
    // repeat off in the middle of a later pass leaves the tune playing that
    // pass out, and the bar has to go on showing where in it playback is.
    state.wrapped = true;
    frame.scalars[kScalarTimeMs] = elapsed % total;
  } else {
    frame.scalars[kScalarTimeMs] = std::min(elapsed, total);
  }
  frame.scalars[kScalarTotalTimeMs] = total;
  frame.scalars[kScalarSequence] = state.subsong;

  const int levelSpan = std::clamp(state.sampleRate * kLevelWindowMs / 1000, 1, kHistory);
  int playing = 0;
  for (unsigned s = 0; s < state.sids; s++) {
    uint8_t regs[32] = {};
    state.engine->getSidStatus(s, regs);
    // The chip's master volume, which scales every voice after the voice
    // taps - a tune fading out, or held low, turns this down.
    const int master = regs[0x18] & 0x0f;
    for (int v = 0; v < kVoices; v++) {
      const int ch = static_cast<int>(s) * kVoices + v;
      int32_t* f = frame.channels.data() + ch * kChannelFields;
      const bool muted = state.muted[ch] != 0;
      const auto& h = state.history[ch];

      // Half the swing rather than the largest value: a voice's DAC output
      // sits off centre, more so on the 6581, and a silent voice would
      // otherwise read that offset as a level.
      int32_t lowest = 32767, highest = -32768;
      for (int i = 1; i <= levelSpan; i++) {
        const int16_t sample = h[(state.historyWrite - i + kHistory) % kHistory];
        lowest = std::min<int32_t>(lowest, sample);
        highest = std::max<int32_t>(highest, sample);
      }
      const int32_t swing = (highest - lowest) / 2;
      const int32_t full = state.fullSwing[s];
      const int32_t level = muted ? 0 : swing * master * kPeakScale / (15 * full);
      f[kChPeak] = std::clamp<int32_t>(level, 0, kPeakScale);
      if (f[kChPeak] > 0) playing++;

      const int base = v * 7;
      const unsigned frequency = regs[base] | (regs[base + 1] << 8);
      const uint8_t control = regs[base + 4];
      const bool gate = (control & 0x01) != 0;
      const int waveform = control >> 4;
      // What the tune has the voice at: its sustain level while the gate is
      // held, scaled by the master volume. The envelope itself cannot be read
      // off the chip for any voice but the third, so the bar shows the real
      // level over this.
      const int sustain = regs[base + 6] >> 4;
      f[kChVolume] = muted || !gate ? 0 : std::clamp(sustain * master * 64 / 225, 0, 64);
      f[kChFinalVolume] = f[kChPeak] * 64 / kPeakScale;
      f[kChPan] = panOf(s, state.sids);
      f[kChPeriod] = static_cast<int32_t>(frequency);
      // The row is labelled with the waveform the voice is set to - the
      // instrument list is the sixteen combinations, see sid_fillModVars.
      f[kChInstrument] = waveform;

      // The note the frequency register works out to, counted from C-0.
      const double hz = frequency * state.clockHz / 16777216.0;
      if ((gate || f[kChPeak] > 0) && waveform > 0 && hz > 8.0) {
        const double midi = 69.0 + 12.0 * std::log2(hz / 440.0);
        f[kChNote] = std::clamp(static_cast<int>(std::lround(midi)) - 11, 1, 127);
        f[kChKey] = f[kChNote] - 1;
        f[kChPitchBend] = static_cast<int32_t>(std::lround((midi - std::round(midi)) * 100.0));
      }

      // The trace at the master volume too, per chip: the views' own global
      // volume scalar is one for the whole song. Centred on its own swing,
      // for the same off-centre DAC as the level.
      int8_t* out = frame.scopes.data() + ch * kScopeColumns;
      if (muted) continue;
      int32_t traceLow = 32767, traceHigh = -32768;
      for (int x = 0; x < kScopeColumns; x++) {
        const int16_t sample = h[(state.historyWrite - kScopeColumns + x + kHistory) % kHistory];
        traceLow = std::min<int32_t>(traceLow, sample);
        traceHigh = std::max<int32_t>(traceHigh, sample);
      }
      const int32_t centre = (traceLow + traceHigh) / 2;
      for (int x = 0; x < kScopeColumns; x++) {
        const int32_t sample = h[(state.historyWrite - kScopeColumns + x + kHistory) % kHistory] - centre;
        out[x] = static_cast<int8_t>(std::clamp<int32_t>(sample * master * 127 / (15 * full), -127, 127));
      }
    }
  }
  frame.scalars[kScalarPlayingChannels] = playing;
  state.visuals.publish(frameStamp);
}

}  // namespace

bool sid_decoder_probe(const uint8_t* data, size_t size) {
  return data && hasMagic(data, size);
}

void joinSeek(SidState& state);

bool sid_decoder_load(const uint8_t* data, size_t size, int sampleRate) {
  if (!sid_decoder_probe(data, size)) return false;
  SidState& state = SidState::instance();
  // Nothing half-sought carried into another tune.
  joinSeek(state);
  std::lock_guard<std::mutex> lock(state.mutex);
  if (sampleRate > 0) state.sampleRate = sampleRate;
  state.loaded = false;
  for (auto& m : state.muted) m.store(0, std::memory_order_relaxed);
  state.mutesDirty.store(true, std::memory_order_release);

  auto tune = std::make_unique<SidTune>(data, static_cast<uint_least32_t>(size));
  if (!tune->getStatus()) {
    LOG_WARN("not a playable tune: %s", tune->statusString());
    return false;
  }
  state.tune = std::move(tune);
  const SidTuneInfo* info = state.tune->getInfo();
  state.songs = std::max(1u, info->songs());
  state.title = info->numberOfInfoStrings() > 0 ? info->infoString(0) : "";
  state.author = info->numberOfInfoStrings() > 1 ? info->infoString(1) : "";
  state.released = info->numberOfInfoStrings() > 2 ? info->infoString(2) : "";
  state.type = modelName(info);
  state.clockHz = info->clockSpeed() == SidTuneInfo::CLOCK_NTSC ? kNtscClockHz : kPalClockHz;
  state.cyclesPerSlice = static_cast<unsigned>(state.clockHz / 200.0);
  state.durationOverrideMs = 0;

  const int start = std::clamp(static_cast<int>(info->startSong()) - 1, 0, state.songs - 1);
  if (!openLocked(state, start)) {
    state.engine.reset();
    state.tune.reset();
    return false;
  }
  state.loaded = true;
  state.visuals.reset();
  LOG_INFO("loaded '%s' by '%s' (%s): %d songs, %u chips", state.title.c_str(), state.author.c_str(),
           state.type.c_str(), state.songs, state.sids);
  return true;
}

void sid_decoder_release() {
  SidState& state = SidState::instance();
  joinSeek(state);
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loaded = false;
  state.engine.reset();
  state.builder.reset();
  state.tune.reset();
  state.spill.clear();
  state.spillRead = 0;
  state.visuals.reset();
}

bool sid_decoder_loaded() { return SidState::instance().loaded; }
const char* sid_decoder_title() { return SidState::instance().title.c_str(); }
const char* sid_decoder_author() { return SidState::instance().author.c_str(); }
const char* sid_decoder_released() { return SidState::instance().released.c_str(); }
const char* sid_decoder_type() { return SidState::instance().type.c_str(); }

void sid_decoder_prepare_start(int rate) {
  sid_decoder_set_sample_rate(rate);
  SidState::instance().visuals.reset();
}

void sid_decoder_set_sample_rate(int rate) {
  SidState& state = SidState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate) return;
  // The chips resample to the output rate, so they are built again for it,
  // and the tune run back to where it was.
  const int at = state.loaded ? static_cast<int>(renderedMsLocked(state)) : 0;
  state.sampleRate = rate;
  if (!state.loaded) return;
  if (!openLocked(state, state.subsong)) {
    state.loaded = false;
    return;
  }
  if (at > 0) seekLocked(state, at, false);
  LOG_INFO("re-opened at %d Hz", rate);
}

int sid_decoder_duration_ms() {
  SidState& state = SidState::instance();
  return totalMsLocked(state);
}

int sid_decoder_native_duration_ms() { return SidState::instance().defaultLengthMs; }

int sid_decoder_position_ms() {
  SidState& state = SidState::instance();
  const int going = state.goingToMs.load(std::memory_order_acquire);
  if (going >= 0) {
    if (state.seeking.load(std::memory_order_acquire) || state.seekTarget.load(std::memory_order_acquire) >= 0 ||
        !state.visuals.published()) {
      return going;
    }
    // Landed, and heard from there: what was rendered says where it is again.
    int landed = going;
    state.goingToMs.compare_exchange_strong(landed, -1, std::memory_order_acq_rel);
  }
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : 0;
}

/** Runs whatever seek is pending, and any that arrive while it runs. */
void runSeeks(SidState& state) {
  for (;;) {
    const int target = state.seekTarget.exchange(-1, std::memory_order_acq_rel);
    if (target < 0) {
      state.seeking.store(false, std::memory_order_release);
      // One posted between the exchange and here would have been turned away
      // by the flag, so it is this thread's to pick up.
      if (state.seekTarget.load(std::memory_order_acquire) < 0) return;
      bool idle = false;
      if (!state.seeking.compare_exchange_strong(idle, true, std::memory_order_acq_rel)) return;
      continue;
    }
    std::lock_guard<std::mutex> lock(state.mutex);
    seekLocked(state, target, false, true);
    // Faded in from where it lands: the fade asked for with the seek was
    // spent on the silence that lasted until now.
    ramp_output_in(10);
  }
}

bool sid_decoder_seeking() { return SidState::instance().seeking.load(std::memory_order_acquire); }

void sid_decoder_seek_ms(int ms) {
  SidState& state = SidState::instance();
  state.goingToMs.store(std::max(0, ms), std::memory_order_release);
  state.seekTarget.store(std::max(0, ms), std::memory_order_release);
  bool idle = false;
  // One at a time: a worker already running will pick this up as it finishes
  // the one before, and a drag along the bar lands where the finger left it
  // rather than through every point it passed.
  if (!state.seeking.compare_exchange_strong(idle, true, std::memory_order_acq_rel)) return;
  if (state.seekThread.joinable()) state.seekThread.join();
  state.seekThread = std::thread([&state] { runSeeks(state); });
}

/** Has a seek in flight stop where it is, and waits for it. Caller must not hold the mutex. */
void joinSeek(SidState& state) {
  state.seekTarget.store(-1, std::memory_order_release);
  state.goingToMs.store(-1, std::memory_order_release);
  state.overtaking.fetch_add(1, std::memory_order_acq_rel);
  if (state.seekThread.joinable()) state.seekThread.join();
  state.overtaking.fetch_sub(1, std::memory_order_acq_rel);
}

bool sid_decoder_ended() { return SidState::instance().ended; }

void sid_decoder_set_loop(bool loop) {
  SidState& state = SidState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loop = loop;
  const int total = totalMsLocked(state);
  // Switched off after it has gone round, the tune plays out the pass it is
  // in rather than stopping where it stands - which is already past the end.
  if (!loop && state.wrapped && total > 0 && state.engine) {
    const int now = static_cast<int>(state.engine->timeMs());
    state.stopAtMs = (now / total + 1) * total;
  } else {
    state.stopAtMs = 0;
  }
}

void sid_decoder_set_default_length_ms(int ms) {
  SidState& state = SidState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (ms > 0) state.defaultLengthMs = ms;
}

double sid_decoder_render_position_ms() {
  SidState& state = SidState::instance();
  // Asked from the audio callback, which never waits out a seek - see
  // sid_decoder_render. Where it last was is near enough until one lands.
  std::unique_lock<std::mutex> lock(state.mutex, std::try_to_lock);
  if (!lock.owns_lock()) return state.renderedMs.load(std::memory_order_relaxed);
  const double at = renderedMsLocked(state);
  state.renderedMs.store(at, std::memory_order_relaxed);
  return at;
}

void sid_decoder_loop_seek_ms(int ms) {
  SidState& state = SidState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, true);
}

void sid_decoder_set_duration_override_ms(int ms) {
  SidState& state = SidState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.durationOverrideMs = ms > 0 ? ms : 0;
}

int sid_decoder_subsong_count() { return SidState::instance().songs; }

bool sid_decoder_start_subsong(int index) {
  SidState& state = SidState::instance();
  // A seek asked of the subsong being left is not carried into this one, and
  // one running stops for it.
  state.seekTarget.store(-1, std::memory_order_release);
  state.goingToMs.store(-1, std::memory_order_release);
  state.overtaking.fetch_add(1, std::memory_order_acq_rel);
  std::lock_guard<std::mutex> lock(state.mutex);
  state.overtaking.fetch_sub(1, std::memory_order_acq_rel);
  if (!state.loaded || index < 0 || index >= state.songs) return false;
  // A length set belongs to the subsong it was set for; the player sends the
  // new one's after this.
  state.durationOverrideMs = 0;
  const bool started = startLocked(state, index);
  state.visuals.reset();
  return started;
}

int sid_decoder_current_subsong() { return SidState::instance().subsong; }

int sid_decoder_channels() {
  SidState& state = SidState::instance();
  return state.loaded ? static_cast<int>(state.sids) * kVoices : 0;
}

void sid_decoder_mute(int channel, int muted) {
  SidState& state = SidState::instance();
  if (channel < 0 || channel >= kMaxSids * kVoices) return;
  // No mutex: see SidState::muted. The engine is told on the next pass of the
  // render loop, which is the next few milliseconds - or, if a seek is
  // running, the first pass after it, which is the soonest anything can be
  // heard anyway.
  state.muted[channel].store(muted ? 1 : 0, std::memory_order_relaxed);
  state.mutesDirty.store(true, std::memory_order_release);
}

int sid_decoder_muted(int channel) {
  SidState& state = SidState::instance();
  if (channel < 0 || channel >= MAX_CHANNELS) return 0;
  return state.muted[channel].load(std::memory_order_relaxed);
}

bool sid_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  return SidState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int sid_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  SidState& state = SidState::instance();
  // Never waits: a seek holds the mutex for as long as the tune takes to run
  // to where it is going, and an audio callback that waited that out is a
  // stall. Silence until it lands, which is what a seek sounds like anyway.
  std::unique_lock<std::mutex> lock(state.mutex, std::try_to_lock);
  if (!lock.owns_lock()) {
    std::memset(audioData, 0, static_cast<size_t>(numFrames) * numChannels * bytesPerSample);
    // Not the end of the tune, which is what anything else would say: the
    // loop took it for one, and went back to its start after every seek.
    return 0;
  }

  const size_t frameBytes = static_cast<size_t>(numChannels) * bytesPerSample;
  if (!state.loaded || state.ended) {
    std::memset(audioData, 0, static_cast<size_t>(numFrames) * frameBytes);
    return state.loaded ? 1 : 0;
  }

  // Whatever was muted or unmuted while the mutex was elsewhere.
  if (state.mutesDirty.exchange(false, std::memory_order_acq_rel) && state.loaded) {
    for (unsigned chip = 0; chip < state.sids; chip++) {
      for (int v = 0; v < kVoices; v++) {
        state.engine->mute(chip, v, state.muted[chip * kVoices + v].load(std::memory_order_relaxed) != 0);
      }
    }
  }

  const int64_t base = get_frames_written();
  int32_t written = 0;
  int emptySlices = 0;
  while (written < numFrames) {
    if (state.spillRead >= state.spill.size()) {
      // A tune has no end of its own; the length set for it is where it
      // stops - or, repeating, where the position goes back round.
      const int stopAt = state.stopAtMs > 0 ? state.stopAtMs : totalMsLocked(state);
      if (!state.loop && static_cast<int>(state.engine->timeMs()) >= stopAt) {
        state.ended = true;
        break;
      }
      const int samples = state.engine->play(state.cyclesPerSlice);
      if (samples < 0) {
        LOG_WARN("tune stopped: %s", state.engine->error());
        state.ended = true;
        break;
      }
      if (samples == 0) {
        if (++emptySlices > 64) {
          state.ended = true;
          break;
        }
        continue;
      }
      emptySlices = 0;
      const size_t channelsOut = state.stereo ? 2 : 1;
      state.mixed.resize(static_cast<size_t>(samples) * channelsOut);
      const unsigned produced = state.engine->mix(state.mixed.data(), static_cast<unsigned>(samples));
      const size_t frames = produced / channelsOut;
      state.spill.resize(frames * 2);
      for (size_t i = 0; i < frames; i++) {
        state.spill[i * 2] = state.mixed[i * channelsOut];
        state.spill[i * 2 + 1] = state.mixed[i * channelsOut + channelsOut - 1];
      }
      state.spillRead = 0;

      for (size_t i = 0; i < frames; i++) {
        for (unsigned s = 0; s < state.sids; s++) {
          for (int v = 0; v < kVoices; v++) {
            state.history[s * kVoices + v][state.historyWrite] = state.taps[s][v][i];
          }
        }
        state.historyWrite = (state.historyWrite + 1) % kHistory;
      }
      publishVisuals(state, base + written);
    }

    const size_t available = (state.spill.size() - state.spillRead) / 2;
    const int32_t take = std::min<int32_t>(numFrames - written, static_cast<int32_t>(available));
    for (int32_t i = 0; i < take; i++) {
      const int16_t l = state.spill[state.spillRead];
      const int16_t r = state.spill[state.spillRead + 1];
      state.spillRead += 2;
      if (bytesPerSample == 4) {
        float* out = static_cast<float*>(audioData) + static_cast<size_t>(written + i) * numChannels;
        out[0] = l / 32768.0f;
        if (numChannels > 1) out[1] = r / 32768.0f;
      } else {
        int16_t* out = static_cast<int16_t*>(audioData) + static_cast<size_t>(written + i) * numChannels;
        out[0] = l;
        if (numChannels > 1) out[1] = r;
      }
    }
    written += take;
  }

  if (written < numFrames) {
    std::memset(static_cast<uint8_t*>(audioData) + static_cast<size_t>(written) * frameBytes, 0,
                static_cast<size_t>(numFrames - written) * frameBytes);
    return 1;
  }
  return 0;
}
