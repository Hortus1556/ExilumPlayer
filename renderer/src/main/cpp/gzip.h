#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

/**
 * Gzip, for the formats that get passed around compressed.
 *
 * A .vgz is a gzipped .vgm, and a gzipped NSF or SPC is still an NSF or an SPC
 * - but not to a header check, which sees gzip's first two bytes and nothing it
 * knows. The file has to be expanded before anyone can tell what it is, so this
 * sits in front of every decoder that might be handed one.
 */

/** True for a gzip member. */
bool gzip_is_member(const uint8_t* data, size_t size);

/**
 * Inflates a whole gzip member. Empty on failure, including a truncated one -
 * and one that comes out larger than any tune is: a few kilobytes of gzip can
 * be made to unpack to gigabytes, and a library scan opens whatever it finds.
 */
std::vector<uint8_t> gzip_inflate(const uint8_t* data, size_t size);

/**
 * Inflates the first [want] bytes of a gzip member into [out], and says how
 * many it got. For telling what a compressed file is, which its first few
 * bytes say: unpacking megabytes of it to read four was most of the time a
 * folder of them took to list.
 */
size_t gzip_inflate_head(const uint8_t* data, size_t size, uint8_t* out, size_t want);
