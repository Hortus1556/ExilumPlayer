# FFmpeg in the renderer

`renderer/src/main/cpp/ffmpeg` is not FFmpeg's whole tree. It is the part of a release that an audio-only build
reads - about 640 of its 9,000 files - together with what FFmpeg's `configure` generates for each Android ABI.
CMake compiles it like every other library there; nothing is prebuilt, and nothing in it is changed.

To move to another release, or to add a format:

1. Unpack the release (`ffmpeg-<version>.tar.xz` from <https://ffmpeg.org/releases/>) into a work directory
   outside the repository.
2. Edit the lists of demuxers, decoders and parsers in `configure-android.sh` if a format is being added.
3. For each ABI, configure and build - the build is what tells which files are needed:

   ```sh
   export ANDROID_NDK_HOME=C:/Users/you/AppData/Local/Android/Sdk/ndk/<version>
   sh tools/ffmpeg/configure-android.sh <work dir> arm64-v8a
   (cd <work dir>/build-arm64-v8a && $ANDROID_NDK_HOME/prebuilt/windows-x86_64/bin/make -j8)
   sh tools/ffmpeg/configure-android.sh <work dir> armeabi-v7a
   (cd <work dir>/build-armeabi-v7a && $ANDROID_NDK_HOME/prebuilt/windows-x86_64/bin/make -j8)
   ```

   On Windows this is run from Git Bash. On Linux or macOS use the system's `make`.
4. From the repository's root, copy in what the two builds read:

   ```sh
   python tools/ffmpeg/import.py <work dir>
   ```

   It replaces `renderer/src/main/cpp/ffmpeg` and writes `sources.cmake`, which `CMakeLists.txt` includes.
5. A new format also needs its extension in `formatGroups()` and, where its first bytes say what it is, in
   `ffmpeg_decoder_claims()` (`renderer/src/main/cpp/decoder.cpp`, `decoder-ffmpeg.cpp`).

The build is LGPL-2.1-or-later: no GPL or nonfree part of FFmpeg is enabled, and assembly is off - the decoders
are fast enough in C for sound, and the two ABIs then compile the same files.
