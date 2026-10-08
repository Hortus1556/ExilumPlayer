//
// The spectrum of the output. See spectrum.h.
//
#include <cstring>  // before the FFT's header, which uses memcpy without it

#include "spectrum.h"
#include "audio.h"
#include "signalsmith-linear/fft.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <complex>
#include <mutex>
#include <vector>

namespace {

/**
 * Frames of the output kept, left and right: a power of two, so a frame's
 * place is its number masked. A second and a third at 48 kHz, which is
 * further back than any device is still playing, with the window to spare.
 */
constexpr int64_t kRingFrames = 65536;
/**
 * Frames each reading is worked out from: 85 ms at 48 kHz, a bin every
 * 11.7 Hz. Long enough for a bass note to sit in one or two of the lowest
 * columns of a wide display rather than across all of them - there the
 * columns are about a bin wide, and a note between two lights both - and no
 * longer: twice this would tell them apart, and smear a kick drum over a
 * sixth of a second on bars that are meant to jump with it.
 */
constexpr int kWindowFrames = 4096;

/** The bands run from here... */
constexpr double kLowHz = 40.0;
/** ...to here, or to just under half the rate where that is lower. */
constexpr double kHighHz = 16000.0;

/**
 * What the bars show, in decibels below a full-scale tone: the bottom of the
 * display and its top. Measured on tracker music as it comes out: a band's
 * loud moments are around -21, its loudest a few above that, and its quiet
 * ones in the -40s. So the loud moments are most of the way up, the top
 * rows are for the peaks, and a note dying away has the whole height to
 * fall through.
 */
constexpr double kFloorDb = -50.0;
constexpr double kCeilDb = -14.0;
/**
 * Decibels added for each octave above 1 kHz, and taken off below. Music has
 * a little less in each octave than in the one under it, even counted by the
 * octave as here, and shown as it is the bars would slope down to the right;
 * this much stands them level.
 */
constexpr double kTiltDbPerOctave = 1.0;

/**
 * Left then right of each frame. Atomic for the sake of being read while
 * written, which it is: a frame the reader takes may be the one the audio
 * thread is putting down. Relaxed, so each is an ordinary load or store; a
 * window with a few frames of the next second in it is a picture a hair off,
 * and no more.
 */
std::array<std::atomic<int16_t>, kRingFrames * 2> g_ring{};
/** The stream frame after the last one kept; 0 for nothing kept of this stream. */
std::atomic<int64_t> g_end{0};
std::atomic<int> g_rate{48000};

/** The reader's working space, made once: the transform, its window, and room for both sides of it. */
struct Scratch {
  signalsmith::linear::Pow2FFT<float> fft{kWindowFrames};
  std::vector<float> window = std::vector<float>(kWindowFrames);
  std::vector<std::complex<float>> time = std::vector<std::complex<float>>(kWindowFrames);
  std::vector<std::complex<float>> freq = std::vector<std::complex<float>>(kWindowFrames);
  std::vector<float> power = std::vector<float>(kWindowFrames / 2);

  Scratch() {
    // Hann: nothing of one band leaks far into another.
    for (int i = 0; i < kWindowFrames; i++) {
      window[i] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * 3.14159265358979323846 * i / kWindowFrames));
    }
  }
};

double highHz(int rate) { return std::min(kHighHz, rate * 0.45); }

/** Where band [index] of [count] starts, in Hz: [count] for where the last one ends. */
double bandEdgeHz(int index, int count, int rate) {
  return kLowHz * std::pow(highHz(rate) / kLowHz, static_cast<double>(index) / count);
}

}  // namespace

void spectrum_write(const int16_t* samples, int32_t frames, int32_t channels, int64_t firstFrame, int rate) {
  if (samples == nullptr || frames <= 0 || channels <= 0 || rate <= 0) return;
  // The first two channels are the left and the right; one alone is both.
  const int32_t second = channels > 1 ? 1 : 0;
  for (int32_t i = 0; i < frames; i++) {
    const int16_t* frame = samples + static_cast<size_t>(i) * channels;
    const size_t at = static_cast<size_t>((firstFrame + i) & (kRingFrames - 1)) * 2;
    g_ring[at].store(frame[0], std::memory_order_relaxed);
    g_ring[at + 1].store(frame[second], std::memory_order_relaxed);
  }
  g_rate.store(rate, std::memory_order_relaxed);
  g_end.store(firstFrame + frames, std::memory_order_release);
}

void spectrum_reset() { g_end.store(0, std::memory_order_release); }

