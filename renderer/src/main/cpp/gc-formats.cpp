//
// The GameCube streams' containers, read into a gc::Stream. See gc-stream.h.
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
#include "psf.h"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <functional>
#include <math.h>
#include <unordered_map>

namespace gc {
namespace {

/** The file's bytes, read with bounds: past the end everything is 0. */
struct Bytes {
  const uint8_t* p = nullptr;
  size_t n = 0;

  bool has(uint64_t at, uint64_t length) const { return at <= n && length <= n - at; }
  uint8_t u8(uint64_t at) const { return has(at, 1) ? p[at] : 0; }
  /** A byte as vgmstream reads one: past the end, all ones. */
  uint8_t u8ff(uint64_t at) const { return has(at, 1) ? p[at] : 0xFF; }
  uint16_t be16(uint64_t at) const { return has(at, 2) ? static_cast<uint16_t>(p[at] << 8 | p[at + 1]) : 0; }
  uint16_t le16(uint64_t at) const { return has(at, 2) ? static_cast<uint16_t>(p[at + 1] << 8 | p[at]) : 0; }
  uint32_t be32(uint64_t at) const {
    return has(at, 4) ? static_cast<uint32_t>(p[at]) << 24 | static_cast<uint32_t>(p[at + 1]) << 16 |
                            static_cast<uint32_t>(p[at + 2]) << 8 | p[at + 3]
                      : 0;
  }
  uint32_t le32(uint64_t at) const {
    return has(at, 4) ? static_cast<uint32_t>(p[at + 3]) << 24 | static_cast<uint32_t>(p[at + 2]) << 16 |
                            static_cast<uint32_t>(p[at + 1]) << 8 | p[at]
                      : 0;
  }
  int16_t sbe16(uint64_t at) const { return static_cast<int16_t>(be16(at)); }
  int32_t sbe32(uint64_t at) const { return static_cast<int32_t>(be32(at)); }
  bool is(uint64_t at, const char* text) const {
    const size_t length = std::strlen(text);
    return has(at, length) && std::memcmp(p + at, text, length) == 0;
  }
};

std::string lowered(std::string text) {
  for (char& c : text) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  return text;
}

/** The name's extension, lower case, without the dot; "" for none. */
std::string extensionOf(const std::string& name) {
  const size_t slash = name.find_last_of("/\\");
  const size_t dot = name.find_last_of('.');
  if (dot == std::string::npos || (slash != std::string::npos && dot < slash)) return "";
  return lowered(name.substr(dot + 1));
}

bool oneOf(const std::string& extension, std::initializer_list<const char*> list) {
  return std::any_of(list.begin(), list.end(), [&](const char* e) { return extension == e; });
}

/** DSP ADPCM: a nibble count, frame headers in it, as samples. */
int64_t dspNibblesToSamples(uint64_t nibbles) {
  const int64_t whole = static_cast<int64_t>(nibbles / 16) * 14;
  const int rest = static_cast<int>(nibbles % 16);
  return whole + (rest > 2 ? rest - 2 : 0);
}

int64_t frameCount(int64_t samples, int frameSamples) { return (samples + frameSamples - 1) / frameSamples; }

/** Every channel's frames evenly spaced from its own start, [stride] apart: one run. */
void evenRun(Stream& s, const std::array<uint32_t, kMaxChannels>& starts, uint32_t stride) {
  Run run;
  run.first = 0;
  run.count = s.frames;
  run.stride = stride;
  run.offset = starts;
  s.runs.assign(1, run);
}

/**
 * Interleaved: from [start], [size] bytes of each channel in turn, over and
 * over - vgmstream's interleave layout. With more than one channel, the
 * first chunk of each may begin [firstSkip] bytes in, and a last row shorter
 * than the rest may be packed at [lastSize] bytes a channel (as vgmstream
 * does it, only after a row of the usual size).
 */
void interleavedRuns(Stream& s, uint64_t start, uint64_t size, uint32_t firstSkip = 0, uint32_t lastSize = 0) {
  s.runs.clear();
  const uint64_t frameBytes = static_cast<uint64_t>(s.frameBytes);
  const int64_t perChunk = static_cast<int64_t>(size / frameBytes);
  if (perChunk <= 0 || firstSkip >= size) return;
  if (s.channels == 1) {
    firstSkip = 0;
    lastSize = 0;
  }
  const uint64_t rowBytes = size * static_cast<uint64_t>(s.channels);
  const auto add = [&](int64_t first, int64_t count, uint64_t rowStart, uint64_t spacing, uint64_t skip, bool chunked) {
    Run run;
    run.first = first;
    run.count = count;
    run.stride = static_cast<uint32_t>(frameBytes);
    if (chunked) {
      run.chunkFrames = static_cast<uint32_t>(perChunk);
      run.chunkStride = rowBytes;
    }
    for (int c = 0; c < s.channels; c++) {
      const uint64_t at = rowStart + static_cast<uint64_t>(c) * spacing + skip;
      run.offset[static_cast<size_t>(c)] = static_cast<uint32_t>(std::min<uint64_t>(at, UINT32_MAX));
    }
    s.runs.push_back(run);
  };
  int64_t frame = 0;
  uint64_t row = 0;
  if (firstSkip > 0) {
    const int64_t count = std::min<int64_t>(static_cast<int64_t>((size - firstSkip) / frameBytes), s.frames);
    if (count > 0) add(0, count, start, size, firstSkip, false);
    frame = count;
    row = 1;
  }
  if (frame >= s.frames) return;
  // The rows of the usual size, all in one run. vgmstream turns to the short
  // last row on leaving a usual one when what is left is less than a row:
  // until then, rows of the usual size, even a short one.
  const int64_t rowSamples = perChunk * s.frameSamples;
  const int64_t full = (s.samples - frame * s.frameSamples) / rowSamples;
  const bool lastRow = lastSize > 0 && full >= 1 && full * perChunk < s.frames - frame;
  const int64_t whole = lastRow ? full * perChunk : s.frames - frame;
  add(frame, whole, start + row * rowBytes, size, 0, true);
  frame += whole;
  if (lastRow) add(frame, s.frames - frame, start + (row + static_cast<uint64_t>(full)) * rowBytes, lastSize, 0, false);
}

/**
 * Holds the stream to what its file has: the channels and rate sane, the
 * samples no more than the frames that start inside the [size] bytes of
 * data - a last one the file cuts short read on into zeros (see kTail), as
 * vgmstream reads it; frames wholly past the end, which vgmstream plays as
 * silence for as long as a header says, are left out - the loop inside
 * them, and a stream that loops cut at the loop's end.
 */
bool finish(Stream& s, uint64_t size) {
  if (s.channels < 1 || s.channels > kMaxChannels) return false;
  if (s.rate < 300 || s.rate > 192000) return false;
  if (s.frameBytes <= 0 || s.frameSamples <= 0 || s.samples <= 0 || s.samples > 1000000000 || s.runs.empty()) {
    return false;
  }
  // The loop as vgmstream takes it: one that ends past the end, or before it starts, is none.
  if (s.loops && (s.loopStart < 0 || (s.loopEnd > 0 && (s.loopEnd <= s.loopStart || s.loopEnd > s.samples)) ||
                  (s.loopEnd <= 0 && s.loopStart >= s.samples))) {
    s.loops = false;
  }
  if (s.loops && s.loopEnd > 0) s.samples = s.loopEnd;
  s.frames = frameCount(s.samples, s.frameSamples);
  // The frames there is data for, in every channel.
  int64_t whole = 0;
  for (const Run& run : s.runs) {
    if (run.first != whole) break;
    int64_t fits = run.count;
    for (int c = 0; c < s.channels; c++) {
      const uint64_t base = run.offset[static_cast<size_t>(c)];
      if (base >= size) {
        fits = 0;
        break;
      }
      // How far past the channel's first frame the last byte of data is.
      const uint64_t room = size - base - 1;
      int64_t n = 0;
      if (run.chunkFrames == 0) {
        n = run.stride == 0 ? run.count : static_cast<int64_t>(room / run.stride) + 1;
      } else {
        const uint64_t chunks = room / run.chunkStride;
        const uint64_t within = std::min<uint64_t>(run.chunkFrames, (room % run.chunkStride) / std::max<uint32_t>(1, run.stride) + 1);
        n = static_cast<int64_t>(chunks * run.chunkFrames + within);
      }
      fits = std::min(fits, n);
    }
    whole += fits;
    if (fits < run.count) break;
  }
  s.frames = std::min(s.frames, whole);
  s.samples = std::min<int64_t>(s.samples, s.frames * s.frameSamples);
  if (s.samples <= 0) return false;
  if (s.loops && s.loopStart >= s.samples) s.loops = false;
  return true;
}

// ---------------------------------------------------------------- Hudson's PDT split (Mario Party 4)

bool parsePdt(const Bytes& b, Stream& s) {
  constexpr size_t kHeader = 0x800;
  if (!b.is(0, "PDT DSP HEADER") || b.n < kHeader + 8) return false;
  const uint8_t flags = b.u8(0x10);
  s.channels = (flags & 0x01) ? 2 : 1;
  s.loops = (flags & 0x02) != 0;
  s.rate = static_cast<int>(b.be32(0x14));
  if (s.rate < 4000 || s.rate > 192000) return false;
  s.samples = dspNibblesToSamples(b.be32(0x18));
  s.loopStart = dspNibblesToSamples(b.be32(0x1C));
  s.codec = Codec::Dsp;
  s.frameBytes = 8;
  s.frameSamples = 14;
  for (int c = 0; c < 2; c++) {
    for (int i = 0; i < 16; i++) s.coefs[static_cast<size_t>(c)][static_cast<size_t>(i)] = b.sbe16(0x50 + 0x20 * c + i * 2);
  }
  // Each side's share of what follows the header: the first straight after
  // it, the second halfway through the rest, as the split lays them out.
  std::array<uint32_t, kMaxChannels> starts{};
  starts[0] = kHeader;
  starts[1] = static_cast<uint32_t>(s.channels == 2 ? (b.n + kHeader) / 2 : kHeader);
  // The first side's last frame reads on into the second's, and the second's
  // past the end, as vgmstream reads them.
  s.frames = frameCount(s.samples, 14);
  evenRun(s, starts, 8);
  s.type = "PDT";
  s.detail = "Hudson PDT, DSP ADPCM";
  return true;
}

// ---------------------------------------------------------------- JAudio: .afc / .stx

bool parseAfc(const Bytes& b, const std::string& extension, Stream& s) {
  if (!oneOf(extension, {"afc", "stx"}) || b.is(0, "FORM")) return false;
  // Big-endian on the GameCube, little on the Switch's re-releases: told by the one field that is always 4.
  const bool big = b.le16(0x0A) > b.be16(0x0A);
  const auto u16 = [&](uint64_t at) { return big ? b.be16(at) : b.le16(at); };
  const auto u32 = [&](uint64_t at) { return big ? b.be32(at) : b.le32(at); };
  if (u32(0x00) > b.n || u16(0x0A) != 4 || u16(0x0C) != 16) return false;
  s.codec = Codec::Afc;
  s.frameBytes = 9;
  s.frameSamples = 16;
  s.channels = 2;
  s.samples = static_cast<int32_t>(u32(0x04));
  s.rate = u16(0x08);
  s.loops = static_cast<int32_t>(u32(0x10)) != 0;
  s.loopStart = static_cast<int32_t>(u32(0x14));
  s.frames = frameCount(s.samples, 16);
  std::array<uint32_t, kMaxChannels> starts{};
  starts[0] = 0x20;
  starts[1] = 0x20 + 9;
  evenRun(s, starts, 18);
  s.type = "AFC";
  s.detail = "Nintendo AFC ADPCM";
  return true;
}

// ---------------------------------------------------------------- JAudio: .ast, STRM and BLCK blocks

bool parseAst(const Bytes& b, const std::string& extension, Stream& s) {
  // Big-endian on the GameCube; little on the Switch's re-releases, every word turned round but the codec's.
  const bool big = b.is(0, "STRM");
  if ((!big && !b.is(0, "MRTS")) || extension != "ast") return false;
  const char* const block = big ? "BLCK" : "KCLB";
  const auto u16 = [&](uint64_t at) { return big ? b.be16(at) : b.le16(at); };
  const auto u32 = [&](uint64_t at) { return big ? b.be32(at) : b.le32(at); };
  const auto s32 = [&](uint64_t at) { return static_cast<int32_t>(u32(at)); };
  if (static_cast<uint64_t>(u32(0x04)) + 0x40 != b.n || u16(0x0A) != 16 || !b.is(0x40, block)) return false;
  const int codec = b.be16(0x08);
  if (codec == 0) {
    s.codec = Codec::Afc;
    s.frameBytes = 9;
    s.frameSamples = 16;
  } else if (codec == 1) {
    s.codec = Codec::Pcm16Be;
    s.frameBytes = 2;
    s.frameSamples = 1;
  } else {
    return false;
  }
  s.channels = u16(0x0C);
  if (s.channels < 1 || s.channels > kMaxChannels) return false;
  s.loops = u16(0x0E) != 0;
  s.rate = s32(0x10);
  s.samples = s32(0x14);
  s.loopStart = s32(0x18);
  s.loopEnd = s32(0x1C);
  if (s.samples <= 0) return false;
  // The blocks: "BLCK", one channel's size, each channel's history, then
  // each channel's data in turn - walked by their sizes, as vgmstream walks
  // them, the name only looked for on the first. One that runs past the
  // end ends the walk.
  int64_t frame = 0;
  const int64_t frames = frameCount(s.samples, s.frameSamples);
  uint64_t at = 0x40;
  while (frame < frames && b.has(at, 0x20)) {
    const uint32_t size = u32(at + 4);
    const uint64_t next = at + 0x20 + static_cast<uint64_t>(size) * static_cast<uint64_t>(s.channels);
    if (size == 0 || next > b.n) break;
    Run run;
    run.first = frame;
    run.count = std::min<int64_t>(size / static_cast<uint32_t>(s.frameBytes), frames - frame);
    run.stride = static_cast<uint32_t>(s.frameBytes);
    for (int c = 0; c < s.channels; c++) {
      run.offset[static_cast<size_t>(c)] = static_cast<uint32_t>(at + 0x20 + static_cast<uint64_t>(size) * c);
    }
    if (run.count <= 0) break;
    s.runs.push_back(run);
    frame += run.count;
    at = next;
  }
  s.samples = std::min<int64_t>(s.samples, frame * s.frameSamples);
  s.frames = frameCount(s.samples, s.frameSamples);
  s.type = "AST";
  s.detail = codec == 0 ? "Nintendo AST, AFC ADPCM" : "Nintendo AST, PCM";
  return true;
}

uint64_t alignUp(uint64_t value, uint64_t to) { return (value + to - 1) / to * to; }

/** A channel's eight DSP coefficient pairs, big-endian, from [at]. */
void readDspCoefs(const Bytes& b, uint64_t at, std::array<int16_t, 16>& coefs) {
  for (int i = 0; i < 16; i++) coefs[static_cast<size_t>(i)] = b.sbe16(at + 2 * static_cast<uint64_t>(i));
}

// ---------------------------------------------------------------- HAL's .hps: DSP ADPCM in linked blocks

bool parseHps(const Bytes& b, Stream& s) {
  if (!b.is(0, " HALPST")) return false;
  s.rate = static_cast<int>(b.be32(0x08));
  s.channels = static_cast<int>(b.be32(0x0C));
  if (s.channels < 1 || s.channels > kMaxChannels || s.rate < 4000 || s.rate > 96000) return false;
  for (int c = 0; c < s.channels; c++) {
    const uint64_t info = 0x10 + 0x38 * static_cast<uint64_t>(c);
    readDspCoefs(b, info + 0x10, s.coefs[static_cast<size_t>(c)]);
    s.start[static_cast<size_t>(c)].h1 = b.sbe16(info + 0x34);
    s.start[static_cast<size_t>(c)].h2 = b.sbe16(info + 0x36);
  }
  s.samples = dspNibblesToSamples(b.be32(0x10 + 0x08)) + 1;
  s.codec = Codec::Dsp;
  s.frameBytes = 8;
  s.frameSamples = 14;
  // The blocks: each the size of all its channels' data, its last nibble,
  // and where the next one is - none, or one already played: the loop.
  const uint64_t first = s.channels <= 2 ? 0x80 : alignUp(0x10 + 0x38 * static_cast<uint64_t>(s.channels), 0x20);
  const uint64_t header = alignUp(0x0C + 8 * static_cast<uint64_t>(s.channels), 0x20);
  // Each block walked, and the nibbles before it. Far more blocks than a
  // real file's 64 KB ones come to is a file made to waste the time.
  constexpr size_t kMaxBlocks = 100000;
  std::unordered_map<uint64_t, uint64_t> nibblesBefore;
  uint64_t at = first;
  uint64_t nibbles = 0;
  int64_t frame = 0;
  for (;;) {
    const uint32_t size = b.be32(at);
    const uint32_t lastNibble = b.be32(at + 4);
    const uint32_t next = b.be32(at + 8);
    const uint64_t share = size / static_cast<uint32_t>(s.channels);
    if (size == 0 || !b.has(at + header, size) || nibblesBefore.size() >= kMaxBlocks) return false;
    Run run;
    run.first = frame;
    run.count = (static_cast<int64_t>(lastNibble) + 1 + 15) / 16;
    run.stride = 8;
    for (int c = 0; c < s.channels; c++) run.offset[static_cast<size_t>(c)] = static_cast<uint32_t>(at + header + share * c);
    if (run.count <= 0 || static_cast<uint64_t>(run.count) * 8 > share + 7) return false;
    nibblesBefore.emplace(at, nibbles);
    s.runs.push_back(run);
    nibbles += static_cast<uint64_t>(lastNibble) + 1;
    frame += run.count;
    if (next == 0xFFFFFFFFu) break;
    const auto back = nibblesBefore.find(next);
    if (back != nibblesBefore.end()) {
      s.loops = true;
      s.loopStart = dspNibblesToSamples(back->second);
      break;
    }
    at = next;
  }
  s.frames = frame;
  s.type = "HPS";
  s.detail = "HAL HPS, DSP ADPCM";
  return true;
}

void dspCodec(Stream& s) {
  s.codec = Codec::Dsp;
  s.frameBytes = 8;
  s.frameSamples = 14;
}

// ---------------------------------------------------------------- Namco's Cstr (NuSound): Star Fox Assault, Donkey Konga

bool parseCstr(const Bytes& b, Stream& s) {
  if (!b.is(0, "Cstr")) return false;
  s.channels = b.u8(0x1B);
  const uint32_t interleave = b.be16(0x06);
  uint32_t skip = static_cast<uint32_t>(std::max(0, b.sbe32(0x0C)));
  if ((s.channels != 1 && s.channels != 2) || interleave == 0 || interleave % 8 != 0 || skip >= interleave) {
    return false;
  }
  dspCodec(s);
  s.samples = b.be32(0x20);
  const uint32_t nibbles = b.be32(0x24);
  s.rate = static_cast<int>(b.be32(0x28));
  for (int c = 0; c < s.channels; c++) readDspCoefs(b, 0x3C + 0x60 * static_cast<uint64_t>(c), s.coefs[static_cast<size_t>(c)]);
  // The loop's start in one channel's bytes; its end, as a DSP header gives it, with Namco's quirks.
  const int32_t loopByte = b.sbe32(0x10);
  s.loops = loopByte >= 0;
  s.loopStart = static_cast<int64_t>(loopByte) / 8 * 14;
  const uint64_t headerEnd = 0x20 + 0x60 * static_cast<uint64_t>(s.channels);
  // Where the audio starts in each channel's first chunk, when the file does not say: where each
  // channel's first frame header is what its DSP header says it is. Looked for by the loop the
  // file gives, before the quirk below takes it away, as vgmstream looks.
  if (!s.loops && s.channels == 2 && skip == 0) {
    for (uint32_t at = 0; at < 0x800 && at < interleave; at += 8) {
      if (b.be16(0x20 + 0x3E) == b.u8(headerEnd + at) && b.be16(0x80 + 0x3E) == b.u8(headerEnd + at + interleave)) {
        skip = at;
        break;
      }
    }
  }
  uint64_t loopEndNibble = b.be32(0x34);
  if (loopEndNibble == nibbles) s.loops = false;
  if (loopEndNibble * 2 + 1 <= nibbles) loopEndNibble *= 2;
  s.loopEnd = std::min<int64_t>(dspNibblesToSamples(loopEndNibble) + 1, s.samples);
  s.loopKeepsHistory = true;
  s.frames = frameCount(s.samples, 14);
  if (s.channels == 1) {
    std::array<uint32_t, kMaxChannels> starts{};
    starts[0] = static_cast<uint32_t>(headerEnd + skip);
    evenRun(s, starts, 8);
  } else {
    interleavedRuns(s, headerEnd, interleave, skip);
  }
  s.type = "Cstr";
  s.detail = "Namco Cstr, DSP ADPCM";
  return true;
}

// ---------------------------------------------------------------- Retro's RS03: Metroid Prime 2

bool parseRs03(const Bytes& b, Stream& s) {
  if (b.be32(0) != 0x52530003u) return false;
  s.channels = static_cast<int>(b.be32(0x04));
  if (s.channels != 1 && s.channels != 2) return false;
  dspCodec(s);
  s.samples = b.be32(0x08);
  s.rate = static_cast<int>(b.be32(0x0C));
  s.loops = b.be16(0x14) != 0;
  s.loopStart = static_cast<int64_t>(b.be32(0x18)) / 8 * 14;
  s.loopEnd = static_cast<int64_t>(b.be32(0x1C)) / 8 * 14;
  s.loopKeepsHistory = true;
  for (int c = 0; c < s.channels; c++) readDspCoefs(b, 0x20 + 0x20 * static_cast<uint64_t>(c), s.coefs[static_cast<size_t>(c)]);
  constexpr uint32_t kInterleave = 0x8F00;
  const uint64_t data = b.n > 0x60 ? b.n - 0x60 : 0;
  const uint32_t last = static_cast<uint32_t>(alignUp((data % (static_cast<uint64_t>(kInterleave) * s.channels)) / 2, 8));
  s.frames = frameCount(s.samples, 14);
  interleavedRuns(s, 0x60, kInterleave, 0, last);
  s.type = "RS03";
  s.detail = "Retro RS03, DSP ADPCM";
  return true;
}

// ---------------------------------------------------------------- Yuke's YMF: WWE WrestleMania X8

bool parseYmf(const Bytes& b, const std::string& extension, Stream& s) {
  if (extension != "ymf" || b.be32(0) != 0x180 || b.be32(0x08) != 3 || b.be32(0x0C) != 0xCCCCCCCCu) return false;
  dspCodec(s);
  s.channels = 2;
  s.rate = b.sbe32(0xA8);
  s.samples = b.sbe32(0xDC);
  readDspCoefs(b, 0xAE, s.coefs[0]);
  readDspCoefs(b, 0x10E, s.coefs[1]);
  s.frames = frameCount(s.samples, 14);
  interleavedRuns(s, b.be32(0), 0x20000);
  s.type = "YMF";
  s.detail = "Yuke's YMF, DSP ADPCM";
  return true;
}

// ---------------------------------------------------------------- Cauldron's .str: Conan

bool parseCauldronStr(const Bytes& b, const std::string& extension, Stream& s) {
  if (extension != "str" || b.be32(0) != 0xFAAF0001u) return false;
  dspCodec(s);
  s.channels = 2;
  s.rate = b.sbe32(0x04);
  s.samples = b.sbe32(0x08);
  const uint32_t interleave = b.be32(0x0C);
  if (interleave == 0 || interleave % 8 != 0) return false;
  readDspCoefs(b, 0x10, s.coefs[0]);
  readDspCoefs(b, 0x30, s.coefs[1]);
  // It always goes round, the whole of it.
  s.loops = true;
  s.loopStart = 0;
  s.frames = frameCount(s.samples, 14);
  interleavedRuns(s, 0x60, interleave);
  s.type = "STR";
  s.detail = "Cauldron STR, DSP ADPCM";
  return true;
}

// ---------------------------------------------------------------- GCub: Sega Soccer Slam, Shrek

/**
 * Whether a two-channel GCub has its channels' frames woven together a byte
 * at a time: at every 16 bytes, the second byte reads as a frame header,
 * and fifty of them in a row are not chance.
 */
bool gcubWoven(const Bytes& b, uint64_t start) {
  int tests = 50;
  for (uint64_t at = start; tests >= 0 && at < b.n; at += 0x10) {
    const uint8_t header = b.u8(at + 1);
    if (header == 0) continue;
    if ((header & 0xF) > 0x0C || (header >> 4) > 8) return false;
    tests--;
  }
  return tests <= 0;
}

bool parseGcub(const Bytes& b, Stream& s) {
  if (!b.is(0, "GCub")) return false;
  dspCodec(s);
  s.channels = static_cast<int>(b.be32(0x04));
  s.rate = static_cast<int>(b.be32(0x08));
  const uint32_t dataSize = b.be32(0x0C);
  if (s.channels < 1 || s.channels > 2) return false;
  readDspCoefs(b, 0x10, s.coefs[0]);
  readDspCoefs(b, 0x30, s.coefs[1]);
  const uint64_t start = b.is(0x60, "GCxx") ? 0x88 : 0x60;
  s.samples = static_cast<int64_t>(dataSize) / s.channels / 8 * 14;
  s.frames = frameCount(s.samples, 14);
  if (s.channels == 2 && gcubWoven(b, start)) {
    // Taken apart into each channel's frames, one after another.
    const uint64_t frames = std::min<uint64_t>(static_cast<uint64_t>(s.frames), (b.n - std::min<uint64_t>(b.n, start)) / 16);
    s.data.assign(static_cast<size_t>(frames) * 16, 0);
    for (uint64_t f = 0; f < frames; f++) {
      for (int c = 0; c < 2; c++) {
        for (int i = 0; i < 8; i++) s.data[static_cast<size_t>(c * frames * 8 + f * 8 + i)] = b.u8(start + f * 16 + i * 2 + c);
      }
    }
    std::array<uint32_t, kMaxChannels> starts{};
    starts[1] = static_cast<uint32_t>(frames * 8);
    evenRun(s, starts, 8);
  } else {
    interleavedRuns(s, start, 0x8000);
  }
  s.type = "GCub";
  s.detail = "GCub, DSP ADPCM";
  return true;
}

// ---------------------------------------------------------------- tri-Crescendo's CAF: Baten Kaitos

bool parseCaf(const Bytes& b, Stream& s) {
  if (!b.is(0, "CAF ")) return false;
  dspCodec(s);
  s.channels = 2;
  s.rate = 32000;
  // Blocks to the end of the file, each with its own coefficients; the
  // loop starts at the first that names itself as where the loop starts.
  int64_t frame = 0;
  bool loopFound = false;
  for (uint64_t at = 0; at < b.n && b.is(at, "CAF "); ) {
    const uint32_t size = b.be32(at + 0x04);
    if (size < 0x80 || !b.has(at, size) || s.blocks.size() > 1000000) break;
    if (!loopFound && b.be32(at + 0x08) == b.be32(at + 0x20)) {
      loopFound = true;
      s.loops = true;
      s.loopStart = frame * 14;
    }
    BlockState state;
    for (int c = 0; c < 2; c++) readDspCoefs(b, at + 0x34 + 0x2C * static_cast<uint64_t>(c), state.coefs[static_cast<size_t>(c)]);
    Run run;
    run.first = frame;
    run.count = b.be32(at + 0x14) / 8;
    run.stride = 8;
    run.offset[0] = static_cast<uint32_t>(at + b.be32(at + 0x10));
    run.offset[1] = static_cast<uint32_t>(at + b.be32(at + 0x18));
    run.block = static_cast<int32_t>(s.blocks.size());
    s.blocks.push_back(state);
    if (run.count > 0) {
      s.runs.push_back(run);
      frame += run.count;
    }
    at += size;
  }
  s.samples = frame * 14;
  s.frames = frame;
  if (!s.blocks.empty()) s.coefs = s.blocks.front().coefs;
  s.type = "CAF";
  s.detail = "tri-Crescendo CAF, DSP ADPCM";
  return true;
}

// ---------------------------------------------------------------- CRI ADX

/**
 * The one coefficient pair, from the high-pass cutoff and the rate - in
 * single precision, as CRI's library and vgmstream work it out: in double
 * some rates come out one apart.
 */
void adxCoefficients(int cutoff, int rate, int16_t& coef1, int16_t& coef2) {
  const float x = static_cast<float>(cutoff);
  const float y = static_cast<float>(rate);
  const float z = ::cosf(static_cast<float>(2.0 * M_PI * x / y));
  const float a = static_cast<float>(M_SQRT2 - z);
  const float b = static_cast<float>(M_SQRT2 - 1.0);
  const float c = (a - ::sqrtf((a + b) * (a - b))) / b;
  coef1 = static_cast<int16_t>(c * 8192);
  coef2 = static_cast<int16_t>(c * c * -4096);
}

bool parseAdx(const Bytes& b, Stream& s) {
  if (b.be16(0) != 0x8000) return false;
  const uint64_t start = static_cast<uint64_t>(b.be16(0x02)) + 4;
  // The copyright ends where the data starts; "AHX" (0x10, 0x11) is another codec altogether.
  if (start < 0x14 || !b.is(start - 6, "(c)CRI")) return false;
  if (b.u8(0x04) != 3 || b.u8(0x05) != 0x12 || b.u8(0x06) != 4) return false;
  s.channels = b.u8(0x07);
  s.rate = b.sbe32(0x08);
  s.samples = b.sbe32(0x0C);
  const int cutoff = b.be16(0x10);
  const uint16_t version = b.be16(0x12);
  // Encrypted (0x0408, 0x0409): without the game's key it is noise.
  if (version != 0x0300 && version != 0x0400 && version != 0x0500) return false;
  if (s.channels < 1 || s.channels > kMaxChannels || s.rate < 300 || s.samples <= 0) return false;
  // Where the loop is kept, by version, when the header has room for it.
  uint64_t loopsAt = 0;
  if (version == 0x0300) {
    if (start - 6 >= 0x14 + 0x18) loopsAt = 0x14;
  } else if (version == 0x0400) {
    const uint64_t histSize = s.channels > 1 ? 4u * static_cast<unsigned>(s.channels) : 8u;
    // The history the encoder starts from: the first sample, twice.
    for (int c = 0; c < s.channels; c++) {
      s.start[static_cast<size_t>(c)].h1 = b.sbe16(0x18 + 4 * c);
      s.start[static_cast<size_t>(c)].h2 = b.sbe16(0x18 + 4 * c + 2);
    }
    const uint64_t ainfAt = 0x18 + histSize + 4;
    const uint64_t ainfSize = b.is(ainfAt, "AINF") ? b.be32(ainfAt + 4) : 0;
    if (start >= ainfSize + 6 && start - ainfSize - 6 >= 0x18 + histSize + 0x18) loopsAt = 0x18 + histSize;
  }
  if (loopsAt != 0) {
    s.loops = b.sbe32(loopsAt + 0x04) != 0;
    s.loopStart = b.sbe32(loopsAt + 0x08);
    s.loopEnd = b.sbe32(loopsAt + 0x10);
    if (s.loopEnd <= s.loopStart || s.loopEnd > s.samples || s.loopStart < 0) s.loops = false;
  }
  s.codec = Codec::Adx;
  s.adxVersion = version == 0x0300 ? 3 : 4;
  s.frameBytes = 0x12;
  s.frameSamples = 32;
  int16_t coef1 = 0;
  int16_t coef2 = 0;
  adxCoefficients(cutoff, s.rate, coef1, coef2);
  for (int c = 0; c < s.channels; c++) {
    s.coefs[static_cast<size_t>(c)][0] = coef1;
    s.coefs[static_cast<size_t>(c)][1] = coef2;
  }
  s.frames = frameCount(s.samples, 32);
  std::array<uint32_t, kMaxChannels> starts{};
  for (int c = 0; c < s.channels; c++) starts[static_cast<size_t>(c)] = static_cast<uint32_t>(start + 0x12 * c);
  evenRun(s, starts, static_cast<uint32_t>(0x12 * s.channels));
  s.type = "ADX";
  s.detail = version == 0x0300 ? "CRI ADX (version 3)" : "CRI ADX";
  return true;
}

// ---------------------------------------------------------------- the DVD's own streams: .adp / .dtk / .trk

/**
 * No header: what the first frames look like. Each starts with the two
 * sides' filter-and-shift bytes, said twice; a filter past 4 or a shift
 * past 12 is no DTK, and nor is a start of mostly blank frames. Past the
 * end of the file a byte reads as 0xFF, as vgmstream reads it - no filter -
 * so a file too short for ten frames' headers is none.
 */
bool looksLikeDtk(const Bytes& b) {
  int blanks = 0;
  for (uint64_t at = 0; at < 0x20 * 10; at += 0x20) {
    const uint8_t header = b.u8ff(at);
    if ((header >> 4) > 4 || (header & 0xF) > 0x0C) return false;
    if (b.u8ff(at) != b.u8ff(at + 2) || b.u8ff(at + 1) != b.u8ff(at + 3)) return false;
    if (b.u8ff(at) == 0 && b.u8ff(at + 1) == 0) blanks++;
  }
  return blanks <= 3;
}

bool parseDtk(const Bytes& b, const std::string& extension, Stream& s) {
  if (!oneOf(extension, {"dtk", "adp", "trk"}) || !looksLikeDtk(b)) return false;
  s.codec = Codec::Dtk;
  s.frameBytes = 32;
  s.frameSamples = 28;
  s.channels = 2;
  // The drive plays at 48 kHz - a shade over, on real consoles.
  s.rate = 48000;
  s.samples = static_cast<int64_t>(b.n / 32) * 28;
  s.frames = frameCount(s.samples, 28);
  evenRun(s, {}, 32);
  s.type = "DTK";
  s.detail = "Nintendo DTK disc stream";
  return true;
}

// ---------------------------------------------------------------- Nintendo's DSP ADPCM with DSPADPCM headers

/** Zero bytes after the file, as vgmstream reads past its end: a last frame the file cuts short is filled out with them. */
constexpr size_t kTail = 0x20;

/** A standard DSPADPCM header, 0x60 bytes, one a channel. */
struct DspHeader {
  uint32_t samples = 0;
  uint32_t nibbles = 0;
  uint32_t rate = 0;
  uint16_t loopFlag = 0;
  uint32_t loopStart = 0;
  uint32_t loopEnd = 0;
  std::array<int16_t, 16> coefs{};
  uint16_t initialPs = 0;
  int16_t hist1 = 0;
  int16_t hist2 = 0;
  uint16_t loopPs = 0;
  int16_t channels = 0;
  uint16_t blockSize = 0;
};

/** The header at [at], held to vgmstream's checks; false when it is none. */
bool readDspHeader(const Bytes& b, uint64_t at, DspHeader& h) {
  if (!b.has(at, 0x60)) return false;
  h.samples = b.be32(at);
  h.nibbles = b.be32(at + 0x04);
  h.rate = b.be32(at + 0x08);
  h.loopFlag = b.be16(at + 0x0C);
  if (h.samples == 0 || h.samples > 0x10000000 || h.nibbles == 0 || h.nibbles > 0x20000000) return false;
  if (h.rate < 5000 || h.rate > 48000 || h.loopFlag > 1 || b.be16(at + 0x0E) != 0) return false;
  h.loopStart = b.be32(at + 0x10);
  h.loopEnd = b.be32(at + 0x14);
  const uint32_t initial = b.be32(at + 0x18);
  if (initial != 2 && initial != 0 && initial != h.loopStart) return false;
  readDspCoefs(b, at + 0x1C, h.coefs);
  if (std::all_of(h.coefs.begin(), h.coefs.end(), [](int16_t c) { return c == 0; })) return false;
  if (b.be16(at + 0x3C) != 0) return false;
  h.initialPs = b.be16(at + 0x3E);
  h.hist1 = b.sbe16(at + 0x40);
  h.hist2 = b.sbe16(at + 0x42);
  h.loopPs = b.be16(at + 0x44);
  h.channels = b.sbe16(at + 0x4A);
  if (h.channels > 64) h.channels = 0;
  h.blockSize = b.be16(at + 0x4C);
  if (h.blockSize >= 0xF000) h.blockSize = 0;
  return true;
}

/** How a game lays its channels' DSPADPCM headers and data out: vgmstream's dsp_meta. */
struct DspMeta {
  int channels = 0;
  int maxChannels = 2;
  uint64_t headerOffset = 0;
  uint64_t headerSpacing = 0;
  uint64_t startOffset = 0;
  uint64_t interleave = 0;
  uint32_t last = 0;
  bool forceLoop = false;
  int forceLoopSeconds = 0;
  bool fixLooping = false;
  bool fixLoopStart = false;
  bool singleHeader = false;
  bool doubleHeader = false;
  bool ignoreAgreement = false;
  const char* type = "DSP";
  const char* detail = "Nintendo DSP ADPCM";
};

/** vgmstream's dsp_common: the headers read and checked against each other and against the data. */
bool dspCommon(const Bytes& b, const DspMeta& m, Stream& s) {
  if (m.channels < 1 || m.channels > m.maxChannels || m.channels > 6) return false;
  std::array<DspHeader, 6> h{};
  for (int c = 0; c < m.channels; c++) {
    if (!readDspHeader(b, m.headerOffset + static_cast<uint64_t>(c) * m.headerSpacing, h[static_cast<size_t>(c)])) return false;
  }
  if (m.fixLoopStart) {
    for (int c = 0; c < m.channels; c++) {
      if (h[static_cast<size_t>(c)].loopFlag) h[static_cast<size_t>(c)].loopStart = 0;
    }
  }
  if (!m.ignoreAgreement) {
    for (int c = 0; c + 1 < m.channels; c++) {
      const DspHeader& x = h[static_cast<size_t>(c)];
      const DspHeader& y = h[static_cast<size_t>(c + 1)];
      if (x.samples != y.samples || x.nibbles != y.nibbles || x.rate != y.rate || x.loopFlag != y.loopFlag ||
          x.loopStart != y.loopStart || x.loopEnd != y.loopEnd) {
        return false;
      }
    }
  }
  // Each channel's first frame starts as its header says; and the frame the loop starts in.
  const int checked = m.singleHeader ? 1 : m.channels;
  for (int c = 0; c < checked; c++) {
    if (h[static_cast<size_t>(c)].initialPs != b.u8ff(m.startOffset + static_cast<uint64_t>(c) * m.interleave)) return false;
  }
  if (h[0].loopFlag) {
    for (int c = 0; c < checked; c++) {
      uint64_t loop = h[static_cast<size_t>(c)].loopStart;
      if (m.interleave == 0) {
        loop = loop / 8 * 8;
      } else {
        loop = loop / 0x10 * 0x8;
        loop = loop / m.interleave * m.interleave * static_cast<uint64_t>(checked) + loop % m.interleave;
      }
      if (h[static_cast<size_t>(c)].loopPs != b.u8ff(m.startOffset + static_cast<uint64_t>(c) * m.interleave + loop)) return false;
    }
  }
  dspCodec(s);
  s.channels = m.channels;
  s.loops = h[0].loopFlag != 0;
  if (!s.loops && m.forceLoop) {
    s.loops = !(m.forceLoopSeconds > 0 && h[0].samples < static_cast<uint64_t>(m.forceLoopSeconds) * h[0].rate);
  }
  s.rate = static_cast<int>(h[0].rate);
  s.samples = h[0].samples;
  s.loopStart = dspNibblesToSamples(h[0].loopStart);
  s.loopEnd = dspNibblesToSamples(h[0].loopEnd) + 1;
  if (!s.loops) {
    s.loopStart = 0;
    s.loopEnd = 0;
  }
  for (int c = 0; c < m.channels; c++) {
    s.coefs[static_cast<size_t>(c)] = h[static_cast<size_t>(c)].coefs;
    s.start[static_cast<size_t>(c)].h1 = h[static_cast<size_t>(c)].hist1;
    s.start[static_cast<size_t>(c)].h2 = h[static_cast<size_t>(c)].hist2;
  }
  if (m.fixLooping && s.loopEnd > s.samples) s.loopEnd = s.samples;
  if (m.doubleHeader) {
    s.samples /= m.channels;
    s.loopStart /= m.channels;
    s.loopEnd /= m.channels;
  }
  s.frames = frameCount(s.samples, 14);
  if (m.interleave == 0) {
    if (m.channels != 1) return false;
    std::array<uint32_t, kMaxChannels> starts{};
    starts[0] = static_cast<uint32_t>(m.startOffset);
    evenRun(s, starts, 8);
  } else {
    interleavedRuns(s, m.startOffset, m.interleave, 0, m.last);
  }
  s.type = m.type;
  s.detail = m.detail;
  return true;
}

/** Two headers that describe the same stream: what vgmstream takes as a channel of something bigger. */
bool sameStream(const DspHeader& a, const DspHeader& b) {
  return a.samples == b.samples && a.nibbles == b.nibbles && a.rate == b.rate && a.loopFlag == b.loopFlag;
}

/** The standard mono .dsp, as DSPADPCM writes it: vgmstream's ngc_dsp_std, before its partner is looked for. */
bool parseDspStdMono(const Bytes& b, const std::string& extension, Stream& s) {
  DspHeader h;
  if (!readDspHeader(b, 0, h)) return false;
  if (!oneOf(extension, {"dsp", "adp", "", "wav", "lwav", "dat", "ldat", "rsm", "adpcm"})) return false;
  if (h.initialPs != b.u8ff(0x60)) return false;
  // Really a channel of something bigger, with its other headers further on.
  DspHeader other;
  if ((readDspHeader(b, 0x60, other) && sameStream(h, other)) ||
      (readDspHeader(b, 0x10000, other) && sameStream(h, other)) ||
      (oneOf(extension, {"adp", ""}) && readDspHeader(b, b.n / 2, other) && sameStream(h, other))) {
    return false;
  }
  dspCodec(s);
  s.channels = 1;
  s.rate = static_cast<int>(h.rate);
  s.samples = h.samples;
  s.loops = h.loopFlag != 0;
  if (s.loops) {
    s.loopStart = dspNibblesToSamples(h.loopStart);
    s.loopEnd = std::min<int64_t>(dspNibblesToSamples(h.loopEnd) + 1, s.samples);
  }
  s.loopKeepsHistory = true;
  s.coefs[0] = h.coefs;
  s.start[0].h1 = h.hist1;
  s.start[0].h2 = h.hist2;
  s.frames = frameCount(s.samples, 14);
  std::array<uint32_t, kMaxChannels> starts{};
  starts[0] = 0x60;
  evenRun(s, starts, 8);
  s.type = "DSP";
  s.detail = "Nintendo DSP ADPCM";
  return true;
}

/**
 * The other half of a stream split in two files, the left and the right,
 * named alike: vgmstream's table, in its order, each way round - the file
 * opened as the left, then as the right. [right] says which it is then.
 */
std::vector<std::pair<std::string, bool>> partnerNames(const std::string& name) {
  struct Pair {
    const char* left;
    const char* right;
    bool dotted;
  };
  static const Pair kPairs[] = {
      {"L", "R", false},       {"l", "r", false}, {"left", "right", false}, {"Left", "Right", false},
      {".V0", ".V1", true},    {".L", ".R", true}, {"_0.dsp", "_1.dsp", true},
  };
  std::vector<std::pair<std::string, bool>> names;
  const auto endsWith = [](const std::string& text, const std::string& end) {
    return text.size() >= end.size() && text.compare(text.size() - end.size(), end.size(), end) == 0;
  };
  for (const Pair& pair : kPairs) {
    for (int side = 0; side < 2; side++) {
      const std::string self = side == 0 ? pair.left : pair.right;
      const std::string other = side == 0 ? pair.right : pair.left;
      if (pair.dotted) {
        if (name.size() > self.size() && endsWith(name, self)) {
          names.push_back({name.substr(0, name.size() - self.size()) + other, side == 1});
        }
      } else {
        const size_t dot = name.find_last_of('.');
        const std::string ext = dot == std::string::npos ? "" : name.substr(dot);
        const std::string stem = name.substr(0, name.size() - ext.size());
        if (stem.size() > self.size() && endsWith(stem, self)) {
          names.push_back({stem.substr(0, stem.size() - self.size()) + other + ext, side == 1});
        }
      }
    }
  }
  return names;
}

/**
 * A standard .dsp's length and loop as its header gives them, for holding
 * the halves of a pair against each other: the loop's ends whether it loops
 * or not, and the length not cut to them - which the stream, held to its
 * data and its loop by then, no longer says.
 */
struct PairSide {
  int64_t samples = 0;
  bool loops = false;
  int64_t loopStart = 0;
  int64_t loopEnd = 0;
};

PairSide pairSide(const Bytes& b) {
  PairSide side;
  DspHeader h;
  if (!readDspHeader(b, 0, h)) return side;
  side.samples = h.samples;
  side.loops = h.loopFlag != 0;
  side.loopStart = dspNibblesToSamples(h.loopStart);
  side.loopEnd = std::min<int64_t>(dspNibblesToSamples(h.loopEnd) + 1, side.samples);
  return side;
}

/**
 * A mono .dsp's other half, when the app handed it over and it holds
 * together with this one as vgmstream asks: both halves in one stream, the
 * left and the right, this one's length and loop.
 */
void pairUp(const Bytes& b, const std::string& name, Stream& s) {
  for (const auto& [partnerName, openedIsRight] : partnerNames(name)) {
    std::vector<uint8_t> bytes;
    if (!psf::companionFile(partnerName, bytes)) continue;
    // The first there is decides, whether it fits or not.
    const Bytes other{bytes.data(), bytes.size()};
    Stream partner;
    if (!parseDspStdMono(other, extensionOf(partnerName), partner) || partner.rate != s.rate) return;
    // The two headers against each other, as vgmstream holds them: the
    // opened file's loop already looked over - one that makes no sense is
    // no loop, and its ends nothing - the other's as its header has it.
    PairSide mine = pairSide(b);
    if (mine.loops && (mine.loopEnd <= mine.loopStart || mine.loopEnd > mine.samples)) {
      mine.loops = false;
      mine.loopStart = mine.loopEnd = 0;
    }
    const PairSide theirs = pairSide(other);
    if (theirs.loops != mine.loops) return;
    // Halves of a low rate may differ a little, as vgmstream allows; others must agree.
    const auto near = [](int64_t x, int64_t y, int64_t by) { return (x > y ? x - y : y - x) <= by; };
    const bool fits = s.rate <= 24000
                          ? near(theirs.samples, mine.samples, mine.loops ? 1600 : 700) &&
                                near(theirs.loopStart, mine.loopStart, 170000) && near(theirs.loopEnd, mine.loopEnd, 170000)
                          : theirs.samples == mine.samples && theirs.loopStart == mine.loopStart && theirs.loopEnd == mine.loopEnd;
    if (!fits) return;
    // This file, then the other, each followed by zeros for as far as this one's frames reach.
    const uint64_t reach = 0x60 + static_cast<uint64_t>(s.frames) * 8 + kTail;
    const uint64_t first = std::max<uint64_t>(b.n + kTail, reach);
    const uint64_t second = std::max<uint64_t>(bytes.size() + kTail, reach);
    std::vector<uint8_t> data(static_cast<size_t>(first + second), 0);
    std::memcpy(data.data(), b.p, b.n);
    std::memcpy(data.data() + first, bytes.data(), bytes.size());
    // Each half its own header's coefficients and history, on its own side.
    const size_t self = openedIsRight ? 1 : 0;
    const size_t half = 1 - self;
    s.channels = 2;
    s.coefs[self] = s.coefs[0];
    s.start[self] = s.start[0];
    s.coefs[half] = partner.coefs[0];
    s.start[half] = partner.start[0];
    Run run;
    run.first = 0;
    run.count = s.frames;
    run.stride = 8;
    run.offset[self] = 0x60;
    run.offset[half] = static_cast<uint32_t>(first + 0x60);
    s.runs.assign(1, run);
    s.data = std::move(data);
    s.detail = "Nintendo DSP ADPCM, the left and the right in two files";
    return;
  }
}

/** DSPADPCM's later multichannel .dsp / .mdsp: back-to-back headers, which say how many and how interleaved. */
bool parseMdsp(const Bytes& b, const std::string& extension, Stream& s) {
  DspHeader h;
  if (!readDspHeader(b, 0, h) || !oneOf(extension, {"dsp", "mdsp"})) return false;
  if (h.channels < 0 || (extension == "dsp" && h.channels == 0)) return false;
  const int channels = h.channels == 0 ? 1 : h.channels;
  if (channels > kMaxChannels) return false;
  const uint64_t start = 0x60 * static_cast<uint64_t>(channels);
  if (h.initialPs != b.u8ff(start)) return false;
  dspCodec(s);
  s.channels = channels;
  s.rate = static_cast<int>(h.rate);
  s.samples = h.samples;
  s.loops = h.loopFlag != 0;
  if (s.loops) {
    s.loopStart = dspNibblesToSamples(h.loopStart);
    s.loopEnd = std::min<int64_t>(dspNibblesToSamples(h.loopEnd) + 1, s.samples);
  }
  s.loopKeepsHistory = true;
  for (int c = 0; c < channels; c++) {
    DspHeader each;
    if (!readDspHeader(b, 0x60 * static_cast<uint64_t>(c), each)) return false;
    s.coefs[static_cast<size_t>(c)] = each.coefs;
    s.start[static_cast<size_t>(c)].h1 = each.hist1;
    s.start[static_cast<size_t>(c)].h2 = each.hist2;
  }
  s.frames = frameCount(s.samples, 14);
  if (channels == 1) {
    std::array<uint32_t, kMaxChannels> starts{};
    starts[0] = static_cast<uint32_t>(start);
    evenRun(s, starts, 8);
  } else {
    const uint64_t interleave = static_cast<uint64_t>(h.blockSize) * 8;
    if (interleave == 0) return false;
    const uint32_t last = static_cast<uint32_t>((h.nibbles / 2 % interleave + 7) / 8 * 8);
    interleavedRuns(s, start, interleave, 0, last);
  }
  s.type = "DSP";
  s.detail = "Nintendo DSP ADPCM, multichannel";
  return true;
}

/** Intelligent Systems' STM: Paper Mario, Fire Emblem, Cubivore. */
bool parseStm(const Bytes& b, const std::string& extension, Stream& s) {
  if (b.be16(0) != 0x0200 || !oneOf(extension, {"stm", "lstm", "dsp"})) return false;
  DspMeta m;
  m.channels = static_cast<int>(b.be32(0x04));
  m.headerOffset = 0x40;
  m.headerSpacing = 0x60;
  m.startOffset = 0x100;
  m.interleave = (static_cast<uint64_t>(b.be32(0x08)) + 0x20) / 0x20 * 0x20;
  m.fixLooping = true;
  m.type = "STM";
  m.detail = "Intelligent Systems STM, DSP ADPCM";
  return dspCommon(b, m, s);
}

/** TOSE's IDSP: Harvest Moon: Another Wonderful Life. */
bool parseIdspTose(const Bytes& b, const std::string& extension, Stream& s) {
  if (b.be32(0) != 0 || extension != "idsp") return false;
  DspMeta m;
  m.channels = b.be16(0x06);
  m.maxChannels = 4;
  m.headerOffset = 0x40;
  m.headerSpacing = 0x60;
  m.startOffset = 0x40 + 0x60 * static_cast<uint64_t>(m.channels);
  m.interleave = b.be32(0x08);
  if (m.startOffset + m.interleave * static_cast<uint64_t>(m.channels) * b.be32(0x0C) != b.n) return false;
  m.type = "IDSP";
  m.detail = "TOSE IDSP, DSP ADPCM";
  return dspCommon(b, m, s);
}

/** Next Level's IDSP: Super Mario Strikers. Songs of fifteen seconds and more go round. */
bool parseIdspNl(const Bytes& b, const std::string& extension, Stream& s) {
  if (!b.is(0, "IDSP") || extension != "idsp") return false;
  DspMeta m;
  m.channels = 2;
  m.headerOffset = 0x0C;
  m.headerSpacing = 0x60;
  m.startOffset = 0xCC;
  m.interleave = b.be32(0x04);
  uint64_t data = b.n;
  if (b.be32(b.n - 4) == 0x30303030) data -= 0x14;
  data = data > 0xCC ? data - 0xCC : 0;
  if (m.interleave != 0) m.last = static_cast<uint32_t>((data / 2) % m.interleave);
  m.fixLooping = true;
  // One with no loop of its own goes round whole, unless it is a jingle of
  // under 15 seconds: what vgmstream means to do. (Its own count is kept in
  // a bool, and so comes out as one second.)
  m.forceLoop = true;
  m.forceLoopSeconds = 15;
  m.type = "IDSP";
  m.detail = "Next Level IDSP, DSP ADPCM";
  return dspCommon(b, m, s);
}

/** Dr. Muto's iadp. */
bool parseIadp(const Bytes& b, const std::string& extension, Stream& s) {
  if (!b.is(0, "iadp") || !oneOf(extension, {"adp", "iadp"})) return false;
  DspMeta m;
  m.channels = b.sbe32(0x04);
  m.headerOffset = 0x20;
  m.headerSpacing = 0x60;
  m.startOffset = static_cast<uint64_t>(std::max(0, b.sbe32(0x1C)));
  m.interleave = static_cast<uint64_t>(std::max(0, b.sbe32(0x08)));
  m.detail = "iadp, DSP ADPCM";
  return dspCommon(b, m, s);
}

/** Acclaim Austin's AAAp: Vexx, Turok: Evolution. */
bool parseAaap(const Bytes& b, const std::string& extension, Stream& s) {
  if (!b.is(0, "AAAp") || extension != "dsp") return false;
  DspMeta m;
  m.interleave = b.be16(0x04);
  m.channels = b.be16(0x06);
  m.headerOffset = 0x08;
  m.headerSpacing = 0x60;
  m.startOffset = 0x08 + 0x60 * static_cast<uint64_t>(m.channels);
  m.detail = "Acclaim AAAp, DSP ADPCM";
  return dspCommon(b, m, s);
}

bool endsWithNoCase(const std::string& text, const char* end) {
  const std::string tail = lowered(end);
  const std::string low = lowered(text);
  return low.size() >= tail.size() && low.compare(low.size() - tail.size(), tail.size(), tail) == 0;
}

/**
 * The interleaved ones with no magic of their own, in vgmstream's order:
 * each a game's way of putting standard headers and data together.
 */
bool parseDspInterleaved(const Bytes& b, const std::string& name, const std::string& extension, Stream& s) {
  const auto attempt = [&](DspMeta m) {
    Stream tried;
    if (!dspCommon(b, m, tried)) return false;
    s = std::move(tried);
    return true;
  };
  DspMeta m;
  m.channels = 2;
  // Monopoly Party: one header for both, counting both.
  if (oneOf(extension, {"ste", "mpdsp"})) {
    DspMeta ste = m;
    ste.startOffset = 0x60;
    ste.interleave = 0xF000;
    ste.singleHeader = true;
    ste.doubleHeader = true;
    ste.detail = "Monopoly Party MPDSP, DSP ADPCM";
    if (attempt(ste)) return true;
  }
  // Bomberman Jetters (_lr.dsp), Free Radical's .mss, Traveller's Tales' .gcm.
  {
    DspMeta two = m;
    two.headerSpacing = 0x60;
    two.startOffset = 0xC0;
    two.fixLooping = true;
    bool known = true;
    if (endsWithNoCase(name, "_lr.dsp")) {
      two.interleave = 0x14180;
    } else if (extension == "mss") {
      two.interleave = 0x1000;
      two.ignoreAgreement = true;
    } else if (extension == "gcm") {
      two.interleave = 0x8000;
    } else {
      known = false;
    }
    if (known && attempt(two)) return true;
  }
  // Shark Tale, The Sims: the two halves of the file.
  if (oneOf(extension, {"adp", "ddsp", "wav", "lwav", ""})) {
    DspMeta ddsp = m;
    ddsp.headerSpacing = b.n / 2;
    ddsp.startOffset = 0x60;
    ddsp.interleave = b.n / 2;
    if (attempt(ddsp)) return true;
  }
  // Micro Machines, Superman: Shadow of Apokolips.
  if (extension == "str") {
    DspMeta str = m;
    str.headerSpacing = 0x80;
    str.startOffset = 0x800;
    str.interleave = 0x4000;
    if (attempt(str)) return true;
  }
  if (extension == "dsp") {
    // XIII: a frame of each in turn, and a loop start to be ignored.
    DspMeta xiii = m;
    xiii.headerSpacing = 0x60;
    xiii.startOffset = 0xC0;
    xiii.interleave = 0x08;
    xiii.fixLoopStart = true;
    if (attempt(xiii)) return true;
    // Cabela's: stereo ones go round, the whole of them.
    if (b.be32(0x50) != 0 && b.be32(0x54) != 0) {
      DspMeta cabelas = m;
      cabelas.channels = b.be32(0x00) == b.be32(0x60) ? 2 : 1;
      cabelas.headerSpacing = 0x60;
      cabelas.startOffset = 0x60 * static_cast<uint64_t>(cabelas.channels);
      cabelas.interleave = 0x10;
      cabelas.forceLoop = cabelas.channels == 2;
      if (attempt(cabelas)) return true;
    }
  }
  // Chanrinko Hero.
  if (extension == "itl") {
    DspMeta itl = m;
    itl.headerSpacing = 0x60;
    itl.startOffset = 0xC0;
    itl.interleave = 0x23C0;
    itl.fixLooping = true;
    if (attempt(itl)) return true;
  }
  // Star Wars: Bounty Hunter: one header, the second channel where it says.
  if (oneOf(extension, {"ds2", "dsp"}) && b.be32(0x50) == 0 && b.be32(0x54) == 0 && b.be32(0x58) == 0) {
    const uint64_t second = b.be32(0x5C);
    if (second != 0 && second >= b.n / 2 && second <= b.n && second > 0x60) {
      DspMeta ds2 = m;
      ds2.startOffset = 0x60;
      ds2.interleave = second - 0x60;
      ds2.singleHeader = true;
      if (attempt(ds2)) return true;
    }
  }
  return false;
}

/**
 * The stream in [data] described in [s], its data left for the caller to
 * fill - except a stream in two files, which puts the two together itself.
 */
bool read(const uint8_t* data, size_t size, const std::string& name, Stream& s) {
  if (data == nullptr || size < 0x20) return false;
  const Bytes b{data, size};
  const std::string extension = extensionOf(name);
  // In vgmstream's order: those that say what they are first, then the
  // DSPADPCM headers, then the ones only their name and their first frames
  // tell. One taken that does not hold together leaves the next to try.
  const std::function<bool(Stream&)> formats[] = {
      [&](Stream& t) { return parseAdx(b, t); },
      [&](Stream& t) { return parseHps(b, t); },
      [&](Stream& t) { return parsePdt(b, t); },
      [&](Stream& t) { return parseAst(b, extension, t); },
      [&](Stream& t) { return parseRs03(b, t); },
      [&](Stream& t) { return parseCstr(b, t); },
      [&](Stream& t) { return parseGcub(b, t); },
      [&](Stream& t) { return parseCaf(b, t); },
      [&](Stream& t) { return parseCauldronStr(b, extension, t); },
      [&](Stream& t) { return parseYmf(b, extension, t); },
      [&](Stream& t) {
        // A mono one held together first, then put with its other half if there is one.
        if (!parseDspStdMono(b, extension, t) || !finish(t, size)) return false;
        pairUp(b, name, t);
        return true;
      },
      [&](Stream& t) { return parseMdsp(b, extension, t); },
      [&](Stream& t) { return parseStm(b, extension, t); },
      [&](Stream& t) { return parseIdspNl(b, extension, t); },
      [&](Stream& t) { return parseIadp(b, extension, t); },
      [&](Stream& t) { return parseAaap(b, extension, t); },
      [&](Stream& t) { return parseIdspTose(b, extension, t); },
      [&](Stream& t) { return parseDtk(b, extension, t); },
      [&](Stream& t) { return parseDspInterleaved(b, name, extension, t); },
      [&](Stream& t) { return parseAfc(b, extension, t); },
  };
  for (const auto& format : formats) {
    Stream tried;
    if (format(tried) && finish(tried, tried.data.empty() ? size : tried.data.size())) {
      s = std::move(tried);
      return true;
    }
  }
  return false;
}

}  // namespace

bool parse(const uint8_t* data, size_t size, const std::string& name, Stream& out) {
  Stream s;
  if (!read(data, size, name, s)) return false;
  if (s.data.empty()) {
    s.data.assign(data, data + size);
    s.data.resize(size + kTail, 0);
  }
  out = std::move(s);
  return true;
}

bool probe(const uint8_t* data, size_t size, const std::string& name, std::string* type) {
  Stream s;
  if (!read(data, size, name, s)) return false;
  if (type) *type = s.type;
  return true;
}

}  // namespace gc
