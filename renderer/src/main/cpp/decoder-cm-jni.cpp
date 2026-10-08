//
// JNI glue for the CustomMade decoder, kept apart from it as the others are.
//
#include "decoder-cm.h"
#include "player-common.h"
#include "visual-state.h"
#include <array>
#include <string>
#include <vector>

void cm_fillModVars(JNIEnv* env, jobject modVars) {
  initModVarsFields(env);
  const int instruments = cm_decoder_instrument_count();

  // The module has no name in it; the file's own is what the player shows.
  jstring name = newJString(env, "");
  jstring type = newJString(env, cm_decoder_type());
  env->SetObjectField(modVars, g_modVars.name, name);
  env->SetObjectField(modVars, g_modVars.type, type);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(type);

  // Each voice has a pattern of its own, of its own length, so there is no
  // grid of rows to draw.
  env->SetIntField(modVars, g_modVars.chn, cm_decoder_channels());
  env->SetIntField(modVars, g_modVars.pat, 0);
  env->SetBooleanField(modVars, g_modVars.hasPatterns, JNI_FALSE);
  env->SetBooleanField(modVars, g_modVars.hasChannels, JNI_TRUE);
  env->SetBooleanField(modVars, g_modVars.durationKnown, cm_decoder_duration_known() ? JNI_TRUE : JNI_FALSE);
  env->SetIntField(modVars, g_modVars.trk, 0);
  env->SetIntField(modVars, g_modVars.ins, instruments);
  env->SetIntField(modVars, g_modVars.smp, 0);
  env->SetIntField(modVars, g_modVars.len, 0);
  env->SetIntField(modVars, g_modVars.spd, 0);
  env->SetIntField(modVars, g_modVars.bpm, 0);
  env->SetIntField(modVars, g_modVars.gvl, 64);
  const int songs = cm_decoder_subsong_count();
  env->SetIntField(modVars, g_modVars.miNumSequences, songs);

  jstring comment = newJString(env, "Ron Klaren");
  env->SetObjectField(modVars, g_modVars.miComment, comment);
  env->DeleteLocalRef(comment);

  initSequenceFields(env);
  jclass seqCls = env->FindClass("com/hortus1556/exilum/renderer/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(songs, seqCls, nullptr);
  for (int i = 0; i < songs; i++) {
    jobject seq = env->AllocObject(seqCls);
    env->SetIntField(seq, g_sequence.entryPoint, i);
    env->SetIntField(seq, g_sequence.duration, cm_decoder_subsong_duration_ms(i));
    const std::string songName = "Song " + std::to_string(i + 1);
    jstring seqName = newJString(env, songName.c_str());
    env->SetObjectField(seq, g_sequence.name, seqName);
    env->DeleteLocalRef(seqName);
    env->SetObjectArrayElement(seqArray, i, seq);
    env->DeleteLocalRef(seq);
  }
  env->SetObjectField(modVars, g_modVars.seqData, seqArray);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(seqCls);

  // What ChannelView labels each row with: the instrument the voice plays.
  jclass stringCls = env->FindClass("java/lang/String");
  jobjectArray names = env->NewObjectArray(instruments, stringCls, nullptr);
  for (int i = 0; i < instruments; i++) {
    jstring s = newJString(env, cm_decoder_instrument_name(i));
    env->SetObjectArrayElement(names, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, g_modVars.instruments, names);
  env->DeleteLocalRef(names);

  jobjectArray empty = env->NewObjectArray(0, stringCls, nullptr);
  env->SetObjectField(modVars, g_modVars.samples, empty);
  env->DeleteLocalRef(empty);
  env->DeleteLocalRef(stringCls);

  jstring engine = newJString(env, "CustomMade replayer");
  env->SetObjectField(modVars, g_modVars.renderingEngine, engine);
  env->DeleteLocalRef(engine);
}

void cm_fillFrameInfo(JNIEnv* env, jobject frameInfo) {
  initFrameInfoFields(env);
  env->SetIntField(frameInfo, g_frameInfo.time, cm_decoder_position_ms());
  env->SetIntField(frameInfo, g_frameInfo.totalTime, cm_decoder_duration_ms());
  env->SetIntField(frameInfo, g_frameInfo.pos, 0);
  env->SetIntField(frameInfo, g_frameInfo.pattern, 0);
  env->SetIntField(frameInfo, g_frameInfo.row, 0);
  env->SetIntField(frameInfo, g_frameInfo.numRows, 0);
  env->SetIntField(frameInfo, g_frameInfo.speed, 0);
  env->SetIntField(frameInfo, g_frameInfo.bpm, 0);
}

jboolean cm_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  if (!cm_decoder_loaded()) return JNI_FALSE;

  std::array<int32_t, kScalarCount> scalarBuf{};
  std::vector<int32_t> channelBuf(static_cast<size_t>(MAX_CHANNELS) * kChannelFields, 0);
  std::vector<int8_t> scopeBuf;
  const bool wantScopes = scopes != nullptr && scopeWidth > 0;
  if (wantScopes) scopeBuf.assign(static_cast<size_t>(MAX_CHANNELS) * scopeWidth, 0);

  const bool ready = cm_decoder_read_visual(
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
