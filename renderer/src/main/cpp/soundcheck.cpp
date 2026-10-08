//
// Sound Check. See soundcheck.h.
//
#include "soundcheck.h"
#include "loudness.h"
#include <algorithm>
#include <atomic>
#include <cmath>

namespace {

constexpr int kMaxChannels = LoudnessMeter::kMaxChannels;

/** Gain limits: a quiet track is brought up at most this far, a loud one down at most this far. */
constexpr float kMaxBoostDb = 15.0f;
constexpr float kMaxCutDb = -24.0f;
/** For a first track of all, the loudness assumed until there is an average: chip music runs loud. */
constexpr float kDefaultPriorLufs = -13.0f;
/**
 * For a track whose analysis has not come: how long before the output stage's
 * own measurement is followed instead. Long, because the analysis nearly
 * always arrives first, and following is a level that moves.
 */
constexpr float kFollowAfterSeconds = 15.0f;
constexpr float kFollowSlewDbPerSecond = 2.0f;

/** How long a known loudness arriving mid-track takes to glide to. */
constexpr double kUpdateGlideSeconds = 1.0;
/** How long the applied gain takes to reach a new value at all - long enough not to be a step. */
constexpr double kGainSmoothingSeconds = 0.05;

/** Where the limiter holds peaks: just under full scale. */
constexpr double kCeiling = 0.966;  // -0.3 dBFS
constexpr double kReleaseSeconds = 0.15;

std::atomic<bool> g_enabled{false};
std::atomic<uint32_t> g_beginSerial{0};
std::atomic<float> g_pendingKnown{kSoundCheckUnknown};
std::atomic<float> g_pendingPrior{kSoundCheckUnknown};
std::atomic<uint32_t> g_updateSerial{0};
std::atomic<float> g_pendingUpdate{kSoundCheckUnknown};

std::atomic<float> g_loudness{kSoundCheckUnknown};
std::atomic<float> g_seconds{0.0f};
std::atomic<float> g_gainDb{0.0f};
std::atomic<int64_t> g_framesProcessed{0};
std::atomic<int> g_processRate{48000};
std::atomic<float> g_limiterDb{0.0f};
std::atomic<bool> g_fixed{false};
std::atomic<uint32_t> g_begins{0};

/** Everything the audio thread keeps; nothing else touches it. */
struct State {
  uint32_t appliedBegin = 0;
  uint32_t appliedUpdate = 0;
  LoudnessMeter meter;

