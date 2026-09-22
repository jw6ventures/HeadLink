# Lab receiver driver linked against the pinned OpenAuto Prodigy protocol library.
# PRODIGY_DIR is a checkout prepared by tools/lab-receiver.sh (outside the repository).
cmake_minimum_required(VERSION 3.22)
project(openauto-lab-receiver LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(PRODIGY_DIR "" CACHE PATH "openauto-prodigy checkout with the lab patch applied")
if(NOT EXISTS "${PRODIGY_DIR}/libs/prodigy-oaa-protocol/CMakeLists.txt")
    message(FATAL_ERROR "PRODIGY_DIR must point at the prepared openauto-prodigy checkout")
endif()
set(OAA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory("${PRODIGY_DIR}/libs/prodigy-oaa-protocol" prodigy-oaa-protocol)

# libavcodec decodes the received H.264 so the receiver can check the picture itself.
find_package(PkgConfig REQUIRED)
pkg_check_modules(FFMPEG REQUIRED IMPORTED_TARGET libavcodec libavutil)

add_executable(lab-receiver receiver.cpp)
target_link_libraries(lab-receiver PRIVATE prodigy-oaa-protocol PkgConfig::FFMPEG)
