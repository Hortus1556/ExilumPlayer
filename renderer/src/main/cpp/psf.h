#pragma once
#include <cstddef>
#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <utility>
#include <vector>

/**
 * The PSF container, shared by the rips of the systems after the PlayStation
 * that took up Neill Corlett's format: "PSF", a byte naming the system (0x01
 * PlayStation, 0x02 PlayStation 2, 0x21 Nintendo 64, 0x22 Game Boy Advance,
 * 0x24 Nintendo DS...), a reserved area, the program zlib-compressed, and
 * after it "[TAG]" and key=value lines.
 *
 * A "mini" file holds only the few bytes that pick one song. The program they
 * patch is in the libraries its _lib, _lib2... tags name - files beside it,
 * which the app reads and hands over with psf::setCompanions before the load,
 * the decoders having only the descriptor of the file itself.
 */
namespace psf {

struct File {
  int version = 0;
  std::vector<uint8_t> reserved;
  /** The program, uncompressed. */
  std::vector<uint8_t> program;
  /** Keys lowercased. A key on several lines has them joined with '\n'. */
  std::map<std::string, std::string> tags;

  /** The tag's value, or "" when there is none. */
  const std::string& tag(const std::string& key) const;
};

/**
 * The most a program may expand to unless a decoder says otherwise: a
 * PlayStation 2's whole memory, twice over. A Nintendo DS cartridge can be
 * far larger, and its decoder says so.
 */
constexpr size_t kMaxProgramBytes = 64u << 20;

/** Whether the data starts as a PSF of this version does. */
bool probe(const uint8_t* data, size_t size, int version);

/**
 * Reads the file. The program's CRC is not checked: zlib checks what it
 * expands on its own. False when the program is more than [maxProgramBytes].
 */
bool parse(const uint8_t* data, size_t size, File& out, size_t maxProgramBytes = kMaxProgramBytes);

/**
 * Expands zlib data, as the program is and as a system's own blocks in the
 * reserved area can be: false when it is not whole, or comes to more than
 * [maxBytes].
 */
bool inflate(const uint8_t* data, size_t size, std::vector<uint8_t>& out, size_t maxBytes = kMaxProgramBytes);

/**
 * "[[h:]m:]s[.fff]" in milliseconds, a comma allowed for the point as some
 * taggers write it; -1 when the text is no time.
 */
int parseTimeMs(const std::string& text);

/**
 * Hands [file] and its libraries to [apply] in the order the format lays
 * down: _lib, then the file itself, then _lib2, _lib3... until one is missing
 * - each library the same way in turn, since one can name libraries of its
 * own. False when a library named is not among the companions or is no PSF of
 * the same version, or when [apply] says so.
 */
bool loadWithLibraries(const File& file, const std::function<bool(const File&)>& apply,
                       size_t maxProgramBytes = kMaxProgramBytes);

/** Whether a file name is a library's - "*.gsflib", "*.psflib"... - which is never played alone. */
bool isLibraryName(const std::string& name);

/**
 * The file called [name] among the companions, whatever its case and path;
 * false when the app handed over none of that name.
 */
bool companionFile(const std::string& name, std::vector<uint8_t>& out);

/** The files beside the one about to be loaded, by name, as the app found them. */
void setCompanions(std::vector<std::pair<std::string, std::vector<uint8_t>>> files);
void clearCompanions();

}  // namespace psf
