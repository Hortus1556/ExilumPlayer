//
// AHX / Hively decoder. See decoder-hvl.h.
//
#include "decoder-hvl.h"
#include "visual-state.h"
#include "visual-publisher.h"
extern "C" {
// types.h first: replay.h uses its int8/uint16/BOOL spellings and does not
// include it itself.
#include "hvl/types.h"
#include "hvl/replay.h"
}
// replay.h spells its own voice ceiling "#define MAX_CHANNELS 16", which from
// here on quietly rewrote every later MAX_CHANNELS in this file - including
// the stride of the pattern arrays, which the wire format fixes at 64. Native
// wrote rows sixteen apart into a buffer Kotlin read sixty-four apart, so the
// pattern view showed every fourth row and ran out of data a quarter of the
// way down. The replayer's own limit lives in hvl_tune::ht_Channels, which is
// what the code below uses; this name belongs to visual-state.h.
/** The replayer's own voice ceiling, kept before the name is given back. */
constexpr int MAX_HVL_VOICES = MAX_CHANNELS;
#undef MAX_CHANNELS
static_assert(MAX_CHANNELS == 64, "MAX_CHANNELS must be the wire format's, not the replayer's");
#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

constexpr const char* TAG = "Exilum HVL";
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

/**
 * The replayer's interrupt rate, before the tune's own multiplier.
 *
 * hvl_DecodeFrame covers a fiftieth of a second, but it runs
 * ht_SpeedMultiplier interrupts to do it - and a row, a voice change and a
 * tick of ht_PlayingTime all happen per interrupt, not per frame. So the real
 * clock is this times the multiplier, which is what every duration here is
 * measured against.
 */
constexpr int kBaseIrqRate = 50;

/**
 * Where the mixer's 16.16 sample position wraps.
 *
 * hvl_mixchunk reads vc_MixSource over 0x280 bytes and wraps there whatever
 * the waveform's own length is, so a trace drawn the same way has to wrap in
 * the same place.
 */
constexpr uint32_t kMixWrap = 0x280u << 16;

/** Longest a single subsong is measured for before its length is estimated. */
constexpr int kMaxMeasureSeconds = 15 * 60;

/** Total measuring work one load may do, in replayer interrupts. */
constexpr int64_t kMeasureBudgetIrqs = 200000;

struct HvlState {
  std::mutex mutex;
  hvl_tune* tune = nullptr;
  bool ended = false;
  bool loop = false;
  int sampleRate = 48000;
  int subsong = 0;
  std::string title;
  std::vector<uint8_t> fileData;
  std::array<int, MAX_CHANNELS> muted{};

  /**
   * Whole replayer frames produced but not yet handed to the output.
   *
   * The replayer works a fiftieth of a second at a time and the audio device
   * asks for whatever its buffer needs, so the two never line up. Rather than
   * ask for a partial frame - which the replayer has no way to give - a whole
   * one is generated and what is left over waits here for the next call.
   */
  std::vector<int16_t> spill;
  size_t spillRead = 0;

  /**
   * The two things about this replayer worth exposing.
   *
   * Separation scales the tune's own stereo rather than replacing it: AHX
   * takes its spread from the ht_defstereo it was built with and HVL carries
   * one in the file, so 200 is whatever the tune asked for, 100 half of that
   * and 0 mono - libopenmpt's scale.
   * Gain multiplies the mix gain the tune loaded with, which for AHX is
   * between 71 and 100 percent depending on that same spread - these tunes
   * are quiet, and a boost is the one control that is always wanted.
   */
  int stereoPercent = 100;
  int gainPercent = 100;
  int baseMixGain = 256;

  /** Global pitch, and the rate multiplier it works out to. */
  double pitchSemitones = 0.0;
  double pitchFactor = 1.0;

  /**
   * Global tempo, as a factor. A faster tune is the same interrupts with
   * fewer frames of audio between them; the voices step through their
   * waveforms per frame, so the pitch stays where it was.
   */
  double tempoFactor = 1.0;

  /** What a tick's frame count was rounded down by, carried into the next. */
  double tickCarry = 0.0;

  /**
   * The unpitched step each voice would have had, and what was last written
   * over it.
   *
   * The replayer only recomputes vc_Delta when a note plants a new period, so
   * scaling it every interrupt would compound. Comparing against what was
   * written last time is how a fresh value is told from one already scaled.
   */
  std::array<uint32_t, MAX_HVL_VOICES> plainDelta{};
  std::array<uint32_t, MAX_HVL_VOICES> pitchedDelta{};
  std::array<uint32_t, MAX_HVL_VOICES> plainRingDelta{};
  std::array<uint32_t, MAX_HVL_VOICES> pitchedRingDelta{};

  /** Measured length of each subsong, filled at load. */
  std::vector<int> subsongMs;
  /** Whether each measurement found the tune's end, rather than running out of time. */
  std::vector<bool> subsongKnown;
  /** A length the listener set for this subsong, or 0 - see hvl_decoder_set_duration_override_ms. */
  int durationOverrideMs = 0;

  /** What the views read. Filled by the render callback - see VisualPublisher. */
  VisualPublisher visuals;
  int positionMs = 0;
  int totalMs = 0;

