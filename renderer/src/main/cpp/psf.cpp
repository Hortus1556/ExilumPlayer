//
// The PSF container. See psf.h.
//
#include "psf.h"
#include <zlib.h>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>

namespace psf {
namespace {

constexpr size_t kHeaderSize = 16;
/** Where the tags may end, past the program: the format's own limit. */
constexpr size_t kMaxTagBytes = 50000;
/** How deep libraries may name libraries, which is only ever a step or two. */
constexpr int kMaxDepth = 10;
/** How many libraries one file may have read for it in all, which is only ever one or two. */
constexpr int kMaxLibraryLoads = 64;

/**
 * The files handed over for the load being made on this thread - each thread
 * its own. A load, the indexer's look at another file and the measuring of a
 * third's loudness are each on a thread of their own, each hand their files
 * over before and take them back after, and with one list between them they
 * took each other's: a .minigsf loaded as the indexer passed a tune with a
 * bank came up without its library.
 */
thread_local std::vector<std::pair<std::string, std::vector<uint8_t>>> g_companions;

uint32_t readLe32(const uint8_t* p) {
  return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

std::string lowercased(std::string text) {
  for (char& c : text) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  return text;
}

/** The format counts every control character as white space. */
bool isBlank(char c) { return static_cast<unsigned char>(c) <= 0x20; }

std::string trimmed(const std::string& text) {
  size_t from = 0, to = text.size();
  while (from < to && isBlank(text[from])) from++;
  while (to > from && isBlank(text[to - 1])) to--;
  return text.substr(from, to - from);
}

/** The name alone: a _lib tag is a name beside the file, whatever path came with it. */
std::string baseName(const std::string& name) {
  const size_t slash = name.find_last_of("/\\");
  return slash == std::string::npos ? name : name.substr(slash + 1);
}

}  // namespace

bool inflate(const uint8_t* data, size_t size, std::vector<uint8_t>& out, size_t maxBytes) {
  out.clear();
  if (size == 0) return true;
  z_stream stream{};
  // zlib's own, which the name here would otherwise hide.
  if (inflateInit(&stream) != Z_OK) return false;
  stream.next_in = const_cast<Bytef*>(data);
  stream.avail_in = static_cast<uInt>(size);
  size_t produced = 0;
  int result = Z_OK;
  while (result == Z_OK) {
    if (produced == out.size()) {
      if (out.size() >= maxBytes) break;
      out.resize(std::min(maxBytes, std::max<size_t>(size * 4, out.size() * 2)));
    }
    stream.next_out = out.data() + produced;
    stream.avail_out = static_cast<uInt>(out.size() - produced);
    result = ::inflate(&stream, Z_NO_FLUSH);
    produced = out.size() - stream.avail_out;
  }
  inflateEnd(&stream);
  out.resize(produced);
  return result == Z_STREAM_END;
}

namespace {

void parseTags(const uint8_t* data, size_t size, std::map<std::string, std::string>& tags) {
  const std::string text(reinterpret_cast<const char*>(data), size);
  size_t start = 0;
  while (start < text.size()) {
    size_t end = text.find('\n', start);
    if (end == std::string::npos) end = text.size();
    const std::string line = text.substr(start, end - start);
    start = end + 1;
    const size_t equals = line.find('=');
    if (equals == std::string::npos) continue;
    const std::string key = lowercased(trimmed(line.substr(0, equals)));
    if (key.empty()) continue;
    const std::string value = trimmed(line.substr(equals + 1));
    auto found = tags.find(key);
    if (found == tags.end()) {
      tags.emplace(key, value);
    } else {
      found->second += "\n" + value;
    }
  }
}

bool companion(const std::string& name, std::vector<uint8_t>& out) {
  const std::string wanted = lowercased(baseName(name));
  for (const auto& [companionName, data] : g_companions) {
    if (lowercased(baseName(companionName)) == wanted) {
      out = data;
      return true;
    }
  }
  return false;
}

bool loadLibrary(const std::string& name, int version, int depth, const std::function<bool(const File&)>& apply,
                 size_t maxProgramBytes, int& loadsLeft);

bool loadAt(const File& file, int depth, const std::function<bool(const File&)>& apply, size_t maxProgramBytes,
            int& loadsLeft) {
  if (depth > kMaxDepth) return false;
  const std::string& lib = file.tag("_lib");
  if (!lib.empty() && !loadLibrary(lib, file.version, depth + 1, apply, maxProgramBytes, loadsLeft)) return false;
  if (!apply(file)) return false;
  for (int n = 2;; n++) {
    const std::string& more = file.tag("_lib" + std::to_string(n));
    if (more.empty()) break;
    if (!loadLibrary(more, file.version, depth + 1, apply, maxProgramBytes, loadsLeft)) return false;
  }
  return true;
}

bool loadLibrary(const std::string& name, int version, int depth, const std::function<bool(const File&)>& apply,
                 size_t maxProgramBytes, int& loadsLeft) {
  // Each is read and expanded again wherever it is named: a file naming one
  // library a thousand times, or libraries that each name the next many times
  // over, would be minutes of it.
  if (loadsLeft-- <= 0) return false;
  std::vector<uint8_t> data;
  if (!companion(name, data)) return false;
  File library;
  if (!parse(data.data(), data.size(), library, maxProgramBytes) || library.version != version) return false;
  // The file as it came is not needed past here: only what was read from it.
  data.clear();
  data.shrink_to_fit();
  return loadAt(library, depth, apply, maxProgramBytes, loadsLeft);
}

}  // namespace

const std::string& File::tag(const std::string& key) const {
  static const std::string kNone;
  auto found = tags.find(key);
  return found == tags.end() ? kNone : found->second;
}

bool probe(const uint8_t* data, size_t size, int version) {
  return size >= kHeaderSize && std::memcmp(data, "PSF", 3) == 0 && data[3] == version;
}

bool parse(const uint8_t* data, size_t size, File& out, size_t maxProgramBytes) {
  if (size < kHeaderSize || std::memcmp(data, "PSF", 3) != 0) return false;
  const uint64_t reservedSize = readLe32(data + 4);
  const uint64_t programSize = readLe32(data + 8);
  if (kHeaderSize + reservedSize + programSize > size) return false;
  out = File();
  out.version = data[3];
  out.reserved.assign(data + kHeaderSize, data + kHeaderSize + reservedSize);
  // The CRC is not checked: zlib checks what it expands on its own.
  if (!inflate(data + kHeaderSize + reservedSize, programSize, out.program, maxProgramBytes)) return false;
  const size_t tagAt = kHeaderSize + reservedSize + programSize;
  if (size - tagAt >= 5 && std::memcmp(data + tagAt, "[TAG]", 5) == 0) {
    parseTags(data + tagAt + 5, std::min(size - tagAt - 5, kMaxTagBytes), out.tags);
  }
  return true;
}

int parseTimeMs(const std::string& text) {
  const std::string value = trimmed(text);
  if (value.empty()) return -1;
  double total = 0.0;
  double part = 0.0;
  double fraction = 0.0;
  bool inFraction = false;
  bool digits = false;
  for (char c : value) {
    if (c >= '0' && c <= '9') {
      digits = true;
      if (inFraction) {
        fraction /= 10.0;
        part += (c - '0') * fraction;
      } else {
        part = part * 10.0 + (c - '0');
      }
    } else if (c == ':' && !inFraction) {
      total = (total + part) * 60.0;
      part = 0.0;
    } else if ((c == '.' || c == ',') && !inFraction) {
      inFraction = true;
      fraction = 1.0;
    } else {
      break;
    }
  }
  if (!digits) return -1;
  // No tune runs a day, and a tag that says more - hundreds of hours fit in
  // the field - was more milliseconds than the number they go into holds: a
  // length that came out negative, with every seek in the file landing at
  // its start.
  const double seconds = std::min(total + part, 24.0 * 3600.0);
  return static_cast<int>(std::lround(seconds * 1000.0));
}

bool loadWithLibraries(const File& file, const std::function<bool(const File&)>& apply, size_t maxProgramBytes) {
  int loadsLeft = kMaxLibraryLoads;
  return loadAt(file, 0, apply, maxProgramBytes, loadsLeft);
}

bool isLibraryName(const std::string& name) {
  const std::string lower = lowercased(baseName(name));
  const size_t dot = lower.find_last_of('.');
  if (dot == std::string::npos) return false;
  const std::string extension = lower.substr(dot + 1);
  return extension.size() > 3 && extension.compare(extension.size() - 3, 3, "lib") == 0;
}

bool companionFile(const std::string& name, std::vector<uint8_t>& out) { return companion(name, out); }

void setCompanions(std::vector<std::pair<std::string, std::vector<uint8_t>>> files) {
  g_companions = std::move(files);
}

void clearCompanions() { g_companions.clear(); }

}  // namespace psf
