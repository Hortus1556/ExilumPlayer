//
// JNI glue for the GME decoder.
//
// Kept apart from decoder-gme.cpp so that decoder stays a plain audio
// component with no opinion about Java: the emulators are the part most
// likely to be swapped, tested or reused, and none of that wants JNI in it.
//
#include "decoder-gme.h"
#include "player-common.h"
#include "visual-state.h"
#include <array>
#include <vector>

void gme_fillModVars(JNIEnv* env, jobject modVars) {
  initModVarsFields(env);
  const int voices = gme_decoder_voice_count();

  jstring name = newJString(env, gme_decoder_title());
  jstring type = newJString(env, gme_decoder_system());
  env->SetObjectField(modVars, g_modVars.name, name);
  env->SetObjectField(modVars, g_modVars.type, type);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(type);

  // Channels are voices here. Patterns, orders and instruments have no
  // meaning for a chip log, so they are left at zero rather than filled with
  // something the views would then draw.
  env->SetIntField(modVars, g_modVars.chn, voices);
  env->SetIntField(modVars, g_modVars.pat, 0);
  env->SetBooleanField(modVars, g_modVars.hasPatterns, JNI_FALSE);
  env->SetBooleanField(modVars, g_modVars.durationKnown,
                       gme_decoder_duration_known() ? JNI_TRUE : JNI_FALSE);
  env->SetIntField(modVars, g_modVars.trk, 0);
  env->SetIntField(modVars, g_modVars.ins, 0);
  env->SetIntField(modVars, g_modVars.smp, 0);
  env->SetIntField(modVars, g_modVars.len, 0);
  env->SetIntField(modVars, g_modVars.spd, 0);
  env->SetIntField(modVars, g_modVars.bpm, 0);
  env->SetIntField(modVars, g_modVars.gvl, 64);
  env->SetIntField(modVars, g_modVars.miNumSequences, gme_decoder_track_count());

  jstring comment = newJString(env, "");
  env->SetObjectField(modVars, g_modVars.miComment, comment);
  env->DeleteLocalRef(comment);

  // One sequence per track. These files routinely hold a whole game's music -
  // an NSF is a ROM with an entry point per tune - and the player already has
  // somewhere to put that: the subsong list.
  initSequenceFields(env);
  const int tracks = gme_decoder_track_count();
  jclass seqCls = env->FindClass("com/hortus1556/exilum/renderer/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(tracks, seqCls, nullptr);
  for (int i = 0; i < tracks; i++) {
    jobject seq = env->AllocObject(seqCls);
    env->SetIntField(seq, g_sequence.entryPoint, i);
    env->SetIntField(seq, g_sequence.duration, gme_decoder_track_duration_ms(i));
    jstring trackName = newJString(env, gme_decoder_track_name(i).c_str());
    env->SetObjectField(seq, g_sequence.name, trackName);
    env->DeleteLocalRef(trackName);
    env->SetObjectArrayElement(seqArray, i, seq);
    env->DeleteLocalRef(seq);
  }
  env->SetObjectField(modVars, g_modVars.seqData, seqArray);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(seqCls);

  // The voice names go in the instrument list, which is what ChannelView
  // labels its rows from - "Square 1", "Noise", "FM 3" and so on.
  jclass stringCls = env->FindClass("java/lang/String");
  jobjectArray names = env->NewObjectArray(voices, stringCls, nullptr);
  for (int i = 0; i < voices; i++) {
    jstring s = newJString(env, gme_decoder_voice_name(i));
    env->SetObjectArrayElement(names, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, g_modVars.instruments, names);
  env->SetObjectField(modVars, g_modVars.samples, names);
  env->DeleteLocalRef(names);
  env->DeleteLocalRef(stringCls);

  jstring engine = newJString(env, "Game Music Emu");
  env->SetObjectField(modVars, g_modVars.renderingEngine, engine);
  env->DeleteLocalRef(engine);
}

void gme_fillFrameInfo(JNIEnv* env, jobject frameInfo) {
  initFrameInfoFields(env);
  env->SetIntField(frameInfo, g_frameInfo.time, gme_decoder_position_ms());
  env->SetIntField(frameInfo, g_frameInfo.totalTime, gme_decoder_duration_ms());
  env->SetIntField(frameInfo, g_frameInfo.pos, 0);
  env->SetIntField(frameInfo, g_frameInfo.pattern, 0);
  env->SetIntField(frameInfo, g_frameInfo.row, 0);
  env->SetIntField(frameInfo, g_frameInfo.numRows, 0);
  env->SetIntField(frameInfo, g_frameInfo.speed, 0);
  env->SetIntField(frameInfo, g_frameInfo.bpm, 0);
}

jboolean gme_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  if (!gme_decoder_loaded()) return JNI_FALSE;

  std::array<int32_t, kScalarCount> scalarBuf{};
  std::vector<int32_t> channelBuf(static_cast<size_t>(MAX_CHANNELS) * kChannelFields, 0);
  std::vector<int8_t> scopeBuf;
  const bool wantScopes = scopes != nullptr && scopeWidth > 0;
  if (wantScopes) scopeBuf.assign(static_cast<size_t>(MAX_CHANNELS) * scopeWidth, 0);

  const bool ready = gme_decoder_read_visual(
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
