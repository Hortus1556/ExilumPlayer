//
// JNI glue for the GameCube DSP decoder, kept apart from it as the others are.
//
#include "decoder-dsp.h"
#include "psf-jni.h"

namespace {

const PsfSong kSong = {
    "GameCube streams",
    dsp_decoder_loaded,
    dsp_decoder_type,
    dsp_decoder_title,
    dsp_decoder_comment,
    dsp_decoder_channels,
    dsp_decoder_channels,
    dsp_decoder_channel_name,
    dsp_decoder_duration_known,
    dsp_decoder_duration_ms,
    dsp_decoder_position_ms,
    dsp_decoder_read_visual,
};

}  // namespace

void dsp_fillModVars(JNIEnv* env, jobject modVars) { psf_fillModVars(env, modVars, kSong); }

void dsp_fillFrameInfo(JNIEnv* env, jobject frameInfo) { psf_fillFrameInfo(env, frameInfo, kSong); }

jboolean dsp_fillVisualState(
    JNIEnv* env, jintArray scalars, jintArray chData, jbyteArray scopes, jint scopeWidth) {
  return psf_fillVisualState(env, scalars, chData, scopes, scopeWidth, kSong);
}
