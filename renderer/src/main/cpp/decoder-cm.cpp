//
// CustomMade decoder. See decoder-cm.h.
//
#include "decoder-cm.h"
#include "custommade/custommade.h"
#include "visual-state.h"
#include "visual-publisher.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
constexpr const char* TAG = "Exilum CustomMade";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

constexpr int kChannels = 4;
/** Frames rendered between two looks at the song, and two published frames. */
constexpr int kBlockFrames = 512;
/** Samples of each channel kept for its trace and its level - at 48 kHz, 42 ms. */
constexpr int kHistory = 2048;
constexpr int kLevelWindowMs = 30;
/** A channel's output at full scale and full volume, as Player::render gives it. */
constexpr float kFullScale = 127.0f * 63.0f * 2.0f / 32768.0f;
/**
 * How far each side reaches into the other, as HVL's default: the Amiga's
 * own panning is two channels hard left and two hard right.
 */
constexpr float kSeparation = 0.5f;
/** Longest a subsong is run for to find its end, before it is taken not to have one. */
constexpr int kMeasureCapMs = 30 * 60 * 1000;
/** The length of a subsong that never ends. */
constexpr int kUnknownLengthMs = 3 * 60 * 1000;
/** Before the place a seek lands, how long Paula is run for too, to settle. */
constexpr int kSeekSettleMs = 1000;

struct CmState {
  std::mutex mutex;
  custommade::Module module;
  std::unique_ptr<custommade::Player> player;

  bool loaded = false;
  bool ended = false;
  bool loop = false;
  int sampleRate = 48000;
  int subsong = 0;
  std::vector<int> subsongMs;
  std::vector<bool> subsongKnown;
  int durationOverrideMs = 0;
  /** Song time at the end of what has been rendered. */
  double positionMs = 0.0;
  double pitchSemitones = 0.0;
  double tempoFactor = 1.0;

  std::array<int, kChannels> muted{};
  std::vector<std::string> instrumentNames;

  std::vector<float> left = std::vector<float>(kBlockFrames);
  std::vector<float> right = std::vector<float>(kBlockFrames);
  std::array<std::vector<float>, kChannels> channel{
      std::vector<float>(kBlockFrames), std::vector<float>(kBlockFrames), std::vector<float>(kBlockFrames),
      std::vector<float>(kBlockFrames)};
  std::array<std::array<float, kHistory>, kChannels> history{};
  int historyWrite = 0;

  VisualPublisher visuals;

  static CmState& instance() {
    static CmState s;
    return s;
  }
};

int totalMsLocked(const CmState& state) {
  if (state.durationOverrideMs > 0) return state.durationOverrideMs;
  return state.subsong < static_cast<int>(state.subsongMs.size()) ? state.subsongMs[state.subsong] : 0;
}

void applySpeedLocked(CmState& state) {
  if (state.player) state.player->setSpeed(std::pow(2.0, state.pitchSemitones / 12.0), state.tempoFactor);
}

/** Starts the current subsong from the top. Caller holds the mutex. */
void restartLocked(CmState& state) {
  state.player->start(state.subsong);
  applySpeedLocked(state);
  state.positionMs = 0.0;
  state.ended = false;
}

void seekLocked(CmState& state, int ms, bool keepVisuals) {
  if (!state.loaded) return;
  const int total = totalMsLocked(state);
  const int target = std::clamp(ms, 0, std::max(0, total - 1));
  restartLocked(state);
  // The routine alone most of the way, which is exact for it, then Paula as
  // well for the last stretch, so the channels are playing what they would be.
  const double rate = state.player->tickRate();
  const long ticks = static_cast<long>(target * rate / 1000.0);
  const long settle = std::min(ticks, static_cast<long>(kSeekSettleMs * rate / 1000.0));
  state.player->skipTicksSilently(ticks - settle);
  state.player->skipTicks(settle);
  state.player->skipMs(target - ticks * 1000.0 / rate);
  state.positionMs = target;
  for (auto& h : state.history) h.fill(0.0f);
  if (!keepVisuals) state.visuals.reset();
}

