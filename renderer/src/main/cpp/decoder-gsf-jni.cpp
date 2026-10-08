//
// JNI glue for the GSF decoder, kept apart from it as the others are.
//
#include "decoder-gsf.h"
#include "psf-jni.h"

namespace {

const PsfSong kSong = {
    "mGBA",
    gsf_decoder_loaded,
    gsf_decoder_type,
    gsf_decoder_title,
    gsf_decoder_comment,
    gsf_decoder_channels,
    gsf_decoder_channels,
    gsf_decoder_channel_name,
    gsf_decoder_duration_known,
    gsf_decoder_duration_ms,
    gsf_decoder_position_ms,
    gsf_decoder_read_visual,
};

}  // namespace

void gsf_fillModVars(JNIEnv* env, jobject modVars) { psf_fillModVars(env, modVars, kSong); }

void gsf_fillFrameInfo(JNIEnv* env, jobject frameInfo) { psf_fillFrameInfo(env, frameInfo, kSong); }

jboolean gsf_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  return psf_fillVisualState(env, scalars, chData, scopes, scopeWidth, kSong);
}
