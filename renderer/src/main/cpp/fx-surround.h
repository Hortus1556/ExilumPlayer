#pragma once
//
// Adapted from OpenMPT sounddsp/DSP.cpp: ModPlug's surround, as Olivier
// Lapicque wrote it for ModPlug Player, and its ShelfEQ filter design.
// Copyright (c) 2004-2026, OpenMPT Project Developers and Contributors
// Copyright (c) 1997-2003, Olivier Lapicque
// SPDX-License-Identifier: BSD-3-Clause - full text in openmpt/LICENSE.
//
// Changes: in floating point rather than ModPlug's fixed point, the same
// filters and the same 20 ms delay; the depth, 1 to 16 in ModPlug, as a
// share from 0 to 1, which multiplies the effect after its filters, so that
// moving it never jolts them; memory for the highest rate held from the start.
//
// What it does: the middle of the mix - what the two sides have in common -
// delayed, band-passed between 200 Hz and 7 kHz, and added to one side and
// taken from the other. On headphones and a pair of speakers the sound opens
// out past them; summed to one speaker it cancels, and the music is as it was.
//

namespace exilum::fx {

class Surround {
 public:
  /** The highest rate there is room for. */
  static constexpr int kMaxRate = 96000;

  /** Sets everything up for [rate], silent. False when the rate is past kMaxRate. */
  bool configure(int rate);
  /** Silent. */
  void clear();

  /**
   * Adds the effect to [left]/[right] in place, at the depth [depth] has for
   * each frame, 0 to 1 - 1 is ModPlug's deepest, its default 0.75.
   */
  void process(float* left, float* right, int frames, const float* depth);

  /** How long it rings on after the music stops, in seconds. */
  static constexpr float kTailSeconds = 0.05f;

 private:
  /** The delay at kMaxRate: 20 ms. */
  static constexpr int kCapacity = kMaxRate * 20 / 1000;

  float buffer_[kCapacity] = {};
  int size_ = 0;
  int position_ = 0;

  // The high-pass at 200 Hz and the low-pass at 7 kHz: y = b0 x + b1 x1 + a1 y1.
  float hpB0_ = 0.0f, hpB1_ = 0.0f, hpA1_ = 0.0f;
  float lpB0_ = 0.0f, lpB1_ = 0.0f, lpA1_ = 0.0f;
  float hpX1_ = 0.0f;  // the high-pass's last input
  float hpY1_ = 0.0f;  // its last output, which is the low-pass's last input too
  float lpY1_ = 0.0f;
};

}  // namespace exilum::fx