  /** Where the tune first went round, or -1. See the render loop. */
  int loopedAtMs = -1;

  static HvlState& instance() {
    static HvlState s;
    return s;
  }
};

/** Interrupts per second for the loaded tune. */
int irqRate(const hvl_tune* tune) {
  const int mult = tune ? std::max<int>(1, tune->ht_SpeedMultiplier) : 1;
  return kBaseIrqRate * mult;
}

/**
 * Frames of audio the next replayer interrupt produces, at the current rate
 * and tempo. Whole frames, with the fraction carried: at a tempo off one the
 * exact figure is rarely whole, and rounding it away every interrupt would
 * leave the tempo off by that much.
 */
int irqFrames(HvlState& state) {
  const double exact =
      static_cast<double>(state.sampleRate) / (irqRate(state.tune) * state.tempoFactor) +
      state.tickCarry;
  const int frames = std::max(1, static_cast<int>(exact));
  state.tickCarry = std::max(0.0, exact - frames);
  return frames;
}

/**
 * How long the tune runs if it plays straight through.
 *
 * Every position, every row of it, at the starting tempo. Jumps, breaks and
 * tempo changes make the real figure differ, and nothing short of playing the
 * tune finds that out - but a seek bar needs a length before the first frame,
 * and this is the same figure the trackers themselves display.
 */
int nominalDurationMs(const hvl_tune* tune, int subsong) {
  if (!tune) return 0;
  // A subsong is an entry point, not a section: it starts at its own position
  // and plays to the end of the tune like any other.
  int start = 0;
  if (subsong > 0 && subsong <= tune->ht_SubsongNr && tune->ht_Subsongs) {
    start = tune->ht_Subsongs[subsong - 1];
  }
  const int positions = std::max(0, tune->ht_PositionNr - start);
  const int64_t rows = static_cast<int64_t>(positions) * tune->ht_TrackLength;
  const int64_t irqs = rows * std::max<int>(1, tune->ht_Tempo);
  return static_cast<int>(irqs * 1000 / irqRate(tune));
}

/**
 * Pushes the stereo and gain settings into the tune. Caller holds the mutex.
 *
 * Written into ht_defpanleft/right as well as into the voices, because
 * hvl_InitSubsong rebuilds every voice's pan from those two - so a setting
 * that only touched the voices would be undone by the next subsong or loop.
 * The voice order is the replayer's: 0 and 3 one side, 1 and 2 the other.
 */
void applySettingsLocked(HvlState& state) {
  hvl_tune* t = state.tune;
  if (!t) return;

  const int base = std::clamp<int>(t->ht_defstereo, 0, 4);
  // On libopenmpt's 0-200 scale, where 200 is the tune's whole spread - see
  // hvl_decoder_set_stereo_separation.
  const int spread = std::clamp(state.stereoPercent, 0, 200);
  const int left = 128 + (stereopan_left[base] - 128) * spread / 200;
  const int right = 128 + (stereopan_right[base] - 128) * spread / 200;
  t->ht_defpanleft = std::clamp(left, 0, 255);
  t->ht_defpanright = std::clamp(right, 0, 255);

  for (int i = 0; i + 3 < MAX_HVL_VOICES; i += 4) {
    const int sides[4] = {static_cast<int>(t->ht_defpanleft), static_cast<int>(t->ht_defpanright),
                          static_cast<int>(t->ht_defpanright), static_cast<int>(t->ht_defpanleft)};
    for (int j = 0; j < 4; j++) {
      hvl_voice& v = t->ht_Voices[i + j];
      v.vc_Pan = static_cast<uint32>(sides[j]);
      v.vc_SetPan = static_cast<uint32>(sides[j]);
      v.vc_PanMultLeft = panning_left[sides[j]];
      v.vc_PanMultRight = panning_right[sides[j]];
    }
  }

  t->ht_mixgain = std::max(1, state.baseMixGain * std::clamp(state.gainPercent, 10, 400) / 100);

  // Muting goes back on too, after anything that rebuilt the voices.
  //
  // Through vc_Muted, a local addition that only silences a voice, and not
  // vc_TrackOn, which this used: the replayer skips every step of a track
  // that is off, and with it the track's speed changes and position jumps -
  // muting the one channel that set the tempo stopped the tempo changing.
  for (int i = 0; i < t->ht_Channels && i < MAX_HVL_VOICES; i++) {
    t->ht_Voices[i].vc_Muted = state.muted[i] ? 1 : 0;
  }
}

/**
 * Pushes the global pitch into the voices. Caller holds the mutex.
 *
 * The replayer turns a period into a 16.16 step through the wavetable, so
 * shifting pitch without touching tempo is a matter of scaling that step: the
 * interrupts still come fifty times a second, the wave is just read faster or
 * slower. Called after every interrupt, because that is when a new note may
 * have planted a fresh step.
 */
