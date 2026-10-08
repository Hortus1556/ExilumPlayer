//
// The output effects. See effects.h.
//
#include "effects.h"
#include "fx-chorus.h"
#include "fx-equalizer.h"
#include "fx-reverb.h"
#include "fx-surround.h"
#include "soundcheck.h"
#include <algorithm>
#include <atomic>
#include <cmath>

namespace {

using exilum::fx::Chorus;
using exilum::fx::Equalizer;
using exilum::fx::Reverb;
using exilum::fx::Surround;

constexpr float kPi = 3.14159265358979323846f;

/** Frames worked on at a time: whatever the callback's size, the effects see blocks of at most this. */
constexpr int kBlock = 256;
/**
 * Frames between two steps of every glide: the effects' control rate. It
 * runs on across callbacks, so what comes out does not depend on how the
 * stream cuts the sound into them.
 */
constexpr int kControl = Equalizer::kStep;
/** How quickly an amount follows its slider: the time constant of its glide. */
constexpr float kMixGlideSeconds = 0.03f;
/** The chorus's spread glides more slowly: it moves the delays, and the pitch with them. */
constexpr float kSpreadGlideSeconds = 0.05f;
/** Close enough to stop gliding. */
constexpr float kSnap = 1e-5f;
/** How long the reverb takes to fade out, and in again, around a change of room. */
constexpr float kRoomFadeSeconds = 0.02f;
/**
 * An effect started from nothing has nothing in its delays: it runs this
 * long on the music before any of it is heard, so its own sound does not
 * start with a jump - the chorus's and the surround's delayed copy of the
 * music arriving all at once, the reverb's first reflections.
 */
constexpr float kChorusPrimeSeconds = 0.025f;
constexpr float kSurroundPrimeSeconds = 0.025f;
constexpr float kReverbPrimeSeconds = 0.1f;
/**
 * And the music goes into an empty reverb or chorus faded in over this
 * long, so that what their delays and feedback bring back later - long after
 * the priming - is no step either. The same for a reverb given a new room.
 */
constexpr float kInputFadeSeconds = 0.02f;

/** Sound Check's limiter, when Sound Check is off: just under full scale, released over 150 ms. */
constexpr double kCeiling = 0.966;  // -0.3 dBFS
constexpr double kReleaseSeconds = 0.15;

// The settings, as the UI has them. The serial is bumped after each change.
std::atomic<bool> g_reverbOn{false};
std::atomic<int> g_reverbType{exilum::fx::kReverbHall};
std::atomic<int> g_reverbMix{25};
std::atomic<bool> g_chorusOn{false};
std::atomic<int> g_chorusRate{11};
std::atomic<int> g_chorusSpread{50};
std::atomic<int> g_chorusMix{50};
std::atomic<bool> g_surroundOn{false};
std::atomic<int> g_surroundMix{50};
std::atomic<bool> g_eqOn{false};
std::atomic<int> g_eqBands[kEffectsEqBands] = {};
std::atomic<uint32_t> g_serial{1};
std::atomic<bool> g_resetRequested{false};

/**
 * An amount gliding to its target, a control step at a time. Across a step
 * it goes in a line from [from] to [value]; [hold] steps keep it at nothing.
 */
struct Glide {
  float from = 0.0f;
  float value = 0.0f;
  float target = 0.0f;
  int hold = 0;

  void step(float coefficient) {
    from = value;
    if (hold > 0) {
      hold--;
      return;
    }
    if (value == target) return;
    value += (target - value) * coefficient;
    if (std::fabs(target - value) < kSnap) value = target;
  }
  void snap() {
    from = value = target;
    hold = 0;
  }
  /** At nothing, and staying there: the effect can stop. */
  bool idle() const { return value == 0.0f && target == 0.0f && from == 0.0f; }
  /**
   * Where it is [frame] frames into the step, the first being 0: worked out
   * from the step alone, so the same however the step is cut into pieces.
   */
  float at(int frame) const {
    return from == value ? value : from + (value - from) * (static_cast<float>(frame + 1) / kControl);
  }
  /** Each of [frames] frames from [first] on, into [out]. */
  void fill(float* out, int first, int frames) const {
    for (int i = 0; i < frames; i++) out[i] = at(first + i);
  }
};

/** A share of the music going into an effect, 0 to 1, rising in a line a control step at a time. */
struct Ramp {
  float from = 1.0f;
  float value = 1.0f;

