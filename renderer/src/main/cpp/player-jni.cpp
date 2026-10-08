#include <android/log.h>
#include <algorithm>
#include <jni.h>
#include "audio.h"
#include "decoder.h"
#include "effects.h"
#include "player.h"
#include "psf.h"
#include "soundcheck.h"
#include "spectrum.h"
#include <string>
#include <utility>
#include <vector>

constexpr const char* TAG = "Exilum JNI";
#define LOG_DEBUG(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define METHOD(type, name) JNIEXPORT type JNICALL Java_com_hortus1556_exilum_renderer_Player_##name
#define GET_CLASS(a, b) a.clazz = env->FindClass(b)
#define GET_FIELD(a, b, c) a.b = env->GetFieldID(a.clazz, #b, c)

extern "C" {

METHOD(jboolean, init)(JNIEnv* env, jobject obj, jint rate, jint ms, jint mode, jint channels, jint api, jint flags) {
  return engine_init(env, rate, ms, mode, channels, api, flags);
}

METHOD(jint, deinit)(JNIEnv* env, jobject obj) {
  return engine_deinit(env);
}

METHOD(jint, loadModuleFd)(JNIEnv* env, jobject obj, jint fd, jstring filename, jobject modInfo) {
  return engine_loadFd(env, fd, filename, modInfo);
}

METHOD(void, setCompanionFiles)(JNIEnv* env, jobject obj, jobjectArray names, jobjectArray contents) {
  std::vector<std::pair<std::string, std::vector<uint8_t>>> files;
  const jsize count = names && contents ? std::min(env->GetArrayLength(names), env->GetArrayLength(contents)) : 0;
  for (jsize i = 0; i < count; i++) {
    auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
    auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
    if (name && bytes) {
      const char* chars = env->GetStringUTFChars(name, nullptr);
      std::vector<uint8_t> data(static_cast<size_t>(env->GetArrayLength(bytes)));
      env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(data.size()), reinterpret_cast<jbyte*>(data.data()));
      files.emplace_back(chars ? chars : "", std::move(data));
      if (chars) env->ReleaseStringUTFChars(name, chars);
    }
    if (name) env->DeleteLocalRef(name);
    if (bytes) env->DeleteLocalRef(bytes);
  }
  psf::setCompanions(std::move(files));
}

METHOD(jboolean, testModuleFd)(JNIEnv* env, jobject obj, jint fd, jstring filename, jobject modInfo) {
  return engine_testFd(env, fd, filename, modInfo);
}

METHOD(jint, startPlayer)(JNIEnv* env, jobject obj, jint rate, jint format) {
  return engine_startPlayer(env, rate, format);
}

METHOD(jint, endPlayer)(JNIEnv* env, jobject obj) {
  return engine_endPlayer(env);
}

METHOD(jint, playAudio)(JNIEnv* env, jobject obj) {
  return engine_playAudio(env);
}

METHOD(jint, releaseModule)(JNIEnv* env, jobject obj) {
  return engine_releaseModule(env);
}

METHOD(jboolean, stopAudio)(JNIEnv* env, jobject obj) {
  return stop_audio() == 0 ? JNI_TRUE : JNI_FALSE;
}

METHOD(jboolean, restartAudio)(JNIEnv* env, jobject obj) {
  // The stream's own, which did nothing: this went to a stub that said no,
  // and the stream a device had stopped under a paused player stayed stopped.
  return restart_audio() == 0 ? JNI_TRUE : JNI_FALSE;
}

METHOD(jboolean, hasAudioDisconnected)(JNIEnv* env, jobject obj) {
  return has_audio_disconnected() ? JNI_TRUE : JNI_FALSE;
}

METHOD(void, clearAudioDisconnected)(JNIEnv* env, jobject obj) {
  openmpt_clearAudioDisconnected(env);
}

METHOD(jboolean, hasModuleEnded)(JNIEnv* env, jobject obj) {
  return engine_hasEnded(env);
}

METHOD(void, setPlaying)(JNIEnv* env, jobject obj, jboolean value) {
  engine_setPlaying(env, value);
}

METHOD(void, setLoopMode)(JNIEnv* env, jobject obj, jboolean loop) {
  engine_setLoopMode(env, loop);
}

METHOD(void, setGlobalPitch)(JNIEnv* env, jobject obj, jint semitones) {
  engine_setGlobalPitch(env, semitones);
}

METHOD(void, setGlobalTempo)(JNIEnv* env, jobject obj, jint percent) {
  engine_setGlobalTempo(env, percent);
}

METHOD(void, setLoopRegion)(JNIEnv* env, jobject obj, jint startMs, jint endMs) {
  engine_setLoopRegion(env, startMs, endMs);
}

METHOD(void, setVolumeBoost)(JNIEnv* env, jobject obj, jint db) {
  engine_setVolumeBoost(env, db);
}

METHOD(void, setDurationOverride)(JNIEnv* env, jobject obj, jint ms) {
  engine_setDurationOverride(env, ms);
}

