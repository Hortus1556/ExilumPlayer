//
// JNI glue for the Furnace decoder. Kept apart from the decoder for the same
// reason as GME's and HVL's - see decoder-gme-jni.cpp.
//
#include "decoder-furnace.h"
#include "player-common.h"
#include "visual-state.h"
#include <algorithm>
#include <array>
#include <string>
#include <vector>

namespace {

/** "01 name", one-based the way the pattern view numbers them. */
jobjectArray nameList(JNIEnv* env, int count, const char* (*nameOf)(int)) {
  jclass stringCls = env->FindClass("java/lang/String");
  jobjectArray names = env->NewObjectArray(count, stringCls, nullptr);
  for (int i = 0; i < count; i++) {
    const std::string label = (i + 1 < 10 ? "0" : "") + std::to_string(i + 1) + " " + nameOf(i);
    jstring s = newJString(env, label.c_str());
    env->SetObjectArrayElement(names, i, s);
    env->DeleteLocalRef(s);
  }
  env->DeleteLocalRef(stringCls);
  return names;
}

}  // namespace

void furnace_fillModVars(JNIEnv* env, jobject modVars) {
  initModVarsFields(env);
  initSequenceFields(env);

  jstring name = newJString(env, furnace_decoder_title());
  jstring type = newJString(env, furnace_decoder_type());
  env->SetObjectField(modVars, g_modVars.name, name);
  env->SetObjectField(modVars, g_modVars.type, type);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(type);

  const int orders = furnace_decoder_order_count();
  const int instruments = furnace_decoder_instrument_count();
  const int samples = furnace_decoder_sample_count();

  env->SetIntField(modVars, g_modVars.chn, furnace_decoder_channels());
  // Patterns belong to channels here, so the orders are what there are to show.
  env->SetIntField(modVars, g_modVars.pat, orders);
  env->SetIntField(modVars, g_modVars.trk, orders);
  env->SetIntField(modVars, g_modVars.len, orders);
  env->SetIntField(modVars, g_modVars.ins, instruments);
  env->SetIntField(modVars, g_modVars.smp, samples);
  env->SetIntField(modVars, g_modVars.gvl, 64);
  env->SetBooleanField(modVars, g_modVars.hasPatterns, JNI_TRUE);
  env->SetBooleanField(modVars, g_modVars.durationKnown,
                       furnace_decoder_duration_known() ? JNI_TRUE : JNI_FALSE);

  const int subsongs = furnace_decoder_subsong_count();
  env->SetIntField(modVars, g_modVars.miNumSequences, subsongs);

  jstring comment = newJString(env, furnace_decoder_author());
  env->SetObjectField(modVars, g_modVars.miComment, comment);
  env->DeleteLocalRef(comment);

  jclass seqCls = env->FindClass("com/hortus1556/exilum/renderer/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(subsongs, seqCls, nullptr);
  for (int i = 0; i < subsongs; i++) {
    jobject seq = env->AllocObject(seqCls);
    env->SetIntField(seq, g_sequence.entryPoint, i);
    env->SetIntField(seq, g_sequence.duration, furnace_decoder_subsong_duration_ms(i));
    // Furnace names its subsongs; blank where the author did not, which is
    // what lets the app offer to name it - see Sequence.name.
    jstring seqName = newJString(env, furnace_decoder_subsong_name(i));
    env->SetObjectField(seq, g_sequence.name, seqName);
    env->DeleteLocalRef(seqName);
    env->SetObjectArrayElement(seqArray, i, seq);
    env->DeleteLocalRef(seq);
  }
  env->SetObjectField(modVars, g_modVars.seqData, seqArray);
  env->DeleteLocalRef(seqArray);
  env->DeleteLocalRef(seqCls);

  jobjectArray insNames = nameList(env, instruments, furnace_decoder_instrument_name);
  env->SetObjectField(modVars, g_modVars.instruments, insNames);
  env->DeleteLocalRef(insNames);
  jobjectArray sampleNames = nameList(env, samples, furnace_decoder_sample_name);
  env->SetObjectField(modVars, g_modVars.samples, sampleNames);
  env->DeleteLocalRef(sampleNames);

  // PlayerEngine.getPatternData reads the effect columns this decoder's way
  // when the engine's name starts with this - see furnace_decoder_pattern_data.
  jstring engine = newJString(env, furnace_decoder_engine_name());
  env->SetObjectField(modVars, g_modVars.renderingEngine, engine);
  env->DeleteLocalRef(engine);
}

void furnace_fillFrameInfo(JNIEnv* env, jobject frameInfo) {
  initFrameInfoFields(env);
  std::array<int32_t, kScalarCount> s{};
  furnace_decoder_read_visual(s.data(), nullptr, nullptr, 0);
  env->SetIntField(frameInfo, g_frameInfo.pos, s[kScalarOrder]);
  env->SetIntField(frameInfo, g_frameInfo.pattern, s[kScalarPattern]);
  env->SetIntField(frameInfo, g_frameInfo.row, s[kScalarRow]);
  env->SetIntField(frameInfo, g_frameInfo.numRows, s[kScalarNumRows]);
  env->SetIntField(frameInfo, g_frameInfo.speed, s[kScalarSpeed]);
  env->SetIntField(frameInfo, g_frameInfo.bpm, s[kScalarBpm]);
  env->SetIntField(frameInfo, g_frameInfo.time, furnace_decoder_position_ms());
  env->SetIntField(frameInfo, g_frameInfo.totalTime, furnace_decoder_duration_ms());
}

jboolean furnace_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  if (!furnace_decoder_loaded()) return JNI_FALSE;

  std::array<int32_t, kScalarCount> scalarBuf{};
  std::vector<int32_t> channelBuf(static_cast<size_t>(MAX_CHANNELS) * kChannelFields, 0);
  std::vector<int8_t> scopeBuf;
  const bool wantScopes = scopes != nullptr && scopeWidth > 0;
  if (wantScopes) scopeBuf.assign(static_cast<size_t>(MAX_CHANNELS) * scopeWidth, 0);

  const bool ready = furnace_decoder_read_visual(
      scalarBuf.data(), channelBuf.data(), wantScopes ? scopeBuf.data() : nullptr, scopeWidth);
  if (!ready) return JNI_FALSE;

  env->SetIntArrayRegion(scalars, 0, kScalarCount, scalarBuf.data());
  const jsize channelCount = env->GetArrayLength(chData);
  env->SetIntArrayRegion(chData, 0, std::min<jsize>(channelCount, channelBuf.size()), channelBuf.data());
  if (wantScopes) {
    const jsize scopeCount = env->GetArrayLength(scopes);
    env->SetByteArrayRegion(scopes, 0, std::min<jsize>(scopeCount, scopeBuf.size()),
                            reinterpret_cast<const jbyte*>(scopeBuf.data()));
  }
  return JNI_TRUE;
}

void furnace_fillPatternData(
    JNIEnv* env, jint pat, jint numRows, jbyteArray notes, jbyteArray instruments,
    jbyteArray fxType, jbyteArray fxParm, jbyteArray fx2Type, jbyteArray fx2Parm) {
  const size_t cells = static_cast<size_t>(numRows) * MAX_CHANNELS;
  std::vector<int8_t> n(cells, 0), i(cells, 0), ft(cells, 0), fp(cells, 0), ft2(cells, 0), fp2(cells, 0);

  furnace_decoder_pattern_data(pat, numRows, n.data(), i.data(), ft.data(), fp.data(), ft2.data(), fp2.data());

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
