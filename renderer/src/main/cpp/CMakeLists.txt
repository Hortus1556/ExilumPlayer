cmake_minimum_required(VERSION 3.4)

set(PROJECT_NAME "openmpt-jni")

project(${PROJECT_NAME} LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Local changes to libraries that are checked out from upstream rather than
# copied in, carried as patches and applied at configure time.
#
# A checkout that already has its patch is left alone: a restored cmake cache
# (.cxx) re-runs this against a tree patched on the previous run. --check can
# also pass and the real apply still fail - in CI, git apply --check runs with
# ERROR_QUIET so safe.directory or similar warnings are silenced and it
# returns 0, then the real apply surfaces the error - so a failed apply falls
# back to the same reverse check before giving up.
#
# The same happens on a first build from a clean checkout: the two ABIs are
# configured side by side, both find the patch applicable, and the second to
# apply it finds the first already has. What git says of that is kept, and
# shown only if the patch turns out not to be there after all.
function(apply_local_patch DIR PATCH LABEL)
    execute_process(
            COMMAND git apply --check --ignore-whitespace "${PATCH}"
            WORKING_DIRECTORY "${DIR}"
            RESULT_VARIABLE CHECK_RESULT
            OUTPUT_QUIET
            ERROR_QUIET
    )
    if (CHECK_RESULT EQUAL 0)
        execute_process(
                COMMAND git apply --ignore-whitespace "${PATCH}"
                WORKING_DIRECTORY "${DIR}"
                RESULT_VARIABLE APPLY_RESULT
                ERROR_VARIABLE APPLY_ERROR
        )
        if (APPLY_RESULT EQUAL 0)
            return()
        endif ()
    endif ()
    execute_process(
            COMMAND git apply --reverse --check --ignore-whitespace "${PATCH}"
            WORKING_DIRECTORY "${DIR}"
            RESULT_VARIABLE REVERSE_RESULT
            OUTPUT_QUIET
            ERROR_QUIET
    )
    if (NOT REVERSE_RESULT EQUAL 0)
        message(FATAL_ERROR "${LABEL} patch is neither applicable nor already applied
${APPLY_ERROR}")
    endif ()
endfunction()

# Add OpenMPT
apply_local_patch("${CMAKE_CURRENT_SOURCE_DIR}/openmpt"
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/openmpt-waveform-api.patch" "OpenMPT waveform API")

file(GLOB OPENMPT_COMMON CONFIGURE_DEPENDS openmpt/common/*.cpp)
file(GLOB OPENMPT_SOUNDLIB CONFIGURE_DEPENDS openmpt/soundlib/*.cpp)
file(GLOB OPENMPT_PLUGINS CONFIGURE_DEPENDS openmpt/soundlib/plugins/*.cpp)
file(GLOB OPENMPT_DMO CONFIGURE_DEPENDS openmpt/soundlib/plugins/dmo/*.cpp)
file(GLOB OPENMPT_SOUNDDSP CONFIGURE_DEPENDS openmpt/sounddsp/*.cpp)

add_library(openmpt_static STATIC
        ${OPENMPT_COMMON}
        openmpt/libopenmpt/libopenmpt_c.cpp
        openmpt/libopenmpt/libopenmpt_cxx.cpp
        openmpt/libopenmpt/libopenmpt_impl.cpp
        openmpt/libopenmpt/libopenmpt_ext_impl.cpp
        ${OPENMPT_SOUNDLIB}
        ${OPENMPT_PLUGINS}
        ${OPENMPT_DMO}
        ${OPENMPT_SOUNDDSP}
        # MO3 keeps its samples as MP3 or Ogg Vorbis, and without a decoder for
        # them libopenmpt loads those samples empty - the file opens and whole
        # channels play nothing. These two come with libopenmpt, for builds
        # without libmpg123 and libvorbis; the flags are the ones its own
        # Makefile uses for them. They also serve Ogg and MP3 samples in XM and IT.
        openmpt/include/minimp3/minimp3.c
        openmpt/include/stb_vorbis/stb_vorbis.c
)
target_include_directories(openmpt_static PUBLIC openmpt openmpt/src openmpt/common PRIVATE openmpt/include)
target_compile_definitions(openmpt_static PRIVATE LIBOPENMPT_BUILD MPT_WITH_ZLIB OPENMPT_VERSION_REVISION=0
        MPT_WITH_MINIMP3 MPT_WITH_STBVORBIS STB_VORBIS_NO_PULLDATA_API STB_VORBIS_NO_STDIO)
target_compile_options(openmpt_static PRIVATE "$<$<COMPILE_LANGUAGE:CXX>:-fexceptions;-frtti>" -Wno-deprecated-declarations)

# AHX / Hively replayer - three vendored C files, see hvl/README.md.
add_library(hvl_static STATIC hvl/replay.c)
target_include_directories(hvl_static PUBLIC hvl)
target_compile_options(hvl_static PRIVATE -Wno-absolute-value -Wno-unused-result)

# Furnace: .fur and .dmf, played by the tracker's own engine, headless - see
# decoder-furnace.h.
#
# Built from the engine source list in Furnace's own CMakeLists rather than
# through it: that project builds the tracker, and with its GUI, SDL and audio
# backends switched off what remains is still an executable. The list is read
# out of it so an update to the checkout brings its sources along.
set(FURNACE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/furnace")
apply_local_patch("${FURNACE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/patches/furnace-exilum.patch" "Furnace")

# Two of Furnace's own submodules are part of the engine. Not SDL, the third,
# which is large and only the tracker's.
if (NOT EXISTS "${FURNACE_DIR}/extern/fmt/src/format.cc" OR NOT EXISTS "${FURNACE_DIR}/extern/adpcm/oki_codec.c")
    execute_process(
            COMMAND git submodule update --init --depth 1 extern/fmt extern/adpcm
            WORKING_DIRECTORY "${FURNACE_DIR}"
            RESULT_VARIABLE FURNACE_SUBMODULES_RESULT
    )
    if (NOT FURNACE_SUBMODULES_RESULT EQUAL 0)
        message(FATAL_ERROR "Could not check out Furnace's fmt and adpcm submodules")
    endif ()
endif ()

file(READ "${FURNACE_DIR}/CMakeLists.txt" FURNACE_CMAKE_TEXT)
string(REGEX MATCH "set\\(ENGINE_SOURCES([^)]*)\\)" FURNACE_ENGINE_BLOCK "${FURNACE_CMAKE_TEXT}")
if (NOT FURNACE_ENGINE_BLOCK)
    message(FATAL_ERROR "No ENGINE_SOURCES list in Furnace's CMakeLists.txt")
endif ()
string(REGEX REPLACE "[ \t\r\n]+" ";" FURNACE_ENGINE_SOURCES "${CMAKE_MATCH_1}")
list(FILTER FURNACE_ENGINE_SOURCES INCLUDE REGEX ".+")
list(TRANSFORM FURNACE_ENGINE_SOURCES PREPEND "furnace/")

# Every core on that list is built, the OPL4's sample core (ymf278.cpp, from
# openMSX) included - a deliberate choice, unlike libvgm's copy of the same
# code below, which stays out; see the licence note there. So are Stella's TIA
# core, YM2608-LLE and Potator's Supervision core, which carry a GPLv2 licence
# text with no version statement of their own.
add_library(furnace_static STATIC
        ${FURNACE_ENGINE_SOURCES}
        # The audio layer's base classes, which the engine is written against;
        # the backends themselves are not built.
        furnace/src/audio/abstract.cpp
        furnace/src/audio/midi.cpp
        furnace/src/audio/pipe.cpp
        furnace/extern/fmt/src/format.cc
        furnace/extern/fmt/src/os.cc
)
target_include_directories(furnace_static PUBLIC
        furnace/src
        furnace/extern/fmt/include
        furnace/extern/blip_buf
        furnace/extern/IconFontCppHeaders
        furnace/src/icon
        furnace/extern/vgsound_emu-modified
)
# As Furnace builds it: C++14, and its loaders throw.
set_target_properties(furnace_static PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON)
target_compile_options(furnace_static PRIVATE -w "$<$<COMPILE_LANGUAGE:CXX>:-fexceptions;-frtti>"
        "SHELL:-include ${CMAKE_CURRENT_SOURCE_DIR}/furnace-symbols.h")
target_link_libraries(furnace_static PUBLIC z)

# C64 SID: libsidplayfp runs the machine and reSIDfp is the sound chip - see
# decoder-sid.h. Both GPL-2.0-or-later.
#
# Upstream builds with autotools, so the three headers it generates are made
# here, and the sources are listed by directory: the library, the C64, the
# tune loaders and the reSIDfp builder. Not the other builders, which are for
# hardware SIDs on USB, nor reSIDfp's resampler test program. The player code
# the C64 runs to start a tune is 6502 assembly that upstream's release tarball
# ships assembled, and sid-generated/ holds those files from 3.1.1.
set(RESIDFP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libresidfp")
set(SIDPLAYFP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libsidplayfp")
apply_local_patch("${RESIDFP_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/patches/libresidfp-exilum.patch" "libresidfp")
apply_local_patch("${SIDPLAYFP_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/patches/libsidplayfp-exilum.patch" "libsidplayfp")

set(SID_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/sid")
set(RESIDFP_BRANCH_HINTS 1)
set(HAVE_BUILTIN_EXPECT 1)
set(PACKAGE_VERSION 1.2.2)
set(RESIDFP_INLINING 1)
set(RESIDFP_INLINE inline)
configure_file(libresidfp/src/siddefs-fp.h.in "${SID_GENERATED}/siddefs-fp.h" @ONLY)
set(LIB_MAJOR 1)
set(LIB_MINOR 2)
set(LIB_LEVEL 2)
configure_file(libresidfp/src/residfp/sidversion.h.in "${SID_GENERATED}/residfp/sidversion.h" @ONLY)
set(LIB_MAJOR 3)
set(LIB_MINOR 1)
set(LIB_LEVEL 1)
configure_file(libsidplayfp/src/sidplayfp/sidversion.h.in "${SID_GENERATED}/sidplayfp/sidversion.h" @ONLY)

file(GLOB SIDPLAYFP_SOURCES CONFIGURE_DEPENDS
        libresidfp/src/*.cpp
        libsidplayfp/src/*.cpp
        libsidplayfp/src/c64/*.cpp
        libsidplayfp/src/c64/*/*.cpp
        libsidplayfp/src/sidplayfp/*.cpp
        libsidplayfp/src/sidtune/*.cpp
        libsidplayfp/src/builders/residfp-builder/*.cpp)
add_library(sidplayfp_static STATIC
        ${SIDPLAYFP_SOURCES}
        libresidfp/src/resample/SincResampler.cpp
        libresidfp/src/residfp/residfp.cpp
        libresidfp/src/version.cc
)
target_include_directories(sidplayfp_static PUBLIC
        "${SID_GENERATED}"
        sid-generated
        sid-generated/sidtune
        libresidfp/src
        libsidplayfp/src
        libsidplayfp/src/sidtune
        libsidplayfp/src/builders/residfp-builder
)
# Furnace carries its own, older and changed, copy of reSIDfp for its C64
# chip, under the same names. Two definitions of reSIDfp::SID in one library
# would have the linker pick between them symbol by symbol, so this copy's
# names are changed as it is compiled.
target_compile_definitions(sidplayfp_static PRIVATE
        VERSION="3.1.1" PACKAGE_NAME="libsidplayfp" PACKAGE_VERSION="3.1.1"
        PACKAGE_URL="https://github.com/libsidplayfp/libsidplayfp"
        HAVE_STRCASECMP=1 HAVE_STRNCASECMP=1 HAVE_CXX17 NDEBUG
        reSIDfp=exilumReSIDfp residfp_version_string=exilum_residfp_version_string)
target_compile_definitions(sidplayfp_static PUBLIC HAVE_CXX17)
target_compile_options(sidplayfp_static PRIVATE -w "$<$<COMPILE_LANGUAGE:CXX>:-fexceptions;-frtti>")

# Game Music Emu: NSF, NSFE, SPC, GBS, HES, KSS, SAP, AY, VGM, VGZ, GYM.
# Built from its own CMakeLists so its per-emulator options stay meaningful.
# Examples are off by default once it is not the top-level project, and
# BUILD_SHARED_LIBS is forced off so gme::gme resolves to the static target -
# everything here links into one .so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GME_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_UBSAN OFF CACHE BOOL "" FORCE)

# GME defaults its YM2612 to Nuked, which is cycle-accurate and far too slow
# to render in a callback on a phone - a VGM using FM would stall the stream
# rather than play. GENS is the fast one, and is LGPLv2.1+ like GME itself;
# MAME is the other alternative but is GPLv2+, which this app cannot take.
set(GME_YM2612_EMU "GENS" CACHE STRING "" FORCE)

# Three systems, not eleven.
#
# NES, SNES and Game Boy are what GME is kept for. Everything else is switched
# off at the library's own options, so those cores are not compiled at all and
# their types never appear in gme_type_list.
#
# VGM and GYM go with them: GME plays them against three chip cores, which is
# the Sega Genesis and nothing else, and libvgm plays them against fifty. PC
# Engine, MSX, Atari and ZX Spectrum are not systems this is meant to cover.
set(USE_GME_NSF ON CACHE BOOL "" FORCE)
set(USE_GME_NSFE ON CACHE BOOL "" FORCE)
set(USE_GME_SPC ON CACHE BOOL "" FORCE)
set(USE_GME_GBS ON CACHE BOOL "" FORCE)
set(USE_GME_VGM OFF CACHE BOOL "" FORCE)
set(USE_GME_AY OFF CACHE BOOL "" FORCE)
set(USE_GME_GYM OFF CACHE BOOL "" FORCE)
set(USE_GME_HES OFF CACHE BOOL "" FORCE)
set(USE_GME_KSS OFF CACHE BOOL "" FORCE)
set(USE_GME_SAP OFF CACHE BOOL "" FORCE)
add_subdirectory(gme EXCLUDE_FROM_ALL)

# libvgm: every chip a VGM can address, against every chip's own emulation.
#
# GME plays a VGM with three cores and so covers one machine; this covers the
# lot - Sega, NEC, MSX, the Yamaha FM family, and forty-odd arcade PCM and
# wavetable chips. Which is why USE_GME_VGM is off above.
#
# Only the emulation and the player are built. The audio output is Oboe's job,
# and the test programs are not ours to build.
set(BUILD_LIBAUDIO OFF CACHE BOOL "" FORCE)
set(BUILD_LIBEMU ON CACHE BOOL "" FORCE)
set(BUILD_LIBPLAYER ON CACHE BOOL "" FORCE)
set(BUILD_PLAYER OFF CACHE BOOL "" FORCE)
set(BUILD_VGM2WAV OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(USE_SANITIZERS OFF CACHE BOOL "" FORCE)

# Every device except the YMF278B, and the exception is a licence rather than
# a preference.
#
# This app is GPL-3.0-or-later. Nearly all of libvgm's cores are BSD, LGPL or
# GPL-2.0-*or-later*, and "or later" is what lets them into a GPLv3 work. The
# OPL4 core is marked GPL-2.0 with no "or later", which GPLv3 cannot take -
# and it has to be GPLv3 rather than v2 because Oboe, AndroidX, Compose and
# Media3 are Apache-2.0, which GPLv2 cannot take. So the one chip is dropped
# and everything else is built.
#
# Listed rather than switched on with SNDEMU__ALL, because that option
# overrides the individual ones and would pull the OPL4 back in.
set(SNDEMU__ALL OFF CACHE BOOL "" FORCE)
set(VGM_DEVICES
        SN76496 YM2413 YM2612 YM2151 SEGAPCM RF5C68 YM2203 YM2608 YM2610
        YM3812 YM3526 Y8950 YMF262 YMZ280B YMF271 AY8910 32X_PWM GAMEBOY
        NESAPU YMW258 UPD7759 MSM6258 MSM6295 K051649 K054539 C6280 C140
        C219 K053260 POKEY QSOUND SCSP WSWAN VBOY_VSU SAA1099 ES5503 ES5506
        X1_010 C352 GA20 MIKEY K007232 K005289 MSM5205 MSM5232 BSMT2000
        ICS2115)
foreach(device ${VGM_DEVICES})
    set(SNDEMU_${device}_ALL ON CACHE BOOL "" FORCE)
endforeach()

add_subdirectory(libvgm EXCLUDE_FROM_ALL)

# libbinio: the byte-order-aware I/O layer AdPlug reads files through.
#
# Configured here because upstream ships autotools only - binio.h is generated
# from binio.h.in, and these four substitutions are the whole of it. The target
# is named so AdPlug's own CMakeLists finds it (it checks for a "libbinio"
# target before falling back to find_package) and links libbinio::libbinio.
set(ENABLE_STRING 1)
set(ENABLE_IOSTREAM 1)
set(ISO_STDLIB 1)
set(WITH_MATH 1)
set(TYPE_INT "long long")
set(TYPE_FLOAT "long double")
configure_file(libbinio/src/binio.h.in "${CMAKE_CURRENT_BINARY_DIR}/binio/binio.h")

add_library(libbinio STATIC
        libbinio/src/binio.cpp
        libbinio/src/binfile.cpp
        libbinio/src/binstr.cpp
        libbinio/src/binwrap.cpp
)
target_include_directories(libbinio PUBLIC
        "${CMAKE_CURRENT_BINARY_DIR}/binio"
        "${CMAKE_CURRENT_SOURCE_DIR}/libbinio/src"
)
# Built as C++14: the library still declares locals 'register', which C++17
# removed. Only the .cpp files are affected - binio.h has none of it - so this
# does not reach anything that includes it.
set_target_properties(libbinio PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON)
add_library(libbinio::libbinio ALIAS libbinio)

# AdPlug: the OPL2/OPL3 formats - rad, hsc, lds, cmf, dro, imf, d00 and the
# rest. Its own CMakeLists builds every player; the options below turn off the
# parts that only make sense when it is the top-level project.
set(adplug_INCLUDE_TEST OFF CACHE BOOL "" FORCE)
set(adplug_INCLUDE_PACKAGING OFF CACHE BOOL "" FORCE)
set(libadplug_BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(ADPLUG_PRECOMPILED_HEADERS OFF CACHE BOOL "" FORCE)
add_subdirectory(adplug EXCLUDE_FROM_ALL)

# mGBA: the Game Boy Advance, for GSF. Only its core - the console without a
# screen, a debugger or files of its own - built from the lists its own
# CMakeLists keeps, without its configure step: the definitions below are
# the ones that step would arrive at for Android.
set(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/mgba")
# Its version file, filled in as its configure step would. In a function, so
# PROJECT_NAME and the rest are its own there and this project's stay ours.
function(mgba_version_file OUT)
    set(GIT_COMMIT "0.10.5")
    set(GIT_COMMIT_SHORT "0.10.5")
    set(GIT_BRANCH "0.10.5")
    set(GIT_REV -1)
    set(BINARY_NAME "mgba")
    set(PROJECT_NAME "mGBA")
    set(VERSION_STRING "0.10.5")
    configure_file("${MGBA_DIR}/src/core/version.c.in" "${OUT}")
endfunction()
mgba_version_file("${CMAKE_CURRENT_BINARY_DIR}/mgba-version.c")

set(MGBA_SOURCES
        arm/arm.c arm/decoder-arm.c arm/decoder.c arm/decoder-thumb.c arm/isa-arm.c arm/isa-thumb.c
        gb/audio.c
        gba/audio.c gba/bios.c gba/cart/ereader.c gba/cart/gpio.c gba/cart/matrix.c gba/cart/vfame.c
        gba/cheats.c gba/cheats/codebreaker.c gba/cheats/gameshark.c gba/cheats/parv3.c gba/core.c gba/dma.c
        gba/gba.c gba/hle-bios.c gba/input.c gba/io.c gba/memory.c gba/overrides.c
        gba/renderers/cache-set.c gba/renderers/common.c gba/renderers/software-bg.c
        gba/renderers/software-mode0.c gba/renderers/software-obj.c gba/renderers/video-software.c
        gba/savedata.c gba/serialize.c gba/sharkport.c gba/sio.c gba/sio/gbp.c gba/sio/joybus.c gba/timer.c
        gba/video.c
        core/bitmap-cache.c core/cache-set.c core/cheats.c core/config.c core/core.c core/directories.c
        core/input.c core/interface.c core/library.c core/lockstep.c core/log.c core/map-cache.c
        core/mem-search.c core/rewind.c core/serialize.c core/sync.c core/thread.c core/tile-cache.c
        core/timing.c
        util/circle-buffer.c util/configuration.c util/crc32.c util/formatting.c util/gbk-table.c util/hash.c
        util/string.c util/table.c util/vfs.c util/convolve.c util/patch.c util/patch-fast.c util/patch-ips.c
        util/patch-ups.c util/ring-fifo.c util/text-codec.c
        util/vfs/vfs-mem.c util/vfs/vfs-fifo.c util/vfs/vfs-fd.c util/vfs/vfs-dirent.c
        platform/posix/memory.c
        third-party/blip_buf/blip_buf.c third-party/inih/ini.c)
list(TRANSFORM MGBA_SOURCES PREPEND "${MGBA_DIR}/src/")
add_library(mgba_static STATIC ${MGBA_SOURCES} "${CMAKE_CURRENT_BINARY_DIR}/mgba-version.c")
target_include_directories(mgba_static PUBLIC "${MGBA_DIR}/include" PRIVATE "${MGBA_DIR}/src")
# Its blip_buf under names of its own. Furnace carries blip_buf too, with
# blip_add_delta as a pointer to the function rather than the function, and
# with both linked in mGBA's calls went to Furnace's pointer - and on into
# data, which crashed. Renamed for mGBA and for the decoder that calls it.
set(MGBA_BLIP_RENAMES)
foreach (name new set_rates clear add_delta add_delta_fast clocks_needed end_frame samples_avail
        read_samples delete)
    list(APPEND MGBA_BLIP_RENAMES "blip_${name}=mgba_blip_${name}")
endforeach ()
# Public: the structures the decoder reads are laid out by these.
target_compile_definitions(mgba_static PUBLIC M_CORE_GBA MINIMAL_CORE=2 DISABLE_THREADING MGBA_STANDALONE
        PRIVATE HAVE_STRDUP HAVE_STRNDUP HAVE_VASPRINTF HAVE_LOCALTIME_R HAVE_STRLCPY HAVE_LOCALE HAVE_STRTOF_L
        # zlib's CRC-32 rather than one of its own under the same name, which
        # would have stood in for zlib's everywhere else in the library too.
        HAVE_CRC32 ${MGBA_BLIP_RENAMES})
target_compile_options(mgba_static PRIVATE -std=gnu11 -Wno-everything)
target_link_libraries(mgba_static PRIVATE z)
set_source_files_properties(decoder-gsf.cpp PROPERTIES COMPILE_DEFINITIONS "${MGBA_BLIP_RENAMES}")

# vio2sf: a Nintendo DS for 2SF files - the DeSmuME core as Christopher
# Snowhill cut it down for playing them, with neither screen drawn. A copy
# rather than a submodule: what the player needs of it is in the core itself.
# See vio2sf/ANDROID-CHANGES.md.
set(VIO2SF_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vio2sf/desmume")
set(VIO2SF_SOURCES)
foreach (name FIFO GPU MMU NDSSystem arm_instructions armcpu barray bios cp15 isqrt matrix mc resampler state
        thumb_instructions)
    list(APPEND VIO2SF_SOURCES "${VIO2SF_DIR}/${name}.c")
endforeach ()
add_library(vio2sf_static STATIC ${VIO2SF_SOURCES} "${VIO2SF_DIR}/SPU.cpp")
# Its checks are in the mixer's inner loop, and one of them is on a value the
# decoder already holds to its range.
target_compile_definitions(vio2sf_static PRIVATE NDEBUG)
target_compile_options(vio2sf_static PRIVATE -Wno-everything "$<$<COMPILE_LANGUAGE:C>:-std=gnu11>")

# lazyusf2: a Nintendo 64 for USF files - Mupen64Plus cut down to the
# console's sound, with its two RSP cores: the sound code redone by the
# emulator (HLE) and the processor itself (rsp_lle). A copy rather than a
# submodule, as vio2sf. See lazyusf2/ANDROID-CHANGES.md.
set(LAZYUSF2_DIR "${CMAKE_CURRENT_SOURCE_DIR}/lazyusf2")
set(LAZYUSF2_SOURCES)
foreach (name ai/ai_controller api/callbacks main/main main/rom main/savestates main/util memory/memory
        pi/cart_rom pi/pi_controller r4300/cached_interp r4300/cp0 r4300/cp1 r4300/exception r4300/interupt
        r4300/mi_controller r4300/pure_interp r4300/r4300 r4300/r4300_core r4300/recomp r4300/reset r4300/tlb
        rdp/rdp_core ri/rdram ri/rdram_detection_hack ri/ri_controller rsp/rsp_core rsp_hle/alist
        rsp_hle/alist_audio rsp_hle/alist_naudio rsp_hle/alist_nead rsp_hle/audio rsp_hle/cicx105 rsp_hle/hle
        rsp_hle/hvqm rsp_hle/jpeg rsp_hle/memory rsp_hle/mp3 rsp_hle/musyx rsp_hle/plugin rsp_hle/re2 rsp_lle/rsp
        si/cic si/game_controller si/n64_cic_nus_6105 si/pif si/si_controller usf/usf usf/barray usf/resampler
        vi/vi_controller)
    list(APPEND LAZYUSF2_SOURCES "${LAZYUSF2_DIR}/${name}.c")
endforeach ()
add_library(lazyusf2_static STATIC ${LAZYUSF2_SOURCES})
target_include_directories(lazyusf2_static PRIVATE ${LAZYUSF2_DIR})
# Its names under a prefix of their own - see lazyusf2-symbols.h. The
# resampler and the bit arrays have hooks for it in their headers, which the
# decoder includes with the same.
target_compile_definitions(lazyusf2_static PRIVATE NDEBUG RESAMPLER_DECORATE=USF BARRAY_DECORATE=USF)
# It reads halves of 64-bit registers through other types, and counts on
# signed overflow wrapping.
target_compile_options(lazyusf2_static PRIVATE -std=gnu11 -Wno-everything -fwrapv -fno-strict-aliasing
        -include "${CMAKE_CURRENT_SOURCE_DIR}/lazyusf2-symbols.h")
target_link_libraries(lazyusf2_static PRIVATE z m)

# aopsf: a PlayStation for PSF files - Audio Overload's console as Christopher
# Snowhill keeps it, its BIOS done over by the emulator, with the sound unit
# of Neill Corlett's Highly Experimental. The MAME processor it came with is
# not here: r3000.c is this player's own. A copy rather than a submodule, as
# vio2sf. See aopsf/ANDROID-CHANGES.md.
set(AOPSF_DIR "${CMAKE_CURRENT_SOURCE_DIR}/aopsf")
set(AOPSF_SOURCES)
foreach (name eng_psf psx_hw r3000 spu/spu spu/spucore)
    list(APPEND AOPSF_SOURCES "${AOPSF_DIR}/${name}.c")
endforeach ()
add_library(aopsf_static STATIC ${AOPSF_SOURCES})
target_include_directories(aopsf_static PRIVATE ${AOPSF_DIR} ${AOPSF_DIR}/spu)
target_compile_definitions(aopsf_static PRIVATE NDEBUG LSB_FIRST=1 EMU_COMPILE EMU_LITTLE_ENDIAN)
# It reads the console's memory and the sound unit's through other types, and
# its envelopes and its reverb count on signed overflow wrapping.
target_compile_options(aopsf_static PRIVATE -std=gnu11 -Wno-everything -fwrapv -fno-strict-aliasing)

# FFmpeg: the decoders for music that is sound and not a program - MP3, FLAC,
# AAC, ALAC, Vorbis, Opus, Musepack, WavPack, WAV - with the demuxers for the
# files they come in. A fifteenth of the release, copied in by
# tools/ffmpeg/import.py together with what its configure made for each ABI;
# nothing in it is changed. Its four libraries are built apart, as its own
# build does: each is told which one it is (BUILDING_<name>).
set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg")
include("${FFMPEG_DIR}/sources.cmake")
foreach (lib avutil avcodec avformat swresample)
    string(TOUPPER ${lib} LIB)
    set(sources)
    foreach (source ${FFMPEG_${LIB}_SOURCES})
        list(APPEND sources "${FFMPEG_DIR}/${source}")
    endforeach ()
    if (lib STREQUAL avutil)
        foreach (source ${FFMPEG_COMPAT_SOURCES})
            list(APPEND sources "${FFMPEG_DIR}/${source}")
        endforeach ()
    endif ()
    add_library(ffmpeg_${lib} STATIC ${sources})
    # config.h and the generated lists first, then the tree: the same for
    # whoever includes its headers.
    target_include_directories(ffmpeg_${lib} PUBLIC "${FFMPEG_DIR}/config/${ANDROID_ABI}" "${FFMPEG_DIR}")
    # What its configure hands the compiler (ffbuild/config.mak).
    target_compile_definitions(ffmpeg_${lib} PRIVATE HAVE_AV_CONFIG_H BUILDING_${lib} _ISOC11_SOURCE
            _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE strtod=avpriv_strtod PIC ZLIB_CONST)
    target_compile_options(ffmpeg_${lib} PRIVATE -std=c17 -O3 -fomit-frame-pointer -fno-math-errno
            -fno-signed-zeros -Wno-everything)
endforeach ()
target_link_libraries(ffmpeg_avformat PRIVATE ffmpeg_avcodec ffmpeg_avutil z m)
target_link_libraries(ffmpeg_avcodec PRIVATE ffmpeg_swresample ffmpeg_avutil z m)
target_link_libraries(ffmpeg_swresample PRIVATE ffmpeg_avutil m)
target_link_libraries(ffmpeg_avutil PRIVATE z m)

# Find Oboe package from gradle import
find_package(oboe REQUIRED CONFIG)

# All required libs
add_library(${PROJECT_NAME} SHARED
        player-jni.cpp
        decoder.cpp
        decoder-gme.cpp
        decoder-gme-jni.cpp
        decoder-hvl.cpp
        decoder-hvl-jni.cpp
        decoder-adplug.cpp
        decoder-adplug-jni.cpp
        decoder-vgm.cpp
        decoder-vgm-jni.cpp
        decoder-furnace.cpp
        decoder-furnace-jni.cpp
        decoder-sid.cpp
        decoder-sid-jni.cpp
        decoder-cm.cpp
        decoder-cm-jni.cpp
        decoder-gsf.cpp
        decoder-gsf-jni.cpp
        decoder-2sf.cpp
        decoder-2sf-jni.cpp
        decoder-usf.cpp
        decoder-usf-jni.cpp
        decoder-psf.cpp
        decoder-psf-jni.cpp
        decoder-dsp.cpp
        decoder-dsp-jni.cpp
        decoder-ffmpeg.cpp
        decoder-ffmpeg-jni.cpp
        gc-codecs.cpp
        gc-formats.cpp
        psf-jni.cpp
        psf.cpp
        custommade/custommade.cpp
        vgm/strutils-android.c
        gzip.cpp
        player-common.cpp
        player-libopenmpt.cpp
        oboe.cpp
        spectrum.cpp
        soundcheck.cpp
        loudness.cpp
        effects.cpp
        fx-chorus.cpp
        fx-equalizer.cpp
        fx-reverb.cpp
        fx-surround.cpp
)

# Link libraries
target_link_libraries(${PROJECT_NAME} openmpt_static gme::gme hvl_static adplug
        vgm-player vgm-emu vgm-utils furnace_static sidplayfp_static mgba_static vio2sf_static lazyusf2_static aopsf_static
        ffmpeg_avformat ffmpeg_avcodec ffmpeg_swresample ffmpeg_avutil oboe::oboe android log z)

# Include directories
target_include_directories(${PROJECT_NAME} PRIVATE openmpt openmpt/src openmpt/common gme libvgm
        ${CMAKE_CURRENT_SOURCE_DIR})
