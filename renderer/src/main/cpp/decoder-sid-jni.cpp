//
// JNI glue for the SID decoder.
//
// Kept apart from decoder-sid.cpp for the same reason as GME's: the emulation
// is the part most likely to be swapped, tested or reused, and none of that
// wants JNI in it.
//
#include "decoder-sid.h"
#include "player-common.h"
#include "visual-state.h"
#include <array>
#include <string>
#include <vector>

void sid_fillModVars(JNIEnv* env, jobject modVars) {
  initModVarsFields(env);
  const int voices = sid_decoder_channels();

  jstring name = newJString(env, sid_decoder_title());
  jstring type = newJString(env, sid_decoder_type());
  env->SetObjectField(modVars, g_modVars.name, name);
  env->SetObjectField(modVars, g_modVars.type, type);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(type);

  // Channels are the chips' voices. A tune is a program, so there are no
  // patterns, orders or instruments to list.
  env->SetIntField(modVars, g_modVars.chn, voices);
  env->SetIntField(modVars, g_modVars.pat, 0);
  env->SetBooleanField(modVars, g_modVars.hasPatterns, JNI_FALSE);
  env->SetBooleanField(modVars, g_modVars.hasChannels, JNI_TRUE);
  // Nothing in the file says how long a tune runs - see sid_decoder_duration_ms.
  env->SetBooleanField(modVars, g_modVars.durationKnown, JNI_FALSE);
  env->SetIntField(modVars, g_modVars.trk, 0);
  env->SetIntField(modVars, g_modVars.ins, 0);
  env->SetIntField(modVars, g_modVars.smp, 0);
  env->SetIntField(modVars, g_modVars.len, 0);
  env->SetIntField(modVars, g_modVars.spd, 0);
  env->SetIntField(modVars, g_modVars.bpm, 0);
  env->SetIntField(modVars, g_modVars.gvl, 64);
  const int songs = sid_decoder_subsong_count();
  env->SetIntField(modVars, g_modVars.miNumSequences, songs);

  std::string commentText = sid_decoder_author();
  const std::string released = sid_decoder_released();
  if (!released.empty()) commentText += (commentText.empty() ? "" : "\n") + released;
  jstring comment = newJString(env, commentText.c_str());
  env->SetObjectField(modVars, g_modVars.miComment, comment);
  env->DeleteLocalRef(comment);

  // One sequence per subsong: a file often holds a whole game's music, each
  // tune an entry point into the same program.
  initSequenceFields(env);
  jclass seqCls = env->FindClass("com/hortus1556/exilum/renderer/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(songs, seqCls, nullptr);
  for (int i = 0; i < songs; i++) {
    jobject seq = env->AllocObject(seqCls);
    env->SetIntField(seq, g_sequence.entryPoint, i);
    env->SetIntField(seq, g_sequence.duration, sid_decoder_native_duration_ms());
    const std::string songName = songs > 1 ? "Song " + std::to_string(i + 1) : sid_decoder_title();
    jstring seqName = newJString(env, songName.c_str());
    env->SetObjectField(seq, g_sequence.name, seqName);
    env->DeleteLocalRef(seqName);
    env->SetObjectArrayElement(seqArray, i, seq);
    env->DeleteLocalRef(seq);
  }
  env->SetObjectField(modVars, g_modVars.seqData, seqArray);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(seqCls);

  // ChannelView labels its rows from the instrument list, and what a SID
  // voice is playing is a waveform: the four the chip has, alone or ANDed
  // together, indexed by the top nibble of the control register.
  static const char* const kWaveforms[16] = {
      "Off",         "Triangle",     "Sawtooth",     "Tri+Saw",
      "Pulse",       "Tri+Pulse",    "Saw+Pulse",    "Tri+Saw+Pulse",
      "Noise",       "Noise+Tri",    "Noise+Saw",    "Noise+Tri+Saw",
      "Noise+Pulse", "Noise+T+P",    "Noise+S+P",    "Noise+T+S+P"};
  jclass stringCls = env->FindClass("java/lang/String");
  jobjectArray names = env->NewObjectArray(16, stringCls, nullptr);
  for (int i = 0; i < 16; i++) {
    jstring s = newJString(env, kWaveforms[i]);
    env->SetObjectArrayElement(names, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, g_modVars.instruments, names);
  env->DeleteLocalRef(names);

  jobjectArray empty = env->NewObjectArray(0, stringCls, nullptr);
  env->SetObjectField(modVars, g_modVars.samples, empty);
  env->DeleteLocalRef(empty);
  env->DeleteLocalRef(stringCls);

  jstring engine = newJString(env, "libsidplayfp 3.1.1 + reSIDfp 1.2.2");
  env->SetObjectField(modVars, g_modVars.renderingEngine, engine);
  env->DeleteLocalRef(engine);
}

void sid_fillFrameInfo(JNIEnv* env, jobject frameInfo) {
  initFrameInfoFields(env);
  env->SetIntField(frameInfo, g_frameInfo.time, sid_decoder_position_ms());
  env->SetIntField(frameInfo, g_frameInfo.totalTime, sid_decoder_duration_ms());
  env->SetIntField(frameInfo, g_frameInfo.pos, 0);
  env->SetIntField(frameInfo, g_frameInfo.pattern, 0);
  env->SetIntField(frameInfo, g_frameInfo.row, 0);
  env->SetIntField(frameInfo, g_frameInfo.numRows, 0);
  env->SetIntField(frameInfo, g_frameInfo.speed, 0);
  env->SetIntField(frameInfo, g_frameInfo.bpm, 0);
}

jboolean sid_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  if (!sid_decoder_loaded()) return JNI_FALSE;

  std::array<int32_t, kScalarCount> scalarBuf{};
  std::vector<int32_t> channelBuf(static_cast<size_t>(MAX_CHANNELS) * kChannelFields, 0);
  std::vector<int8_t> scopeBuf;
  const bool wantScopes = scopes != nullptr && scopeWidth > 0;
  if (wantScopes) scopeBuf.assign(static_cast<size_t>(MAX_CHANNELS) * scopeWidth, 0);

  const bool ready = sid_decoder_read_visual(
      scalarBuf.data(), channelBuf.data(), wantScopes ? scopeBuf.data() : nullptr, scopeWidth);
  if (!ready) return JNI_FALSE;

  env->SetIntArrayRegion(scalars, 0, kScalarCount, scalarBuf.data());
  const jsize channelCount = env->GetArrayLength(chData);
  env->SetIntArrayRegion(
      chData, 0, std::min<jsize>(channelCount, channelBuf.size()), channelBuf.data());
  if (wantScopes) {
    const jsize scopeCount = env->GetArrayLength(scopes);
    env->SetByteArrayRegion(
        scopes, 0, std::min<jsize>(scopeCount, scopeBuf.size()),
        reinterpret_cast<const jbyte*>(scopeBuf.data()));
  }
  return JNI_TRUE;
}