  void restart() { from = value = 0.0f; }
  void full() { from = value = 1.0f; }
  void step(float increment) {
    from = value;
    if (value < 1.0f) value = std::min(1.0f, value + increment);
  }
  bool whole() const { return from == 1.0f && value == 1.0f; }
  /** Where it is [frame] frames into the step, the first being 0. */
  float at(int frame) const { return from + (value - from) * (static_cast<float>(frame + 1) / kControl); }
};

/** Everything the audio thread keeps; nothing else touches it. */
struct Chain {
  int rate = 0;
  bool usable = false;
  uint32_t appliedSerial = 0;
  /** Frames into the control step, 0 to kControl - 1. */
  int phase = 0;

  Equalizer eq;
  Chorus chorus;
  Reverb reverb;
  Surround surround;

  Glide reverbMix, chorusMix, surroundMix, chorusSpread;
  int reverbWanted = exilum::fx::kReverbHall;  // the room asked for; Reverb::type() is the one in use
  float roomFade = 1.0f;                       // 0 to 1: the reverb faded out for a change of room
  float roomFadeFrom = 1.0f;

  // Whether each was running: one that stops is emptied, one that starts is primed.
  bool chorusRunning = false, reverbRunning = false, surroundRunning = false;
  // The music into the reverb and the chorus, faded in when they start empty.
  Ramp reverbIn, chorusIn;
  float inputStep = 0.0f;

  double limiter = 1.0;
  double release = 0.0;
  float mixCoefficient = 0.0f, spreadCoefficient = 0.0f, roomFadeStep = 0.0f;
  int chorusPrime = 0, surroundPrime = 0, reverbPrime = 0;  // in control steps

  /** Frames of silence in a row at the input, and whether everything has been emptied after them. */
  int64_t silentFrames = 0;
  bool idle = false;