void applyPitchLocked(HvlState& state) {
  hvl_tune* t = state.tune;
  if (!t) return;
  const int voices = std::min<int>(t->ht_Channels, MAX_HVL_VOICES);
  for (int i = 0; i < voices; i++) {
    hvl_voice& v = t->ht_Voices[i];

    if (v.vc_Delta != state.pitchedDelta[i]) state.plainDelta[i] = v.vc_Delta;
    const auto scaled = static_cast<uint32_t>(std::clamp<double>(
        state.plainDelta[i] * state.pitchFactor, 1.0, static_cast<double>(kMixWrap - 1)));
    v.vc_Delta = scaled;
    state.pitchedDelta[i] = scaled;

    if (!v.vc_RingMixSource) continue;
    if (v.vc_RingDelta != state.pitchedRingDelta[i]) state.plainRingDelta[i] = v.vc_RingDelta;
    const auto ringScaled = static_cast<uint32_t>(std::clamp<double>(
        state.plainRingDelta[i] * state.pitchFactor, 1.0, static_cast<double>(kMixWrap - 1)));
    v.vc_RingDelta = ringScaled;
    state.pitchedRingDelta[i] = ringScaled;
  }
}

/**
 * How long a subsong runs, by playing it without listening.
 *
 * Counting positions and rows gets this wrong for most tunes, because a
 * position jump or a pattern break ends them long before the order list does
 * - which is why the seek bar ran on into a stretch of silence. The replayer
 * knows exactly when a tune is over; it sets ht_SongEndReached. Running the
 * interrupts with no mixing is cheap, exact, and leaves the tune ready to be
 * reinitialised.
 *
 * Capped, because a tune that never sets the flag would otherwise spin here.
 */
int measureSubsongMs(hvl_tune* tune, int subsong, int64_t& budget) {
  const int rate = irqRate(tune);
  const int64_t cap = std::min<int64_t>(budget, static_cast<int64_t>(rate) * kMaxMeasureSeconds);
  if (cap <= 0) return 0;

  hvl_InitSubsong(tune, static_cast<uint32>(subsong));
  int64_t irqs = 0;
  while (irqs < cap && !tune->ht_SongEndReached) {
    hvl_play_irq(tune);
    irqs++;
  }
  budget -= irqs;
  return static_cast<int>(irqs * 1000 / rate);
}

void releaseLocked(HvlState& state) {
  if (state.tune) {
    hvl_FreeTune(state.tune);
    state.tune = nullptr;
  }
  state.ended = false;
  state.spill.clear();
  state.spillRead = 0;
}

/**
 * Whether [buf] is a tune the replayer can walk without leaving it - and,
 * where it names something the tune does not have, makes it name nothing.
 *
 * The replayer trusts the file: it reads the counts in the header and walks
 * that many subsongs, positions, tracks and instruments from wherever it has
 * got to, with never a look at how long the file is. One cut short - a copy
 * interrupted, a download that stopped - was read hundreds of kilobytes past
 * its end. A Hively header can also say up to 67 channels where the replayer
 * holds 16, and its positions were written past their sixteen places. So the
 * same walk is made here first, counting, and a file that does not hold what
 * it says it holds is no tune.
 *
 * What a whole file can still get wrong is patched rather than turned away,
 * as the replayer itself does for an AHX's subsongs: a subsong that starts
 * past the last position starts at the first, a position naming a track the
 * file has not got plays the first, a step naming an instrument it has not
 * got plays none. Each of those was an index into memory the tune never
 * filled.
 */
bool laidOutWithin(uint8_t* buf, size_t size) {
  if (size < 16) return false;
  const bool ahx = buf[0] == 'T' && buf[1] == 'H' && buf[2] == 'X' && buf[3] < 3;
  const bool hively = buf[0] == 'H' && buf[1] == 'V' && buf[2] == 'L' && buf[3] <= 1;
  if (!ahx && !hively) return false;

  const size_t positions = (static_cast<size_t>(buf[6] & 0x0f) << 8) | buf[7];
  const size_t trackLength = buf[10];
  const size_t lastTrack = buf[11];
  const size_t instruments = buf[12];
  const size_t subsongs = buf[13];
  const size_t channels = ahx ? 4 : static_cast<size_t>(buf[8] >> 2) + 4;
  if (positions < 1 || positions > 1000 || trackLength < 1 || trackLength > 64 || instruments > 64) return false;
  if (channels > MAX_HVL_VOICES) return false;
  // The panning it starts with is one of five.
  if (hively && buf[15] > 4) return false;
  // Where its name is; the names are read as far as a zero, which is why
  // the replayer is handed a copy with zeros after it.
  if (((static_cast<size_t>(buf[4]) << 8) | buf[5]) >= size) return false;

  size_t at = ahx ? 14 : 16;
  const auto has = [&](size_t bytes) { return bytes <= size - at; };

  if (!has(subsongs * 2)) return false;
  for (size_t i = 0; i < subsongs; i++, at += 2) {
    if (((static_cast<size_t>(buf[at]) << 8) | buf[at + 1]) >= positions) buf[at] = buf[at + 1] = 0;
  }

  if (!has(positions * channels * 2)) return false;
  for (size_t i = 0; i < positions * channels; i++, at += 2) {
    if (buf[at] > lastTrack) buf[at] = 0;
  }

  // Track 0 is not in the file when the header says it is blank.
  const size_t stored = lastTrack + 1 - ((buf[6] & 0x80) != 0 ? 1 : 0);
  for (size_t step = 0; step < stored * trackLength; step++) {
    if (ahx) {
      if (!has(3)) return false;
      const size_t instrument = (static_cast<size_t>(buf[at] & 0x03) << 4) | (buf[at + 1] >> 4);
      if (instrument > instruments) {
        buf[at] &= 0xfc;
        buf[at + 1] &= 0x0f;
      }
      at += 3;
    } else {
      // An empty step is one byte, any other five.
      if (!has(1)) return false;
      if (buf[at] == 0x3f) {
        at += 1;
        continue;
      }
      if (!has(5)) return false;
      if (buf[at + 1] > instruments) buf[at + 1] = 0;
      at += 5;
    }
  }

  for (size_t i = 0; i < instruments; i++) {
    if (!has(22)) return false;
    const size_t entries = buf[at + 21];
    if (!has(22 + entries * (ahx ? 4 : 5))) return false;
    at += 22 + entries * (ahx ? 4 : 5);
  }
  return true;
}

