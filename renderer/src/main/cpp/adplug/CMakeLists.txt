cmake_minimum_required(VERSION 3.16..3.25)

list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

###
### Project
###

project(adplug VERSION 2.3.3)

if(CMAKE_VERSION VERSION_LESS 3.21)
  # Set "top level" if the current CMake is too old to do so in project().
  string(COMPARE EQUAL "${CMAKE_SOURCE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}" adplug_IS_TOP_LEVEL)
endif()

###
### C++ Language
###

if(NOT DEFINED CMAKE_CXX_STANDARD)
  set(CMAKE_CXX_STANDARD 17)
endif()
if(NOT DEFINED CMAKE_CXX_STANDARD_REQUIRED)
  set(CMAKE_CXX_STANDARD_REQUIRED ON)
endif()
if(NOT DEFINED CMAKE_CXX_STANDARD)
  set(CMAKE_CXX_STANDARD OFF)
endif()

add_compile_definitions($<$<CONFIG:Debug>:DEBUG>)

option(ADPLUG_PRECOMPILED_HEADERS "Use precompiled headers" ON)
if(ADPLUG_PRECOMPILED_HEADERS)
    message(STATUS "Enabling precompiled headers")
endif()

if(MSVC)
  # Tell MSVC to use UTF-8 when reading the source code and when generating the
  # object file.  Otherwise, both the compiler's input and output can vary
  # depending on the current user code page.  (MSVC always uses UTF-8
  # internally.)
  # https://learn.microsoft.com/en-us/cpp/build/reference/utf-8-set-source-and-executable-character-sets-to-utf-8
  add_compile_options("/utf-8")

  set(CMAKE_DEBUG_POSTFIX "d")
endif()

###
### Library type
###

if(DEFINED adplug_BUILD_SHARED_LIBS)
  set(BUILD_SHARED_LIBS "${adplug_BUILD_SHARED_LIBS}")
endif ()

if(BUILD_SHARED_LIBS AND NOT DEFINED CMAKE_INTERPROCEDURAL_OPTIMIZATION)
  message(STATUS "Enabling interprocedural optimization")
  set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)
  set(CMAKE_INTERPROCEDURAL_OPTIMIZATION_DEBUG OFF)
endif()

if(NOT TARGET libbinio)
  find_package(libbinio REQUIRED CONFIG)
endif()

###
### Configuration checks
###

include(CheckCXXSymbolExists)

check_cxx_symbol_exists(_stricmp "string.h" HAVE_STRICMP)
check_cxx_symbol_exists(strcasecmp "strings.h" HAVE_STRCASECMP)

###
### Core
###

add_subdirectory(doc)
add_subdirectory(src)

# Local change: the test corpus and the packaging rules are not vendored -
# the first is forty-six megabytes of music files and the second builds
# distribution archives, and this tree exists to be compiled into one .so.
# See ANDROID-CHANGES.md.
