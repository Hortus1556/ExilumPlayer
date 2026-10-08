//
// JNI glue for the USF decoder, kept apart from it as the others are.
//
#include "decoder-usf.h"
#include "psf-jni.h"

namespace {

const PsfSong kSong = {
    "lazyusf2",
    usf_decoder_loaded,
    usf_decoder_type,
    usf_decoder_title,
    usf_decoder_comment,
    usf_decoder_channels,
    usf_decoder_channel_names,
    usf_decoder_channel_name,
    usf_decoder_duration_known,
    usf_decoder_duration_ms,
    usf_decoder_position_ms,
    usf_decoder_read_visual,
    usf_decoder_channel_layout,
};

}  // namespace

void usf_fillModVars(JNIEnv* env, jobject modVars) { psf_fillModVars(env, modVars, kSong); }

void usf_fillFrameInfo(JNIEnv* env, jobject frameInfo) { psf_fillFrameInfo(env, frameInfo, kSong); }

jboolean usf_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  return psf_fillVisualState(env, scalars, chData, scopes, scopeWidth, kSong);
}