/** Builds the tune from state.fileData at state.sampleRate. Caller holds the mutex. */
bool openLocked(HvlState& state, int subsong) {
  // A copy, to be put right where it needs it, with zeros after it: the
  // replayer reads the tune's name and its instruments' as far as the zero
  // that ends each, and a file whose last name has none ran on past the end.
  std::vector<uint8_t> file(state.fileData);
  if (!laidOutWithin(file.data(), file.size())) return false;
  const size_t length = file.size();
  file.resize(length + 256, 0);

  // hvl_reset takes ownership of nothing and copies what it needs.
  hvl_tune* tune = hvl_reset(
      file.data(), static_cast<uint32>(length),
      /* defstereo */ 4, static_cast<uint32>(state.sampleRate), /* freeit */ FALSE);
  if (!tune) return false;

  state.tune = tune;
  state.title = tune->ht_Name;
  // Whatever the tune loaded with is what 100 percent means.
  state.baseMixGain = tune->ht_mixgain;

  // Every subsong's length, measured before anything is heard - cheap,
  // because nothing is mixed - under one shared budget, so a tune with many
  // of them cannot make loading crawl.
  const int subsongs = tune->ht_SubsongNr + 1;
  state.subsongMs.assign(subsongs, 0);
  state.subsongKnown.assign(subsongs, false);
  int64_t budget = kMeasureBudgetIrqs;
  for (int i = 0; i < subsongs; i++) {
    state.subsongMs[i] = measureSubsongMs(tune, i, budget);
    state.subsongKnown[i] = state.subsongMs[i] > 0 && tune->ht_SongEndReached != 0;
    if (state.subsongMs[i] <= 0) state.subsongMs[i] = nominalDurationMs(tune, i);
  }
  state.durationOverrideMs = 0;

  // Subsong indices run 0..ht_SubsongNr inclusive - ht_SubsongNr is the last
  // index, not the count, so the last subsong of a tune is a valid choice.
  // Started here rather than before the measuring, which left the replayer at
  // the end of whichever subsong it measured last.
  const int wanted = std::clamp<int>(subsong, 0, tune->ht_SubsongNr);
  hvl_InitSubsong(tune, static_cast<uint32>(wanted));
  state.subsong = wanted;
  state.totalMs = state.subsongMs[wanted];

  applySettingsLocked(state);
  // No stamp from the old tune describes this one's timeline.
  state.visuals.reset();
  state.positionMs = 0;
  state.loopedAtMs = -1;
  state.ended = false;
  state.spill.clear();
  state.spillRead = 0;
  state.plainDelta.fill(0);
  state.pitchedDelta.fill(0);
  state.plainRingDelta.fill(0);
  state.pitchedRingDelta.fill(0);
  return true;
}

}  // namespace

bool hvl_decoder_probe(const uint8_t* data, size_t size) {
  if (!data || size < 4) return false;
  // The replayer's own check, made before loading so a file it would refuse
  // never gets as far as being parsed.
  const bool ahx = data[0] == 'T' && data[1] == 'H' && data[2] == 'X' && data[3] < 3;
  const bool hvl = data[0] == 'H' && data[1] == 'V' && data[2] == 'L' && data[3] <= 1;
  return ahx || hvl;
}

bool hvl_decoder_load(const uint8_t* data, size_t size, int sampleRate) {
  if (!hvl_decoder_probe(data, size)) return false;

  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  // Builds the replayer's shared tables. Idempotent, and cheap after the
  // first call, so it lives here rather than needing an init hook.
  static bool tablesReady = false;
  if (!tablesReady) {
    hvl_InitReplayer();
    tablesReady = true;
  }

  releaseLocked(state);
  state.muted.fill(0);
  state.sampleRate = sampleRate > 0 ? sampleRate : state.sampleRate;
  state.fileData.assign(data, data + size);

  if (!openLocked(state, 0)) {
    state.fileData.clear();
    LOG_WARN("load failed");
    return false;
  }
  LOG_INFO("loaded '%s': %d channels, %d subsongs",
           state.title.c_str(), state.tune->ht_Channels, state.tune->ht_SubsongNr + 1);
  return true;
}