  bool fixed = false;
  float startGainDb = 0.0f;
  float targetGainDb = 0.0f;
  double targetGain = 1.0;
  double gain = 1.0;
  /** Per-frame smoothing of the gain: quick normally, a second's glide after an update. */
  double smoothing = 0.0;
  double limiter = 1.0;
  double hopLimiter = 1.0;
};

State& state() {
  static State s;
  return s;
}

inline float clampGain(float db) { return std::clamp(db, kMaxCutDb, kMaxBoostDb); }

inline double smoothingFor(double seconds, int rate) { return 1.0 - std::exp(-1.0 / (seconds * rate)); }

void setTarget(State& s, float db) {
  s.targetGainDb = db;
  s.targetGain = std::pow(10.0, db / 20.0);
  g_gainDb.store(db, std::memory_order_relaxed);
}

void applyBegin(State& s, int rate) {
  s.meter.reset(rate);
  const float known = g_pendingKnown.load(std::memory_order_relaxed);
  const float prior = g_pendingPrior.load(std::memory_order_relaxed);
  s.fixed = known > kSoundCheckUnknown + 1.0f;
  const float assumed = s.fixed ? known : (prior > kSoundCheckUnknown + 1.0f ? prior : kDefaultPriorLufs);
  s.startGainDb = clampGain(kSoundCheckTargetLufs - assumed);
  setTarget(s, s.startGainDb);
  // Straight to it: a track starts from silence, and there is nothing to glide from.
  s.gain = s.targetGain;
  s.smoothing = smoothingFor(kGainSmoothingSeconds, rate);
  g_loudness.store(kSoundCheckUnknown, std::memory_order_relaxed);
  g_seconds.store(0.0f, std::memory_order_relaxed);
  g_fixed.store(s.fixed, std::memory_order_relaxed);
  g_begins.fetch_add(1, std::memory_order_relaxed);
}

void applyUpdate(State& s, int rate) {
  const float known = g_pendingUpdate.load(std::memory_order_relaxed);
  if (known <= kSoundCheckUnknown + 1.0f) return;
  s.fixed = true;
  setTarget(s, clampGain(kSoundCheckTargetLufs - known));
  // A time constant of a quarter of the glide: all but 2% of the way in it.
  s.smoothing = smoothingFor(kUpdateGlideSeconds / 4.0, rate);
  g_fixed.store(true, std::memory_order_relaxed);
}

/** A 100 ms hop has ended: report, and for an unanalysed track, maybe follow. */
void endHop(State& s) {
  g_limiterDb.store(static_cast<float>(20.0 * std::log10(std::max(s.hopLimiter, 1e-6))), std::memory_order_relaxed);
  s.hopLimiter = 1.0;
  const float integrated = s.meter.integrated();
  const float seconds = s.meter.gatedSeconds();
  g_loudness.store(integrated, std::memory_order_relaxed);
  g_seconds.store(seconds, std::memory_order_relaxed);
  if (s.fixed || integrated <= kSoundCheckUnknown + 1.0f || seconds < kFollowAfterSeconds) return;
  const float desired = clampGain(kSoundCheckTargetLufs - integrated);
  const float slew = kFollowSlewDbPerSecond * 0.1f;
  setTarget(s, s.targetGainDb + std::clamp(desired - s.targetGainDb, -slew, slew));
}

/**
 * The start of a callback's worth: false when Sound Check is off. Takes up a
 * new track and a loudness that has come, counts the audio, and keeps the
 * meter at the rate it is handed.
 */
bool prepare(State& s, int32_t numFrames, int numChannels, int rate) {
  if (!g_enabled.load(std::memory_order_relaxed) || rate <= 0 || numChannels <= 0) return false;
  g_framesProcessed.fetch_add(numFrames, std::memory_order_relaxed);
  g_processRate.store(rate, std::memory_order_relaxed);
  if (s.meter.rate() != rate) s.meter.reset(rate);

  // A begin first, then any update made after it. Updates made before the
  // begin belonged to the track before, and are passed over.
  const uint32_t begin = g_beginSerial.load(std::memory_order_acquire);
  if (begin != s.appliedBegin) {
    s.appliedBegin = begin;
    s.appliedUpdate = g_updateSerial.load(std::memory_order_acquire);
    applyBegin(s, rate);
  }
  const uint32_t update = g_updateSerial.load(std::memory_order_acquire);
  if (update != s.appliedUpdate) {
    s.appliedUpdate = update;
    applyUpdate(s, rate);
  }
  return true;
}

/** One frame's gain and limiting, given its loudest sample before them: what it is multiplied by. */
/** The gain one frame on. */
void stepGain(State& s, double settled) {
  s.gain += (s.targetGain - s.gain) * s.smoothing;
  // Once a glide has arrived, back to the quick smoothing.
  if (s.smoothing != settled && std::fabs(s.targetGain - s.gain) < s.targetGain * 1e-3) s.smoothing = settled;
}

/** The limiter one frame on, given the frame's loudest sample once it has had its gain. */
void stepLimiter(State& s, double peak, double release) {
  if (peak * s.limiter > kCeiling) {
    s.limiter = kCeiling / peak;
  } else {
    s.limiter += (1.0 - s.limiter) * release;
    if (peak * s.limiter > kCeiling) s.limiter = kCeiling / peak;
  }
  s.hopLimiter = std::min(s.hopLimiter, s.limiter);
}

double step(State& s, double peak, double settled, double release) {
  stepGain(s, settled);
  stepLimiter(s, peak * s.gain, release);
  return s.gain * s.limiter;
}

}  // namespace

void sound_check_set_enabled(bool enabled) { g_enabled.store(enabled, std::memory_order_relaxed); }
bool sound_check_enabled() { return g_enabled.load(std::memory_order_relaxed); }

void sound_check_begin(float knownLufs, float priorLufs) {
  g_pendingKnown.store(knownLufs, std::memory_order_relaxed);
  g_pendingPrior.store(priorLufs, std::memory_order_relaxed);
  g_beginSerial.fetch_add(1, std::memory_order_release);
}

