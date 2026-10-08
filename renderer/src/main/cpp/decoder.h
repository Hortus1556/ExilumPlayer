#pragma once
#include <jni.h>
#include <cstddef>
#include <cstdint>

/**
 * Which decoder owns what is currently loaded.
 *
 * The app was one decoder deep - every JNI entry point called straight into
 * libopenmpt - and most of the formats worth adding are not trackers at all.
 * A SID or an NSF is CPU code for a machine that no longer exists; a VGM is a
 * log of chip register writes. They have no patterns, often no samples, and
 * nothing libopenmpt could be taught to read.
 *
 * So the module-level entry points route through here instead. Audio-level
 * ones (opening the stream, starting and stopping it, buffer statistics) do
 * not: those belong to the output, not to whatever is feeding it, and they
 * stay shared.
 */
enum class EngineKind {
  None,
  OpenMpt,
  Gme,
  Hvl,
  AdPlug,
  Vgm,
  Furnace,
  Sid,
  CustomMade,
  Gsf,
  TwoSf,
  Usf,
  Psf1,
  Dsp,
  Ffmpeg,
};

/** The decoder holding the current module, or None when nothing is loaded. */
EngineKind engine_kind();

/**
 * Whether the loaded format has patterns to show.
 *
 * False for the register-log and CPU-dump formats, where PatternView has
 * nothing to draw and is dropped from the player rather than shown empty.
 */
bool engine_has_patterns();

// ---------------------------------------------------------------------------
// Module lifetime
//
// Loading takes the bytes rather than the descriptor: with several decoders to
// try, a file read per attempt would mean re-reading it once per format that
// says no. It is read once here and offered around.
// ---------------------------------------------------------------------------

// [filename] is the file's own name, not a path to open - the descriptor is
// still where the bytes come from. AdPlug needs it: several of its players
// decide whether a file is theirs partly by its extension, and with a few
// dozen formats sharing no magic number that check is load-bearing.
jint engine_loadFd(JNIEnv* env, jint fd, jstring filename, jobject modInfo);
jboolean engine_testFd(JNIEnv* env, jint fd, jstring filename, jobject modInfo);
jint engine_releaseModule(JNIEnv* env);
jint engine_restartModule(JNIEnv* env);
jint engine_stopModule(JNIEnv* env);
jboolean engine_hasEnded(JNIEnv* env);

// ---------------------------------------------------------------------------
// What the views read
// ---------------------------------------------------------------------------

void engine_getModVars(JNIEnv* env, jobject modVars);
void engine_getFrameInfo(JNIEnv* env, jobject frameInfo);
jboolean engine_readVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth);
void engine_getPatternData(
    JNIEnv* env, jint pat, jint numRows, jbyteArray notes, jbyteArray instruments,
    jbyteArray fxType, jbyteArray fxParm, jbyteArray fx2Type, jbyteArray fx2Parm);
jint engine_getPatternRows(JNIEnv* env, jint pat);

// ---------------------------------------------------------------------------
// Transport and per-module settings
// ---------------------------------------------------------------------------

/**
 * Seeks to [time] ms. [ticket] is the player's name for this seek: see
 * engine_setSeekWanted.
 */
jint engine_seek(JNIEnv* env, jint time, jint ticket);
/**
 * The ticket of the latest thing asked of the tune - a seek, or whatever
 * takes a seek's place. One being carried out under any other ticket has
 * been overtaken, and stops where it can (see seek-ticket.h). Waits on
 * nothing: it is called from the thread that would otherwise be kept waiting.
 */
void engine_setSeekWanted(jint ticket);
jint engine_time(JNIEnv* env);
jint engine_setPosition(JNIEnv* env, jint n);
jint engine_nextPosition(JNIEnv* env);
jint engine_prevPosition(JNIEnv* env);
jboolean engine_setSequence(JNIEnv* env, jint seq);
jint engine_mute(JNIEnv* env, jint chn, jint status);
/**
 * Every channel back as the file loaded has it, the listener's mutes gone:
 * playing, but for the channels a module was saved with switched off, which
 * are returned - bit n for channel n.
 */
jlong engine_resetMutes(JNIEnv* env);
void engine_setLoopMode(JNIEnv* env, jboolean loop);
void engine_setGlobalPitch(JNIEnv* env, jint semitones);
void engine_setGlobalTempo(JNIEnv* env, jint percent);
void engine_setLoopRegion(JNIEnv* env, jint startMs, jint endMs);
void engine_setVolumeBoost(JNIEnv* env, jint db);
void engine_setDurationOverride(JNIEnv* env, jint ms);
void engine_setPlaying(JNIEnv* env, jboolean value);
jint engine_setPlayer(JNIEnv* env, jint parm, jint value);
jint engine_getPlayer(JNIEnv* env, jint parm);
jint engine_setResampler(JNIEnv* env, jint mode);

// ---------------------------------------------------------------------------
// The audio session
//
// One stream, shared by every decoder, owned by none of them. These used to
// live in libopenmpt's file and carry its name, which is why it ended up
// registering its own render callback whether or not it was the thing
// playing - and why every other decoder was a guest in its house.
// ---------------------------------------------------------------------------

