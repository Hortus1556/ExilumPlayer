#pragma once
#include <array>
#include <cstdint>

/**
 * Loudness as ITU-R BS.1770-4 measures it: each channel K-weighted (a high
 * shelf for the head, a high-pass under 40 Hz), mean square over 400 ms blocks
 * that step every 100 ms, and the integrated figure over every block louder
 * than -70 LUFS and no more than 10 LU under the average of those.
 *
 * The blocks are kept as a histogram in tenths of a dB, so a whole track can
 * be measured in constant memory. Shared by Sound Check in the output stage
 * and by the analysis that measures a track before it plays, so the two can
 * never disagree about what a number means.
 */
class LoudnessMeter {
 public:
  static constexpr float kUnknown = -100.0f;
  static constexpr int kMaxChannels = 8;

  /** Starts again, for audio at [rate]. */
  void reset(int rate);

  /**
   * Adds one frame of [channels] samples, full scale 1.0. True when it
   * completed a 100 ms hop - the moment the integrated figure can change.
   */
  bool addFrame(const double* frame, int channels);

  /** Integrated loudness so far, in LUFS, or kUnknown. */
  float integrated() const { return integrated_; }
  /** How much audio that figure counts, in seconds: the blocks that passed both gates. */
  float gatedSeconds() const { return gatedSeconds_; }
  int rate() const { return rate_; }

 private:
  static constexpr int kBins = 800;  // -70 .. +10 LUFS
  static constexpr float kFloor = -70.0f;
  static constexpr int kHopsPerBlock = 4;

  struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
  };

  void endHop();

  int rate_ = 0;
  Biquad shelf_, highpass_;
  std::array<double, kMaxChannels> s1_{}, s2_{}, h1_{}, h2_{};
  int hopFrames_ = 4800;
  int hopFilled_ = 0;
  double hopEnergy_ = 0.0;
  std::array<double, kHopsPerBlock> hops_{};
  int hopsSeen_ = 0;
  std::array<uint32_t, kBins> binCount_{};
  std::array<double, kBins> binEnergy_{};
  float integrated_ = kUnknown;
  float gatedSeconds_ = 0.0f;
};