void sound_check_update(float knownLufs) {
  g_pendingUpdate.store(knownLufs, std::memory_order_relaxed);
  g_updateSerial.fetch_add(1, std::memory_order_release);
}

float sound_check_loudness() { return g_loudness.load(std::memory_order_relaxed); }
float sound_check_measured_seconds() { return g_seconds.load(std::memory_order_relaxed); }
float sound_check_gain_db() { return g_gainDb.load(std::memory_order_relaxed); }

void sound_check_stats(float* out) {
  out[kStatEnabled] = g_enabled.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
  out[kStatProcessedSeconds] = static_cast<float>(
      static_cast<double>(g_framesProcessed.load(std::memory_order_relaxed)) /
      std::max(1, g_processRate.load(std::memory_order_relaxed)));
  out[kStatLoudness] = g_loudness.load(std::memory_order_relaxed);
  out[kStatMeasuredSeconds] = g_seconds.load(std::memory_order_relaxed);
  out[kStatGainDb] = g_gainDb.load(std::memory_order_relaxed);
  out[kStatLimiterDb] = g_limiterDb.load(std::memory_order_relaxed);
  out[kStatFixed] = g_fixed.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
  out[kStatBegins] = static_cast<float>(g_begins.load(std::memory_order_relaxed));
}

void sound_check_process(void* data, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample, int rate) {
  State& s = state();
  if (!prepare(s, numFrames, numChannels, rate)) return;

  const int channels = std::min(numChannels, kMaxChannels);
  const double release = smoothingFor(kReleaseSeconds, rate);
  const double settled = smoothingFor(kGainSmoothingSeconds, rate);
  if (s.smoothing <= 0.0) s.smoothing = settled;
  double frame[kMaxChannels];

  for (int32_t f = 0; f < numFrames; f++) {
    double peak = 0.0;
    for (int c = 0; c < channels; c++) {
      const int32_t at = f * numChannels + c;
      frame[c] = bytesPerSample == 4 ? static_cast<float*>(data)[at] : static_cast<int16_t*>(data)[at] / 32768.0;
      peak = std::max(peak, std::fabs(frame[c]));
    }
    if (s.meter.addFrame(frame, channels)) endHop(s);

    const double scale = step(s, peak, settled, release);

    for (int c = 0; c < numChannels; c++) {
      const int32_t at = f * numChannels + c;
      const double in = c < channels ? frame[c]
                                     : (bytesPerSample == 4 ? static_cast<float*>(data)[at]
                                                            : static_cast<int16_t*>(data)[at] / 32768.0);
      const double out = std::clamp(in * scale, -1.0, 1.0);
      if (bytesPerSample == 4) {
        static_cast<float*>(data)[at] = static_cast<float>(out);
      } else {
        static_cast<int16_t*>(data)[at] =
            static_cast<int16_t>(std::lround(std::clamp(out * 32768.0, -32768.0, 32767.0)));
      }
    }
  }
}

void sound_check_level(float* left, float* right, int32_t frames, int rate) {
  State& s = state();
  if (!prepare(s, frames, 2, rate)) return;
  const double settled = smoothingFor(kGainSmoothingSeconds, rate);
  if (s.smoothing <= 0.0) s.smoothing = settled;
  double frame[2];
  for (int32_t f = 0; f < frames; f++) {
    frame[0] = left[f];
    frame[1] = right[f];
    if (s.meter.addFrame(frame, 2)) endHop(s);
    stepGain(s, settled);
    left[f] = static_cast<float>(frame[0] * s.gain);
    right[f] = static_cast<float>(frame[1] * s.gain);
  }
}

void sound_check_limit(float* left, float* right, int32_t frames, int rate) {
  if (!g_enabled.load(std::memory_order_relaxed) || rate <= 0) return;
  State& s = state();
  const double release = smoothingFor(kReleaseSeconds, rate);
  for (int32_t f = 0; f < frames; f++) {
    stepLimiter(s, std::max(std::fabs(static_cast<double>(left[f])), std::fabs(static_cast<double>(right[f]))), release);
    left[f] = static_cast<float>(std::clamp(left[f] * s.limiter, -1.0, 1.0));
    right[f] = static_cast<float>(std::clamp(right[f] * s.limiter, -1.0, 1.0));
  }
}
