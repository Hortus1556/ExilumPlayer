#pragma once
#include <jni.h>
#include <cstdint>

/**
 * The JNI glue the decoders of the PSF family share. What the app is told
 * about a song is the same whichever console plays it - a game's sound
 * driver, running: no patterns, one song to a file, its channels to show -
 * so each decoder says where its answers are, and the telling is done once.
 */
struct PsfSong {
  /** The emulator's name, which the app picks the file's settings by. */
  const char* engine;
  bool (*loaded)();
  const char* (*type)();
  const char* (*title)();
  const char* (*comment)();
  int (*channels)();
  /** The names the channel rows are labelled with, by the instrument a channel shows. */
  int (*voices)();
  const char* (*voiceName)(int index);
  bool (*durationKnown)();
  int (*durationMs)();
  int (*positionMs)();
  bool (*readVisual)(int32_t* scalars, int32_t* channels, int8_t* scopes, int scopeWidth);
  /**
   * For a song whose channels change as it plays: a number bumped when they
   * do, for the app to read them again. Null for channels that stay as they
   * were loaded.
   */
  int (*channelLayout)() = nullptr;
};

void psf_fillModVars(JNIEnv* env, jobject modVars, const PsfSong& song);
void psf_fillFrameInfo(JNIEnv* env, jobject frameInfo, const PsfSong& song);
jboolean psf_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth, const PsfSong& song);
