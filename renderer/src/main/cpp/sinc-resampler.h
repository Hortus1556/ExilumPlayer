#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

/**
 * A windowed-sinc interpolator, for a decoder whose music comes at a rate
 * of its own: each output frame is worked out from where it falls between
 * the input's samples, and from nothing else - so a seek lands where playing
 * there would have, sample for sample, as long as the caller hands over the
 * same input around the same place.
 *
 * Each frame is a sum over kTaps input samples, weighted by a sinc cut off
 * at [cutoff] of the input's Nyquist and shaped by a Kaiser window, the
 * weights read from a table of kPhases positions between two samples and
 * interpolated between the nearest two. Every position's weights add up to
 * one, so nothing is louder or quieter for where it falls.
 */
class SincKernel {
 public:
  /** Input samples each output frame is made from: kTaps / 2 before it, kTaps / 2 after. */
  static constexpr int kTaps = 32;
  /** Positions between two input samples the table holds. */
  static constexpr int kPhases = 512;

  /**
   * The table for an output at [outRate] from an input at [inRate]: cut off
   * just under the lower of the two Nyquists, so neither the input's images
   * nor anything above the output's band get through.
   */
  void design(int inRate, int outRate) {
    const double ratio = outRate < inRate ? static_cast<double>(outRate) / inRate : 1.0;
    const double cutoff = 0.95 * ratio;
    table_.assign(static_cast<size_t>(kPhases + 1) * kTaps, 0.0f);
    const double half = kTaps / 2.0;
    const double i0beta = besselI0(kBeta);
    for (int p = 0; p <= kPhases; p++) {
      const double frac = static_cast<double>(p) / kPhases;
      double sum = 0.0;
      double row[kTaps];
      for (int t = 0; t < kTaps; t++) {
        // How far input sample t of the window is from the output's place.
        const double d = (t - (kTaps / 2 - 1)) - frac;
        const double x = d / half;
        const double window = std::fabs(x) >= 1.0 ? 0.0 : besselI0(kBeta * std::sqrt(1.0 - x * x)) / i0beta;
        const double arg = kPi * cutoff * d;
        const double sinc = d == 0.0 ? 1.0 : std::sin(arg) / arg;
        row[t] = cutoff * sinc * window;
        sum += row[t];
      }
      for (int t = 0; t < kTaps; t++) table_[static_cast<size_t>(p) * kTaps + t] = static_cast<float>(row[t] / sum);
    }
  }

  /** The weights for a place [frac] of the way from one input sample to the next, into [out]. */
  void weights(double frac, float* out) const {
    const double at = frac * kPhases;
    const int p = std::min(kPhases - 1, static_cast<int>(at));
    const float mu = static_cast<float>(at - p);
    const float* a = table_.data() + static_cast<size_t>(p) * kTaps;
    const float* b = a + kTaps;
    for (int t = 0; t < kTaps; t++) out[t] = a[t] + (b[t] - a[t]) * mu;
  }

 private:
  static constexpr double kPi = 3.14159265358979323846;
  /** The window's shape: about 80 dB down outside the band. */
  static constexpr double kBeta = 8.0;

  static double besselI0(double x) {
    double sum = 1.0, term = 1.0;
    const double q = x * x / 4.0;
    for (int k = 1; k < 64; k++) {
      term *= q / (static_cast<double>(k) * k);
      sum += term;
      if (term < sum * 1e-17) break;
    }
    return sum;
  }

  std::vector<float> table_;
};
