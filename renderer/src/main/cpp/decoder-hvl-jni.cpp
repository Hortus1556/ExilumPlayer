//
// JNI glue for the AHX / Hively decoder. Kept apart from the decoder for the
// same reason as GME's - see decoder-gme-jni.cpp.
//
#include "decoder-hvl.h"
#include "player-common.h"
#include "visual-state.h"
#include <algorithm>
#include <array>
#include <string>
#include <vector>

void hvl_fillModVars(JNIEnv* env, jobject modVars) {
  initModVarsFields(env);
  initSequenceFields(env);

  jstring name = newJString(env, hvl_decoder_title());
  jstring type = newJString(env, "AHX / Hively");
  env->SetObjectField(modVars, g_modVars.name, name);
  env->SetObjectField(modVars, g_modVars.type, type);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(type);

  const int positions = hvl_decoder_position_count();
  const int instruments = hvl_decoder_instrument_count();

  // A tracker, so all of this means what it usually means. The one thing it
  // has no equivalent for is samples: each instrument is a small synthesis
  // program, not a recording.
  env->SetIntField(modVars, g_modVars.chn, hvl_decoder_channels());
  env->SetIntField(modVars, g_modVars.pat, positions);
  env->SetIntField(modVars, g_modVars.trk, positions);
  env->SetIntField(modVars, g_modVars.len, positions);
  env->SetIntField(modVars, g_modVars.ins, instruments);
  env->SetIntField(modVars, g_modVars.smp, instruments);
  env->SetIntField(modVars, g_modVars.gvl, 64);
  env->SetBooleanField(modVars, g_modVars.hasPatterns, JNI_TRUE);
  env->SetBooleanField(modVars, g_modVars.durationKnown,
                       hvl_decoder_duration_known() ? JNI_TRUE : JNI_FALSE);

  const int subsongs = hvl_decoder_subsong_count();
  env->SetIntField(modVars, g_modVars.miNumSequences, subsongs);

  jstring comment = newJString(env, "");
  env->SetObjectField(modVars, g_modVars.miComment, comment);
  env->DeleteLocalRef(comment);

  jclass seqCls = env->FindClass("com/hortus1556/exilum/renderer/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(subsongs, seqCls, nullptr);
  for (int i = 0; i < subsongs; i++) {
    jobject seq = env->AllocObject(seqCls);
    env->SetIntField(seq, g_sequence.entryPoint, i);
    env->SetIntField(seq, g_sequence.duration, hvl_decoder_subsong_duration_ms(i));
    // Blank: an AHX subsong is a position to start at, and the format carries
    // no name for it. The app numbers it, and lets the listener name it.
    jstring seqName = newJString(env, "");
    env->SetObjectField(seq, g_sequence.name, seqName);
    env->DeleteLocalRef(seqName);
    env->SetObjectArrayElement(seqArray, i, seq);
    env->DeleteLocalRef(seq);
  }
  env->SetObjectField(modVars, g_modVars.seqData, seqArray);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(seqCls);

  jclass stringCls = env->FindClass("java/lang/String");
  jobjectArray names = env->NewObjectArray(instruments, stringCls, nullptr);
  for (int i = 0; i < instruments; i++) {
    // One-based on screen, matching how the pattern refers to them.
    const std::string label =
        (i + 1 < 10 ? "0" : "") + std::to_string(i + 1) + " " + hvl_decoder_instrument_name(i + 1);
    jstring s = newJString(env, label.c_str());
    env->SetObjectArrayElement(names, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, g_modVars.instruments, names);
  env->SetObjectField(modVars, g_modVars.samples, names);
  env->DeleteLocalRef(names);
  env->DeleteLocalRef(stringCls);

  jstring engine = newJString(env, "HivelyTracker replayer");
  env->SetObjectField(modVars, g_modVars.renderingEngine, engine);
  env->DeleteLocalRef(engine);
}

void hvl_fillFrameInfo(JNIEnv* env, jobject frameInfo) {
  initFrameInfoFields(env);
  std::array<int32_t, kScalarCount> s{};
  hvl_decoder_read_visual(s.data(), nullptr, nullptr, 0);
  env->SetIntField(frameInfo, g_frameInfo.pos, s[kScalarOrder]);
  env->SetIntField(frameInfo, g_frameInfo.pattern, s[kScalarPattern]);
  env->SetIntField(frameInfo, g_frameInfo.row, s[kScalarRow]);
  env->SetIntField(frameInfo, g_frameInfo.numRows, s[kScalarNumRows]);
  env->SetIntField(frameInfo, g_frameInfo.speed, s[kScalarSpeed]);
  env->SetIntField(frameInfo, g_frameInfo.bpm, s[kScalarBpm]);
  env->SetIntField(frameInfo, g_frameInfo.time, hvl_decoder_position_ms());
  env->SetIntField(frameInfo, g_frameInfo.totalTime, hvl_decoder_duration_ms());
}

jboolean hvl_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  if (!hvl_decoder_loaded()) return JNI_FALSE;

  std::array<int32_t, kScalarCount> scalarBuf{};
  std::vector<int32_t> channelBuf(static_cast<size_t>(MAX_CHANNELS) * kChannelFields, 0);
  std::vector<int8_t> scopeBuf;
  const bool wantScopes = scopes != nullptr && scopeWidth > 0;
  if (wantScopes) scopeBuf.assign(static_cast<size_t>(MAX_CHANNELS) * scopeWidth, 0);

  // False until the render callback has published something. Reported rather
  // than assumed, because that is what the debug view's "Valid" means.
  const bool ready = hvl_decoder_read_visual(
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

void hvl_fillPatternData(
    JNIEnv* env, jint pat, jint numRows, jbyteArray notes, jbyteArray instruments,
    jbyteArray fxType, jbyteArray fxParm, jbyteArray fx2Type, jbyteArray fx2Parm) {
  const size_t cells = static_cast<size_t>(numRows) * MAX_CHANNELS;
  std::vector<int8_t> n(cells, 0), i(cells, 0), ft(cells, 0), fp(cells, 0), ft2(cells, 0), fp2(cells, 0);

  hvl_decoder_pattern_data(pat, numRows, n.data(), i.data(), ft.data(), fp.data(), ft2.data(), fp2.data());

  auto put = [&](jbyteArray target, const std::vector<int8_t>& src) {
    if (!target) return;
    const jsize len = std::min<jsize>(env->GetArrayLength(target), src.size());
    env->SetByteArrayRegion(target, 0, len, reinterpret_cast<const jbyte*>(src.data()));
  };
  put(notes, n);
  put(instruments, i);
  put(fxType, ft);
  put(fxParm, fp);
  put(fx2Type, ft2);
  put(fx2Parm, fp2);
}
