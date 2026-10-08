#pragma once
//
// A ten-band graphic equalizer, one peaking filter an octave wide per band.
// The bands are those of OpenMPT soundlib/plugins/dmo/ParamEq.cpp, its
// emulation of the DirectX parametric equalizer: the Audio EQ Cookbook's
// peaking filter, with its width given in octaves as there.
// Copyright (c) 2004-2026, OpenMPT Project Developers and Contributors
// Copyright (c) 1997-2003, Olivier Lapicque
// SPDX-License-Identifier: BSD-3-Clause - full text in openmpt/LICENSE.
//
// Changes: ten of them in a row at fixed frequencies, 31.25 Hz to 16 kHz;
// each run as a state-variable filter - Andrew Simper's linear trapezoidal
// one (Cytomic, "Solving the continuous SVF equations using trapezoidal
// integration", 2013) - rather than a biquad: the same response, but one
// whose gain can move while music goes through it without the jolts a
// biquad's moving coefficients give; in double precision, which the lowest
// bands need; a band's gain glides when it is moved rather than stepping;
// the whole turned down by its largest boost, so it never clips by itself.
//
#include <cstdint>

namespace exilum::fx {

class Equalizer {
 public:
  static constexpr int kBands = 10;
  /** How far a band goes either way, in dB. */
  static constexpr int kMaxDb = 12;

  /** Sets everything up for [rate], at the gains set and silent. */
  void configure(int rate);
  /** Silent: every band's memory emptied, and every gain where it is going. */
  void clear();

  /** The gains to glide to, in dB, [kBands] of them. */
  void setTargets(const float* gainsDb);

  /** Frames between two steps of the glides: the caller's control rate, which it steps at with step(). */
  static constexpr int kStep = 32;
  /** The glides one step on, the filters worked out again; [kStep] frames are processed until the next. */
  void step();

  /**
   * Equalizes [frames] frames of [left]/[right] in place: [at] frames into
   * the step, which they do not go past. The gain before the bands moves
   * across the step in a line from where it was to where step() took it.
   */
  void process(float* left, float* right, int frames, int at);

  /**
   * Nothing to do, nor to come: every band at 0 dB and meant to be. It is
   * then left out, and its filters emptied; they start again from silence.
   */
  bool flat() const { return flat_; }
  /** The gain before the bands, in dB: minus the largest boost the bands add up to. */
  float preampDb() const { return preampDb_; }

  /** How long it rings on after the music stops, in seconds. */
  static constexpr float kTailSeconds = 0.25f;

 private:
  struct Band {
    double g = 0.0;       // tan(w0 / 2): the band's frequency
    double q = 1.0;       // its width, as a Q
    float targetDb = 0.0f;
    float db = 0.0f;      // where its glide has got to
    double a1 = 1.0, a2 = 0.0, a3 = 0.0, m1 = 0.0;  // the filter at that gain
    // The filter at the step before: across a step each term goes in a line
    // from there to the above, so the gain never jumps.
    double a1From = 1.0, a2From = 0.0, a3From = 0.0, m1From = 0.0;
    double ic1eq[2] = {0.0, 0.0};  // the two integrators' states, per side
    double ic2eq[2] = {0.0, 0.0};
  };

  void design(Band& band);
  void recomputePreamp();
  void updateFlat();

  int rate_ = 0;
  Band bands_[kBands];
  bool flat_ = true;
  float targetPreampDb_ = 0.0f;
  float preampDb_ = 0.0f;
  double preampGain_ = 1.0;
  double preampFrom_ = 1.0;  // the gain before the bands at the last step
  float smoothing_ = 0.0f;   // per step

  /**
   * Where the gain before the bands is worked out from: cos w and cos 2w at
   * each band's centre and at points about a 25th of an octave apart from 20 Hz to
   * 20 kHz - close enough that the loudest point between two of them is at
   * most a few hundredths of a dB above the louder.
   */
  static constexpr int kProbes = kBands + 246;
  double probeCos1_[kProbes] = {};
  double probeCos2_[kProbes] = {};
  /** And each band's Cookbook terms for them: cos w0 and alpha. */
  double cosW0_[kBands] = {};
  double alpha_[kBands] = {};
};

}  // namespace exilum::fx
