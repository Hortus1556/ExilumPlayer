#pragma once
#include <jni.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <string>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#define GET_CLASS(a, b) a.clazz = env->FindClass(b)
#define GET_FIELD(a, b, c) a.b = env->GetFieldID(a.clazz, #b, c)

/**
 * A Java string from text read out of a file, whatever it is encoded as.
 *
 * Never NewStringUTF on such text. That wants modified UTF-8, and a tune's
 * title or instrument names are whatever the tracker it came from wrote -
 * ISO-8859-1 on the Amiga, CP437 on DOS, Shift-JIS in a Japanese SPC tag. One
 * byte that is not valid modified UTF-8 and ART aborts the whole process: an
 * AHX with "Søstre" in an instrument name, its ø a single 0xF8, took the
 * player down as soon as it loaded. Even valid UTF-8 is not safe - a four-byte
 * sequence, an emoji, is valid UTF-8 and not valid modified UTF-8.
 *
 * So the text is decoded here into UTF-16. As UTF-8 when all of it is valid
 * UTF-8, which is what libopenmpt hands back and what any ASCII is. Otherwise
 * byte for byte as ISO-8859-1, which cannot fail, is exactly right for the
 * Amiga formats, and turns anything else into readable if wrong letters rather
 * than a crash.
 */
inline jstring newJString(JNIEnv* env, const char* text) {
  if (text == nullptr) text = "";
  const auto* bytes = reinterpret_cast<const unsigned char*>(text);
  const size_t length = strlen(text);

  std::u16string out;
  out.reserve(length);
  bool valid = true;
  for (size_t i = 0; i < length && valid;) {
    const unsigned char lead = bytes[i];
    uint32_t cp;
    size_t extra;
    if (lead < 0x80) {
      cp = lead;
      extra = 0;
    } else if ((lead & 0xE0) == 0xC0) {
      cp = lead & 0x1F;
      extra = 1;
    } else if ((lead & 0xF0) == 0xE0) {
      cp = lead & 0x0F;
      extra = 2;
    } else if ((lead & 0xF8) == 0xF0) {
      cp = lead & 0x07;
      extra = 3;
    } else {
      valid = false;
      break;
    }
    if (i + extra >= length) {  // cut off by the end of the string
      valid = false;
      break;
    }
    for (size_t k = 1; k <= extra; k++) {
      const unsigned char next = bytes[i + k];
      if ((next & 0xC0) != 0x80) {
        valid = false;
        break;
      }
      cp = (cp << 6) | (next & 0x3F);
    }
    if (!valid) break;
    // Overlong forms, surrogates written as UTF-8, and anything past Unicode.
    static const uint32_t kMin[4] = {0, 0x80, 0x800, 0x10000};
    if (cp < kMin[extra] || (cp >= 0xD800 && cp <= 0xDFFF) || cp > 0x10FFFF) {
      valid = false;
      break;
    }
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
      out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    } else {
      out.push_back(static_cast<char16_t>(cp));
    }
    i += 1 + extra;
  }

  if (!valid) {
    out.clear();
    for (size_t i = 0; i < length; i++) out.push_back(static_cast<char16_t>(bytes[i]));
  }
  return env->NewString(reinterpret_cast<const jchar*>(out.data()), static_cast<jsize>(out.size()));
}

struct AudioStatsFields {
  jclass clazz = nullptr;
  jfieldID xrunCount = nullptr;
  jfieldID underrunCount = nullptr;
  jfieldID framesPerBurst = nullptr;
  jfieldID bufferCapacity = nullptr;
  jfieldID bufferSize = nullptr;
  jfieldID sampleRate = nullptr;
  jfieldID audioApi = nullptr;
  jfieldID sharingMode = nullptr;
  jfieldID perfMode = nullptr;
  jfieldID audioFormat = nullptr;
};

struct FrameInfoFields {
  jclass clazz = nullptr;
  jfieldID pos = nullptr;
  jfieldID pattern = nullptr;
  jfieldID row = nullptr;
  jfieldID numRows = nullptr;
  jfieldID frame = nullptr;
  jfieldID speed = nullptr;
  jfieldID bpm = nullptr;
  jfieldID time = nullptr;
  jfieldID totalTime = nullptr;
  jfieldID frameTime = nullptr;
  jfieldID bufferSize = nullptr;
  jfieldID totalSize = nullptr;
  jfieldID volume = nullptr;
  jfieldID loopCount = nullptr;
  jfieldID virtChannels = nullptr;
  jfieldID virtUsed = nullptr;
  jfieldID sequence = nullptr;
  jfieldID channelLayout = nullptr;
};

struct ModInfoFields {
  jclass clazz = nullptr;
  jfieldID name = nullptr;
  jfieldID type = nullptr;
};

struct ModVarsFields {
  jclass clazz = nullptr;
  jfieldID name = nullptr;
  jfieldID type = nullptr;
  jfieldID pat = nullptr;
  jfieldID trk = nullptr;
  jfieldID chn = nullptr;
  jfieldID ins = nullptr;
  jfieldID smp = nullptr;
  jfieldID spd = nullptr;
  jfieldID bpm = nullptr;
  jfieldID len = nullptr;
  jfieldID rst = nullptr;
  jfieldID gvl = nullptr;
  jfieldID miNumSequences = nullptr;
  jfieldID miComment = nullptr;
  jfieldID seqData = nullptr;
  jfieldID instruments = nullptr;
  jfieldID samples = nullptr;
  jfieldID hasPatterns = nullptr;
  jfieldID hasChannels = nullptr;
  jfieldID durationKnown = nullptr;
  jfieldID renderingEngine = nullptr;
};

struct SequenceFields {
  jfieldID name = nullptr;
  jclass clazz = nullptr;
  jfieldID entryPoint = nullptr;
  jfieldID duration = nullptr;
};

extern AudioStatsFields g_audioStats;
extern FrameInfoFields g_frameInfo;
extern ModInfoFields g_modInfo;
extern ModVarsFields g_modVars;
extern SequenceFields g_sequence;

void initAudioStatsFields(JNIEnv* env);
void initFrameInfoFields(JNIEnv* env);
void initModInfoFields(JNIEnv* env);
void initModVarsFields(JNIEnv* env);
void initSequenceFields(JNIEnv* env);

std::vector<unsigned char> readFd(int fd);