void publishVisuals(CmState& state, int64_t frameStamp) {
  VisualFrame& frame = state.visuals.back();
  frame.clear();
  const custommade::Player& player = *state.player;
  frame.scalars[kScalarNumChannels] = kChannels;
  frame.scalars[kScalarTimeMs] = static_cast<int32_t>(state.positionMs);
  frame.scalars[kScalarTotalTimeMs] = totalMsLocked(state);
  frame.scalars[kScalarSequence] = state.subsong;

  const int levelSpan = std::clamp(state.sampleRate * kLevelWindowMs / 1000, 1, kHistory);
  int playing = 0;
  for (int c = 0; c < kChannels; c++) {
    int32_t* f = frame.channels.data() + c * kChannelFields;
    const bool muted = state.muted[c] != 0;
    const custommade::PaulaChannel& paula = player.channel(c);
    const auto& h = state.history[c];

    float loudest = 0.0f;
    for (int i = 1; i <= levelSpan; i++) {
      loudest = std::max(loudest, std::fabs(h[(state.historyWrite - i + kHistory) % kHistory]));
    }
    f[kChPeak] = muted ? 0 : std::clamp(static_cast<int32_t>(loudest / kFullScale * kPeakScale), 0, kPeakScale);
    if (f[kChPeak] > 0) playing++;

    const bool sounding = paula.playing && paula.vol > 0 && !muted;
    f[kChVolume] = sounding ? std::min(64, paula.vol * 64 / 63) : 0;
    f[kChFinalVolume] = f[kChPeak] * 64 / kPeakScale;
    // Channels 0 and 3 on the left, 1 and 2 on the right, as far over as the
    // separation puts them.
    const bool leftSide = c == 0 || c == 3;
    f[kChPan] = 128 + static_cast<int>((leftSide ? -127.0f : 127.0f) * kSeparation);
    f[kChPeriod] = paula.per;
    f[kChInstrument] = player.instrumentOf(c);

    // The note Paula is playing it at, arpeggio, portamento and vibrato
    // included, on the scale the Amiga trackers use: period 856 is C-4.
    if (sounding && paula.per > 0) {
      const double note = 49.0 + 12.0 * std::log2(856.0 / paula.per);
      f[kChNote] = std::clamp(static_cast<int>(std::lround(note)), 1, 120);
      f[kChKey] = f[kChNote] - 1;
      f[kChPitchBend] = static_cast<int32_t>(std::lround((note - std::round(note)) * 100.0));
    }

    if (muted) continue;
    int8_t* out = frame.scopes.data() + c * kScopeColumns;
    for (int x = 0; x < kScopeColumns; x++) {
      const float sample = h[(state.historyWrite - kScopeColumns + x + kHistory) % kHistory];
      out[x] = static_cast<int8_t>(std::clamp(static_cast<int>(sample / kFullScale * 127.0f), -127, 127));
    }
  }
  frame.scalars[kScalarPlayingChannels] = playing;
  state.visuals.publish(frameStamp);
}

}  // namespace

// The tables have to be found as well as the routine: a module whose routine
// is a version this one cannot read - there is one among the .rk files - is
// turned away here, rather than listed and then failing to load.
bool cm_decoder_probe(const uint8_t* data, size_t size) {
  custommade::Module module;
  return custommade::parse(data, size, module);
}

bool cm_decoder_load(const uint8_t* data, size_t size, int sampleRate) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loaded = false;
  state.player.reset();
  custommade::Module module;
  if (!custommade::parse(data, size, module)) return false;
  state.module = std::move(module);
  if (sampleRate > 0) state.sampleRate = sampleRate;

  // Each subsong's length: the routine alone, until every voice has been
  // round its track list.
  state.subsongMs.assign(state.module.subsongs, kUnknownLengthMs);
  state.subsongKnown.assign(state.module.subsongs, false);
  custommade::Player measure(state.module);
  for (int s = 0; s < state.module.subsongs; s++) {
    measure.start(s);
    const long cap = static_cast<long>(kMeasureCapMs * measure.tickRate() / 1000.0);
    while (measure.songEnds() == 0 && measure.ticks() < cap) measure.skipTicksSilently(1);
    if (measure.songEnds() > 0) {
      state.subsongMs[s] = std::max(1, static_cast<int>(measure.ticks() * 1000.0 / measure.tickRate()));
      state.subsongKnown[s] = true;
    }
  }

  state.instrumentNames.clear();
  state.player = std::make_unique<custommade::Player>(state.module);
  for (int i = 0; i < state.module.instruments; i++) {
    state.instrumentNames.push_back((state.player->isSynth(i) ? "Synth " : "Sample ") + std::to_string(i + 1));
  }
  state.subsong = 0;
  state.durationOverrideMs = 0;
  state.muted.fill(0);
  restartLocked(state);
  for (auto& h : state.history) h.fill(0.0f);
  state.visuals.reset();
  state.loaded = true;
  LOG_INFO("loaded: %d subsongs, %d instruments, timer %04x, first subsong %d ms", state.module.subsongs,
           state.module.instruments, state.module.timer, state.subsongMs[0]);
  return true;
}

void cm_decoder_release() {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loaded = false;
  state.player.reset();
  state.module = custommade::Module();
  state.visuals.reset();
}

bool cm_decoder_loaded() { return CmState::instance().loaded; }
const char* cm_decoder_type() { return "CustomMade"; }

void cm_decoder_prepare_start(int rate) {
  cm_decoder_set_sample_rate(rate);
  CmState::instance().visuals.reset();
}

void cm_decoder_set_sample_rate(int rate) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  // Paula is rendered at whatever rate is asked for, each call; nothing to rebuild.
  if (rate > 0) state.sampleRate = rate;
}

int cm_decoder_duration_ms() { return totalMsLocked(CmState::instance()); }

int cm_decoder_subsong_duration_ms(int index) {
  const CmState& state = CmState::instance();
  return index >= 0 && index < static_cast<int>(state.subsongMs.size()) ? state.subsongMs[index] : 0;
}

bool cm_decoder_duration_known() {
  const CmState& state = CmState::instance();
  return state.subsong < static_cast<int>(state.subsongKnown.size()) && state.subsongKnown[state.subsong];
}