void hvl_decoder_release() {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  releaseLocked(state);
  state.fileData.clear();
}

bool hvl_decoder_loaded() { return HvlState::instance().tune != nullptr; }
const char* hvl_decoder_title() { return HvlState::instance().title.c_str(); }

void hvl_decoder_prepare_start(int rate) {
  hvl_decoder_set_sample_rate(rate);
  HvlState::instance().visuals.reset();
}

void hvl_decoder_set_sample_rate(int rate) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (rate <= 0 || rate == state.sampleRate || !state.tune) return;

  // The mixing rate is baked into the tune, so this rebuilds it. Position is
  // lost, which is acceptable: this only happens before playback starts.
  const int subsong = state.subsong;
  releaseLocked(state);
  state.sampleRate = rate;
  openLocked(state, subsong);
  LOG_INFO("re-opened at %d Hz", rate);
}

int hvl_decoder_duration_ms() { return HvlState::instance().totalMs; }

int hvl_decoder_subsong_duration_ms(int index) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (index < 0 || index >= static_cast<int>(state.subsongMs.size())) return 0;
  return state.subsongMs[index];
}

int hvl_decoder_position_ms() {
  // From the audible frame, not from the replayer: the replayer is already a
  // buffer ahead of the speaker, and the seek bar should agree with the ear.
  // Except in the moment after a seek, when the history has been dropped and
  // nothing audible describes the new position yet - reporting zero there
  // made the bar snap back to the start before catching up.
  HvlState& state = HvlState::instance();
  return state.visuals.published() ? state.visuals.scalar(kScalarTimeMs) : state.positionMs;
}

void hvl_decoder_seek_ms(int ms) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  hvl_tune* t = state.tune;
  if (!t) return;

  // There is no seek to call. A tune's state at a given moment is the result
  // of every interrupt before it, so the only way to a position is to play up
  // to it - which is exact, and silent, because nothing is mixed. This used
  // to just restart the subsong, which is why dragging the playback line
  // started the music again from the top.
  const int rate = irqRate(t);
  const int64_t want = static_cast<int64_t>(std::max(0, ms)) * rate / 1000;

  if (want < static_cast<int64_t>(t->ht_PlayingTime)) {
    hvl_InitSubsong(t, static_cast<uint32>(state.subsong));
    applySettingsLocked(state);
  }
  const int64_t cap = static_cast<int64_t>(rate) * kMaxMeasureSeconds;
  while (static_cast<int64_t>(t->ht_PlayingTime) < want &&
         static_cast<int64_t>(t->ht_PlayingTime) < cap && !t->ht_SongEndReached) {
    hvl_play_irq(t);
  }

  // In 64 bits: the playing time is a count of interrupts, and a thousand times
  // it passes what an int holds after three hours at 200 Hz.
  state.positionMs = static_cast<int>(static_cast<int64_t>(t->ht_PlayingTime) * 1000 / rate);
  // Whatever was rendered but not handed over yet belongs to the old position.
  state.spill.clear();
  state.spillRead = 0;
  state.visuals.reset();
  state.ended = false;
}

bool hvl_decoder_ended() { return HvlState::instance().ended; }
void hvl_decoder_set_loop(bool loop) { HvlState::instance().loop = loop; }

void hvl_decoder_set_stereo_separation(int percent) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.stereoPercent = std::clamp(percent, 0, 200);
  applySettingsLocked(state);
}

void hvl_decoder_set_pitch(double semitones) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.pitchSemitones = std::clamp(semitones, -12.0, 12.0);
  state.pitchFactor = std::pow(2.0, state.pitchSemitones / 12.0);
}

void hvl_decoder_set_tempo(double percent) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.tempoFactor = std::clamp(percent, 10.0, 200.0) / 100.0;
}

void hvl_decoder_set_gain(int percent) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.gainPercent = std::clamp(percent, 10, 400);
  applySettingsLocked(state);
}

int hvl_decoder_subsong_count() {
  HvlState& state = HvlState::instance();
  return state.tune ? state.tune->ht_SubsongNr + 1 : 1;
}

int hvl_decoder_current_subsong() { return HvlState::instance().subsong; }

bool hvl_decoder_start_subsong(int index) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.tune || index < 0 || index > state.tune->ht_SubsongNr) return false;
  hvl_InitSubsong(state.tune, static_cast<uint32>(index));
  applySettingsLocked(state);
  state.subsong = index;
  state.totalMs = index < static_cast<int>(state.subsongMs.size()) ? state.subsongMs[index] : 0;
  state.positionMs = 0;
  state.loopedAtMs = -1;
  state.visuals.reset();
  state.ended = false;
  return true;
}

// These ask the tune, which is freed - on the thread that loads the next
// one - under the mutex: so do they, and the name is handed back as a copy.
int hvl_decoder_channels() {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.tune ? std::min<int>(state.tune->ht_Channels, MAX_HVL_VOICES) : 0;
}

int hvl_decoder_instrument_count() {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.tune ? state.tune->ht_InstrumentNr : 0;
}

