#pragma once
//
// Adapted from OpenMPT soundlib/plugins/dmo/Chorus.{h,cpp}, its emulation of
// the DirectX chorus.
// Copyright (c) 2004-2026, OpenMPT Project Developers and Contributors
// Copyright (c) 1997-2003, Olivier Lapicque
// SPDX-License-Identifier: BSD-3-Clause - full text in openmpt/LICENSE.
//
// Changes: on its own, without OpenMPT's plugin interface; the chorus only,
// not the flanger, with the sine wave; the DirectX defaults for its delay
// (16 ms), depth (10%) and feedback (25%) held fixed; the stereo phase, five
// steps in DirectX, made continuous - the "spread", 0 to 180 degrees, giving
// the same at DirectX's 0, 90 and 180; the sound out only, the music mixed in
// by the caller; a ring the size of a power of two, as long as its longest
// delay needs; a whisper of offset on the feedback against denormals.
//
#include <cstdint>

namespace exilum::fx {

class Chorus {
 public:
  /** The highest rate there is room for. */
  static constexpr int kMaxRate = 96000;

  /** Sets everything up for [rate], silent. False when the rate is past kMaxRate. */
  bool configure(int rate);
  /** Silent, the wave back at its start. */
  void clear();

  /** How fast the delay sways, in Hz: takes from the next sample on, the wave carrying on where it was. */
  void setRate(float hertz);

  /**
   * The chorus's sound for [frames] frames into [wetL]/[wetR]; none of the
   * music itself. The right side's sway is the left's turned by the spread,
   * given frame by frame as its cos and sin in [spreadCos] and [spreadSin] -
   * see spreadCos and spreadSin.
   */
  void process(const float* inL, const float* inR, float* wetL, float* wetR, int frames, const float* spreadCos,
               const float* spreadSin);

  /** cos and sin of a spread, 0 to 1 of half a turn: exactly 0 where they should be, so 0, 0.5 and 1 are DirectX's own. */
  static float spreadCos(float spread);
  static float spreadSin(float spread);

  /** How long the chorus rings on after the music stops, in seconds. */
  static constexpr float kTailSeconds = 0.25f;

 private:
  int32_t offset(int32_t fixedPoint) const { return (position_ + (fixedPoint >> 12)) & mask_; }

  /** Room for the longest delay at kMaxRate: 16 ms and its sway, a power of two. */
  static constexpr int kCapacity = 2048;

  float buffer_[kCapacity] = {};
  int32_t mask_ = 0;
  int32_t position_ = 0;

  int rate_ = 0;
  float depthDelay_ = 0.0f;     // the sway, in 1/4096 of a sample, at its widest
  int32_t delayOffset_ = 0;     // the delay at rest, in 1/4096 of a sample
  float waveShapeVal_ = 0.0f;   // the wave's step: 2 sin(pi f / rate)
  float waveShapeMin_ = 0.0f;   // the wave, a pair a quarter turn apart
  float waveShapeMax_ = 1.0f;
  int32_t delayL_ = 0;
  int32_t delayR_ = 0;
};

}  // namespace exilum::fx