METHOD(void, setSoundCheck)(JNIEnv* env, jobject obj, jboolean enabled) {
  sound_check_set_enabled(enabled == JNI_TRUE);
}

METHOD(void, soundCheckBegin)(JNIEnv* env, jobject obj, jfloat knownLufs, jfloat priorLufs) {
  sound_check_begin(knownLufs, priorLufs);
}

METHOD(void, soundCheckUpdate)(JNIEnv* env, jobject obj, jfloat knownLufs) {
  sound_check_update(knownLufs);
}

METHOD(jint, analyzeLoudnessFd)(JNIEnv* env, jobject obj, jint fd, jstring filename, jint subsong, jint maxSeconds,
                                jint budgetMs, jobject modInfo, jfloatArray out) {
  return engine_analyzeLoudness(env, fd, filename, subsong, maxSeconds, budgetMs, modInfo, out);
}

METHOD(jfloat, getSoundCheckLoudness)(JNIEnv* env, jobject obj) {
  return sound_check_loudness();
}

METHOD(jfloat, getSoundCheckSeconds)(JNIEnv* env, jobject obj) {
  return sound_check_measured_seconds();
}

METHOD(void, getSoundCheckStats)(JNIEnv* env, jobject obj, jfloatArray out) {
  float stats[kStatCount];
  sound_check_stats(stats);
  const jsize n = std::min<jsize>(env->GetArrayLength(out), kStatCount);
  env->SetFloatArrayRegion(out, 0, n, stats);
}

METHOD(void, setHivelyStereo)(JNIEnv* env, jobject obj, jint percent) {
  engine_setHivelyStereo(env, percent);
}

METHOD(void, setHivelyGain)(JNIEnv* env, jobject obj, jint percent) {
  engine_setHivelyGain(env, percent);
}

METHOD(void, setAdPlugGain)(JNIEnv* env, jobject obj, jint percent) {
  engine_setAdPlugGain(env, percent);
}

METHOD(void, setAdPlugCore)(JNIEnv* env, jobject obj, jint core) {
  engine_setAdPlugCore(env, core);
}

METHOD(void, setGsfStereo)(JNIEnv* env, jobject obj, jint percent) {
  engine_setGsfStereo(env, percent);
}

METHOD(void, setGsfLowpass)(JNIEnv* env, jobject obj, jboolean on) {
  engine_setGsfLowpass(env, on);
}

METHOD(void, setGsfPan)(JNIEnv* env, jobject obj, jint channel, jint value) {
  engine_setGsfPan(env, channel, value);
}

METHOD(void, setTwoSfStereo)(JNIEnv* env, jobject obj, jint percent) {
  engine_setTwoSfStereo(env, percent);
}

METHOD(void, setTwoSfInterpolation)(JNIEnv* env, jobject obj, jint mode) {
  engine_setTwoSfInterpolation(env, mode);
}

METHOD(void, setTwoSfPan)(JNIEnv* env, jobject obj, jint channel, jint value) {
  engine_setTwoSfPan(env, channel, value);
}

METHOD(void, setUsfStereo)(JNIEnv* env, jobject obj, jint percent) {
  engine_setUsfStereo(env, percent);
}

METHOD(void, setUsfHle)(JNIEnv* env, jobject obj, jboolean on) {
  engine_setUsfHle(env, on);
}

METHOD(void, setPsfStereo)(JNIEnv* env, jobject obj, jint percent) {
  engine_setPsfStereo(env, percent);
}

METHOD(void, setPsfReverb)(JNIEnv* env, jobject obj, jboolean on) {
  engine_setPsfReverb(env, on);
}

METHOD(void, setDspStereo)(JNIEnv* env, jobject obj, jint percent) {
  engine_setDspStereo(env, percent);
}

METHOD(void, setAudioStereo)(JNIEnv* env, jobject obj, jint percent) {
  engine_setAudioStereo(env, percent);
}

METHOD(jstring, getLyrics)(JNIEnv* env, jobject obj) {
  return engine_getLyrics(env);
}

METHOD(void, setReverb)(JNIEnv* env, jobject obj, jboolean enabled, jint type, jint mix) {
  effects_set_reverb(enabled == JNI_TRUE, type, mix);
}

METHOD(void, setChorus)(JNIEnv* env, jobject obj, jboolean enabled, jint rateTenthsHz, jint spread, jint mix) {
  effects_set_chorus(enabled == JNI_TRUE, rateTenthsHz, spread, mix);
}

METHOD(void, setSurround)(JNIEnv* env, jobject obj, jboolean enabled, jint mix) {
  effects_set_surround(enabled == JNI_TRUE, mix);
}

METHOD(void, setEqualizer)(JNIEnv* env, jobject obj, jboolean enabled, jintArray bandsDb) {
  int32_t bands[kEffectsEqBands] = {};
  const jsize count = bandsDb ? std::min<jsize>(env->GetArrayLength(bandsDb), kEffectsEqBands) : 0;
  if (count > 0) env->GetIntArrayRegion(bandsDb, 0, count, reinterpret_cast<jint*>(bands));
  effects_set_equalizer(enabled == JNI_TRUE, bands, count);
}

