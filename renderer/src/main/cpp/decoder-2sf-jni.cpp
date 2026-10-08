//
// JNI glue for the 2SF decoder, kept apart from it as the others are.
//
#include "decoder-2sf.h"
#include "psf-jni.h"

namespace {

const PsfSong kSong = {
    "vio2sf",
    twosf_decoder_loaded,
    twosf_decoder_type,
    twosf_decoder_title,
    twosf_decoder_comment,
    twosf_decoder_channels,
    twosf_decoder_voice_kinds,
    twosf_decoder_voice_kind_name,
    twosf_decoder_duration_known,
    twosf_decoder_duration_ms,
    twosf_decoder_position_ms,
    twosf_decoder_read_visual,
};

}  // namespace

void twosf_fillModVars(JNIEnv* env, jobject modVars) { psf_fillModVars(env, modVars, kSong); }

void twosf_fillFrameInfo(JNIEnv* env, jobject frameInfo) { psf_fillFrameInfo(env, frameInfo, kSong); }

jboolean twosf_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  return psf_fillVisualState(env, scalars, chData, scopes, scopeWidth, kSong);
}
