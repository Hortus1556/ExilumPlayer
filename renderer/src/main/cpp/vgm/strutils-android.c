//
// Codepage conversion for libvgm, on Android.
//
// libvgm converts tag text through iconv, and Android has no iconv to link
// against below API 31 - so its own StrUtils-CPConv_IConv.c compiles into
// nothing here and the three CPConv_ symbols come up undefined at link time.
// This provides them.
//
// Written rather than pulled in, because libvgm only ever asks for three
// conversions and they all go one way:
//
//   UTF-16LE -> UTF-8   the GD3 tags on a VGM
//   CP932    -> UTF-8   the tags on an S98, which is a PC-98 format
//   CP1252   -> UTF-8   the tags on a GYM
//
// A whole character-set library to do that would be several hundred kilobytes
// of tables for text that is displayed and nothing more.
//
// The semantics are StrUtils-CPConv_IConv.c's, because that is what libvgm is
// written against: an input size of zero means measure the string and include
// its terminator, an output pointer of NULL means allocate one the caller then
// frees, and the size written back excludes nothing - it is exactly how many
// bytes were produced.
//
#include <stdlib.h>
#include <string.h>
#include <strings.h>

/* libvgm is on the include path, so these are its own spellings. */
#include "stdtype.h"
#include "utils/StrUtils.h"

typedef enum {
  CONV_UTF16LE,
  CONV_CP932,
  CONV_CP1252,
  CONV_COPY  /* anything else: hand the bytes back unchanged */
} ConvMode;

struct _codepage_conversion {
  ConvMode mode;
};

/*
 * The thirty-two places CP1252 differs from Latin-1.
 *
 * Everything else in the range 0xA0-0xFF is its own code point, and everything
 * below 0x80 is ASCII. Zero marks the five positions the codepage leaves
 * undefined.
 */
static const UINT16 cp1252High[32] = {
    0x20AC, 0x0000, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
    0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x0000, 0x017D, 0x0000,
    0x0000, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
    0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x0000, 0x017E, 0x0178};