std::string hvl_decoder_instrument_name(int index) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.tune || index < 0 || index > state.tune->ht_InstrumentNr) return "";
  return state.tune->ht_Instruments[index].ins_Name;
}

void hvl_decoder_mute(int channel, int muted) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (channel < 0 || channel >= MAX_CHANNELS) return;
  state.muted[channel] = muted ? 1 : 0;
  if (state.tune && channel < state.tune->ht_Channels) {
    state.tune->ht_Voices[channel].vc_Muted = muted ? 1 : 0;
  }
}

int hvl_decoder_muted(int channel) {
  HvlState& state = HvlState::instance();
  if (channel < 0 || channel >= MAX_CHANNELS) return 0;
  return state.muted[channel];
}

int hvl_decoder_pattern_rows() {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.tune ? state.tune->ht_TrackLength : 0;
}

int hvl_decoder_position_count() {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  return state.tune ? state.tune->ht_PositionNr : 0;
}

void hvl_decoder_pattern_data(
    int position, int numRows, int8_t* notes, int8_t* instruments,
    int8_t* fxType, int8_t* fxParm, int8_t* fx2Type, int8_t* fx2Parm) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.tune || position < 0 || position >= state.tune->ht_PositionNr) return;

  const hvl_position& pos = state.tune->ht_Positions[position];
  const int channels = std::min<int>(state.tune->ht_Channels, MAX_HVL_VOICES);
  const int rows = std::min(numRows, static_cast<int>(state.tune->ht_TrackLength));

  // A position names one track per channel, and a track is the column. The
  // caller's arrays are row-major with a fixed MAX_CHANNELS stride, which is
  // the layout PatternView reads.
  for (int row = 0; row < rows; row++) {
    for (int ch = 0; ch < channels; ch++) {
      const hvl_step& step = state.tune->ht_Tracks[pos.pos_Track[ch]][row];
      const int idx = row * MAX_CHANNELS + ch;
      // Shifted up an octave. Both sides count from one with zero meaning
      // empty, but the view names the first octave 0 and the tracker names it
      // 1, so without this every note reads an octave low against what
      // HivelyTracker shows for the same file.
      notes[idx] = step.stp_Note > 0
          ? static_cast<int8_t>(std::min(step.stp_Note + 12, 127))
          : 0;
      instruments[idx] = static_cast<int8_t>(step.stp_Instrument);
      // -1 for an empty slot. AHX spells "nothing here" as command zero with
      // parameter zero, and the view draws any command it is given - so
      // passing the zero through printed a solid column of 000.
      const bool hasFx = step.stp_FX != 0 || step.stp_FXParam != 0;
      const bool hasFx2 = step.stp_FXb != 0 || step.stp_FXbParam != 0;
      fxType[idx] = hasFx ? static_cast<int8_t>(step.stp_FX) : -1;
      fxParm[idx] = static_cast<int8_t>(step.stp_FXParam);
      fx2Type[idx] = hasFx2 ? static_cast<int8_t>(step.stp_FXb) : -1;
      fx2Parm[idx] = static_cast<int8_t>(step.stp_FXbParam);
    }
  }
}