jboolean engine_init(JNIEnv* env, jint rate, jint ms, jint mode, jint channels, jint api, jint flags);
jint engine_deinit(JNIEnv* env);
jint engine_endPlayer(JNIEnv* env);
jint engine_playAudio(JNIEnv* env);

/**
 * Readies the decoder that owns playback, points the stream at it, starts it.
 *
 * Which decoder that is is decided by whichever one accepted the file, not by
 * which one is compiled first.
 */
jint engine_startPlayer(JNIEnv* env, jint rate, jint format);

/**
 * The Hively replayer's two settings.
 *
 * Taken whether or not an AHX is loaded: these are preferences, and one
 * changed while a tracker plays has to be waiting for the next .ahx.
 */
void engine_setHivelyStereo(JNIEnv* env, jint percent);
void engine_setHivelyGain(JNIEnv* env, jint percent);

/** AdPlug's, taken on the same terms. */
void engine_setAdPlugCore(JNIEnv* env, jint core);
void engine_setAdPlugGain(JNIEnv* env, jint percent);

/**
 * How long GME plays a track whose file gives no length, in seconds.
 *
 * Only GME's, because it is the only decoder here playing formats that have no
 * end - see gme_decoder_set_default_length_ms.
 */
void engine_setGmeDefaultLength(JNIEnv* env, jint seconds);

/** The Game Boy Advance's: its stereo spread, 0 to 200, its low-pass filter, and each channel's pan. */
void engine_setGsfStereo(JNIEnv* env, jint percent);
void engine_setGsfLowpass(JNIEnv* env, jboolean on);
void engine_setGsfPan(JNIEnv* env, jint channel, jint value);

/** The Nintendo DS's: its stereo spread, 0 to 200, how its recordings are interpolated, and each voice's pan. */
void engine_setTwoSfStereo(JNIEnv* env, jint percent);
void engine_setTwoSfInterpolation(JNIEnv* env, jint mode);
void engine_setTwoSfPan(JNIEnv* env, jint channel, jint value);

/** The Nintendo 64's: its stereo spread, 0 to 200, and whether its sound code is done over by the emulator (HLE). */
void engine_setUsfStereo(JNIEnv* env, jint percent);
void engine_setUsfHle(JNIEnv* env, jboolean on);

/** The PlayStation's: its stereo spread, 0 to 200, and whether its sound unit's reverb is heard. */
void engine_setPsfStereo(JNIEnv* env, jint percent);
void engine_setPsfReverb(JNIEnv* env, jboolean on);

/** The GameCube's: its stereo spread, 0 to 200. */
void engine_setDspStereo(JNIEnv* env, jint percent);

/** Recorded music's: the same, for MP3, FLAC and the rest. */
void engine_setAudioStereo(JNIEnv* env, jint percent);

/**
 * The words of the file playing, as it carries them - timed lines with
 * their times in front, or plain ones - or null when it has none, or is not
 * a kind of file that carries any.
 */
jstring engine_getLyrics(JNIEnv* env);

/**
 * The console settings, one machine at a time.
 *
 * console is a Player.kt GmeConsole; the pan is per voice and the echo and
 * interpolation are the SNES DSP's. See decoder-gme.h.
 */
void engine_setGmeVolume(JNIEnv* env, jint console, jint percent);
void engine_setGmeStereo(JNIEnv* env, jint console, jint percent);
void engine_setGmePan(JNIEnv* env, jint console, jint voice, jint pan);
void engine_setGmeEcho(JNIEnv* env, jboolean enabled);
void engine_setGmeInterpolation(JNIEnv* env, jint mode);

/**
 * Measures the loudness of [subsong] of the file on [fd], for Sound Check: the
 * file loaded and played through, once, for at most [maxSeconds] of audio and
 * [budgetMs] of time when that is above zero, into a BS.1770 meter.
 *
 * Meant for a process of its own. It loads the file into the same decoders
 * playback uses, and there is only one of each per process - so it runs apart
 * from the player, where it can take whatever it takes without anything being
 * heard. Leaves the file loaded, for its ModVars to be read.
 *
 * [out] receives the integrated loudness in LUFS (-100 for silence), the
 * seconds of audio analysed, the seconds that passed the gates, and 1 when
 * the track ended within the limit. Returns 0, or the load's error.
 */
jint engine_analyzeLoudness(JNIEnv* env, jint fd, jstring filename, jint subsong, jint maxSeconds, jint budgetMs,
                            jobject modInfo, jfloatArray out);

/** Every extension any decoder here can open, sorted and without repeats. */
jobjectArray engine_getFormats(JNIEnv* env);

/**
 * The same extensions by decoder, in the order a file is offered to them:
 * one String[] each, the decoder's id first and its sorted extensions after.
 */
jobjectArray engine_getFormatGroups(JNIEnv* env);

/**
 * Tells the active decoder what rate the output stream ended up at.
 *
 * Called from startPlayer, which is the first moment the rate is known - the
 * file is already loaded by then. libopenmpt takes a rate per render call and
 * does not care; the emulators fix theirs when they are built, and re-open.
 */
void engine_setSampleRate(jint rate);