METHOD(void, setGmeDefaultLength)(JNIEnv* env, jobject obj, jint seconds) {
  engine_setGmeDefaultLength(env, seconds);
}

METHOD(void, setGmeVolume)(JNIEnv* env, jobject obj, jint console, jint percent) {
  engine_setGmeVolume(env, console, percent);
}

METHOD(void, setGmeStereo)(JNIEnv* env, jobject obj, jint console, jint percent) {
  engine_setGmeStereo(env, console, percent);
}

METHOD(void, setGmePan)(JNIEnv* env, jobject obj, jint console, jint voice, jint pan) {
  engine_setGmePan(env, console, voice, pan);
}

METHOD(void, setGmeEcho)(JNIEnv* env, jobject obj, jboolean enabled) {
  engine_setGmeEcho(env, enabled);
}

METHOD(void, setGmeInterpolation)(JNIEnv* env, jobject obj, jint mode) {
  engine_setGmeInterpolation(env, mode);
}

METHOD(jint, nextPosition)(JNIEnv* env, jobject obj) {
  return engine_nextPosition(env);
}

METHOD(jint, prevPosition)(JNIEnv* env, jobject obj) {
  return engine_prevPosition(env);
}

METHOD(jint, setPosition)(JNIEnv* env, jobject obj, jint pos) {
  return engine_setPosition(env, pos);
}

METHOD(jint, stopModule)(JNIEnv* env, jobject obj) {
  return engine_stopModule(env);
}

METHOD(jint, restartModule)(JNIEnv* env, jobject obj) {
  return engine_restartModule(env);
}

METHOD(jint, seek)(JNIEnv* env, jobject obj, jint time, jint ticket) {
  return engine_seek(env, time, ticket);
}

METHOD(void, setSeekWanted)(JNIEnv* env, jobject obj, jint ticket) {
  engine_setSeekWanted(ticket);
}

METHOD(jint, time)(JNIEnv* env, jobject obj) {
  return engine_time(env);
}

METHOD(jint, mute)(JNIEnv* env, jobject obj, jint chn, jint status) {
  return engine_mute(env, chn, status);
}

METHOD(jlong, resetMutes)(JNIEnv* env, jobject) {
  return engine_resetMutes(env);
}

METHOD(void, getFrameInfo)(JNIEnv* env, jobject obj, jobject frameInfo) {
  engine_getFrameInfo(env, frameInfo);
}

METHOD(jboolean, readVisualState)(JNIEnv* env, jobject obj, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  return engine_readVisualState(env, scalars, chData, scopes, scopeWidth);
}

METHOD(jboolean, readSpectrum)(JNIEnv* env, jobject obj, jfloatArray bands, jint count) {
  if (bands == nullptr) return JNI_FALSE;
  float values[kSpectrumMaxBands];
  const jsize n = std::min<jsize>({count, env->GetArrayLength(bands), kSpectrumMaxBands});
  if (n <= 0) return JNI_FALSE;
  const bool read = spectrum_read(values, n);
  env->SetFloatArrayRegion(bands, 0, n, values);
  return read ? JNI_TRUE : JNI_FALSE;
}

METHOD(jfloat, spectrumBandHz)(JNIEnv* env, jobject obj, jint index, jint count) {
  return spectrum_band_hz(index, count);
}

METHOD(jint, setPlayer)(JNIEnv* env, jobject obj, jint parm, jint value) {
  return engine_setPlayer(env, parm, value);
}

METHOD(jint, getPlayer)(JNIEnv* env, jobject obj, jint parm) {
  return engine_getPlayer(env, parm);
}

METHOD(void, getModVars)(JNIEnv* env, jobject obj, jobject modVars) {
  engine_getModVars(env, modVars);
}

METHOD(jstring, getVersion)(JNIEnv* env, jobject obj) {
  return openmpt_getVersion(env);
}

METHOD(jobjectArray, getFormats)(JNIEnv* env, jobject obj) {
  return engine_getFormats(env);
}

METHOD(jobjectArray, getFormatGroups)(JNIEnv* env, jobject obj) {
  return engine_getFormatGroups(env);
}

METHOD(jint, getPatternRows)(JNIEnv* env, jobject obj, jint pat) {
  return engine_getPatternRows(env, pat);
}

METHOD(void, getPatternData)(JNIEnv* env, jobject obj, jint pat, jint numRows, jbyteArray notes, jbyteArray instruments, jbyteArray fxType, jbyteArray fxParm, jbyteArray fx2Type, jbyteArray fx2Parm) {
  engine_getPatternData(env, pat, numRows, notes, instruments, fxType, fxParm, fx2Type, fx2Parm);
}

METHOD(jboolean, setSequence)(JNIEnv* env, jobject obj, jint seq) {
  return engine_setSequence(env, seq);
}

METHOD(void, getAudioStats)(JNIEnv* env, jobject obj, jobject info) {
  openmpt_getAudioStats(env, info);
}

METHOD(jint, setResampler)(JNIEnv* env, jobject, jint mode) {
  return engine_setResampler(env, mode);
}
}
