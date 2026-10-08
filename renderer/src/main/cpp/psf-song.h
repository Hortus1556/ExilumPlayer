#pragma once
#include "psf.h"
#include "shift-stage.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

/**
 * What the decoders of the PSF family share once their console is running.
 *
 * The music goes round by itself, so a song is played for as long as its
 * tags say and faded after; and its sound gets out the same way whichever
 * console made it - through the stage that keeps pitch and tempo apart (see
 * shift-stage.h), in pieces short enough to show the channels between them.
 */
namespace psf {

/** How often the channels are published: twice a sixty hertz display. */
constexpr int kPublishHz = 120;

inline int64_t msToFrames(int64_t ms, int rate) { return ms * rate / 1000; }

/** How long a song is played for, and how it ends. */
struct SongLength {
  int lengthMs = 0;
  int fadeMs = 0;
  /** Whether the tags gave the length, rather than the setting for files that give none. */
  bool known = false;
  /** The listener's own length for the file, in place of both; 0 when there is none. */
  int overrideMs = 0;

  /** The "length" and "fade" tags: the setting's length where there is none, and ten seconds of fade. */
  void fromTags(const File& file, int defaultLengthMs) {
    const int length = parseTimeMs(file.tag("length"));
    const int fade = parseTimeMs(file.tag("fade"));
    known = length > 0;
    lengthMs = known ? length : defaultLengthMs;
    fadeMs = fade >= 0 ? fade : 10000;
    overrideMs = 0;
  }

  int totalMs() const {
    if (overrideMs > 0) return overrideMs;
    // Added wide: a length and a fade each within range are not, together.
    const int64_t total = static_cast<int64_t>(lengthMs) + fadeMs;
    return static_cast<int>(std::min<int64_t>(total, std::numeric_limits<int>::max()));
  }

  /** Where the song stops, in frames. */
  int64_t endFrame(int rate) const {
    if (overrideMs > 0) return msToFrames(overrideMs, rate);
    return msToFrames(static_cast<int64_t>(lengthMs) + fadeMs, rate);
  }

  /** The fade's gain at a frame: whole until the length, down to nothing at its end. */
  float gainAt(int64_t frame, int rate) const {
    if (overrideMs > 0 || fadeMs <= 0) return 1.0f;
    const int64_t fadeFrom = msToFrames(lengthMs, rate);
    if (frame < fadeFrom) return 1.0f;
    const int64_t fadeFrames = std::max<int64_t>(1, msToFrames(fadeMs, rate));
    return std::max(0.0f, 1.0f - static_cast<float>(frame - fadeFrom) / fadeFrames);
  }
};

/** The tags worth showing besides the title, one to a line. */
inline std::string describe(const File& file) {
  std::string text;
  for (const char* key : {"game", "artist", "copyright", "year", "comment"}) {
    const std::string& value = file.tag(key);
    if (value.empty()) continue;
    if (!text.empty()) text += "\n";
    text += value;
  }
  return text;
}

/** The two sides apart by [width]: as they are at 1, one at 0, wider beyond. */
inline void spread(float& left, float& right, float width) {
  const float mid = (left + right) * 0.5f;
  const float side = (left - right) * 0.5f * width;
  left = mid + side;
  right = mid - side;
}

/**
 * Fills [audioData] with [numFrames] frames of [source] taken through
 * [stage], a published piece at a time: [publish] is called after each with
 * the frames written before it, for the channels to be shown as of then.
 * Returns 1 when the song ended inside the call, what was left being filled
 * with silence, and 0 otherwise. [scratch] is the caller's, kept between
 * calls so nothing is allocated in them.
 */
template <typename Publish>
int renderSong(ShiftStage& stage, std::array<std::vector<float>, 2>& scratch, int sampleRate,
               const ShiftStage::Source& source, const Publish& publish, void* audioData, int32_t numFrames,
               int32_t numChannels, int32_t bytesPerSample) {
  const size_t frameBytes = static_cast<size_t>(numChannels) * bytesPerSample;
  const int32_t publishFrames = std::max(1, sampleRate / kPublishHz);
  if (static_cast<int32_t>(scratch[0].size()) < publishFrames) {
    scratch[0].resize(publishFrames);
    scratch[1].resize(publishFrames);
  }
  int32_t written = 0;
  while (written < numFrames) {
    const int32_t chunk = std::min(numFrames - written, publishFrames);
    const int got = stage.render(scratch[0].data(), scratch[1].data(), chunk, source);
    for (int32_t i = 0; i < got; i++) {
      const float l = std::clamp(scratch[0][i], -1.0f, 1.0f);
      const float r = std::clamp(scratch[1][i], -1.0f, 1.0f);
      if (bytesPerSample == 4) {
        float* out = static_cast<float*>(audioData) + static_cast<size_t>(written + i) * numChannels;
        out[0] = l;
        if (numChannels > 1) out[1] = r;
      } else {
        int16_t* out = static_cast<int16_t*>(audioData) + static_cast<size_t>(written + i) * numChannels;
        out[0] = static_cast<int16_t>(std::lround(l * 32767.0f));
        if (numChannels > 1) out[1] = static_cast<int16_t>(std::lround(r * 32767.0f));
      }
    }
    publish(written);
    written += got;
    if (got < chunk) {
      std::memset(static_cast<uint8_t*>(audioData) + static_cast<size_t>(written) * frameBytes, 0,
                  static_cast<size_t>(numFrames - written) * frameBytes);
      return 1;
    }
  }
  return 0;
}

}  // namespace psf
