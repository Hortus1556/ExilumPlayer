#pragma once
//
// Adapted from OpenMPT soundlib/plugins/dmo/I3DL2Reverb.{h,cpp}, its emulation
// of the DirectX I3DL2 reverb, and the room presets of sounddsp/Reverb.cpp.
// Copyright (c) 2004-2026, OpenMPT Project Developers and Contributors
// Copyright (c) 1997-2003, Olivier Lapicque
// SPDX-License-Identifier: BSD-3-Clause - full text in openmpt/LICENSE.
//
// Changes: on its own, without OpenMPT's plugin interface; always at the
// highest quality (every delay line, at the full rate); the rooms set in
// their own units rather than through the plugin's 0 to 1 parameters; the
// sound out only, the music mixed in by the caller; every delay line long
// enough for its tap - see fx-reverb.cpp; a whisper of offset on the input,
// so a tail dying away never turns into denormals; memory for the highest
// rate held from the start, so nothing is allocated where it runs.
//
#include <cstdint>

namespace exilum::fx {

/** The rooms, by the stable numbers the settings store. */
enum ReverbType : int {
  kReverbSmallRoom = 0,
  kReverbMediumRoom,
  kReverbLargeRoom,
  kReverbHall,
  kReverbConcertHall,
  kReverbArena,
  kReverbPlate,
  kReverbBathroom,
  kReverbCave,
  kReverbTypes,
};

class Reverb {
 public:
  /** The highest rate there is room for. */
  static constexpr int kMaxRate = 96000;

  /** Sets everything up for [rate] in [type]'s room, silent. False when the rate is past kMaxRate. */
  bool configure(int rate, int type);
  /** Another room, at the rate it has; silent again. */
  void setType(int type);
  int type() const { return type_; }
  /** Silent: every line and filter emptied. */
  void clear();

  /** The room's sound for [frames] frames of [inL]/[inR], into [wetL]/[wetR]; none of the music itself. */
  void process(const float* inL, const float* inR, float* wetL, float* wetR, int frames);

  /** What the room's sound is multiplied by to be about as loud as the music it is of. */
  float makeup() const;
  /** How long the room rings on after the music stops, in seconds - with a margin. */
  float tailSeconds() const;

 private:
  class DelayLine {
   public:
    void init(float* buffer, int32_t length, int32_t delayTap);
    void setDelayTap(int32_t delayTap);
    void clear();
    void advance() {
      if (--position_ < 0) position_ += length_;
      if (--delayPosition_ < 0) delayPosition_ += length_;
    }
    void set(float value) { buffer_[position_] = value; }
    float get(int32_t offset) const {
      int32_t at = position_ + offset;
      if (at >= length_) at -= length_;
      return buffer_[at];
    }
    float get() const { return buffer_[delayPosition_]; }

   private:
    float* buffer_ = nullptr;
    int32_t length_ = 0;
    int32_t position_ = 0;
    int32_t delayPosition_ = 0;
  };

  void recalculate();
  void setDelayTaps();
  void setDecayCoeffs();
  float calcDecayCoeffs(int index);

  int rate_ = 0;
  int type_ = 0;

  // The room, in I3DL2's units: millibels, seconds, percent.
  float room_ = 0.0f, roomHF_ = 0.0f, decayTime_ = 1.0f, decayHFRatio_ = 0.5f;
  float reflections_ = 0.0f, reflectionsDelay_ = 0.0f, reverb_ = 0.0f, reverbDelay_ = 0.0f;
  float diffusion_ = 100.0f, density_ = 100.0f, hfReference_ = 5000.0f;

  // Worked out from it.
  float diffusionCoeff_ = 0.0f;
  float roomFilter_ = 0.0f;
  float erLevel_ = 0.0f;
  float reverbLevelL_ = 0.0f;
  float reverbLevelR_ = 0.0f;
  int32_t delayTaps_[15] = {};  // 6*L + 6*R + LR + Early L + Early R
  int32_t earlyTaps_[2][6] = {};
  float delayCoeffs_[13][2] = {};

  // State. Line 12 is never used: it keeps the numbering of the taps and
  // coefficients, as in the original.
  DelayLine lines_[19];
  float filterHist_[19] = {};

  /** Room for every line at kMaxRate: 12 late lines of up to 80 ms, two of 4 ms, 407 + 400 ms of early ones, two of 10. */
  static constexpr int kPoolFloats = 12 * (kMaxRate * 80 / 1000 + 8) + 2 * (kMaxRate * 4 / 1000 + 8) +
                                     (kMaxRate * 407 / 1000 + 2) + (kMaxRate * 400 / 1000 + 2) +
                                     2 * (kMaxRate * 10 / 1000 + 2);
  /** The lines' memory, about 700 KB: a Reverb is kept for the life of the process, not made on the stack. */
  float pool_[kPoolFloats];
};

}  // namespace exilum::fx