int cm_decoder_position_ms() {
  CmState& state = CmState::instance();
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : 0;
}

void cm_decoder_seek_ms(int ms) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, false);
}

bool cm_decoder_ended() { return CmState::instance().ended; }

void cm_decoder_set_loop(bool loop) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.loop = loop;
}

double cm_decoder_render_position_ms() {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.positionMs;
}

void cm_decoder_loop_seek_ms(int ms) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  seekLocked(state, ms, true);
}

void cm_decoder_set_duration_override_ms(int ms) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.durationOverrideMs = ms > 0 ? ms : 0;
}

void cm_decoder_set_pitch(double semitones) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitchSemitones = std::clamp(semitones, -12.0, 12.0);
  applySpeedLocked(state);
}

void cm_decoder_set_tempo(double percent) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
  applySpeedLocked(state);
}

int cm_decoder_subsong_count() { return CmState::instance().module.subsongs; }

bool cm_decoder_start_subsong(int index) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.loaded || index < 0 || index >= state.module.subsongs) return false;
  state.subsong = index;
  state.durationOverrideMs = 0;
  restartLocked(state);
  for (auto& h : state.history) h.fill(0.0f);
  state.visuals.reset();
  return true;
}

int cm_decoder_current_subsong() { return CmState::instance().subsong; }
int cm_decoder_channels() { return CmState::instance().loaded ? kChannels : 0; }
int cm_decoder_instrument_count() { return static_cast<int>(CmState::instance().instrumentNames.size()); }

const char* cm_decoder_instrument_name(int index) {
  const CmState& state = CmState::instance();
  return index >= 0 && index < static_cast<int>(state.instrumentNames.size()) ? state.instrumentNames[index].c_str()
                                                                              : "";
}

void cm_decoder_mute(int channel, int muted) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (channel >= 0 && channel < kChannels) state.muted[channel] = muted ? 1 : 0;
}

int cm_decoder_muted(int channel) {
  const CmState& state = CmState::instance();
  return channel >= 0 && channel < kChannels ? state.muted[channel] : 0;
}

bool cm_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  return CmState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int cm_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  CmState& state = CmState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  const size_t frameBytes = static_cast<size_t>(numChannels) * bytesPerSample;
  if (!state.loaded || state.ended) {
    std::memset(audioData, 0, static_cast<size_t>(numFrames) * frameBytes);
    return state.loaded ? 1 : 0;
  }

  custommade::Player& player = *state.player;
  const int64_t base = get_frames_written();
  const double msPerFrame = 1000.0 * state.tempoFactor / std::max(1, state.sampleRate);
  float* channels[kChannels] = {state.channel[0].data(), state.channel[1].data(), state.channel[2].data(),
                                state.channel[3].data()};

  int32_t written = 0;
  while (written < numFrames) {
    // A length the listener set is where it ends; otherwise the tune's own
    // end, or the routine stopping it.
    const bool over = state.durationOverrideMs > 0 ? state.positionMs >= state.durationOverrideMs
                                                   : player.songEnds() > 0;
    if (over || player.stopped()) {
      if (!state.loop) {
        state.ended = true;
        break;
      }
      restartLocked(state);
    }

    const int32_t chunk = std::min<int32_t>(numFrames - written, kBlockFrames);
    player.render(state.left.data(), state.right.data(), chunk, state.sampleRate, channels);
    state.positionMs += chunk * msPerFrame;

    for (int32_t i = 0; i < chunk; i++) {
      float side[2] = {0.0f, 0.0f};
      for (int c = 0; c < kChannels; c++) {
        const float s = channels[c][i];
        state.history[c][(state.historyWrite + i) % kHistory] = s;
        if (state.muted[c]) continue;
        side[(c == 0 || c == 3) ? 0 : 1] += s;
      }
      const float near = (1.0f + kSeparation) * 0.5f, far = (1.0f - kSeparation) * 0.5f;
      const float l = std::clamp(side[0] * near + side[1] * far, -1.0f, 1.0f);
      const float r = std::clamp(side[1] * near + side[0] * far, -1.0f, 1.0f);
      if (bytesPerSample == 4) {
        float* out = static_cast<float*>(audioData) + static_cast<size_t>(written + i) * numChannels;
        out[0] = l;
        if (numChannels > 1) out[1] = r;
      } else {
        int16_t* out = static_cast<int16_t*>(audioData) + static_cast<size_t>(written + i) * numChannels;
        out[0] = static_cast<int16_t>(std::lround(l * 32767.0f));
        if (numChannels > 1) out[1] = static_cast<int16_t>(std::lround(r * 32767.0f));
      }
    }
    state.historyWrite = (state.historyWrite + chunk) % kHistory;
    publishVisuals(state, base + written);
    written += chunk;
  }

  if (written < numFrames) {
    std::memset(static_cast<uint8_t*>(audioData) + static_cast<size_t>(written) * frameBytes, 0,
                static_cast<size_t>(numFrames - written) * frameBytes);
    return 1;
  }
  return 0;
}
