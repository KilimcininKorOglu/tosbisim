# hermessimd — HermesSIM host audio daemon (GPL-3.0-or-later). Build: ./build.sh (NDK, arm64-v8a + armeabi-v7a).
cmake_minimum_required(VERSION 3.22)
project(hermessimd C)
set(CMAKE_C_STANDARD 11)

# libopus 1.5.2 (BSD) — voice codec. Fixed-point build is fastest on phones without FPU concerns; we
# keep float (arm64 and NEON-era armv7 have good FPUs) and NEON intrinsics enabled by opus's own detection.
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/opus-1.5.2 opus EXCLUDE_FROM_ALL)

# PCM I/O: the phone's own libtinyalsa.so (lib64, or lib for the 32-bit build), loaded at runtime (see audio.c ta_load).

add_executable(hermessimd hermessimd.c audio.c box.c)
# 16 KB-aligned segments: Android 15+ phones may run 16 KB-page kernels, where a 4 KB-aligned binary won't start (no call
# audio, box mode or charge limit on that host). 16 KB alignment also runs on 4 KB kernels.
target_link_options(hermessimd PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
target_compile_options(hermessimd PRIVATE -Wall -Wextra -Werror)
target_link_libraries(hermessimd PRIVATE opus log m dl)
