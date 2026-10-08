//
// The GameCube streams' codecs, a frame at a time. See gc-stream.h.
//
// Ported from vgmstream (https://github.com/vgmstream/vgmstream):
//
// Copyright (c) 2008-2025 Adam Gashlin, Fastelbja, Ronny Elfert, bnnm,
// Christopher Snowhill, NicknineTheEagle, bxaimc, Thealexbarney, CyberBotX,
// EdnessP, et al
//
// Permission to use, copy, modify, and distribute this software for any
// purpose with or without fee is hereby granted, provided that the above
// copyright notice and this permission notice appear in all copies.
//
// THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
// WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
// ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
// WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
// ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
// OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
//
#include "gc-stream.h"
#include <algorithm>
#include <cstring>

namespace gc {
namespace {

int16_t clamp16(int64_t value) { return static_cast<int16_t>(std::clamp<int64_t>(value, -32768, 32767)); }

int16_t be16(const uint8_t* p) { return static_cast<int16_t>(static_cast<uint16_t>(p[0]) << 8 | p[1]); }

/** A nibble as a signed number, -8 to 7. */
int nibble(int value) { return value >= 8 ? value - 16 : value; }

/**
 * DSP ADPCM: a byte that picks one of the channel's eight coefficient pairs
 * and a scale, then fourteen 4-bit steps, high nibble first.
 */
void decodeDsp(const std::array<int16_t, 16>& coefs, const uint8_t* p, Hist& h, int16_t* out, int from) {
  // A pair past the eighth, which only damaged data asks for, is the eighth, as vgmstream takes it.
  const int predictor = std::min(7, p[0] >> 4);
  const int64_t scale = int64_t{1} << (p[0] & 0xF);
  const int64_t coef1 = coefs[static_cast<size_t>(predictor * 2)];
  const int64_t coef2 = coefs[static_cast<size_t>(predictor * 2 + 1)];
  for (int i = from; i < 14; i++) {
    const int byte = p[1 + i / 2];
    const int step = nibble((i & 1) ? (byte & 0xF) : (byte >> 4));
    // Wide: a file's coefficients can be anything, and two of them times a
    // full-scale history do not fit 32 bits.
    const int16_t value = clamp16((step * scale * 2048 + 1024 + coef1 * h.h1 + coef2 * h.h2) >> 11);
    h.h2 = h.h1;
    h.h1 = value;
    out[i] = value;
  }
}

/**
 * CRI ADX: a 16-bit scale less one, then thirty-two 4-bit steps, high
 * nibble first, predicted with the stream's one pair. The first libraries
 * rounded each half of the prediction on its own. A scale of 0x8001 marks
 * the end, and is silence.
 */
void decodeAdx(const Stream& s, int c, const uint8_t* p, Hist& h, int16_t* out, int from) {
  int64_t scale = be16(p) + 1;
  if (p[0] == 0x80 && p[1] == 0x01) scale = 0;
  const int64_t coef1 = s.coefs[c][0];
  const int64_t coef2 = s.coefs[c][1];
  for (int i = from; i < 32; i++) {
    const int byte = p[2 + i / 2];
    const int step = nibble((i & 1) ? (byte & 0xF) : (byte >> 4));
    int64_t sample = step * scale;
    if (s.adxVersion == 3) {
      sample += ((coef1 * h.h1) >> 12) + ((coef2 * h.h2) >> 12);
    } else {
      sample += (coef1 * h.h1 + coef2 * h.h2) >> 12;
    }
    const int16_t value = clamp16(sample);
    h.h2 = h.h1;
    h.h1 = value;
    out[i] = value;
  }
}

/**
 * The DVD drive's streaming ADPCM, as Dolphin decodes it: a header byte for
 * each side - one of four filters and a shift - then 28 bytes, each the left
 * sample in its low nibble and the right in its high. The history is kept
 * six bits wider than a sample, and only the output clamped.
 */
void decodeDtk(int c, const uint8_t* p, Hist& h, int16_t* out, int from) {
  const int header = p[c & 1];
  const int filter = header >> 4;
  const int shift = header & 0xF;
  for (int i = from; i < 28; i++) {
    int64_t predicted = 0;
    switch (filter) {
      case 1: predicted = int64_t{h.h1} * 0x3C; break;
      case 2: predicted = int64_t{h.h1} * 0x73 - int64_t{h.h2} * 0x34; break;
      case 3: predicted = int64_t{h.h1} * 0x62 - int64_t{h.h2} * 0x37; break;
      default: break;
    }
    predicted = std::clamp<int64_t>((predicted + 0x20) >> 6, -0x200000, 0x1FFFFF);
    const int byte = p[4 + i];
    const int bits = (c & 1) ? (byte >> 4) : (byte & 0xF);
    const int32_t step = static_cast<int16_t>(static_cast<uint16_t>(bits << 12)) >> shift;
    const int32_t current = static_cast<int32_t>(int64_t{step} * 64 + predicted);
    h.h2 = h.h1;
    h.h1 = current;
    out[i] = clamp16(current >> 6);
  }
}

/** JAudio's AFC: a byte with a scale and one of sixteen fixed pairs, then sixteen 4-bit steps, high nibble first. */
constexpr int16_t kAfcCoefs[16][2] = {
    {0, 0},       {2048, 0},     {0, 2048},     {1024, 1024}, {4096, -2048}, {3584, -1536},
    {3072, -1024}, {4608, -2560}, {4200, -2248}, {4800, -2300}, {5120, -3072}, {2048, -2048},
    {1024, -1024}, {-1024, 1024}, {-1024, 0},    {-2048, 0},
};

void decodeAfc(const uint8_t* p, Hist& h, int16_t* out, int from) {
  const int64_t scale = int64_t{1} << ((p[0] >> 4) & 0xF);
  const int64_t coef1 = kAfcCoefs[p[0] & 0xF][0];
  const int64_t coef2 = kAfcCoefs[p[0] & 0xF][1];
  for (int i = from; i < 16; i++) {
    const int byte = p[1 + i / 2];
    const int step = nibble((i & 1) ? (byte & 0xF) : (byte >> 4));
    const int16_t value = clamp16(((step * scale) * 2048 + coef1 * h.h1 + coef2 * h.h2) >> 11);
    h.h2 = h.h1;
    h.h1 = value;
    out[i] = value;
  }
}

}  // namespace

void decodeFrame(const Stream& s, int c, int64_t frame, Hist& hist, int16_t* out, size_t& run, int from) {
  // The run the frame is in: the one asked about, the next, or found from the start.
  const std::vector<Run>& runs = s.runs;
  const auto holds = [&](size_t r) { return r < runs.size() && frame >= runs[r].first && frame < runs[r].first + runs[r].count; };
  if (!holds(run)) {
    if (holds(run + 1)) {
      run++;
    } else {
      const auto it = std::upper_bound(runs.begin(), runs.end(), frame,
                                       [](int64_t f, const Run& r) { return f < r.first; });
      run = it == runs.begin() ? runs.size() : static_cast<size_t>(it - runs.begin()) - 1;
    }
  }
  const uint8_t* p = nullptr;
  const BlockState* block = nullptr;
  if (holds(run)) {
    const Run& r = runs[run];
    const uint64_t at = frameAt(r, r.offset[static_cast<size_t>(c)], frame - r.first);
    if (at + static_cast<uint64_t>(s.frameBytes) <= s.data.size()) p = s.data.data() + at;
    if (r.block >= 0 && static_cast<size_t>(r.block) < s.blocks.size()) {
      block = &s.blocks[static_cast<size_t>(r.block)];
      // A block that says where its history starts starts it there.
      if (block->resetsHist && frame == r.first && from == 0) hist = block->hist[static_cast<size_t>(c)];
    }
  }
  if (p == nullptr) {
    std::fill(out + from, out + s.frameSamples, int16_t{0});
    return;
  }
  switch (s.codec) {
    case Codec::Dsp:
      decodeDsp(block ? block->coefs[static_cast<size_t>(c)] : s.coefs[static_cast<size_t>(c)], p, hist, out, from);
      break;
    case Codec::Adx: decodeAdx(s, c, p, hist, out, from); break;
    case Codec::Dtk: decodeDtk(c, p, hist, out, from); break;
    case Codec::Afc: decodeAfc(p, hist, out, from); break;
    case Codec::Pcm16Be:
      for (int i = from; i < s.frameSamples; i++) out[i] = be16(p + i * 2);
      break;
  }
}

}  // namespace gc
