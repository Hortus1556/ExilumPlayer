#pragma once
#include "audio.h"
#include "visual-state.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cstring>

/**
 * How a decoder hands its state to the views: without blocking on them, and
 * describing what the speaker is emitting rather than what the renderer has
 * already run ahead and produced.
 *
 * The decoders that were added first did the obvious thing twice over. They
 * read the live state through the same mutex the render callback holds -
 * which makes the UI wait on the audio thread sixty times a second - and they
 * reported it as of the moment it was rendered, which is one full output
 * buffer ahead of anything anyone can hear. So the traces stuttered *and* led
 * the sound.
 *
 * libopenmpt's path here solved both years ago with a ring of frame-stamped
 * snapshots, and this is the same idea for everyone else: the render callback
 * fills the next slot and stamps it with the output frame the audio it just
 * produced will start playing at; a reader asks for the newest slot that has
 * already reached the speaker. Nobody waits for anybody.
 */

/** Columns in a published trace. Matches PlaybackSnapshot.SCOPE_WIDTH. */
constexpr int kScopeColumns = 256;

struct VisualFrame {
  std::array<int32_t, kScalarCount> scalars{};
  std::array<int32_t, MAX_CHANNELS * kChannelFields> channels{};
  std::array<int8_t, MAX_CHANNELS * kScopeColumns> scopes{};

  void clear() {
    scalars.fill(0);
    channels.fill(0);
    scopes.fill(0);
    // Unity, not zero: a decoder with no master volume of its own is at full
    // volume, and a zero here would flatten every trace it publishes.
    scalars[kScalarGlobalVolume] = kGlobalVolumeUnity;
  }
};

/**
 * Slots of history. A power of two so the index wraps with a mask.
 *
 * Far smaller than libopenmpt's 256-entry ring because a frame here carries
 * every channel's trace and so costs about 19KB against that one's few
 * hundred bytes. Thirty-two of them is around 640ms of history at the rate
 * these decoders publish, which comfortably outlasts any output buffer this
 * runs against - and wrapping past an entry a reader is still copying is the
 * one genuinely unsafe event, so it is checked for rather than assumed away.
 */
constexpr int kVisualSlots = 32;
constexpr int kVisualSlotMask = kVisualSlots - 1;

class VisualPublisher {
 public:
  /** The slot to write into. Only the render callback may touch this. */
  VisualFrame& back() { return slots_[write_.load(std::memory_order_relaxed) & kVisualSlotMask]; }

  /**
   * Makes what was written visible, as of the output frame it is true at.
   *
   * [frameStamp] is where in the output stream the audio just rendered
   * begins - get_frames_written() as the callback was entered, plus whatever
   * the callback has produced since.
   */
  void publish(int64_t frameStamp) {
    const uint64_t seq = write_.load(std::memory_order_relaxed);
    stamps_[seq & kVisualSlotMask] = frameStamp;
    write_.store(seq + 1, std::memory_order_release);
  }

  /** Drops all history. After a seek or a stream restart no stamp still means anything. */
  void reset() { write_.store(0, std::memory_order_release); }

  /** Whether anything has been published since the last reset. */
  bool published() const { return write_.load(std::memory_order_acquire) != 0; }

  /**
   * The newest frame the speaker has already reached.
   *
   * Falls back to the oldest entry still held when playback has not caught up
   * with any of them yet - right after starting, before the first buffer has
   * been consumed - so the views show the beginning of the track rather than
   * nothing at all. Returns nullptr only when nothing has ever been published.
   */
  const VisualFrame* audible() const {
    const uint64_t writeSeq = write_.load(std::memory_order_acquire);
    if (writeSeq == 0) return nullptr;

    // One slot of headroom: the producer is filling the entry at writeSeq
    // right now, and once the ring has wrapped that is also the oldest one.
    const uint64_t count = std::min<uint64_t>(writeSeq, kVisualSlots - 1);
    const uint64_t oldest = writeSeq - count;
    const int64_t played = get_frames_played();
    const int64_t reached = played < 0 ? 0 : played;

    uint64_t chosen = oldest;
    for (uint64_t seq = writeSeq; seq > oldest;) {
      --seq;
      if (stamps_[seq & kVisualSlotMask] <= reached) {
        chosen = seq;
        break;
      }
    }
    return &slots_[chosen & kVisualSlotMask];
  }

  /** Copies the audible frame into the caller's buffers, in the shape JNI wants. */
  bool copyOut(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth) const {
    const VisualFrame* picked = audible();
    if (!picked) return false;
    const VisualFrame& frame = *picked;

    if (scalars) {
      std::memcpy(scalars, frame.scalars.data(), sizeof(frame.scalars));
      // Diagnostic, and the one field that is about now rather than about the
      // frame: how far the renderer is ahead of the speaker, which is the gap
      // the pick above just corrected for.
      const int64_t ahead = get_frames_written() - get_frames_played();
      scalars[kScalarLatencyFrames] =
          static_cast<int32_t>(std::clamp<int64_t>(ahead, 0, INT32_MAX));
    }
    if (channels) std::memcpy(channels, frame.channels.data(), sizeof(frame.channels));
    if (scopes && scopeWidth > 0) {
      if (scopeWidth == kScopeColumns) {
        std::memcpy(scopes, frame.scopes.data(), sizeof(frame.scopes));
      } else {
        // A different width than published: resample rather than refuse, so a
        // change on the Kotlin side cannot silently blank every trace.
        for (int ch = 0; ch < MAX_CHANNELS; ch++) {
          const int8_t* src = frame.scopes.data() + ch * kScopeColumns;
          int8_t* dst = scopes + ch * scopeWidth;
          for (int x = 0; x < scopeWidth; x++) {
            dst[x] = src[(x * kScopeColumns) / scopeWidth];
          }
        }
      }
    }
    return true;
  }

  /** One scalar of the audible frame, for callers that want only the clock. */
  int32_t scalar(ScalarIndex index) const {
    const VisualFrame* picked = audible();
    return picked ? picked->scalars[index] : 0;
  }

 private:
  std::array<VisualFrame, kVisualSlots> slots_{};
  std::array<int64_t, kVisualSlots> stamps_{};
  std::atomic<uint64_t> write_{0};
};