namespace {

/** Fills and publishes the frame the views read. Caller holds the mutex. */
void publishVisuals(HvlState& state, int64_t frameStamp) {
  if (!state.tune) return;
  VisualFrame& frame = state.visuals.back();
  frame.clear();
  int32_t* scalars = frame.scalars.data();
  int32_t* channels = frame.channels.data();
  int8_t* scopes = frame.scopes.data();
  const int scopeWidth = kScopeColumns;

  const hvl_tune* t = state.tune;
  const int chn = std::min<int>(t->ht_Channels, MAX_HVL_VOICES);

  scalars[kScalarOrder] = t->ht_PosNr;
  scalars[kScalarPattern] = t->ht_PosNr;
  scalars[kScalarRow] = t->ht_NoteNr;
  scalars[kScalarNumRows] = t->ht_TrackLength;
  scalars[kScalarSpeed] = t->ht_Tempo;
  scalars[kScalarBpm] = 125;
  scalars[kScalarNumChannels] = chn;
  scalars[kScalarTimeMs] = state.positionMs;
  scalars[kScalarTotalTimeMs] = state.totalMs;
  scalars[kScalarSequence] = state.subsong;

  int playing = 0;
  for (int i = 0; i < chn; i++) {
    const hvl_voice& v = t->ht_Voices[i];
    int32_t* f = channels + i * kChannelFields;
    const int volume = state.muted[i] ? 0 : v.vc_AudioVolume;

    f[kChVolume] = std::clamp(volume, 0, 64);
    f[kChFinalVolume] = f[kChVolume];
    // The voice's own pan, not the middle. These are Amiga voices: the
    // replayer puts 0 and 3 on one side and 1 and 2 on the other, and vc_Pan
    // is already the 0..255 the views read.
    f[kChPan] = static_cast<int32_t>(std::min<uint32_t>(v.vc_Pan, 255u));
    f[kChPeriod] = v.vc_AudioPeriod;
    // What the slides and the vibrato are adding on top of the note's own
    // table period - the three terms hvl_process_frame sums there, and the
    // only thing that moves a note off its pitch. Negated because these are
    // periods: the replayer adds to one to pitch a note down, and bend reads
    // positive when a note is pitched up, the way libopenmpt reports it.
    f[kChPitchBend] =
        -(v.vc_PeriodSlidePeriod + v.vc_PeriodPerfSlidePeriod + v.vc_VibratoPeriod);
    // vc_TrackPeriod is the note, not a period: the replayer uses it to index
    // period_tab, so it is already the note number the pattern held. Shifted
    // the same octave the pattern is, so the two agree on screen.
    f[kChNote] = v.vc_TrackPeriod > 0 ? std::min<int32_t>(v.vc_TrackPeriod + 12, 127) : 0;
    // key counts from zero where note counts from one, the way libopenmpt
    // reports the same pair.
    f[kChKey] = f[kChNote] > 0 ? f[kChNote] - 1 : -1;
    // The step being played is what the pattern shows; the voice itself only
    // keeps the resulting period and the instrument it came from.
    f[kChInstrument] = v.vc_Instrument
        ? static_cast<int32_t>(v.vc_Instrument - t->ht_Instruments)
        : -1;
    f[kChSample] = f[kChInstrument] + 1;

    if (!scopes || scopeWidth <= 0) continue;

    // The mixer's own read, one output sample per column.
    //
    // This used to walk vc_AudioSource - the instrument's waveform table -
    // over a fixed 64-byte window, which is neither what the voice is putting
    // out nor at the rate it is putting it out, and stretching 64 bytes over
    // 256 columns is why it looked like it had almost no samples in it. The
    // mixer reads vc_MixSource stepping by vc_Delta in 16.16, wraps at 0x280
    // whatever the waveform's own length is, and ring-modulates against
    // vc_RingMixSource when one is set. Doing the same thing here is the
    // difference between a picture of the wavetable and a picture of the
    // sound. See hvl_mixchunk.
    int8_t* out = scopes + i * scopeWidth;
    const int8_t* src = v.vc_MixSource;
    if (!src || volume == 0) {
      std::memset(out, 0, scopeWidth);
      continue;
    }

    const int8_t* ring = v.vc_RingMixSource;
    uint32_t pos = v.vc_SamplePos;
    uint32_t ringPos = v.vc_RingSamplePos;
    const uint32_t delta = std::max<uint32_t>(1u, v.vc_Delta);
    const uint32_t ringDelta = std::max<uint32_t>(1u, v.vc_RingDelta);
    const int32_t voiceVolume = std::clamp<int32_t>(v.vc_VoiceVolume, 0, 64);

    int32_t peak = 0;
    for (int x = 0; x < scopeWidth; x++) {
      if (pos >= kMixWrap) pos -= kMixWrap;
      int32_t sample = src[pos >> 16];
      if (ring) {
        if (ringPos >= kMixWrap) ringPos -= kMixWrap;
        sample = (sample * ring[ringPos >> 16]) >> 7;
        ringPos += ringDelta;
      }
      // Scaled by the volume the mixer will apply, so a quiet voice draws a
      // small trace rather than being stretched to fill its box.
      sample = (sample * voiceVolume) / 64;
      peak = std::max(peak, std::abs(sample));
      out[x] = static_cast<int8_t>(std::clamp<int32_t>(sample, -127, 127));
      pos += delta;
    }

    // Measured off the trace rather than taken from the commanded volume: a
    // voice holding a note through a silent part of its waveform is quiet,
    // and this is the field the views use to decide there is anything to draw.
    f[kChPeak] = std::clamp<int32_t>((peak * kPeakScale) / 128, 0, kPeakScale);
    if (f[kChPeak] > 0) playing++;
  }
  scalars[kScalarPlayingChannels] = playing;
  state.visuals.publish(frameStamp);
}

}  // namespace

bool hvl_decoder_read_visual(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) {
  // No lock: the render callback publishes, this copies. Reading the live
  // replayer here meant taking the mutex the audio callback holds, sixty
  // times a second - which is what made these traces stutter.
  return HvlState::instance().visuals.copyOut(scalars, channels, scopes, scopeWidth);
}

