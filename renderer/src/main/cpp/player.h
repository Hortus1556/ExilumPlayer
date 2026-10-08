#pragma once
#include <jni.h>
#include <string>
#include <vector>

// libopenmpt, as one decoder among several.
//
// It owned the audio session once - opening the stream, starting it, and
// registering its own render callback whether or not it was the thing
// playing. That is the decoder layer's job now (see decoder.h); what is left
// here is libopenmpt's own state and the hooks the session calls to keep it
// in step.

/** Records the rate the shared stream opened at. */
void openmpt_notifyInit(jint actualRate);

/** Readies libopenmpt to be the render source. Does not touch the stream. */
void openmpt_prepareStart(jint rate);

/** The stream stopped; libopenmpt is no longer producing. */
void openmpt_notifyStop();

/** Releases the module and marks libopenmpt uninitialised. */
void openmpt_shutdown();

/** libopenmpt's render callback, registered by the decoder layer. */
int renderOpenMpt(void* audioData, int32_t numFrames, int32_t numChannels, int32_t bytesPerSample);

jboolean openmpt_hasEnded(JNIEnv* env);
void openmpt_clearAudioDisconnected(JNIEnv* env);
jboolean openmpt_setSequence(JNIEnv* env, jint seq);
/** Whether libopenmpt takes [bytes], filling [modInfo] with its title and type if so. */
jboolean openmpt_testData(JNIEnv* env, const unsigned char* bytes, size_t size, jobject modInfo);
jint openmpt_getPatternRows(JNIEnv* env, jint pat);
jint openmpt_getPlayer(JNIEnv* env, jint parm);
jint openmpt_loadFd(JNIEnv* env, jint fd, jobject modInfo);
/**
 * Loads from bytes already in memory.
 *
 * Exists because the descriptor can only be read once: with several decoders
 * to offer a file to, whoever reads it has to be the one that keeps it.
 */
jint openmpt_loadData(JNIEnv* env, const unsigned char* bytes, size_t size, jobject modInfo);
jint openmpt_mute(JNIEnv* env, jint chn, jint status);
/** Every channel as the module itself has it, and which those it mutes are: bit n for channel n. */
jlong openmpt_resetMutes(JNIEnv* env);
jint openmpt_nextPosition(JNIEnv* env);
jint openmpt_prevPosition(JNIEnv* env);
jint openmpt_releaseModule(JNIEnv* env);
jint openmpt_restartModule(JNIEnv* env);
jint openmpt_seek(JNIEnv* env, jint time);
jint openmpt_setPlayer(JNIEnv* env, jint parm, jint value);
jint openmpt_setPosition(JNIEnv* env, jint n);
jint openmpt_setResampler(JNIEnv* env, jint mode);
jint openmpt_stopModule(JNIEnv* env);
jint openmpt_time(JNIEnv* env);
jboolean openmpt_readVisualState(JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
std::vector<std::string> openmpt_formatList();
jstring openmpt_getVersion(JNIEnv* env);
void openmpt_getAudioStats(JNIEnv* env, jobject info);
void openmpt_getFrameInfo(JNIEnv* env, jobject frameInfo);
void openmpt_getModVars(JNIEnv* env, jobject modVars);
void openmpt_getPatternData(JNIEnv* env, jint pat, jint numRows, jbyteArray notes, jbyteArray instruments, jbyteArray fxType, jbyteArray fxParm, jbyteArray fx2Type, jbyteArray fx2Parm);
void openmpt_setLoopMode(JNIEnv* env, jboolean loop);
void openmpt_setGlobalPitch(JNIEnv* env, double semitones);
void openmpt_setGlobalTempo(JNIEnv* env, double percent);
/** Song time at the end of what has been rendered, in milliseconds - see the loop in decoder.cpp. */
double openmpt_render_position_ms();
void openmpt_setPlaying(JNIEnv* env, jboolean value);
void openmpt_freeContext(JNIEnv* env);
