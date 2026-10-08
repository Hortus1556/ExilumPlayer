#pragma once
// Before the stretch: its FFT uses std::memcpy without including it.
#include <cstring>
#include "signalsmith-stretch/signalsmith-stretch.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <functional>
#include <vector>

/**
 * Pitch and tempo for a decoder that cannot change them itself: an emulated
 * console, whose music runs on the console's own clock. Run faster, a
 * console plays higher as well, as a record does; so the two are kept apart
 * here, after the console, by Signalsmith Stretch - the tempo by how much is
 * taken in for what is given out, the pitch by transposing what comes out.
 *
 * Out of the way while both are where the song has them: nothing is
 * stretched and nothing is delayed. Once either moves it stays in the way
 * until the next reset - a load or a seek - since leaving it would jump the
 * song by its latency.
 */
class ShiftStage {
 public:
  /** Fills up to [frames] frames of each side and says how many; fewer at the song's end. */
  using Source = std::function<int(float* left, float* right, int frames)>;

  /** Sets the stage up for a rate. Resets it. */
  void configure(int sampleRate) {
    if (sampleRate != sampleRate_) {
      sampleRate_ = sampleRate;
      // The cheaper preset spreads its work over the calls, as an audio
      // callback wants, rather than doing a block's worth in one of them.
      stretch_.presetCheaper(2, static_cast<float>(sampleRate));
    }
    reset();
  }

  void setPitch(double semitones) {
    pitch_ = semitones;
    if (engaged_) stretch_.setTransposeSemitones(static_cast<float>(pitch_));
  }

  void setTempo(double factor) { tempo_ = std::clamp(factor, 0.05, 4.0); }

  /** Back out of the way, the next render taking the source from where it is. */
  void reset() {
    engaged_ = false;
    carry_ = 0.0;
    tailLeft_ = -1;
  }

  bool engaged() const { return engaged_; }
  bool neutral() const { return std::fabs(pitch_) < 1e-6 && std::fabs(tempo_ - 1.0) < 1e-6; }

  /** Frames of the source taken but not heard yet: how far the source is ahead of the output. */
  double latencyFrames() const { return engaged_ ? lead_ : 0.0; }

  /**
   * Fills [frames] frames of each side from [source], shifted; returns how
   * many are the song's, the rest being silence after its end.
   */
  int render(float* left, float* right, int frames, const Source& source) {
    if (!engaged_) {
      if (neutral()) return source(left, right, frames);
      engage(source);
    }
    int done = 0;
    while (done < frames) {
      const int chunk = std::min(frames - done, kChunk);
      // The tempo is the input a stretch of output is made from.
      const double wanted = chunk * tempo_ + carry_;
      const int input = static_cast<int>(wanted);
      carry_ = wanted - input;
      ensure(input);
      int got = 0;
      if (tailLeft_ < 0) {
        got = source(in_[0].data(), in_[1].data(), input);
        if (got < input) {
          // The song is over: what is still inside the stretch comes out
          // over the latency, and after it, nothing.
          tailLeft_ = static_cast<int>(std::ceil(lead_ / std::max(tempo_, 0.05))) + chunk;
        }
      }
      std::fill(in_[0].begin() + got, in_[0].begin() + input, 0.0f);
      std::fill(in_[1].begin() + got, in_[1].begin() + input, 0.0f);
      float* inputs[2] = {in_[0].data(), in_[1].data()};
      float* outputs[2] = {left + done, right + done};
      stretch_.process(inputs, input, outputs, chunk);
      lead_ = stretch_.inputLatency() + stretch_.outputLatency() * tempo_;
      if (tailLeft_ >= 0) {
        const int heard = std::min(chunk, tailLeft_);
        tailLeft_ -= heard;
        if (tailLeft_ <= 0) {
          std::fill(left + done + heard, left + frames, 0.0f);
          std::fill(right + done + heard, right + frames, 0.0f);
          return done + heard;
        }
      }
      done += chunk;
    }
    return done;
  }

 private:
  /** Frames stretched at a time, so a change of tempo reaches the next of them. */
  static constexpr int kChunk = 256;

  /**
   * Into the way without a gap: the stretch is handed the source ahead of
   * where it is, and set so that what it gives out next starts from where
   * the song had got to.
   */
  void engage(const Source& source) {
    stretch_.reset();
    stretch_.setTransposeSemitones(static_cast<float>(pitch_));
    const int ahead = stretch_.outputSeekLength(static_cast<float>(tempo_));
    ensure(ahead);
    const int got = source(in_[0].data(), in_[1].data(), ahead);
    std::fill(in_[0].begin() + got, in_[0].begin() + ahead, 0.0f);
    std::fill(in_[1].begin() + got, in_[1].begin() + ahead, 0.0f);
    float* inputs[2] = {in_[0].data(), in_[1].data()};
    stretch_.outputSeek(inputs, ahead);
    lead_ = ahead;
    carry_ = 0.0;
    engaged_ = true;
    if (got < ahead) tailLeft_ = static_cast<int>(std::ceil(ahead / std::max(tempo_, 0.05)));
  }

  void ensure(int frames) {
    if (static_cast<int>(in_[0].size()) < frames) {
      in_[0].resize(frames);
      in_[1].resize(frames);
    }
  }

  signalsmith::stretch::SignalsmithStretch<float> stretch_;
  std::array<std::vector<float>, 2> in_;
  int sampleRate_ = 0;
  double pitch_ = 0.0;
  double tempo_ = 1.0;
  bool engaged_ = false;
  double carry_ = 0.0;
  double lead_ = 0.0;
  /** Output frames left before the end, once the source has run out; -1 before. */
  int tailLeft_ = -1;
};