int hvl_decoder_render(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);

  const size_t outSamples = static_cast<size_t>(numFrames) * numChannels;
  if (!state.tune) {
    std::memset(audioData, 0, outSamples * bytesPerSample);
    return 0;
  }

  int32_t written = 0;
  // Where in the output stream this callback's audio begins. Each tick is
  // stamped with the frame it will be heard at, so the views can be shown the
  // one that has actually reached the speaker rather than the one just
  // rendered - a whole buffer ahead of anything audible.
  const int64_t base = get_frames_written();

  while (written < numFrames) {
    // Nothing left over: run the replayer for another tick.
    if (state.spillRead >= state.spill.size()) {
      // A length the listener set ends the tune there, looping or not the
      // way the tune's own end would.
      const bool overLength = state.durationOverrideMs > 0 &&
          static_cast<int64_t>(state.tune->ht_PlayingTime) * 1000 / irqRate(state.tune) >=
              state.durationOverrideMs;
      if (overLength && !state.loop) {
        state.ended = true;
        break;
      }
      if (overLength) {
        // Repeating, the length that was set is where it goes round, from
        // the top: it used to be passed by, the tune played to its own end
        // and the bar held at the end of a length it had left behind.
        hvl_InitSubsong(state.tune, static_cast<uint32>(state.subsong));
        applySettingsLocked(state);
        state.positionMs = 0;
        state.loopedAtMs = -1;
        state.tickCarry = 0.0;
      }
      if (state.tune->ht_SongEndReached) {
        if (state.loop) {
          // Clearing the flag is the whole of it. The replayer raises it as
          // it wraps, and it has already set ht_PosNr to ht_Restart by then -
          // so reinitialising the subsong here, which is what this did, threw
          // away the loop point and started the tune again from the top.
          state.tune->ht_SongEndReached = 0;
          if (state.loopedAtMs < 0) state.loopedAtMs = state.positionMs;
        } else {
          state.ended = true;
          break;
        }
      }
      const int tick = irqFrames(state);
      state.spill.assign(static_cast<size_t>(tick) * 2, 0);
      state.spillRead = 0;

      // One interrupt, not one hvl_DecodeFrame. A frame is
      // ht_SpeedMultiplier interrupts, and the row, the voices and the
      // playing time all move on every one of them - so decoding a whole
      // frame at a time skipped up to three replayer states out of four
      // before anything could look at them. That is what made the pattern
      // view disagree with what was being played, and the traces jump.
      hvl_play_irq(state.tune);
      applyPitchLocked(state);
      hvl_mixchunk(
          state.tune, static_cast<uint32>(tick), reinterpret_cast<int8*>(state.spill.data()),
          reinterpret_cast<int8*>(state.spill.data()) + 2, 4);

      // In 64 bits, as in the seek: a thousand interrupts' worth of a session
      // left looping for hours does not fit in an int.
      const int elapsed = static_cast<int>(
          static_cast<int64_t>(state.tune->ht_PlayingTime) * 1000 / irqRate(state.tune));
      // Folded back into the loop once the tune has gone round, so the bar
      // cycles through the looping part instead of pinning at the end.
      state.positionMs = elapsed;
      if (state.loopedAtMs >= 0 && elapsed > state.loopedAtMs) {
        const int loop = std::max(1, state.loopedAtMs - std::max(0, state.loopedAtMs - state.totalMs));
        state.positionMs = state.loopedAtMs - loop + (elapsed - state.loopedAtMs) % loop;
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
    const size_t remaining = static_cast<size_t>(numFrames - written) * numChannels;
    std::memset(
        static_cast<uint8_t*>(audioData) + static_cast<size_t>(written) * numChannels * bytesPerSample,
        0, remaining * bytesPerSample);
    return 1;
  }
  return 0;
}

double hvl_decoder_render_position_ms() {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  const hvl_tune* t = state.tune;
  if (!t) return 0.0;
  // ht_PlayingTime is the end of the last interrupt produced; what is still
  // waiting in the spill has not been handed over yet.
  const double produced = static_cast<double>(t->ht_PlayingTime) * 1000.0 / irqRate(t);
  const size_t waiting = (state.spill.size() - std::min(state.spillRead, state.spill.size())) / 2;
  return produced - waiting * 1000.0 * state.tempoFactor / std::max(1, state.sampleRate);
}

void hvl_decoder_loop_seek_ms(int ms) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  hvl_tune* t = state.tune;
  if (!t) return;
  // hvl_decoder_seek_ms, keeping the published history.
  const int rate = irqRate(t);
  const int64_t want = static_cast<int64_t>(std::max(0, ms)) * rate / 1000;
  if (want < static_cast<int64_t>(t->ht_PlayingTime)) {
    hvl_InitSubsong(t, static_cast<uint32>(state.subsong));
    applySettingsLocked(state);
  }
  const int64_t cap = static_cast<int64_t>(rate) * kMaxMeasureSeconds;
  while (static_cast<int64_t>(t->ht_PlayingTime) < want &&
         static_cast<int64_t>(t->ht_PlayingTime) < cap && !t->ht_SongEndReached) {
    hvl_play_irq(t);
  }
  state.positionMs = static_cast<int>(static_cast<int64_t>(t->ht_PlayingTime) * 1000 / rate);
  state.spill.clear();
  state.spillRead = 0;
  state.tickCarry = 0.0;
  state.ended = false;
}

void hvl_decoder_set_duration_override_ms(int ms) {
  HvlState& state = HvlState::instance();
  std::lock_guard<std::mutex> lock(state.mutex);
  state.durationOverrideMs = ms > 0 ? ms : 0;
  const int native = state.subsong < static_cast<int>(state.subsongMs.size())
      ? state.subsongMs[state.subsong] : 0;
  state.totalMs = ms > 0 ? ms : native;
}

bool hvl_decoder_duration_known() {
  HvlState& state = HvlState::instance();
  return state.subsong < static_cast<int>(state.subsongKnown.size()) &&
         state.subsongKnown[state.subsong];
}