  float left[kBlock], right[kBlock], wetL[kBlock], wetR[kBlock];
  float inL[kControl], inR[kControl];
};

Chain& chain() {
  static Chain c;
  return c;
}

float stepCoefficient(float seconds, int rate) {
  return 1.0f - std::exp(-static_cast<float>(kControl) / (seconds * static_cast<float>(rate)));
}

int steps(float seconds, int rate) { return static_cast<int>(std::ceil(seconds * rate / kControl)); }

/** The settings into targets. Off is an amount of none, which an effect fades to and then stops at. */
void applySettings(Chain& c) {
  c.reverbMix.target = g_reverbOn.load(std::memory_order_relaxed) ? g_reverbMix.load(std::memory_order_relaxed) / 100.0f : 0.0f;
  c.reverbWanted = g_reverbType.load(std::memory_order_relaxed);
  c.chorusMix.target = g_chorusOn.load(std::memory_order_relaxed) ? g_chorusMix.load(std::memory_order_relaxed) / 100.0f : 0.0f;
  c.chorusSpread.target = g_chorusSpread.load(std::memory_order_relaxed) / 100.0f;
  c.chorus.setRate(g_chorusRate.load(std::memory_order_relaxed) / 10.0f);
  c.surroundMix.target =
      g_surroundOn.load(std::memory_order_relaxed) ? g_surroundMix.load(std::memory_order_relaxed) / 100.0f : 0.0f;
  float bands[kEffectsEqBands] = {};
  if (g_eqOn.load(std::memory_order_relaxed)) {
    for (int i = 0; i < kEffectsEqBands; i++) bands[i] = static_cast<float>(g_eqBands[i].load(std::memory_order_relaxed));
  }
  c.eq.setTargets(bands);
}

/**
 * Everything silent, for silence: every effect emptied, and every glide
 * where it was going - nothing is heard of it now, and the music that comes
 * next starts from the settings as they are, however far a glide had got.
 */
void clearAll(Chain& c) {
  c.eq.clear();
  c.chorus.clear();
  c.reverb.clear();
  c.surround.clear();
  c.chorusRunning = c.reverbRunning = c.surroundRunning = false;
  c.reverbMix.snap();
  c.chorusMix.snap();
  c.surroundMix.snap();
  c.chorusSpread.snap();
  if (c.reverbWanted != c.reverb.type()) c.reverb.setType(c.reverbWanted);
  c.roomFade = c.roomFadeFrom = 1.0f;
  c.reverbIn.full();
  c.chorusIn.full();
  c.phase = 0;
}

/** Set up for a new rate, everything silent and at its settings. */
void configure(Chain& c, int rate) {
  c.rate = rate;
  applySettings(c);
  c.usable = rate > 0 && c.reverb.configure(rate, c.reverbWanted) && c.chorus.configure(rate) && c.surround.configure(rate);
  if (!c.usable) return;
  c.eq.configure(rate);
  c.chorus.setRate(g_chorusRate.load(std::memory_order_relaxed) / 10.0f);
  c.mixCoefficient = stepCoefficient(kMixGlideSeconds, rate);
  c.spreadCoefficient = stepCoefficient(kSpreadGlideSeconds, rate);
  c.roomFadeStep = static_cast<float>(kControl) / (kRoomFadeSeconds * rate);
  c.inputStep = static_cast<float>(kControl) / (kInputFadeSeconds * rate);
  c.release = 1.0 - std::exp(-1.0 / (kReleaseSeconds * rate));
  c.chorusPrime = steps(kChorusPrimeSeconds, rate);
  c.surroundPrime = steps(kSurroundPrimeSeconds, rate);
  c.reverbPrime = steps(kReverbPrimeSeconds, rate);
  c.limiter = 1.0;
  clearAll(c);
  c.silentFrames = 0;
  c.idle = false;
}

/** Any effect sounding, or about to. */
bool effectsActive(const Chain& c) {
  return !c.eq.flat() || !c.chorusMix.idle() || !c.reverbMix.idle() || !c.surroundMix.idle();
}

/** Anything for the chain to do: an effect, or the limiter still letting go. */
bool needed(const Chain& c, bool soundCheck) { return effectsActive(c) || (!soundCheck && c.limiter != 1.0); }

/** How long the effects that are running ring on after the music stops, in seconds. */
float tailSeconds(const Chain& c) {
  float tail = 0.0f;
  if (!c.eq.flat()) tail = std::max(tail, Equalizer::kTailSeconds);
  if (!c.chorusMix.idle()) tail = std::max(tail, Chorus::kTailSeconds);
  if (!c.reverbMix.idle()) tail = std::max(tail, c.reverb.tailSeconds());
  if (!c.surroundMix.idle()) tail = std::max(tail, Surround::kTailSeconds);
  return tail;
}

/** The limiter letting go over [frames] frames, holding nothing. */
void recover(Chain& c, int32_t frames) {
  if (c.limiter == 1.0) return;
  c.limiter = 1.0 - (1.0 - c.limiter) * std::pow(1.0 - c.release, frames);
  if (c.limiter > 1.0 - 1e-6) c.limiter = 1.0;
}

/** One control step on: every glide, the equalizer's, the change of room. Starts and stops the effects. */
void controlStep(Chain& c) {
  // An effect starting from nothing runs on the music, faded into it and
  // primed, before its glide lets it be heard. One already where it is going
  // - put there in silence, see clearAll - starts with the music, as it is.
  const auto start = [](Glide& glide, bool& running, int prime, Ramp* in) {
    if (!running && !glide.idle()) {
      running = true;
      if (glide.value == 0.0f) {
        glide.hold = prime;
        if (in) in->restart();
      }
    }
  };
  start(c.chorusMix, c.chorusRunning, c.chorusPrime, &c.chorusIn);
  start(c.reverbMix, c.reverbRunning, c.reverbPrime, &c.reverbIn);
  start(c.surroundMix, c.surroundRunning, c.surroundPrime, nullptr);

  c.reverbIn.step(c.inputStep);
  c.chorusIn.step(c.inputStep);
  c.reverbMix.step(c.mixCoefficient);
  c.chorusMix.step(c.mixCoefficient);
  c.surroundMix.step(c.mixCoefficient);
  c.chorusSpread.step(c.spreadCoefficient);
  c.eq.step();

  // A new room: the old one fades out, the new one is put in empty - the
  // music faded into it - and fades in.
  c.roomFadeFrom = c.roomFade;
  if (c.reverbWanted != c.reverb.type()) {
    if (!c.reverbRunning || c.roomFade == 0.0f) {
      c.reverb.setType(c.reverbWanted);
      if (c.reverbRunning) c.reverbIn.restart();
    } else {
      c.roomFade = std::max(0.0f, c.roomFade - c.roomFadeStep);
    }
  } else if (c.roomFade != 1.0f) {
    c.roomFade = std::min(1.0f, c.roomFade + c.roomFadeStep);
  }

  // An effect that has faded out stops, emptied.
  const auto stop = [](Glide& glide, bool& running, auto&& clear) {
    if (running && glide.idle()) {
      running = false;
      clear();
    }
  };
  stop(c.chorusMix, c.chorusRunning, [&] { c.chorus.clear(); });
  stop(c.reverbMix, c.reverbRunning, [&] { c.reverb.clear(); });
  stop(c.surroundMix, c.surroundRunning, [&] { c.surround.clear(); });
}

/** [frames] frames of c.left/c.right through the effects, in place, not past the end of the control step. */
void processChunk(Chain& c, float* left, float* right, int frames, bool soundCheck) {
  const int at = c.phase;

  // The equalizer.
  if (!c.eq.flat()) c.eq.process(left, right, frames, at);

  float line[kControl];

  // The chorus: the music crossfaded with the chorus's sound, as DirectX's does.
  if (c.chorusRunning) {
    // The spread's cos and sin, in a line across the step from where it was to where it is.
    const float cosFrom = Chorus::spreadCos(c.chorusSpread.from), cosTo = Chorus::spreadCos(c.chorusSpread.value);
    const float sinFrom = Chorus::spreadSin(c.chorusSpread.from), sinTo = Chorus::spreadSin(c.chorusSpread.value);
    float cosLine[kControl], sinLine[kControl];
    for (int i = 0; i < frames; i++) {
      const float t = static_cast<float>(at + i + 1) / kControl;
      cosLine[i] = cosFrom == cosTo ? cosTo : cosFrom + (cosTo - cosFrom) * t;
      sinLine[i] = sinFrom == sinTo ? sinTo : sinFrom + (sinTo - sinFrom) * t;
    }
    const float* inL = left;
    const float* inR = right;
    if (!c.chorusIn.whole()) {
      for (int i = 0; i < frames; i++) {
        const float gain = c.chorusIn.at(at + i);
        c.inL[i] = left[i] * gain;
        c.inR[i] = right[i] * gain;
      }
      inL = c.inL;
      inR = c.inR;
    }
    c.chorus.process(inL, inR, c.wetL, c.wetR, frames, cosLine, sinLine);
    c.chorusMix.fill(line, at, frames);
    for (int i = 0; i < frames; i++) {
      left[i] += (c.wetL[i] - left[i]) * line[i];
      right[i] += (c.wetR[i] - right[i]) * line[i];
    }
  }

  // The reverb: the music and the room at the same loudness all the way
  // across, the room brought up to the music's level first.
  if (c.reverbRunning) {
    const float* inL = left;
    const float* inR = right;
    if (!c.reverbIn.whole()) {
      for (int i = 0; i < frames; i++) {
        const float gain = c.reverbIn.at(at + i);
        c.inL[i] = left[i] * gain;
        c.inR[i] = right[i] * gain;
      }
      inL = c.inL;
      inR = c.inR;
    }
    c.reverb.process(inL, inR, c.wetL, c.wetR, frames);
    const float makeup = c.reverb.makeup();
    c.reverbMix.fill(line, at, frames);
    for (int i = 0; i < frames; i++) {
      const float theta = line[i] * (kPi / 2.0f);
      const float fade = c.roomFade == c.roomFadeFrom
                             ? c.roomFade
                             : c.roomFadeFrom + (c.roomFade - c.roomFadeFrom) * (static_cast<float>(at + i + 1) / kControl);
      const float wet = std::sin(theta) * makeup * fade;
      const float dry = std::cos(theta);
      left[i] = left[i] * dry + c.wetL[i] * wet;
      right[i] = right[i] * dry + c.wetR[i] * wet;
    }
  }

  // The surround, around all of it.
  if (c.surroundRunning) {
    c.surroundMix.fill(line, at, frames);
    c.surround.process(left, right, frames, line);
  }

  // The level, and the peaks held under full scale. With every effect gone
  // quiet the limiter only lets go, as there is nothing of theirs to hold.
  if (soundCheck) {
    sound_check_limit(left, right, frames, c.rate);
  } else if (c.limiter != 1.0 || effectsActive(c)) {
    const bool holding = effectsActive(c);
    for (int i = 0; i < frames; i++) {
      const double peak = std::max(std::fabs(static_cast<double>(left[i])), std::fabs(static_cast<double>(right[i])));
      if (holding && peak * c.limiter > kCeiling) {
        c.limiter = kCeiling / peak;
      } else {
        c.limiter += (1.0 - c.limiter) * c.release;
        if (holding && peak * c.limiter > kCeiling) c.limiter = kCeiling / peak;
        if (c.limiter > 1.0 - 1e-6) c.limiter = 1.0;
      }
      left[i] = static_cast<float>(left[i] * c.limiter);
      right[i] = static_cast<float>(right[i] * c.limiter);
    }
  }
}

/** A block of at most kBlock frames, cut where the control steps fall. */
void processBlock(Chain& c, int frames, bool soundCheck) {
  if (soundCheck) sound_check_level(c.left, c.right, frames, c.rate);
  for (int done = 0; done < frames;) {
    if (c.phase == 0) controlStep(c);
    const int count = std::min(frames - done, kControl - c.phase);
    processChunk(c, c.left + done, c.right + done, count, soundCheck);
    done += count;
    c.phase = (c.phase + count) % kControl;
  }
}

bool allZero(const int16_t* samples, int32_t count) {
  for (int32_t i = 0; i < count; i++) {
    if (samples[i] != 0) return false;
  }
  return true;
}

}  // namespace

