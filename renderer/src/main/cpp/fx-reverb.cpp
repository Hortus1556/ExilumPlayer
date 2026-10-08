//
// Adapted from OpenMPT soundlib/plugins/dmo/I3DL2Reverb.cpp and the presets in
// sounddsp/Reverb.cpp. See fx-reverb.h for what is changed and for the licence.
//
#include "fx-reverb.h"
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <iterator>

namespace exilum::fx {

namespace {

constexpr float kPi = 3.14159265358979323846f;

/** A tiny offset on the input: a tail that dies away stays above the denormals, where floats get slow. */
constexpr float kAntiDenormal = 1e-18f;

/** I3DL2's room description, as sounddsp/Reverb.cpp's SNDMIX_REVERB_PROPERTIES. */
struct Preset {
  int32_t room;              // mB
  int32_t roomHF;            // mB
  float decayTime;           // s
  float decayHFRatio;
  int32_t reflections;       // mB
  float reflectionsDelay;    // s
  int32_t reverb;            // mB
  float reverbDelay;         // s
  float diffusion;           // %
  float density;             // %
  /**
   * What the room's sound is brought up by to be about as loud as the music:
   * 20 log10 of the music's level over the room's, measured on 30 seconds of
   * stereo pink noise at 48 kHz from the tenth second on.
   */
  float makeupDb;
};

/** The rooms, in ReverbType's order: rows of OpenMPT's ReverbPresets. */
constexpr Preset kPresets[kReverbTypes] = {
    // GM Small Room (1.1 s): a small room, five metres or so long.
    {-1000, -600, 1.10f, 0.83f, -400, 0.005f, 500, 0.010f, 100.0f, 100.0f, 7.9f},
    // GM Medium Room (1.3 s): a medium-size room, ten metres or so long.
    {-1000, -600, 1.30f, 0.83f, -1000, 0.010f, -200, 0.020f, 100.0f, 100.0f, 14.7f},
    // GM Large Room (1.5 s): a large room, for live performance.
    {-1000, -600, 1.50f, 0.83f, -1600, 0.020f, -1000, 0.040f, 100.0f, 100.0f, 22.0f},
    // GM Medium Hall (1.8 s): a medium-size concert hall.
    {-1000, -600, 1.80f, 0.70f, -1300, 0.015f, -800, 0.030f, 100.0f, 100.0f, 19.8f},
    // Concert Hall (3.9 s).
    {-1000, -500, 3.92f, 0.70f, -1230, 0.020f, -2, 0.029f, 100.0f, 100.0f, 13.0f},
    // Arena (7.2 s).
    {-1000, -698, 7.24f, 0.33f, -1166, 0.020f, 16, 0.030f, 100.0f, 100.0f, 12.9f},
    // GM Plate (1.3 s): a plate reverb.
    {-1000, -200, 1.30f, 0.90f, 0, 0.002f, 0, 0.010f, 100.0f, 75.0f, 8.7f},
    // Bathroom (1.5 s).
    {-1000, -1200, 1.49f, 0.54f, -370, 0.007f, 1030, 0.011f, 100.0f, 60.0f, 3.7f},
    // Cave (2.9 s).
    {-1000, 0, 2.91f, 1.30f, -602, 0.015f, -302, 0.022f, 100.0f, 100.0f, 12.6f},
};

/** The late lines' lengths in ms, as I3DL2Reverb::PositionChanged makes them: 6 left, then 6 right. */
constexpr int32_t kLateMs[12] = {67, 62, 53, 43, 32, 22, 75, 69, 60, 48, 36, 25};

int32_t muldiv(int32_t a, int32_t b, int32_t c) {
  return static_cast<int32_t>(static_cast<int64_t>(a) * b / c);
}

/**
 * The late tap of line [index] at the densest a room can be, as SetDelayTaps
 * works it out. OpenMPT makes each line its length in whole milliseconds, and
 * at full density ten of the twelve taps are longer than their line: wrapped
 * round it, a tap of 70 ms became one of a few samples, and the rooms died
 * away in half the time they are made for. Each line here is long enough for
 * its tap.
 */
int32_t longestLateTap(int index, float rate) {
  const float delay = (index < 6 ? 67.0f : 75.0f) / 1000.0f * rate;
  int power = 0;
  for (int i = 0; i <= index % 6; i++) power += i;
  return static_cast<int32_t>(delay * std::pow(0.93f, static_cast<float>(power)));
}

}  // namespace

void Reverb::DelayLine::init(float* buffer, int32_t length, int32_t delayTap) {
  buffer_ = buffer;
  length_ = length;
  position_ = 0;
  setDelayTap(delayTap);
}

void Reverb::DelayLine::setDelayTap(int32_t delayTap) {
  if (length_ > 0) delayPosition_ = (delayTap + position_ + length_) % length_;
}

void Reverb::DelayLine::clear() {
  if (buffer_) std::fill(buffer_, buffer_ + length_, 0.0f);
}

bool Reverb::configure(int rate, int type) {
  if (rate <= 0 || rate > kMaxRate) return false;
  rate_ = rate;
  type_ = std::clamp(type, 0, kReverbTypes - 1);
  const Preset& preset = kPresets[type_];
  room_ = static_cast<float>(preset.room);
  roomHF_ = static_cast<float>(preset.roomHF);
  decayTime_ = preset.decayTime;
  decayHFRatio_ = preset.decayHFRatio;
  reflections_ = static_cast<float>(preset.reflections);
  reflectionsDelay_ = preset.reflectionsDelay;
  reverb_ = static_cast<float>(preset.reverb);
  reverbDelay_ = preset.reverbDelay;
  diffusion_ = preset.diffusion;
  density_ = preset.density;
  recalculate();

  // The lines, laid out in the pool. The taps are set from recalculate's.
  float* at = pool_;
  const auto line = [&](int index, int32_t length, int32_t tap) {
    lines_[index].init(at, length, tap);
    at += length;
  };
  for (int i = 0; i < 12; i++) {
    const int32_t length = std::max(muldiv(rate, kLateMs[i], 1000) + 5, longestLateTap(i, static_cast<float>(rate)) + 5);
    line(i, length, delayTaps_[i]);
  }
  lines_[12].init(nullptr, 0, 0);
  line(13, std::max(muldiv(rate, 3, 1000), static_cast<int32_t>(3.25f / 1000.0f * rate) + 5), delayTaps_[13]);
  line(14, std::max(muldiv(rate, 3, 1000), static_cast<int32_t>(3.53f / 1000.0f * rate) + 5), delayTaps_[14]);
  line(15, muldiv(rate, 407, 1000) + 1, 0);
  line(16, muldiv(rate, 400, 1000) + 1, 0);
  line(17, muldiv(rate, 10, 1000), -1);
  line(18, muldiv(rate, 10, 1000), -1);
  if (at > pool_ + kPoolFloats) return false;  // cannot happen up to kMaxRate
  clear();
  return true;
}

void Reverb::setType(int type) { configure(rate_, type); }

void Reverb::clear() {
  std::fill(std::begin(filterHist_), std::end(filterHist_), 0.0f);
  for (DelayLine& line : lines_) line.clear();
}

float Reverb::makeup() const { return std::pow(10.0f, kPresets[type_].makeupDb / 20.0f); }

float Reverb::tailSeconds() const { return 2.5f * decayTime_ + reflectionsDelay_ + reverbDelay_ + 0.05f; }

void Reverb::process(const float* inLeft, const float* inRight, float* wetL, float* wetR, int frames) {
  for (int f = 0; f < frames; f++) {
    // Apply room filter and insert into early reflection delay lines
    const float inL = inLeft[f] + kAntiDenormal;
    const float inRoomL = (filterHist_[12] - inL) * roomFilter_ + inL;
    filterHist_[12] = inRoomL;
    lines_[15].set(inRoomL);

    const float inR = inRight[f] + kAntiDenormal;
    const float inRoomR = (filterHist_[13] - inR) * roomFilter_ + inR;
    filterHist_[13] = inRoomR;
    lines_[16].set(inRoomR);

    // Early reflections (left)
    float earlyL = lines_[15].get(earlyTaps_[0][1]) * 0.68f - lines_[15].get(earlyTaps_[0][2]) * 0.5f -
                   lines_[15].get(earlyTaps_[0][3]) * 0.62f - lines_[15].get(earlyTaps_[0][4]) * 0.5f -
                   lines_[15].get(earlyTaps_[0][5]) * 0.62f;
    {
      const float earlyL2 = earlyL;
      earlyL = lines_[13].get() + earlyL * 0.618034f;
      lines_[13].set(earlyL2 - earlyL * 0.618034f);
    }
    const float earlyRefOutL = earlyL * erLevel_;
    filterHist_[15] = lines_[15].get(earlyTaps_[0][0]) + filterHist_[15];
    filterHist_[16] = lines_[16].get(earlyTaps_[1][0]) + filterHist_[16];

    float reverbL1, reverbL2, reverbL3, reverbR1, reverbR2, reverbR3;

    reverbL1 = -filterHist_[15] * 0.707f;
    reverbL2 = filterHist_[16] * 0.707f + reverbL1;
    reverbR2 = reverbL1 - filterHist_[16] * 0.707f;

    filterHist_[5] = (filterHist_[5] - lines_[5].get()) * delayCoeffs_[5][1] + lines_[5].get();
    reverbL1 = filterHist_[5] * delayCoeffs_[5][0] + reverbL2 * diffusionCoeff_;
    lines_[5].set(reverbL2 - reverbL1 * diffusionCoeff_);
    reverbL2 = reverbL1;
    reverbL3 = -0.15f * reverbL1;

    filterHist_[4] = (filterHist_[4] - lines_[4].get()) * delayCoeffs_[4][1] + lines_[4].get();
    reverbL1 = filterHist_[4] * delayCoeffs_[4][0] + reverbL2 * diffusionCoeff_;
    lines_[4].set(reverbL2 - reverbL1 * diffusionCoeff_);
    reverbL2 = reverbL1;
    reverbL3 -= reverbL1 * 0.2f;

    filterHist_[3] = (filterHist_[3] - lines_[3].get()) * delayCoeffs_[3][1] + lines_[3].get();
    reverbL1 = filterHist_[3] * delayCoeffs_[3][0] + reverbL2 * diffusionCoeff_;
    lines_[3].set(reverbL2 - reverbL1 * diffusionCoeff_);
    reverbL2 = reverbL1;
    reverbL3 += 0.35f * reverbL1;

    filterHist_[2] = (filterHist_[2] - lines_[2].get()) * delayCoeffs_[2][1] + lines_[2].get();
    reverbL1 = filterHist_[2] * delayCoeffs_[2][0] + reverbL2 * diffusionCoeff_;
    lines_[2].set(reverbL2 - reverbL1 * diffusionCoeff_);
    reverbL2 = reverbL1;
    reverbL3 -= reverbL1 * 0.38f;

    lines_[17].set(reverbL2);

    reverbL1 = lines_[17].get() * delayCoeffs_[12][0];
    filterHist_[17] = (filterHist_[17] - reverbL1) * delayCoeffs_[12][1] + reverbL1;

    filterHist_[1] = (filterHist_[1] - lines_[1].get()) * delayCoeffs_[1][1] + lines_[1].get();
    reverbL1 = filterHist_[17] * diffusionCoeff_ + filterHist_[1] * delayCoeffs_[1][0];
    lines_[1].set(filterHist_[17] - reverbL1 * diffusionCoeff_);
    reverbL2 = reverbL1;
    const float reverbL4 = reverbL1 * 0.38f;

    filterHist_[0] = (filterHist_[0] - lines_[0].get()) * delayCoeffs_[0][1] + lines_[0].get();
    reverbL1 = filterHist_[0] * delayCoeffs_[0][0] + reverbL2 * diffusionCoeff_;
    lines_[0].set(reverbL2 - reverbL1 * diffusionCoeff_);
    reverbL3 -= reverbL1 * 0.38f;
    filterHist_[15] = reverbL1;

    // Early reflections (right)
    float earlyR = lines_[16].get(earlyTaps_[1][1]) * 0.707f - lines_[16].get(earlyTaps_[1][2]) * 0.6f -
                   lines_[16].get(earlyTaps_[1][3]) * 0.5f - lines_[16].get(earlyTaps_[1][4]) * 0.6f -
                   lines_[16].get(earlyTaps_[1][5]) * 0.5f;
    {
      const float earlyR2 = earlyR;
      earlyR = lines_[14].get() + earlyR * 0.618034f;
      lines_[14].set(earlyR2 - earlyR * 0.618034f);
    }
    const float earlyRefOutR = earlyR * erLevel_;

    filterHist_[11] = (filterHist_[11] - lines_[11].get()) * delayCoeffs_[11][1] + lines_[11].get();
    reverbR1 = filterHist_[11] * delayCoeffs_[11][0] + reverbR2 * diffusionCoeff_;
    lines_[11].set(reverbR2 - reverbR1 * diffusionCoeff_);
    reverbR2 = reverbR1;

    filterHist_[10] = (filterHist_[10] - lines_[10].get()) * delayCoeffs_[10][1] + lines_[10].get();
    reverbR1 = filterHist_[10] * delayCoeffs_[10][0] + reverbR2 * diffusionCoeff_;
    lines_[10].set(reverbR2 - reverbR1 * diffusionCoeff_);
    reverbR3 = reverbL4 - reverbR2 * 0.15f - reverbR1 * 0.2f;
    reverbR2 = reverbR1;

    filterHist_[9] = (filterHist_[9] - lines_[9].get()) * delayCoeffs_[9][1] + lines_[9].get();
    reverbR1 = filterHist_[9] * delayCoeffs_[9][0] + reverbR2 * diffusionCoeff_;
    lines_[9].set(reverbR2 - reverbR1 * diffusionCoeff_);
    reverbR2 = reverbR1;
    reverbR3 += reverbR1 * 0.35f;

    filterHist_[8] = (filterHist_[8] - lines_[8].get()) * delayCoeffs_[8][1] + lines_[8].get();
    reverbR1 = filterHist_[8] * delayCoeffs_[8][0] + reverbR2 * diffusionCoeff_;
    lines_[8].set(reverbR2 - reverbR1 * diffusionCoeff_);
    reverbR2 = reverbR1;
    reverbR3 -= reverbR1 * 0.38f;

    lines_[18].set(reverbR2);

    reverbR1 = lines_[18].get() * delayCoeffs_[12][0];
    filterHist_[18] = (filterHist_[18] - reverbR1) * delayCoeffs_[12][1] + reverbR1;

    filterHist_[7] = (filterHist_[7] - lines_[7].get()) * delayCoeffs_[7][1] + lines_[7].get();
    reverbR1 = filterHist_[18] * diffusionCoeff_ + filterHist_[7] * delayCoeffs_[7][0];
    lines_[7].set(filterHist_[18] - reverbR1 * diffusionCoeff_);
    reverbR2 = reverbR1;

    const float lateRevOutL = (reverbL3 + reverbR1 * 0.38f) * reverbLevelL_;

    filterHist_[6] = (filterHist_[6] - lines_[6].get()) * delayCoeffs_[6][1] + lines_[6].get();
    reverbR1 = filterHist_[6] * delayCoeffs_[6][0] + reverbR2 * diffusionCoeff_;
    lines_[6].set(reverbR2 - reverbR1 * diffusionCoeff_);
    filterHist_[16] = reverbR1;

    const float lateRevOutR = (reverbR3 - reverbR1 * 0.38f) * reverbLevelR_;

    wetL[f] = earlyRefOutL + lateRevOutL;
    wetR[f] = earlyRefOutR + lateRevOutR;

    // Line 12 is a placeholder and is not advanced.
    for (int i = 0; i < 19; i++) {
      if (i != 12) lines_[i].advance();
    }
  }
}

void Reverb::recalculate() {
  const float sampleRate = static_cast<float>(rate_);

  // Diffusion
  diffusionCoeff_ = diffusion_ * (0.618034f / 100.0f);
  // Early Reflection Level
  erLevel_ = std::min(std::pow(10.0f, (room_ + reflections_) / (100.0f * 20.0f)), 1.0f) * 0.761f;

  // Room Filter
  const float roomHF = std::pow(10.0f, roomHF_ / 100.0f / 10.0f);
  if (roomHF == 1.0f) {
    roomFilter_ = 0.0f;
  } else {
    const float freq = std::min(std::cos(hfReference_ * (2.0f * kPi) / sampleRate), 0.9999f);
    const float roomFilter =
        (freq * (roomHF + roomHF) - 2.0f +
         std::sqrt(freq * (roomHF * roomHF * freq * 4.0f) + roomHF * 8.0f - roomHF * roomHF * 4.0f - roomHF * freq * 8.0f)) /
        (roomHF + roomHF - 2.0f);
    roomFilter_ = std::clamp(roomFilter, 0.0f, 1.0f);
  }

  setDelayTaps();
  setDecayCoeffs();
}

void Reverb::setDelayTaps() {
  // Early reflections
  static constexpr float delays[] = {
      1.0000f, 1.0000f, 0.0000f, 0.1078f, 0.1768f, 0.2727f, 0.3953f, 0.5386f, 0.6899f, 0.8306f, 0.9400f, 0.9800f,
  };

  const float sampleRate = static_cast<float>(rate_);
  const float reflectionsDelay = reflectionsDelay_;
  const float reverbDelay = std::max(reverbDelay_, 5.0f / 1000.0f);
  earlyTaps_[0][0] = static_cast<int32_t>((reverbDelay + reflectionsDelay + 7.0f / 1000.0f) * sampleRate);
  for (int i = 1; i < 12; i++) {
    earlyTaps_[i % 2][i / 2] = static_cast<int32_t>((reverbDelay * delays[i] + reflectionsDelay) * sampleRate);
  }

  // Late reflections
  const float density = std::min((density_ / 100.0f + 0.1f) * 0.9091f, 1.0f);
  const float delayL = density * 67.0f / 1000.0f * sampleRate;
  const float delayR = density * 75.0f / 1000.0f * sampleRate;
  for (int i = 0, power = 0; i < 6; i++) {
    power += i;
    const float factor = std::pow(0.93f, static_cast<float>(power));
    delayTaps_[i + 0] = static_cast<int32_t>(delayL * factor);
    delayTaps_[i + 6] = static_cast<int32_t>(delayR * factor);
  }
  delayTaps_[12] = static_cast<int32_t>(10.0f / 1000.0f * sampleRate);
  // Early reflections (extra delay lines)
  delayTaps_[13] = static_cast<int32_t>(3.25f / 1000.0f * sampleRate);
  delayTaps_[14] = static_cast<int32_t>(3.53f / 1000.0f * sampleRate);

  for (int d = 0; d < 15; d++) lines_[d].setDelayTap(delayTaps_[d]);
}

void Reverb::setDecayCoeffs() {
  float levelLtmp = 1.0f, levelRtmp = 1.0f;
  float levelL = 0.0f, levelR = 0.0f;

  levelLtmp *= calcDecayCoeffs(5);
  levelRtmp *= calcDecayCoeffs(11);
  levelL += levelLtmp * 0.0225f;
  levelR += levelRtmp * 0.0225f;

  levelLtmp *= calcDecayCoeffs(4);
  levelRtmp *= calcDecayCoeffs(10);
  levelL += levelLtmp * 0.04f;
  levelR += levelRtmp * 0.04f;

  levelLtmp *= calcDecayCoeffs(3);
  levelRtmp *= calcDecayCoeffs(9);
  levelL += levelLtmp * 0.1225f;
  levelR += levelRtmp * 0.1225f;

  levelLtmp *= calcDecayCoeffs(2);
  levelRtmp *= calcDecayCoeffs(8);
  levelL += levelLtmp * 0.1444f;
  levelR += levelRtmp * 0.1444f;

  calcDecayCoeffs(12);
  levelLtmp *= delayCoeffs_[12][0] * delayCoeffs_[12][0];
  levelRtmp *= delayCoeffs_[12][0] * delayCoeffs_[12][0];

  levelLtmp *= calcDecayCoeffs(1);
  levelRtmp *= calcDecayCoeffs(7);
  levelL += levelRtmp * 0.1444f;
  levelR += levelLtmp * 0.1444f;

  levelLtmp *= calcDecayCoeffs(0);
  levelRtmp *= calcDecayCoeffs(6);
  levelL += levelLtmp * 0.1444f;
  levelR += levelRtmp * 0.1444f;

  // Final Reverb Level
  const float level = std::min(std::pow(10.0f, (room_ + reverb_) / (100.0f * 20.0f)), 1.0f);
  const float monoInv = 1.0f - ((levelLtmp + levelRtmp) * 0.5f);
  reverbLevelL_ = level * std::sqrt(monoInv / levelL);
  reverbLevelR_ = level * std::sqrt(monoInv / levelR);
}

float Reverb::calcDecayCoeffs(int index) {
  float hfRef = (2.0f * kPi) / static_cast<float>(rate_) * hfReference_;
  const float decayHFRatio = decayHFRatio_;
  if (decayHFRatio > 1.0f) hfRef = kPi;

  float c1 = std::pow(10.0f, ((static_cast<float>(delayTaps_[index]) / static_cast<float>(rate_)) * -60.0f / decayTime_) / 20.0f);
  float c2 = 0.0f;

  const float c21 = (std::pow(c1, 2.0f - 2.0f / decayHFRatio) - 1.0f) / (1.0f - std::cos(hfRef));
  if (c21 != 0 && std::isfinite(c21)) {
    const float c22 = -2.0f * c21 - 2.0f;
    const float c23sq = c22 * c22 - c21 * c21 * 4.0f;
    const float c23 = c23sq > 0.0f ? std::sqrt(c23sq) : 0.0f;
    c2 = (c23 - c22) / (c21 + c21);
    if (std::abs(c2) > 1.0f) c2 = (-c22 - c23) / (c21 + c21);
    if (std::isnan(c2)) c2 = 0.0f;
  }
  delayCoeffs_[index][0] = c1;
  delayCoeffs_[index][1] = c2;

  c1 *= c1;
  const float diff2 = diffusionCoeff_ * diffusionCoeff_;
  return diff2 + c1 / (1.0f - diff2 * c1) * (1.0f - diff2) * (1.0f - diff2);
}

}  // namespace exilum::fx
