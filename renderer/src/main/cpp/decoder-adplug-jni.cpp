//
// JNI glue for the AdPlug decoder. Kept apart from the decoder for the same
// reason as GME's and HVL's - see decoder-gme-jni.cpp.
//
#include "decoder-adplug.h"
#include "player-common.h"
#include "visual-state.h"
#include <algorithm>
#include <array>
#include <string>
#include <vector>

void adplug_fillModVars(JNIEnv* env, jobject modVars) {
  initModVarsFields(env);
  initSequenceFields(env);

  jstring name = newJString(env, adplug_decoder_title());
  jstring type = newJString(env, adplug_decoder_type());
  env->SetObjectField(modVars, g_modVars.name, name);
  env->SetObjectField(modVars, g_modVars.type, type);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(type);

  const int channels = adplug_decoder_channels();
  const int instruments = adplug_decoder_instrument_count();

  const bool patterns = adplug_decoder_has_patterns();

  env->SetIntField(modVars, g_modVars.chn, channels);
  env->SetIntField(modVars, g_modVars.ins, instruments);
  env->SetIntField(modVars, g_modVars.smp, instruments);
  env->SetIntField(modVars, g_modVars.gvl, 64);
  env->SetIntField(modVars, g_modVars.pat, adplug_decoder_pattern_count());
  env->SetIntField(modVars, g_modVars.trk, adplug_decoder_pattern_count());
  env->SetIntField(modVars, g_modVars.len, adplug_decoder_order_count());
  // True for the tracker formats, which is most of them, and false for the
  // register logs - a .dro or a .imf is a stream of chip writes with no grid
  // behind it, and the player says so by reporting no rows.
  env->SetBooleanField(modVars, g_modVars.hasPatterns, patterns ? JNI_TRUE : JNI_FALSE);
  env->SetBooleanField(modVars, g_modVars.durationKnown,
                       adplug_decoder_duration_known() ? JNI_TRUE : JNI_FALSE);

  const int subsongs = adplug_decoder_subsong_count();
  env->SetIntField(modVars, g_modVars.miNumSequences, subsongs);

  jstring comment = newJString(env, adplug_decoder_author());
  env->SetObjectField(modVars, g_modVars.miComment, comment);
  env->DeleteLocalRef(comment);

  jclass seqCls = env->FindClass("com/hortus1556/exilum/renderer/model/Sequence");
  jobjectArray seqArray = env->NewObjectArray(subsongs, seqCls, nullptr);
  for (int i = 0; i < subsongs; i++) {
    jobject seq = env->AllocObject(seqCls);
    env->SetIntField(seq, g_sequence.entryPoint, i);
    env->SetIntField(seq, g_sequence.duration, adplug_decoder_subsong_duration_ms(i));
    // Blank: AdPlug has no names for these, and a made-up "Subsong 2" would
    // stop the app offering to name it - see Sequence.name.
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
    // One-based on screen, the way the tune's own editor numbered them.
    const std::string label =
        (i + 1 < 10 ? "0" : "") + std::to_string(i + 1) + " " + adplug_decoder_instrument_name(i);
    jstring s = newJString(env, label.c_str());
    env->SetObjectArrayElement(names, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, g_modVars.instruments, names);
  env->DeleteLocalRef(names);

  // Samples are the fallback labels, for the players that do not track which
  // instrument a channel is playing. Those channels report no instrument and
  // are named by the identity of the voice loaded on the chip instead - see
  // recordPatchByte - and this is the list those names come from.
  const int patches = adplug_decoder_patch_count();
  jobjectArray patchNames = env->NewObjectArray(patches, stringCls, nullptr);
  for (int i = 0; i < patches; i++) {
    jstring s = newJString(env, adplug_decoder_patch_name(i));
    env->SetObjectArrayElement(patchNames, i, s);
    env->DeleteLocalRef(s);
  }
  env->SetObjectField(modVars, g_modVars.samples, patchNames);
  env->DeleteLocalRef(patchNames);
  env->DeleteLocalRef(stringCls);

  jstring engine = newJString(env, "AdPlug, Nuked OPL3");
  env->SetObjectField(modVars, g_modVars.renderingEngine, engine);
  env->DeleteLocalRef(engine);
}

void adplug_fillPatternData(
    JNIEnv* env, jint pat, jint numRows, jbyteArray notes, jbyteArray instruments,
    jbyteArray fxType, jbyteArray fxParm, jbyteArray fx2Type, jbyteArray fx2Parm) {
  const size_t cells = static_cast<size_t>(numRows) * MAX_CHANNELS;
  std::vector<int8_t> n(cells, 0), i(cells, 0), ft(cells, -1), fp(cells, -1), ft2(cells, -1),
      fp2(cells, -1);

  adplug_decoder_pattern_data(
      pat, numRows, n.data(), i.data(), ft.data(), fp.data(), ft2.data(), fp2.data());

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

void adplug_fillFrameInfo(JNIEnv* env, jobject frameInfo) {
  initFrameInfoFields(env);
  std::array<int32_t, kScalarCount> s{};
  adplug_decoder_read_visual(s.data(), nullptr, nullptr, 0);
  env->SetIntField(frameInfo, g_frameInfo.pos, s[kScalarOrder]);
  env->SetIntField(frameInfo, g_frameInfo.pattern, s[kScalarPattern]);
  env->SetIntField(frameInfo, g_frameInfo.row, s[kScalarRow]);
  env->SetIntField(frameInfo, g_frameInfo.numRows, s[kScalarNumRows]);
  env->SetIntField(frameInfo, g_frameInfo.speed, s[kScalarSpeed]);
  env->SetIntField(frameInfo, g_frameInfo.bpm, s[kScalarBpm]);
  env->SetIntField(frameInfo, g_frameInfo.time, adplug_decoder_position_ms());
  env->SetIntField(frameInfo, g_frameInfo.totalTime, adplug_decoder_duration_ms());
}

jboolean adplug_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  if (!adplug_decoder_loaded()) return JNI_FALSE;

  std::array<int32_t, kScalarCount> scalarBuf{};
  std::vector<int32_t> channelBuf(static_cast<size_t>(MAX_CHANNELS) * kChannelFields, 0);
  std::vector<int8_t> scopeBuf;
  const bool wantScopes = scopes != nullptr && scopeWidth > 0;
  if (wantScopes) scopeBuf.assign(static_cast<size_t>(MAX_CHANNELS) * scopeWidth, 0);

  const bool ready = adplug_decoder_read_visual(
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