void effects_set_reverb(bool enabled, int type, int mixPercent) {
  g_reverbOn.store(enabled, std::memory_order_relaxed);
  g_reverbType.store(type >= 0 && type < exilum::fx::kReverbTypes ? type : exilum::fx::kReverbHall,
                     std::memory_order_relaxed);
  g_reverbMix.store(std::clamp(mixPercent, 0, 100), std::memory_order_relaxed);
  g_serial.fetch_add(1, std::memory_order_release);
}

void effects_set_chorus(bool enabled, int rateTenthsHz, int spreadPercent, int mixPercent) {
  g_chorusOn.store(enabled, std::memory_order_relaxed);
  g_chorusRate.store(std::clamp(rateTenthsHz, 1, 50), std::memory_order_relaxed);
  g_chorusSpread.store(std::clamp(spreadPercent, 0, 100), std::memory_order_relaxed);
  g_chorusMix.store(std::clamp(mixPercent, 0, 100), std::memory_order_relaxed);
  g_serial.fetch_add(1, std::memory_order_release);
}

void effects_set_surround(bool enabled, int mixPercent) {
  g_surroundOn.store(enabled, std::memory_order_relaxed);
  g_surroundMix.store(std::clamp(mixPercent, 0, 100), std::memory_order_relaxed);
  g_serial.fetch_add(1, std::memory_order_release);
}

