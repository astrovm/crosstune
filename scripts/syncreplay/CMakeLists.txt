# whisper.cpp's tiny model on the computer, as the app runs it on the phone: see heard.cpp.
cmake_minimum_required(VERSION 3.16)
project(heard CXX C)
# Built into the program, so it still runs from build/syncreplay copied to another checkout, which
# keeps the recordings and what was heard in them: a shared library is looked for where it was built.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/whisper.cpp whisper)
add_executable(heard heard.cpp)
target_link_libraries(heard PRIVATE whisper)
