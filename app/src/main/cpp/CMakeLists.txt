cmake_minimum_required(VERSION 3.22)
project(crosstune_milkdrop LANGUAGES C CXX)

# Builds the same bytes wherever the source is checked out, so the release APK stays reproducible.
set(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
get_filename_component(PROJECT_ROOT "${PROJECT_ROOT}" ABSOLUTE)
add_compile_options("-ffile-prefix-map=${PROJECT_ROOT}=." "-fdebug-prefix-map=${PROJECT_ROOT}=.")
# The NDK's headers too: their path ends up in the debug info, which the library's build ID hashes,
# and the NDK sits in a different place on GitHub's runners and on F-Droid's build server.
add_compile_options("-ffile-prefix-map=${ANDROID_NDK}=/ndk" "-fdebug-prefix-map=${ANDROID_NDK}=/ndk")

# projectM's expression parser comes with its generated sources; flex or bison on the machine would
# make them again, differently from one machine to the next.
set(CMAKE_DISABLE_FIND_PACKAGE_FLEX TRUE)
set(CMAKE_DISABLE_FIND_PACKAGE_BISON TRUE)
# projectM stores the current Git commit, which the app never reads; without Git it stores "Unknown",
# so the library doesn't depend on the commit or on the checkout having Git history.
set(CMAKE_DISABLE_FIND_PACKAGE_Git TRUE)

# projectM as one static library inside ours, with only what drawing presets needs.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_PLAYLIST OFF CACHE BOOL "" FORCE)
set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_GLM OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
set(ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
# projectM takes its version from the top project, which is this one, so it's read from its own.
file(STRINGS "${PROJECT_ROOT}/third_party/projectm/CMakeLists.txt" PROJECTM_VERSION_LINE REGEX "^ *VERSION [0-9]+\\.[0-9]+\\.[0-9]+$")
string(REGEX MATCH "([0-9]+)\\.([0-9]+)\\.([0-9]+)" CMAKE_PROJECT_VERSION "${PROJECTM_VERSION_LINE}")
set(CMAKE_PROJECT_VERSION_MAJOR "${CMAKE_MATCH_1}")
set(CMAKE_PROJECT_VERSION_MINOR "${CMAKE_MATCH_2}")
set(CMAKE_PROJECT_VERSION_PATCH "${CMAKE_MATCH_3}")
add_subdirectory("${PROJECT_ROOT}/third_party/projectm" projectm EXCLUDE_FROM_ALL)

add_library(milkdrop SHARED milkdrop.cpp)
target_link_libraries(milkdrop PRIVATE libprojectM::projectM)

# whisper.cpp, to hear a song's words on the phone and line its timed words up to them. A checkout
# without it still builds, with the words lined up as before.
if(EXISTS "${PROJECT_ROOT}/third_party/whisper.cpp/CMakeLists.txt")
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
    # Built for every phone of an ABI, not the machine building it; ggml's own threads, not OpenMP's.
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    add_subdirectory("${PROJECT_ROOT}/third_party/whisper.cpp" whisper EXCLUDE_FROM_ALL)
    # Optimized in the dev build too: unoptimized, hearing 20 seconds takes a phone minutes.
    foreach(library whisper ggml ggml-base ggml-cpu)
        target_compile_options(${library} PRIVATE -O3)
    endforeach()

    add_library(sungwords SHARED sungwords.cpp)
    target_link_libraries(sungwords PRIVATE whisper)
endif()