void effects_set_equalizer(bool enabled, const int32_t* bandsDb, int count) {
  g_eqOn.store(enabled, std::memory_order_relaxed);
  for (int i = 0; i < kEffectsEqBands; i++) {
    const int db = i < count && bandsDb ? bandsDb[i] : 0;
    g_eqBands[i].store(std::clamp(db, -Equalizer::kMaxDb, Equalizer::kMaxDb), std::memory_order_relaxed);
  }
  g_serial.fetch_add(1, std::memory_order_release);
}

void effects_request_reset() { g_resetRequested.store(true, std::memory_order_release); }

void effects_process_output(void* data, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample, int rate) {
  Chain& c = chain();
  if (numChannels != 2 || bytesPerSample != 2 || rate <= 0) {
    sound_check_process(data, numFrames, numChannels, bytesPerSample, rate);
    return;
  }
  if (rate != c.rate) configure(c, rate);
  if (g_resetRequested.exchange(false, std::memory_order_acquire)) clearAll(c);
  const uint32_t serial = g_serial.load(std::memory_order_acquire);
  if (serial != c.appliedSerial) {
    c.appliedSerial = serial;
    applySettings(c);
    // Changed while everything rests in silence: there now, rather than
    // gliding there once music comes - which would play the old setting
    // first.
    if (c.idle) clearAll(c);
  }

  const bool soundCheck = sound_check_enabled();
  if (soundCheck) c.limiter = 1.0;  // Sound Check's own holds the peaks
  int16_t* samples = static_cast<int16_t*>(data);
  if (!c.usable || !needed(c, soundCheck)) {
    // Resting is the chain's, after its effects: with none, there is none.
    c.idle = false;
    c.silentFrames = 0;
    sound_check_process(data, numFrames, numChannels, bytesPerSample, rate);
    return;
  }

  // Silence in, and everything has rung out: nothing to do until music comes.
  if (allZero(samples, numFrames * 2)) {
    c.silentFrames += numFrames;
    if (!c.idle && c.silentFrames >= static_cast<int64_t>(tailSeconds(c) * rate)) {
      clearAll(c);
      c.idle = true;
    }
  } else {
    c.silentFrames = 0;
    c.idle = false;
  }
  if (c.idle) {
    recover(c, numFrames);
    sound_check_process(data, numFrames, numChannels, bytesPerSample, rate);
    return;
  }

  for (int32_t start = 0; start < numFrames; start += kBlock) {
    const int frames = std::min<int32_t>(kBlock, numFrames - start);
    int16_t* block = samples + start * 2;
    constexpr float kIn = 1.0f / 32768.0f;
    for (int i = 0; i < frames; i++) {
      c.left[i] = block[i * 2] * kIn;
      c.right[i] = block[i * 2 + 1] * kIn;
    }
    processBlock(c, frames, soundCheck);
    for (int i = 0; i < frames; i++) {
      block[i * 2] = static_cast<int16_t>(std::lrint(std::clamp(c.left[i] * 32768.0f, -32768.0f, 32767.0f)));
      block[i * 2 + 1] = static_cast<int16_t>(std::lrint(std::clamp(c.right[i] * 32768.0f, -32768.0f, 32767.0f)));
    }
  }
}
