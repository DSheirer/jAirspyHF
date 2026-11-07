# Builds libairspyhf as a shared library from the upstream sources.
#
# Upstream's own CMake files need CMake < 4, only find libusb through
# pkg-config or fixed paths, and expect a prebuilt pthreads-win32 DLL on
# Windows. Two C files are simple enough to build directly instead.
#
# Used by build-unix.sh and build-windows.ps1; not meant to be run by hand.
cmake_minimum_required(VERSION 3.16)

# The C runtime is linked statically on Windows, so the DLL needs nothing installed.
set(CMAKE_MSVC_RUNTIME_LIBRARY MultiThreaded)

project(jairspyhf_native C)

set(AIRSPYHF_SOURCE_DIR "" CACHE PATH "Upstream airspyhf source tree")
set(LIBUSB_INCLUDE_DIR "" CACHE PATH "Directory containing libusb.h")
set(LIBUSB_LIBRARY "" CACHE FILEPATH "libusb shared library, or import library on Windows")
set(PTHREADS4W_SOURCE_DIR "" CACHE PATH "pthreads4w source tree (Windows only)")

foreach(var AIRSPYHF_SOURCE_DIR LIBUSB_INCLUDE_DIR LIBUSB_LIBRARY)
    if(NOT ${var})
        message(FATAL_ERROR "${var} is required")
    endif()
endforeach()

set(SRC "${AIRSPYHF_SOURCE_DIR}/libairspyhf/src")
add_library(airspyhf SHARED "${SRC}/airspyhf.c" "${SRC}/iqbalancer.c")
set_target_properties(airspyhf PROPERTIES C_STANDARD 99)
target_include_directories(airspyhf PRIVATE "${SRC}" "${LIBUSB_INCLUDE_DIR}")
target_link_libraries(airspyhf PRIVATE "${LIBUSB_LIBRARY}")

if(MSVC)
    if(NOT PTHREADS4W_SOURCE_DIR)
        message(FATAL_ERROR "PTHREADS4W_SOURCE_DIR is required on Windows")
    endif()
    # pthread.c includes every pthreads4w module. Compiling it into the DLL
    # makes its DllMain the DLL's, which gives pthreads4w the thread attach and
    # detach notifications it needs to clean up after threads that called it.
    target_sources(airspyhf PRIVATE "${PTHREADS4W_SOURCE_DIR}/pthread.c")
    set_source_files_properties("${PTHREADS4W_SOURCE_DIR}/pthread.c" PROPERTIES
        COMPILE_DEFINITIONS HAVE_CONFIG_H)
    target_include_directories(airspyhf PRIVATE "${PTHREADS4W_SOURCE_DIR}")
    target_compile_definitions(airspyhf PRIVATE _CRT_SECURE_NO_WARNINGS)
    target_link_options(airspyhf PRIVATE /INCREMENTAL:NO)
else()
    set(THREADS_PREFER_PTHREAD_FLAG ON)
    find_package(Threads REQUIRED)
    target_compile_options(airspyhf PRIVATE -Wall)
    target_link_libraries(airspyhf PRIVATE Threads::Threads m)
endif()
