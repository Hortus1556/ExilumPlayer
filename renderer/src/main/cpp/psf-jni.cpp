//
// JNI glue for the decoders of the PSF family. See psf-jni.h.
//
#include "psf-jni.h"
#include "player-common.h"
#include "visual-state.h"
#include <array>
#include <string>
#include <vector>

void psf_fillModVars(JNIEnv* env, jobject modVars, const PsfSong& song) {
  initModVarsFields(env);
  const int channels = song.channels();
  const int voices = song.voices();

  jstring name = newJString(env, song.title());
  jstring type = newJString(env, song.type());
  env->SetObjectField(modVars, g_modVars.name, name);
  env->SetObjectField(modVars, g_modVars.type, type);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(type);

  // A game's sound driver, running: no patterns to draw, and a song a file.
  env->SetIntField(modVars, g_modVars.chn, channels);
  env->SetIntField(modVars, g_modVars.pat, 0);
  env->SetBooleanField(modVars, g_modVars.hasPatterns, JNI_FALSE);
  env->SetBooleanField(modVars, g_modVars.hasChannels, JNI_TRUE);
  env->SetBooleanField(modVars, g_modVars.durationKnown, song.durationKnown() ? JNI_TRUE : JNI_FALSE);
  env->SetIntField(modVars, g_modVars.trk, 0);
  env->SetIntField(modVars, g_modVars.ins, voices);
  env->SetIntField(modVars, g_modVars.smp, 0);
  env->SetIntField(modVars, g_modVars.len, 0);
  env->SetIntField(modVars, g_modVars.spd, 0);
  env->SetIntField(modVars, g_modVars.bpm, 0);
  env->SetIntField(modVars, g_modVars.gvl, 64);
  env->SetIntField(modVars, g_modVars.miNumSequences, 1);

  jstring comment = newJString(env, song.comment());
  env->SetObjectField(modVars, g_modVars.miComment, comment);
  env->DeleteLocalRef(comment);

  initSequenceFields(env);
  jclass seqCls = env->FindClass("com/hortus1556/exilum/renderer/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(1, seqCls, nullptr);
  jobject seq = env->AllocObject(seqCls);
  env->SetIntField(seq, g_sequence.entryPoint, 0);
  env->SetIntField(seq, g_sequence.duration, song.durationMs());
  jstring seqName = newJString(env, song.title());
  env->SetObjectField(seq, g_sequence.name, seqName);
  env->DeleteLocalRef(seqName);
  env->SetObjectArrayElement(seqArray, 0, seq);
  env->DeleteLocalRef(seq);
  env->SetObjectField(modVars, g_modVars.seqData, seqArray);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(seqCls);

  // What ChannelView labels each row with: the console's own names for its voices.
  jclass stringCls = env->FindClass("java/lang/String");
  jobjectArray names = env->NewObjectArray(voices, stringCls, nullptr);
  for (int i = 0; i < voices; i++) {
    jstring s = newJString(env, song.voiceName(i));
    env->SetObjectArrayElement(names, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, g_modVars.instruments, names);
  env->DeleteLocalRef(names);

  jobjectArray empty = env->NewObjectArray(0, stringCls, nullptr);
  env->SetObjectField(modVars, g_modVars.samples, empty);
  env->DeleteLocalRef(empty);
  env->DeleteLocalRef(stringCls);

  jstring engine = newJString(env, song.engine);
  env->SetObjectField(modVars, g_modVars.renderingEngine, engine);
  env->DeleteLocalRef(engine);
}

void psf_fillFrameInfo(JNIEnv* env, jobject frameInfo, const PsfSong& song) {
  initFrameInfoFields(env);
  env->SetIntField(frameInfo, g_frameInfo.time, song.positionMs());
  env->SetIntField(frameInfo, g_frameInfo.totalTime, song.durationMs());
  env->SetIntField(frameInfo, g_frameInfo.pos, 0);
  env->SetIntField(frameInfo, g_frameInfo.pattern, 0);
  env->SetIntField(frameInfo, g_frameInfo.row, 0);
  env->SetIntField(frameInfo, g_frameInfo.numRows, 0);
  env->SetIntField(frameInfo, g_frameInfo.speed, 0);
  env->SetIntField(frameInfo, g_frameInfo.bpm, 0);
  env->SetIntField(frameInfo, g_frameInfo.channelLayout, song.channelLayout ? song.channelLayout() : 0);
}

jboolean psf_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth, const PsfSong& song) {
  if (!song.loaded()) return JNI_FALSE;

  std::array<int32_t, kScalarCount> scalarBuf{};
  std::vector<int32_t> channelBuf(static_cast<size_t>(MAX_CHANNELS) * kChannelFields, 0);
  std::vector<int8_t> scopeBuf;
  const bool wantScopes = scopes != nullptr && scopeWidth > 0;
  if (wantScopes) scopeBuf.assign(static_cast<size_t>(MAX_CHANNELS) * scopeWidth, 0);

  const bool ready = song.readVisual(
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
