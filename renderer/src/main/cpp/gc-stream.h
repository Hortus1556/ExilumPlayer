#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

/**
 * GameCube streams: the files the console's games keep their recorded music
 * in, read into one description - how many channels at what rate, where each
 * channel's frames are in the file, in which codec - and decoded a frame at a
 * time. What plays them is decoder-dsp.cpp.
 *
 * The formats and codecs are ports of vgmstream's (ISC licence: see NOTICE),
 * with DTK checked against Dolphin's. The containers differ - a standard DSP
 * header, CRI's ADX, HAL's HPS blocks, Nintendo's AST blocks, a DVD stream
 * with no header at all - but each comes down to frames of one codec, laid
 * out channel by channel in runs.
 */
namespace gc {

constexpr int kMaxChannels = 8;

enum class Codec {
  /** Nintendo's DSP ADPCM: 8 bytes, 14 samples, eight coefficient pairs a channel. */
  Dsp,
  /** CRI ADX: 18 bytes, 32 samples, one coefficient pair from the high-pass cutoff. */
  Adx,
  /** The DVD drive's own ADPCM: 32 bytes, 28 samples of each of two channels. */
  Dtk,
  /** Nintendo's JAudio ADPCM: 9 bytes, 16 samples, sixteen fixed coefficient pairs. */
  Afc,
  /** Plain 16-bit samples, big-endian. */
  Pcm16Be,
};

/** The two samples before, which each new one is predicted from - wider than a sample for DTK. */
struct Hist {
  int32_t h1 = 0;
  int32_t h2 = 0;
};

/**
 * Frames laid out evenly: frame f of channel c, for f from [first] to
 * [first] + [count] - 1, the n-th of the run, starts at [offset][c] + n *
 * [stride] - or, interleaved in chunks of [chunkFrames] frames, at
 * [offset][c] + (n / chunkFrames) * [chunkStride] + (n % chunkFrames) * [stride].
 */
struct Run {
  int64_t first = 0;
  int64_t count = 0;
  uint32_t stride = 0;
  /** Frames to a chunk, 0 for one chunk; and how far apart chunks start. */
  uint32_t chunkFrames = 0;
  uint64_t chunkStride = 0;
  std::array<uint32_t, kMaxChannels> offset{};
  /** For blocks that carry their own coefficients, and maybe history: which of Stream::blocks; -1 for none. */
  int32_t block = -1;
};

/** Where the [n]-th frame of [run] starts, for a channel whose first is at [base]. */
inline uint64_t frameAt(const Run& run, uint64_t base, int64_t n) {
  const uint64_t i = static_cast<uint64_t>(n);
  if (run.chunkFrames == 0) return base + i * run.stride;
  return base + (i / run.chunkFrames) * run.chunkStride + (i % run.chunkFrames) * run.stride;
}

/** What a block of a THP or a CAF says for itself: DSP coefficients, and maybe where the history starts again. */
struct BlockState {
  std::array<std::array<int16_t, 16>, kMaxChannels> coefs{};
  std::array<Hist, kMaxChannels> hist{};
  bool resetsHist = false;
};

struct Stream {
  /** The file - and for a stream split in two, the other half after it. */
  std::vector<uint8_t> data;
  /** What it is, for the type shown: "ADX", "HPS", "DSP"... */
  std::string type;
  /** How it is laid out, for the comment. */
  std::string detail;

  Codec codec = Codec::Dsp;
  int channels = 0;
  int rate = 0;
  /** A channel's samples: to the loop's end, when it loops. */
  int64_t samples = 0;
  bool loops = false;
  int64_t loopStart = 0;
  /** Where the file says the loop ends, before [samples] is cut to it; 0 for the end. */
  int64_t loopEnd = 0;
  /**
   * Going round, the loop starts again with the history its end left, not
   * the history it had the first time: a standard .dsp (and its pairs and
   * multichannel kin), Cstr and RS03, which vgmstream plays so.
   */
  bool loopKeepsHistory = false;

  int frameBytes = 0;
  int frameSamples = 0;
  /** A channel's frames, and where they are, in order. */
  int64_t frames = 0;
  std::vector<Run> runs;

  /** DSP: eight pairs a channel. ADX: the one pair, in [0] and [1]. */
  std::array<std::array<int16_t, 16>, kMaxChannels> coefs{};
  /** For runs that name one: their coefficients instead, and their history at their start. */
  std::vector<BlockState> blocks;
  /** Each channel's history before its first frame. */
  std::array<Hist, kMaxChannels> start{};
  /** ADX: 3 for the first libraries' arithmetic, 4 for the later ones'. */
  int adxVersion = 4;
};

/**
 * Whether [data], a file called [name], is a GameCube stream this reads:
 * by what it starts with, or for the ones with no header by its name and by
 * what its first frames look like.
 */
bool probe(const uint8_t* data, size_t size, const std::string& name, std::string* type = nullptr);

/**
 * [data], a file called [name], into [out]: false when it is none of these,
 * or does not hold together. A stream split in two finds its other half
 * among the companion files (see psf::setCompanions).
 */
bool parse(const uint8_t* data, size_t size, const std::string& name, Stream& out);

/**
 * Frame [frame] of channel [c] into [out] - frameSamples samples - carrying
 * [hist] on, as the hardware does. [run] is where to start looking for the
 * frame's run, and is left at the run it was in: frames are mostly read in
 * order. A frame the file has no room for is silence.
 *
 * From [from] on, the frame's samples before it left out: [hist] is then the
 * history before sample [from], and only out[from] onwards is written.
 */
void decodeFrame(const Stream& s, int c, int64_t frame, Hist& hist, int16_t* out, size_t& run, int from = 0);

}  // namespace gc