float spectrum_band_hz(int index, int count) {
  if (count <= 0) return 0.0f;
  const int rate = g_rate.load(std::memory_order_relaxed);
  return static_cast<float>(std::sqrt(bandEdgeHz(index, count, rate) * bandEdgeHz(index + 1, count, rate)));
}

bool spectrum_read(float* bands, int count) {
  if (bands == nullptr || count <= 0) return false;
  count = std::min(count, kSpectrumMaxBands);
  std::fill(bands, bands + count, -1.0f);
  const int64_t end = g_end.load(std::memory_order_acquire);
  const int rate = g_rate.load(std::memory_order_relaxed);
  if (end <= 0 || rate <= 0) return false;

  // The stretch around the frame being heard: half of it is still to come,
  // which is there to take because the device plays behind what it is given.
  // Where it is not - a device with almost no latency - the newest there is;
  // and never further back than is still kept.
  int64_t last = std::min(end, get_frames_played() + kWindowFrames / 2);
  last = std::max(last, end - (kRingFrames - kWindowFrames));
  const int64_t first = last - kWindowFrames;

  // One display asks, once a frame; a second asker waits its turn rather
  // than share the working space.
  static std::mutex readers;
  const std::lock_guard<std::mutex> lock(readers);
  static Scratch scratch;
  // The left as the real part and the right as the imaginary: one transform
  // of the pair, from which the power of each comes apart again below.
  for (int i = 0; i < kWindowFrames; i++) {
    const int64_t frame = first + i;
    // Before the stream's first frame there was nothing.
    if (frame < 0) {
      scratch.time[i] = 0.0f;
      continue;
    }
    const size_t at = static_cast<size_t>(frame & (kRingFrames - 1)) * 2;
    const float scale = scratch.window[i] / 32768.0f;
    scratch.time[i] = {g_ring[at].load(std::memory_order_relaxed) * scale,
                       g_ring[at + 1].load(std::memory_order_relaxed) * scale};
  }
  scratch.fft.fft(scratch.time.data(), scratch.freq.data());
  // The power of the two channels, and not of their sum: a voice on one side
  // only, or out of phase between the two - a tracker's surround, the
  // surround effect here - is as loud in the bars as it is in the ears, where
  // their sum has half of the one and nothing of the other. With L and R the
  // transforms of each, bin k of the pair is L + iR and bin N-k the
  // conjugates' L* + iR*; their squares add up to twice |L|^2 + |R|^2, so a
  // quarter of that is the mean of the two - and sound that is the same on
  // both sides reads exactly what it would alone.
  //
  // A full-scale tone comes out of a Hann window as a quarter of the frames
  // in its own bin: against that, so 1 is full scale there. (With the half
  // of it in the bin either side, a band that takes in all three reads a
  // tone 1.8 dB over; the range above was set by eye with that in it.) Bin 0
  // is the steady part, which is nothing a bar shows.
  const float unit = 0.25f / (static_cast<float>(kWindowFrames / 4) * static_cast<float>(kWindowFrames / 4));
  scratch.power[0] = 0.0f;
  for (int k = 1; k < kWindowFrames / 2; k++) {
    scratch.power[k] = (std::norm(scratch.freq[k]) + std::norm(scratch.freq[kWindowFrames - k])) * unit;
  }

  const double binsPerHz = static_cast<double>(kWindowFrames) / rate;
  for (int b = 0; b < count; b++) {
    const double lowHz = bandEdgeHz(b, count, rate);
    const double highEdgeHz = bandEdgeHz(b + 1, count, rate);
    // Bin k is the sound from k - 1/2 to k + 1/2: the band takes of each bin
    // the share that lies inside it.
    const double from = lowHz * binsPerHz + 0.5;
    const double to = std::max(from + 1e-6, highEdgeHz * binsPerHz + 0.5);
    double sum = 0.0;
    for (int k = std::max(1, static_cast<int>(from)); k < kWindowFrames / 2 && k < to; k++) {
      const double inside = std::min<double>(k + 1, to) - std::max<double>(k, from);
      if (inside > 0.0) sum += scratch.power[k] * inside;
    }
    // A band narrower than a bin has only a share of the one it sits in,
    // which would read a tone there as fainter than it is.
    const double width = to - from;
    if (width < 1.0) sum /= width;

    const double centreHz = std::sqrt(lowHz * highEdgeHz);
    const double db = 10.0 * std::log10(sum + 1e-12) + kTiltDbPerOctave * std::log2(centreHz / 1000.0);
    // Not held to the display: what is under its bottom or over its top is said, for a display that turns itself up.
    bands[b] = static_cast<float>(std::clamp((db - kFloorDb) / (kCeilDb - kFloorDb), -1.0, 2.0));
  }
  return true;
}
