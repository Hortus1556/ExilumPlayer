//
// JNI glue for the FFmpeg decoder, kept apart from it as the others are.
//
#include "decoder-ffmpeg.h"
#include "psf-jni.h"

namespace {

const PsfSong kSong = {
    "FFmpeg",
    ffmpeg_decoder_loaded,
    ffmpeg_decoder_type,
    ffmpeg_decoder_title,
    ffmpeg_decoder_comment,
    ffmpeg_decoder_channels,
    ffmpeg_decoder_channels,
    ffmpeg_decoder_channel_name,
    ffmpeg_decoder_duration_known,
    ffmpeg_decoder_duration_ms,
    ffmpeg_decoder_position_ms,
    ffmpeg_decoder_read_visual,
};

}  // namespace

void ffmpeg_fillModVars(JNIEnv* env, jobject modVars) { psf_fillModVars(env, modVars, kSong); }

void ffmpeg_fillFrameInfo(JNIEnv* env, jobject frameInfo) { psf_fillFrameInfo(env, frameInfo, kSong); }

jboolean ffmpeg_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  return psf_fillVisualState(env, scalars, chData, scopes, scopeWidth, kSong);
}
