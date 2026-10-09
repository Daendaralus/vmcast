cmake_minimum_required(VERSION 3.22.1)
project(vmcast C CXX)

# libopus 1.5.2 (vendored, verified against xiph.org SHA256SUMS). Static, float API.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/opus EXCLUDE_FROM_ALL)

add_library(vmcast SHARED player.cpp)
target_compile_features(vmcast PRIVATE cxx_std_17)
target_compile_options(vmcast PRIVATE -O2 -Wall -Wextra)
target_link_options(vmcast PRIVATE "-Wl,-z,max-page-size=16384") # Android 15+ 16 KB pages
target_link_libraries(vmcast opus aaudio log)
