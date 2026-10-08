//
// JNI glue for the PSF decoder, kept apart from it as the others are.
//
#include "decoder-psf.h"
#include "psf-jni.h"

namespace {

const PsfSong kSong = {
    "aopsf",
    psf1_decoder_loaded,
    psf1_decoder_type,
    psf1_decoder_title,
    psf1_decoder_comment,
    psf1_decoder_channels,
    psf1_decoder_voice_kinds,
    psf1_decoder_voice_kind_name,
    psf1_decoder_duration_known,
    psf1_decoder_duration_ms,
    psf1_decoder_position_ms,
    psf1_decoder_read_visual,
};

}  // namespace

void psf1_fillModVars(JNIEnv* env, jobject modVars) { psf_fillModVars(env, modVars, kSong); }

void psf1_fillFrameInfo(JNIEnv* env, jobject frameInfo) { psf_fillFrameInfo(env, frameInfo, kSong); }

jboolean psf1_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  return psf_fillVisualState(env, scalars, chData, scopes, scopeWidth, kSong);
}
