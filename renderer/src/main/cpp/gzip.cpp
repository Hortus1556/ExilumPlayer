#include "gzip.h"

#include <zlib.h>

namespace {
/** More than any tune unpacks to: a VGM with an hour of sampled sound in it is a tenth of this. */
constexpr size_t kMaxInflated = 512u * 1024u * 1024u;
}  // namespace

bool gzip_is_member(const uint8_t* data, size_t size) {
  return data && size > 2 && data[0] == 0x1f && data[1] == 0x8b;
}

size_t gzip_inflate_head(const uint8_t* data, size_t size, uint8_t* out, size_t want) {
  if (!gzip_is_member(data, size) || out == nullptr || want == 0) return 0;
  z_stream zs{};
  if (inflateInit2(&zs, 16 + MAX_WBITS) != Z_OK) return 0;
  zs.next_in = const_cast<Bytef*>(data);
  zs.avail_in = static_cast<uInt>(size);
  zs.next_out = out;
  zs.avail_out = static_cast<uInt>(want);
  const int status = inflate(&zs, Z_SYNC_FLUSH);
  const size_t got = (status == Z_OK || status == Z_STREAM_END || status == Z_BUF_ERROR) ? want - zs.avail_out : 0;
  inflateEnd(&zs);
  return got;
}

std::vector<uint8_t> gzip_inflate(const uint8_t* data, size_t size) {
  std::vector<uint8_t> out;
  if (!gzip_is_member(data, size)) return out;

  z_stream zs{};
  // 16 + MAX_WBITS: decode a gzip wrapper rather than a raw zlib stream.
  if (inflateInit2(&zs, 16 + MAX_WBITS) != Z_OK) return out;

  zs.next_in = const_cast<Bytef*>(data);
  zs.avail_in = static_cast<uInt>(size);

  std::vector<uint8_t> chunk(256 * 1024);
  int status = Z_OK;
  while (status != Z_STREAM_END) {
    zs.next_out = chunk.data();
    zs.avail_out = static_cast<uInt>(chunk.size());
    status = inflate(&zs, Z_NO_FLUSH);
    if (status != Z_OK && status != Z_STREAM_END) {
      out.clear();
      break;
    }
    out.insert(out.end(), chunk.data(), chunk.data() + (chunk.size() - zs.avail_out));
    if (out.size() > kMaxInflated) {
      out.clear();
      break;
    }
    if (zs.avail_in == 0 && status != Z_STREAM_END) break;
  }
  inflateEnd(&zs);
  return out;
}
