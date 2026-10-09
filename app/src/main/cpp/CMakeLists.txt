cmake_minimum_required(VERSION 3.22)
project(crosstune_milkdrop LANGUAGES C CXX)

# Builds the same bytes wherever the source is checked out, so the release APK stays reproducible.
set(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
get_filename_component(PROJECT_ROOT "${PROJECT_ROOT}" ABSOLUTE)
add_compile_options("-ffile-prefix-map=${PROJECT_ROOT}=." "-fdebug-prefix-map=${PROJECT_ROOT}=.")

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
