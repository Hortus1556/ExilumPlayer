//
// The equalizer. See fx-equalizer.h for where its filters come from and for
// the licence.
//
#include "fx-equalizer.h"
#include <algorithm>
#include <cmath>

namespace exilum::fx {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kLn2 = 0.69314718055994530942;
/** How quickly a gain follows its slider: the time constant of its glide. */
constexpr double kGlideSeconds = 0.03;
/** Close enough to stop gliding, in dB. */
constexpr float kSnapDb = 0.001f;
/** The bands' width, in octaves. */
constexpr double kBandwidth = 1.0;

double centre(int band) { return 31.25 * std::ldexp(1.0, band); }

}  // namespace

void Equalizer::configure(int rate) {
  rate_ = rate;
  const double nyquistGuard = 0.45 * rate;
  for (int i = 0; i < kBands; i++) {
    Band& band = bands_[i];
    const double w0 = 2.0 * kPi * std::min(centre(i), nyquistGuard) / rate;
    const double sinW0 = std::sin(w0);
    // ParamEq's width, an octave wide at its edges, as the Cookbook's alpha
    // - and the same as a Q for the state-variable form.
    const double alpha = sinW0 * std::sinh((kBandwidth * kLn2 / 2.0) * w0 / sinW0);
    cosW0_[i] = std::cos(w0);
    alpha_[i] = alpha;
    band.q = sinW0 / (2.0 * alpha);
    band.g = std::tan(w0 / 2.0);
  }
  // Where the response is looked at: each band's centre, and points spaced
  // evenly in octaves across what can be heard.
  const double top = std::min(20000.0, nyquistGuard);
  for (int p = 0; p < kProbes; p++) {
    const double f = p < kBands ? std::min(centre(p), nyquistGuard)
                                : 20.0 * std::pow(top / 20.0, static_cast<double>(p - kBands) / (kProbes - kBands - 1));
    const double w = 2.0 * kPi * f / rate;
    probeCos1_[p] = std::cos(w);
    probeCos2_[p] = std::cos(2.0 * w);
  }
  smoothing_ = static_cast<float>(1.0 - std::exp(-kStep / (kGlideSeconds * rate)));
  recomputePreamp();
  clear();
}

void Equalizer::clear() {
  for (Band& band : bands_) {
    band.ic1eq[0] = band.ic1eq[1] = band.ic2eq[0] = band.ic2eq[1] = 0.0;
    band.db = band.targetDb;
    if (rate_ > 0) design(band);
    band.a1From = band.a1;
    band.a2From = band.a2;
    band.a3From = band.a3;
    band.m1From = band.m1;
  }
  preampDb_ = targetPreampDb_;
  preampGain_ = preampFrom_ = std::pow(10.0, preampDb_ / 20.0);
  updateFlat();
}

void Equalizer::setTargets(const float* gainsDb) {
  for (int i = 0; i < kBands; i++) {
    bands_[i].targetDb = std::clamp(gainsDb[i], static_cast<float>(-kMaxDb), static_cast<float>(kMaxDb));
  }
  recomputePreamp();
  // Turned on or moved: running from the next step.
  for (const Band& band : bands_) {
    if (band.targetDb != 0.0f) flat_ = false;
  }
}

void Equalizer::design(Band& band) {
  // The bell of Simper's state-variable filter: at the same g and Q, the
  // Cookbook's peaking filter to the last bit of its response.
  const double a = std::pow(10.0, band.db / 40.0);
  const double k = 1.0 / (band.q * a);
  band.a1 = 1.0 / (1.0 + band.g * (band.g + k));
  band.a2 = band.g * band.a1;
  band.a3 = band.g * band.a2;
  band.m1 = k * (a * a - 1.0);
}

void Equalizer::recomputePreamp() {
  // The response of the bands as they will be, at each point: the largest
  // boost anywhere is what the whole is turned down by.
  // Each band's |H|^2 as a function of cos w and cos 2w: three terms over three.
  struct Terms {
    double n0, n1, n2, d0, d1, d2;
  } terms[kBands];
  int count = 0;
  for (int i = 0; i < kBands; i++) {
    const float db = bands_[i].targetDb;
    if (db == 0.0f) continue;
    const double a = std::pow(10.0, db / 40.0);
    const double alpha = alpha_[i];
    const double b0 = 1.0 + alpha * a, b1 = -2.0 * cosW0_[i], b2 = 1.0 - alpha * a;
    const double a0 = 1.0 + alpha / a, a1 = b1, a2 = 1.0 - alpha / a;
    terms[count++] = {b0 * b0 + b1 * b1 + b2 * b2, 2.0 * (b0 * b1 + b1 * b2), 2.0 * b0 * b2,
                      a0 * a0 + a1 * a1 + a2 * a2, 2.0 * (a0 * a1 + a1 * a2), 2.0 * a0 * a2};
  }
  double peakDb = 0.0;
  for (int p = 0; p < kProbes && count > 0; p++) {
    const double c1 = probeCos1_[p], c2 = probeCos2_[p];
    double magnitude2 = 1.0;
    for (int i = 0; i < count; i++) {
      const Terms& t = terms[i];
      magnitude2 *= (t.n0 + t.n1 * c1 + t.n2 * c2) / (t.d0 + t.d1 * c1 + t.d2 * c2);
    }
    peakDb = std::max(peakDb, 10.0 * std::log10(magnitude2));
  }
  targetPreampDb_ = peakDb > 1e-4 ? static_cast<float>(-peakDb) : 0.0f;
}

