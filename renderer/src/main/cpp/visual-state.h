#pragma once
#include <cstdint>

/**
 * The wire format between a decoder and the views.
 *
 * Shared rather than private to libopenmpt because every decoder has to fill
 * the same arrays: readVisualState hands Kotlin three flat buffers and the
 * index constants on the other side (PlaybackSnapshot) are fixed. A decoder
 * that reported its channels at different offsets would draw another
 * decoder's data.
 *
 * Fields a decoder has nothing to say about are left at zero. A register-log
 * or CPU-dump format has no pattern, no row and no note, and says so by
 * leaving them alone rather than by inventing them.
 */

/** Ceiling on channels reported to the UI, and the stride of every channel array. */
constexpr int MAX_CHANNELS = 64;

/** Fixed-point scale for kChPeak: a full-scale channel reads kPeakScale. */
constexpr int32_t kPeakScale = 1024;

enum ScalarIndex {
  kScalarOrder = 0,
  kScalarPattern,
  kScalarRow,
  kScalarNumRows,
  kScalarSpeed,
  kScalarBpm,
  kScalarTimeMs,
  kScalarTotalTimeMs,
  kScalarPlayingChannels,
  kScalarNumChannels,
  kScalarSequence,
  kScalarLatencyFrames,
  /**
   * The song's own master volume, kGlobalVolumeUnity for full. The tracker
   * formats' Vxx / Gxx; everything without one reports unity. Views scale their
   * traces by it, which each channel's own level cannot do for them when the
   * format applies it to the mix.
   */
  kScalarGlobalVolume,
  kScalarCount
};

/** kScalarGlobalVolume at full volume. */
constexpr int32_t kGlobalVolumeUnity = 256;

enum ChannelField {
  kChSample = 0,
  kChInstrument,
  kChNote,
  kChKey,
  kChVolume,
  kChFinalVolume,
  kChPan,
  kChPeriod,
  kChPitchBend,
  // Measured output level of this channel. Unlike every field above it this is
  // not something the format reports; it is taken from the audio actually
  // being heard, which is why a decoder that renders its voices separately can
  // fill it and one that only produces a mix cannot.
  kChPeak,
  kChannelFields
};
