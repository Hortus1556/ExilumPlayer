//
// Adapted from OpenMPT soundlib/plugins/dmo/Chorus.cpp. See fx-chorus.h for
// what is changed and for the licence.
//
#include "fx-chorus.h"
#include <algorithm>
#include <cmath>
#include <iterator>

namespace exilum::fx {

namespace {

constexpr float kPi = 3.14159265358979323846f;
/** DirectX's defaults, which the chorus keeps. */
constexpr float kDelayMs = 16.0f;
constexpr float kDepth = 0.1f;
constexpr float kFeedback = 0.25f;
constexpr float kAntiDenormal = 1e-18f;

/** cos or sin of the spread, exactly 0 where it should be, so the spread's ends and middle are DirectX's own. */
float snapped(float value) { return std::fabs(value) < 1e-6f ? 0.0f : value; }

}  // namespace

bool Chorus::configure(int rate) {
  if (rate <= 0 || rate > kMaxRate) return false;
  rate_ = rate;
  const float delaySamples = kDelayMs * static_cast<float>(rate) / 1000.0f;
  depthDelay_ = kDepth * delaySamples * 2048.0f;
  delayOffset_ = static_cast<int32_t>(std::lround(4096.0f * (delaySamples + 2.0f)));
  // The ring, as small a power of two as holds the longest delay read.
  const int32_t longest = (delayOffset_ + static_cast<int32_t>(depthDelay_)) / 4096 + 2;
  int32_t size = 1;
  while (size < longest) size <<= 1;
  mask_ = std::min(size, kCapacity) - 1;
  clear();
  return true;
}

void Chorus::clear() {
  std::fill(std::begin(buffer_), std::end(buffer_), 0.0f);
  position_ = 0;
  waveShapeMin_ = 0.0f;
  waveShapeMax_ = 1.0f;
  delayL_ = delayR_ = delayOffset_;
}

void Chorus::setRate(float hertz) {
  if (rate_ <= 0) return;
  waveShapeVal_ = std::sin(hertz / static_cast<float>(rate_) * kPi) * 2.0f;
}

float Chorus::spreadCos(float spread) { return snapped(std::cos(spread * kPi)); }
float Chorus::spreadSin(float spread) { return snapped(std::sin(spread * kPi)); }

void Chorus::process(const float* inL, const float* inR, float* wetL, float* wetR, int frames, const float* spreadCos,
                     const float* spreadSin) {
  for (int i = 0; i < frames; i++) {
    const int32_t readOffset = offset(delayOffset_);
    buffer_[position_] = (buffer_[readOffset] * kFeedback) + (inL[i] + inR[i]) * 0.5f + kAntiDenormal;

    waveShapeMin_ = waveShapeMax_ * waveShapeVal_ + waveShapeMin_;
    waveShapeMax_ = waveShapeMax_ - waveShapeMin_ * waveShapeVal_;
    const float waveMin = waveShapeMin_;
    const float waveMax = waveShapeMax_;

    const float left1 = buffer_[offset(delayL_)];
    const float left2 = buffer_[offset(delayL_ + 4096)];
    float fracPos = static_cast<float>(delayL_ & 0xFFF) * (1.0f / 4096.0f);
    wetL[i] = (left2 - left1) * fracPos + left1;

    const float right1 = buffer_[offset(delayR_)];
    const float right2 = buffer_[offset(delayR_ + 4096)];
    fracPos = static_cast<float>(delayR_ & 0xFFF) * (1.0f / 4096.0f);
    wetR[i] = (right2 - right1) * fracPos + right1;

    // DirectX's phase steps are 0, 90 and 180 degrees of this: the right
    // side follows the left's wave, the one a quarter turn on, or the left's
    // upside down.
    delayL_ = delayOffset_ + static_cast<int32_t>(waveMin * depthDelay_);
    delayR_ = delayOffset_ + static_cast<int32_t>((waveMin * spreadCos[i] + waveMax * spreadSin[i]) * depthDelay_);

    position_ = (position_ - 1) & mask_;
  }
}

}  // namespace exilum::fx
