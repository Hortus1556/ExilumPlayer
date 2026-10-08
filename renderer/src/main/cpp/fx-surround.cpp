//
// Adapted from OpenMPT sounddsp/DSP.cpp. See fx-surround.h for what is changed
// and for the licence.
//
#include "fx-surround.h"
#include <algorithm>
#include <cmath>

namespace exilum::fx {

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr int kDelayMs = 20;

float sgn(float x) { return (x >= 0) ? 1.0f : -1.0f; }

/** ShelfEQ from DSP.cpp, in floating point: a first-order shelf through gainDC, gainFT at F_c, and gainPI. */
void shelfEQ(float& outA1, float& outB0, float& outB1, float F_c, float F_s, float gainDC, float gainFT, float gainPI) {
  const float wT = kPi * F_c / F_s;
  const float gainPI2 = gainPI * gainPI;
  const float gainFT2 = gainFT * gainFT;
  const float gainDC2 = gainDC * gainDC;

  float quad = gainPI2 + gainDC2 - (gainFT2 * 2);

  float alpha = 0;
  if (quad != 0) {
    const float lambda = (gainPI2 - gainDC2) / quad;
    alpha = lambda - sgn(lambda) * std::sqrt(lambda * lambda - 1.0f);
  }

  const float beta0 = 0.5f * ((gainDC + gainPI) + (gainDC - gainPI) * alpha);
  const float beta1 = 0.5f * ((gainDC - gainPI) + (gainDC + gainPI) * alpha);
  const float rho = std::sin((wT * 0.5f) - (kPi / 4.0f)) / std::sin((wT * 0.5f) + (kPi / 4.0f));

  quad = 1.0f / (1.0f + rho * alpha);

  outB0 = ((beta0 + rho * beta1) * quad);
  outB1 = ((beta1 + rho * beta0) * quad);
  outA1 = -((rho + alpha) * quad);
}

}  // namespace

bool Surround::configure(int rate) {
  if (rate <= 0 || rate > kMaxRate) return false;
  size_ = std::clamp(rate * kDelayMs / 1000, 1, kCapacity);
  const float fs = static_cast<float>(rate);
  shelfEQ(hpA1_, hpB0_, hpB1_, 200.0f, fs, 0.0f, 0.5f, 1.0f);
  shelfEQ(lpA1_, lpB0_, lpB1_, 7000.0f, fs, 1.0f, 0.75f, 0.0f);
  clear();
  return true;
}

void Surround::clear() {
  std::fill(buffer_, buffer_ + kCapacity, 0.0f);
  position_ = 0;
  hpX1_ = hpY1_ = lpY1_ = 0.0f;
}

void Surround::process(float* left, float* right, int frames, const float* depth) {
  float hy1 = hpY1_;
  for (int i = 0; i < frames; i++) {
    // Delay
    const float echo = buffer_[position_];
    buffer_[position_] = (left[i] + right[i]) * 0.5f;
    // High-pass
    const float v0 = hpB0_ * echo + hpB1_ * hpX1_ + hpA1_ * hy1;
    hpX1_ = echo;
    // Low-pass
    const float v = lpB0_ * v0 + lpB1_ * hy1 + lpA1_ * lpY1_;
    hy1 = v0;
    lpY1_ = v;
    // Add echo
    left[i] += v * depth[i];
    right[i] -= v * depth[i];
    if (++position_ >= size_) position_ = 0;
  }
  hpY1_ = hy1;
  // Below anything audible, and before the floats turn slow: off.
  if (std::fabs(hpY1_) < 1e-30f) hpY1_ = 0.0f;
  if (std::fabs(hpX1_) < 1e-30f) hpX1_ = 0.0f;
  if (std::fabs(lpY1_) < 1e-30f) lpY1_ = 0.0f;
}

}  // namespace exilum::fx
