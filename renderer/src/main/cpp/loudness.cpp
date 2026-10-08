//
// BS.1770 loudness. See loudness.h.
//
#include "loudness.h"
#include <algorithm>
#include <cmath>

namespace {
inline double loudnessOf(double energy) { return -0.691 + 10.0 * std::log10(std::max(energy, 1e-20)); }
}  // namespace

void LoudnessMeter::reset(int rate) {
  rate_ = rate;
  {
    const double f0 = 1681.974450955533, gainDb = 3.999843853973347, q = 0.7071752369554196;
    const double k = std::tan(M_PI * f0 / rate);
    const double vh = std::pow(10.0, gainDb / 20.0);
    const double vb = std::pow(vh, 0.4996667741545416);
    const double a0 = 1.0 + k / q + k * k;
    shelf_.b0 = (vh + vb * k / q + k * k) / a0;
    shelf_.b1 = 2.0 * (k * k - vh) / a0;
    shelf_.b2 = (vh - vb * k / q + k * k) / a0;
    shelf_.a1 = 2.0 * (k * k - 1.0) / a0;
    shelf_.a2 = (1.0 - k / q + k * k) / a0;
  }
  {
    const double f0 = 38.13547087602444, q = 0.5003270373238773;
    const double k = std::tan(M_PI * f0 / rate);
    const double a0 = 1.0 + k / q + k * k;
    highpass_.b0 = 1.0;
    highpass_.b1 = -2.0;
    highpass_.b2 = 1.0;
    highpass_.a1 = 2.0 * (k * k - 1.0) / a0;
    highpass_.a2 = (1.0 - k / q + k * k) / a0;
  }
  s1_.fill(0.0);
  s2_.fill(0.0);
  h1_.fill(0.0);
  h2_.fill(0.0);
  hopFrames_ = std::max(1, rate / 10);
  hopFilled_ = 0;
  hopEnergy_ = 0.0;
  hops_.fill(0.0);
  hopsSeen_ = 0;
  binCount_.fill(0);
  binEnergy_.fill(0.0);
  integrated_ = kUnknown;
  gatedSeconds_ = 0.0f;
}

bool LoudnessMeter::addFrame(const double* frame, int channels) {
  const int n = std::min(channels, kMaxChannels);
  double energy = 0.0;
  for (int c = 0; c < n; c++) {
    const double x = frame[c];
    const double y1 = shelf_.b0 * x + s1_[c];
    s1_[c] = shelf_.b1 * x - shelf_.a1 * y1 + s2_[c];
    s2_[c] = shelf_.b2 * x - shelf_.a2 * y1;
    const double y2 = highpass_.b0 * y1 + h1_[c];
    h1_[c] = highpass_.b1 * y1 - highpass_.a1 * y2 + h2_[c];
    h2_[c] = highpass_.b2 * y1 - highpass_.a2 * y2;
    energy += y2 * y2;
  }
  hopEnergy_ += energy;
  if (++hopFilled_ < hopFrames_) return false;
  endHop();
  return true;
}

void LoudnessMeter::endHop() {
  hops_[hopsSeen_ % kHopsPerBlock] = hopEnergy_ / hopFrames_;
  hopsSeen_++;
  hopEnergy_ = 0.0;
  hopFilled_ = 0;
  if (hopsSeen_ < kHopsPerBlock) return;

  double block = 0.0;
  for (double h : hops_) block += h;
  block /= kHopsPerBlock;
  const double lufs = loudnessOf(block);
  if (lufs < kFloor) return;
  const int bin = std::clamp(static_cast<int>((lufs - kFloor) * 10.0), 0, kBins - 1);
  binCount_[bin]++;
  binEnergy_[bin] += block;

  double sum = 0.0;
  uint64_t count = 0;
  for (int i = 0; i < kBins; i++) {
    sum += binEnergy_[i];
    count += binCount_[i];
  }
  const double relative = loudnessOf(sum / count) - 10.0;
  const int from = std::clamp(static_cast<int>(std::ceil((relative - kFloor) * 10.0)), 0, kBins);
  double gatedSum = 0.0;
  uint64_t gatedCount = 0;
  for (int i = from; i < kBins; i++) {
    gatedSum += binEnergy_[i];
    gatedCount += binCount_[i];
  }
  if (gatedCount == 0) return;
  integrated_ = static_cast<float>(loudnessOf(gatedSum / gatedCount));
  gatedSeconds_ = gatedCount * 0.1f;
}