/* Writes one code point as UTF-8. Returns how many bytes it needed. */
static size_t writeUtf8(char* out, size_t room, UINT32 cp) {
  if (cp < 0x80) {
    if (room < 1) return 0;
    out[0] = (char)cp;
    return 1;
  }
  if (cp < 0x800) {
    if (room < 2) return 0;
    out[0] = (char)(0xC0 | (cp >> 6));
    out[1] = (char)(0x80 | (cp & 0x3F));
    return 2;
  }
  if (cp < 0x10000) {
    if (room < 3) return 0;
    out[0] = (char)(0xE0 | (cp >> 12));
    out[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
    out[2] = (char)(0x80 | (cp & 0x3F));
    return 3;
  }
  if (room < 4) return 0;
  out[0] = (char)(0xF0 | (cp >> 18));
  out[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
  out[2] = (char)(0x80 | ((cp >> 6) & 0x3F));
  out[3] = (char)(0x80 | (cp & 0x3F));
  return 4;
}

UINT8 CPConv_Init(CPCONV** retCPC, const char* cpFrom, const char* cpTo) {
  CPCONV* cpc;

  /* Everything libvgm asks for converts to UTF-8; anything else is copied. */
  cpc = (CPCONV*)calloc(1, sizeof(CPCONV));
  if (cpc == NULL) return 0xFF;

  if (cpTo == NULL || strcasecmp(cpTo, "UTF-8") != 0) {
    cpc->mode = CONV_COPY;
  } else if (cpFrom == NULL) {
    cpc->mode = CONV_COPY;
  } else if (strncasecmp(cpFrom, "UTF-16", 6) == 0 || strncasecmp(cpFrom, "UCS-2", 5) == 0) {
    cpc->mode = CONV_UTF16LE;
  } else if (strcasecmp(cpFrom, "CP932") == 0 || strcasecmp(cpFrom, "SHIFT-JIS") == 0 ||
             strcasecmp(cpFrom, "SHIFT_JIS") == 0) {
    cpc->mode = CONV_CP932;
  } else if (strcasecmp(cpFrom, "CP1252") == 0 || strcasecmp(cpFrom, "WINDOWS-1252") == 0) {
    cpc->mode = CONV_CP1252;
  } else {
    cpc->mode = CONV_COPY;
  }

  *retCPC = cpc;
  return 0x00;
}

void CPConv_Deinit(CPCONV* cpc) { free(cpc); }

/* Length of the input in bytes, terminator included, when none was given. */
static size_t measure(ConvMode mode, const char* in) {
  if (mode == CONV_UTF16LE) {
    const UINT16* u16 = (const UINT16*)in;
    size_t units = 0;
    while (u16[units] != 0) units++;
    return (units + 1) * sizeof(UINT16);
  }
  return strlen(in) + 1;
}

UINT8 CPConv_StrConvert(CPCONV* cpc, size_t* outSize, char** outStr, size_t inSize,
                        const char* inStr) {
  size_t capacity;
  size_t written = 0;
  size_t at = 0;
  UINT8 allocated = 0;
  UINT8 result = 0x00;
  char* out;

  if (cpc == NULL || outSize == NULL || outStr == NULL || inStr == NULL) return 0xFF;

  if (inSize == 0) inSize = measure(cpc->mode, inStr);
  if (inSize == 0) {
    *outSize = 0;
    return 0x00;
  }

  if (*outStr == NULL) {
    /* Three bytes of UTF-8 per input byte covers every case here, and four
       for a surrogate pair still comes to less than that per input byte. */
    capacity = inSize * 3 + 4;
    *outStr = (char*)malloc(capacity);
    if (*outStr == NULL) {
      *outSize = 0;
      return 0xFF;
    }
    allocated = 1;
  } else {
    capacity = *outSize;
  }
  out = *outStr;
  (void)allocated;

  if (cpc->mode == CONV_COPY) {
    written = inSize < capacity ? inSize : capacity;
    memcpy(out, inStr, written);
    if (written < inSize) result = 0x10;
    *outSize = written;
    return result;
  }

  while (at < inSize) {
    UINT32 cp;
    size_t used;

    if (cpc->mode == CONV_UTF16LE) {
      UINT16 unit;
      if (at + 1 >= inSize) {
        /* An odd trailing byte: the string was cut short. */
        result = 0x01;
        break;
      }
      unit = (UINT16)((UINT8)inStr[at] | ((UINT8)inStr[at + 1] << 8));
      at += 2;
      if (unit >= 0xD800 && unit <= 0xDBFF && at + 1 < inSize) {
        UINT16 low = (UINT16)((UINT8)inStr[at] | ((UINT8)inStr[at + 1] << 8));
        if (low >= 0xDC00 && low <= 0xDFFF) {
          at += 2;
          cp = 0x10000 + (((UINT32)(unit - 0xD800)) << 10) + (low - 0xDC00);
        } else {
          cp = 0xFFFD;
        }
      } else if (unit >= 0xD800 && unit <= 0xDFFF) {
        cp = 0xFFFD;
      } else {
        cp = unit;
      }
    } else if (cpc->mode == CONV_CP1252) {
      UINT8 byte = (UINT8)inStr[at++];
      if (byte < 0x80) {
        cp = byte;
      } else if (byte < 0xA0) {
        cp = cp1252High[byte - 0x80];
        if (cp == 0) cp = 0xFFFD;
      } else {
        cp = byte;
      }
    } else { /* CONV_CP932 */
      UINT8 byte = (UINT8)inStr[at++];
      if (byte < 0x80) {
        cp = byte;
      } else if (byte >= 0xA1 && byte <= 0xDF) {
        /* Half-width katakana, which are a straight run in Unicode. */
        cp = 0xFF61 + (byte - 0xA1);
      } else {
        /*
         * A double-byte lead. Mapping these needs the seven-thousand-entry
         * Shift-JIS table, which is not carried here - so the pair becomes a
         * replacement character and the rest of the tag survives.
         *
         * Only S98 tags reach this, and only the Japanese ones. If they turn
         * out to matter, the table is what to add.
         */
        if (at < inSize) at++;
        cp = 0xFFFD;
      }
    }

    used = writeUtf8(out + written, capacity - written, cp);
    if (used == 0) {
      result = 0x10; /* out of room in a buffer that cannot grow */
      break;
    }
    written += used;
  }

  *outSize = written;
  return result;
}