void Equalizer::updateFlat() {
  bool flat = preampDb_ == 0.0f && targetPreampDb_ == 0.0f;
  for (const Band& band : bands_) {
    if (band.db != 0.0f || band.targetDb != 0.0f) flat = false;
  }
  if (flat && !flat_) {
    for (Band& band : bands_) band.ic1eq[0] = band.ic1eq[1] = band.ic2eq[0] = band.ic2eq[1] = 0.0;
  }
  flat_ = flat;
}

void Equalizer::step() {
  for (Band& band : bands_) {
    band.a1From = band.a1;
    band.a2From = band.a2;
    band.a3From = band.a3;
    band.m1From = band.m1;
    if (band.db == band.targetDb) continue;
    band.db += (band.targetDb - band.db) * smoothing_;
    if (std::fabs(band.targetDb - band.db) < kSnapDb) band.db = band.targetDb;
    design(band);
  }
  preampFrom_ = preampGain_;
  if (preampDb_ != targetPreampDb_) {
    preampDb_ += (targetPreampDb_ - preampDb_) * smoothing_;
    if (std::fabs(targetPreampDb_ - preampDb_) < kSnapDb) preampDb_ = targetPreampDb_;
    preampGain_ = std::pow(10.0, preampDb_ / 20.0);
  }
  // Only once all of it has arrived at nothing - and the gain before the
  // bands has got there across the step just gone - is it left out.
  if (!flat_ && preampFrom_ == preampGain_) updateFlat();
}

void Equalizer::process(float* left, float* right, int frames, int at) {
  double block[2][kStep];
  const double gainStep = (preampGain_ - preampFrom_) / kStep;
  for (int i = 0; i < frames; i++) {
    const double gain = preampGain_ == preampFrom_ ? preampGain_ : preampFrom_ + gainStep * (at + i + 1);
    block[0][i] = left[i] * gain;
    block[1][i] = right[i] * gain;
  }

  // Every band runs while any is on, those at 0 dB too: at 0 dB a band adds
  // nothing to what goes through it, and its filter keeps up with the music
  // for when it is moved.
  for (Band& band : bands_) {
    const bool moving = band.m1 != band.m1From || band.a1 != band.a1From;
    for (int c = 0; c < 2; c++) {
      double ic1eq = band.ic1eq[c];
      double ic2eq = band.ic2eq[c];
      double* x = block[c];
      for (int i = 0; i < frames; i++) {
        double a1 = band.a1, a2 = band.a2, a3 = band.a3, m1 = band.m1;
        if (moving) {
          const double t = static_cast<double>(at + i + 1) / kStep;
          a1 = band.a1From + (band.a1 - band.a1From) * t;
          a2 = band.a2From + (band.a2 - band.a2From) * t;
          a3 = band.a3From + (band.a3 - band.a3From) * t;
          m1 = band.m1From + (band.m1 - band.m1From) * t;
        }
        const double v0 = x[i];
        const double v3 = v0 - ic2eq;
        const double v1 = a1 * ic1eq + a2 * v3;
        const double v2 = ic2eq + a2 * ic1eq + a3 * v3;
        ic1eq = 2.0 * v1 - ic1eq;
        ic2eq = 2.0 * v2 - ic2eq;
        x[i] = v0 + m1 * v1;
      }
      // Below anything audible, and before the numbers turn slow: off.
      band.ic1eq[c] = std::fabs(ic1eq) < 1e-30 ? 0.0 : ic1eq;
      band.ic2eq[c] = std::fabs(ic2eq) < 1e-30 ? 0.0 : ic2eq;
    }
  }

  for (int i = 0; i < frames; i++) {
    left[i] = static_cast<float>(block[0][i]);
    right[i] = static_cast<float>(block[1][i]);
  }
}

}  // namespace exilum::fx
