#!/bin/sh
# configure-android.sh <work dir> <arm64-v8a|armeabi-v7a>
#
# Configures the FFmpeg release unpacked in <work dir> (ffmpeg-<version>) for one Android ABI with the NDK's clang, as
# the audio-only build the player needs - the demuxers, parsers and decoders for the formats it plays and nothing
# else - in <work dir>/build-<abi>. See README.md beside this for the whole of it.
#
# ANDROID_NDK_HOME is the NDK, with forward slashes. On Windows this runs from Git Bash, with the NDK's own make.
set -e
WORK=$1
ABI=$2
: "${ANDROID_NDK_HOME:?where the NDK is}"
case "$(uname -s)" in
  MINGW*|MSYS*) HOST=windows-x86_64; EXE=.exe ;;
  Darwin) HOST=darwin-x86_64; EXE= ;;
  *) HOST=linux-x86_64; EXE= ;;
esac
BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin
case $ABI in
  arm64-v8a) ARCH=aarch64; TARGET=aarch64-linux-android26 ;;
  armeabi-v7a) ARCH=arm; TARGET=armv7a-linux-androideabi26 ;;
  *) echo "which ABI?"; exit 1 ;;
esac
cd "$WORK"
SRC=$(ls -d ffmpeg-[0-9]* | head -1)
# Its tests are compiled from files it makes here: a path both the shell and the compiler can read.
mkdir -p tmp build-$ABI
export TMPDIR="$(pwd -W 2>/dev/null || pwd)/tmp"
export TMP="$TMPDIR" TEMP="$TMPDIR"
cd build-$ABI

DEMUXERS="aac mp3 flac wav w64 ogg mov caf mpc mpc8 wv aiff"
DECODERS="mp3float mp2float mp1float flac alac aac aac_latm vorbis opus mpc7 mpc8 wavpack \
  pcm_u8 pcm_s8 pcm_s16le pcm_s16be pcm_s24le pcm_s24be pcm_s32le pcm_s32be pcm_f32le pcm_f32be pcm_f64le pcm_f64be \
  pcm_alaw pcm_mulaw adpcm_ms adpcm_ima_wav adpcm_ima_qt"
PARSERS="mpegaudio flac aac aac_latm vorbis opus"

args=""
for d in $DEMUXERS; do args="$args --enable-demuxer=$d"; done
for d in $DECODERS; do args="$args --enable-decoder=$d"; done
for p in $PARSERS; do args="$args --enable-parser=$p"; done

# The cross compiler stands in for the host's as well: configure wants one, and nothing built with it is ever run.
../$SRC/configure \
  --target-os=android --arch=$ARCH --enable-cross-compile \
  --cc="$BIN/clang$EXE --target=$TARGET" \
  --cxx="$BIN/clang++$EXE --target=$TARGET" \
  --host-cc="$BIN/clang$EXE --target=$TARGET" --host-ld="$BIN/clang$EXE --target=$TARGET" \
  --ar=$BIN/llvm-ar$EXE --nm=$BIN/llvm-nm$EXE --ranlib=$BIN/llvm-ranlib$EXE --strip=$BIN/llvm-strip$EXE \
  --disable-everything --disable-programs --disable-doc --disable-debug \
  --disable-avdevice --disable-avfilter --disable-swscale --disable-network \
  --disable-autodetect --enable-zlib --disable-asm \
  --enable-static --disable-shared --enable-pic \
  --enable-swresample \
  $args

# The NDK's make is a Windows program: it cannot follow the shell's own names for a drive.
case "$(uname -s)" in
  MINGW*|MSYS*) sed -i -E 's#(^|[ =])/([a-zA-Z])/#\1\2:/#g' Makefile ffbuild/config.mak ;;
esac
echo "configured $ABI: now run make in $WORK/build-$ABI"
