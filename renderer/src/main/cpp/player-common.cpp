#include "player-common.h"

std::vector<unsigned char> readFd(int fd) {
  std::vector<unsigned char> data;
  struct stat st{};
  if (fstat(fd, &st) == 0 && st.st_size > 0) data.reserve(static_cast<size_t>(st.st_size));
  std::array<unsigned char, 64 * 1024> buf{};
  while (true) {
    ssize_t n = read(fd, buf.data(), buf.size());
    if (n < 0) {
      if (errno == EINTR) continue;
      data.clear();
      return data;
    }
    if (n == 0) break;
    data.insert(data.end(), buf.begin(), buf.begin() + n);
  }
  return data;
}

AudioStatsFields g_audioStats{};
FrameInfoFields g_frameInfo{};
ModInfoFields g_modInfo{};
ModVarsFields g_modVars{};
SequenceFields g_sequence{};

void initAudioStatsFields(JNIEnv* env) {
  if (g_audioStats.clazz) return;
  GET_CLASS(g_audioStats, "com/hortus1556/exilum/renderer/model/AudioStats");
  GET_FIELD(g_audioStats, xrunCount, "I");
  GET_FIELD(g_audioStats, underrunCount, "I");
  GET_FIELD(g_audioStats, framesPerBurst, "I");
  GET_FIELD(g_audioStats, bufferCapacity, "I");
  GET_FIELD(g_audioStats, bufferSize, "I");
  GET_FIELD(g_audioStats, sampleRate, "I");
  GET_FIELD(g_audioStats, audioApi, "Ljava/lang/String;");
  GET_FIELD(g_audioStats, sharingMode, "Ljava/lang/String;");
  GET_FIELD(g_audioStats, perfMode, "Ljava/lang/String;");
  GET_FIELD(g_audioStats, audioFormat, "Ljava/lang/String;");
}

void initFrameInfoFields(JNIEnv* env) {
  if (g_frameInfo.clazz) return;
  GET_CLASS(g_frameInfo, "com/hortus1556/exilum/renderer/model/FrameInfo");
  GET_FIELD(g_frameInfo, pos, "I");
  GET_FIELD(g_frameInfo, pattern, "I");
  GET_FIELD(g_frameInfo, row, "I");
  GET_FIELD(g_frameInfo, numRows, "I");
  GET_FIELD(g_frameInfo, frame, "I");
  GET_FIELD(g_frameInfo, speed, "I");
  GET_FIELD(g_frameInfo, bpm, "I");
  GET_FIELD(g_frameInfo, time, "I");
  GET_FIELD(g_frameInfo, totalTime, "I");
  GET_FIELD(g_frameInfo, frameTime, "I");
  GET_FIELD(g_frameInfo, bufferSize, "I");
  GET_FIELD(g_frameInfo, totalSize, "I");
  GET_FIELD(g_frameInfo, volume, "I");
  GET_FIELD(g_frameInfo, loopCount, "I");
  GET_FIELD(g_frameInfo, virtChannels, "I");
  GET_FIELD(g_frameInfo, virtUsed, "I");
  GET_FIELD(g_frameInfo, sequence, "I");
  GET_FIELD(g_frameInfo, channelLayout, "I");
}

void initModInfoFields(JNIEnv* env) {
  if (g_modInfo.clazz) return;
  GET_CLASS(g_modInfo, "com/hortus1556/exilum/renderer/model/ModInfo");
  GET_FIELD(g_modInfo, name, "Ljava/lang/String;");
  GET_FIELD(g_modInfo, type, "Ljava/lang/String;");
}

void initModVarsFields(JNIEnv* env) {
  if (g_modVars.clazz) return;
  GET_CLASS(g_modVars, "com/hortus1556/exilum/renderer/model/ModVars");
  GET_FIELD(g_modVars, name, "Ljava/lang/String;");
  GET_FIELD(g_modVars, type, "Ljava/lang/String;");
  GET_FIELD(g_modVars, pat, "I");
  GET_FIELD(g_modVars, trk, "I");
  GET_FIELD(g_modVars, chn, "I");
  GET_FIELD(g_modVars, ins, "I");
  GET_FIELD(g_modVars, smp, "I");
  GET_FIELD(g_modVars, spd, "I");
  GET_FIELD(g_modVars, bpm, "I");
  GET_FIELD(g_modVars, len, "I");
  GET_FIELD(g_modVars, rst, "I");
  GET_FIELD(g_modVars, gvl, "I");
  GET_FIELD(g_modVars, miNumSequences, "I");
  GET_FIELD(g_modVars, miComment, "Ljava/lang/String;");
  GET_FIELD(g_modVars, seqData, "[Lcom/hortus1556/exilum/renderer/model/Sequence;");
  GET_FIELD(g_modVars, instruments, "[Ljava/lang/String;");
  GET_FIELD(g_modVars, samples, "[Ljava/lang/String;");
  GET_FIELD(g_modVars, hasPatterns, "Z");
  GET_FIELD(g_modVars, hasChannels, "Z");
  GET_FIELD(g_modVars, durationKnown, "Z");
  GET_FIELD(g_modVars, renderingEngine, "Ljava/lang/String;");
}

void initSequenceFields(JNIEnv* env) {
  if (g_sequence.clazz) return;
  GET_CLASS(g_sequence, "com/hortus1556/exilum/renderer/model/Sequence");
  GET_FIELD(g_sequence, entryPoint, "I");
  GET_FIELD(g_sequence, duration, "I");
  GET_FIELD(g_sequence, name, "Ljava/lang/String;");
}
