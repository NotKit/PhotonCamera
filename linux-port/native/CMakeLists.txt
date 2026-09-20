# The app's JNI libraries for glibc.
#
# Mirrors app/src/main/cpp/CMakeLists.txt target by target; it does not include
# it, because that file downloads headers into the app tree, links a per-ABI
# prebuilt ncnn and expects the NDK's bionic. Re-read the Android file before
# changing anything here and keep the source lists in step with it.
#
# Deviations from the Android build (BRINGUP_NOTES.md has the reasoning):
#   * -llog is a static stub (stubs/android_log.c) writing to stderr, not
#     Android's liblog. atlas's libandroid.so.0 does not export __android_log_*.
#   * the header-only deps land in ${PORT_DEPS_DIR}, outside the app tree.
#   * ncnnMl is not built: ncnn ships here as a per-ABI Android prebuilt only.
#     KernelNet and FlowNet are off until native/build_ncnn_linux.sh exists.
#   * libarchive-jni.so is a stub that pulls in the system libarchive, so
#     dngCreator's dlopen("libarchive-jni.so") + dlsym path works unchanged.
cmake_minimum_required(VERSION 3.16)
project(photoncamera_port C CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED APP_CPP_DIR)
	set(APP_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")
endif()
if(NOT DEFINED PORT_DEPS_DIR)
	set(PORT_DEPS_DIR "${CMAKE_BINARY_DIR}/deps")
endif()
get_filename_component(APP_CPP_DIR "${APP_CPP_DIR}" ABSOLUTE)

# Same version stamp the Android build compiles into dngCreator.
file(READ "${APP_CPP_DIR}/../../../version.properties" ver)
string(REGEX MATCH "VERSION_BUILD=([0-9]*)" _ ${ver})
set(VERSION_BUILD ${CMAKE_MATCH_1})
string(REGEX MATCH "VERSION_NAME=([0-9,.]*)" _ ${ver})
set(VERSION_NAME ${CMAKE_MATCH_1})

# The three headers the Android CMakeLists downloads into app/src/main/cpp/deps.
# Fetched here instead, so a port build never writes into the app tree; the URLs
# are the Android file's, verbatim.
file(MAKE_DIRECTORY "${PORT_DEPS_DIR}")
foreach(dep
	"tiny_dng_writer.h|https://raw.githubusercontent.com/ParticlesDevs/tinydng/refs/heads/master/tiny_dng_writer.h"
	"technicallyflac.h|https://raw.githubusercontent.com/ParticlesDevs/technicallyflac/main/technicallyflac.h"
	"archive.h|https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/archive.h"
	"archive_entry.h|https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/archive_entry.h")
	string(REPLACE "|" ";" dep "${dep}")
	list(GET dep 0 dep_name)
	list(GET dep 1 dep_url)
	if(NOT EXISTS "${PORT_DEPS_DIR}/${dep_name}")
		message(STATUS "downloading ${dep_name}")
		file(DOWNLOAD "${dep_url}" "${PORT_DEPS_DIR}/${dep_name}" STATUS dep_status)
		list(GET dep_status 0 dep_code)
		if(NOT dep_code EQUAL 0)
			message(FATAL_ERROR "could not download ${dep_url}: ${dep_status}")
		endif()
	endif()
endforeach()

# jni.h straight from $JAVA_HOME, not find_package(JNI): a headless JDK has no
# libjawt and FindJNI fails on it, while nothing here needs AWT or libjvm.
if(NOT DEFINED JAVA_HOME OR JAVA_HOME STREQUAL "")
	set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()
set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/linux")
if(NOT EXISTS "${JAVA_HOME}/include/jni.h")
	message(FATAL_ERROR "no jni.h under JAVA_HOME=${JAVA_HOME}")
endif()

# liblog: the app's sources include <android/log.h> and call __android_log_print.
add_library(androidlog STATIC "${CMAKE_CURRENT_SOURCE_DIR}/stubs/android_log.c")
target_include_directories(androidlog PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")

# The sources come from the app tree unchanged (FR: never edit app/src/main/cpp),
# but they include "deps/<header>" relative to themselves, so the fetched copies
# have to be reachable under a deps/ prefix as well.
file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/include-deps")
if(NOT EXISTS "${CMAKE_BINARY_DIR}/include-deps/deps")
	file(CREATE_LINK "${PORT_DEPS_DIR}" "${CMAKE_BINARY_DIR}/include-deps/deps" SYMBOLIC)
endif()

set(APP_JNI_LIBS dngCreator allocator flacRecorder camera2native ncnnMl mcraw)

# ncnnMl: the real library when native/build_ncnn_linux.sh has installed an ncnn
# prefix, and otherwise the same stub the Android CMakeLists generates for an ABI
# with no prebuilt — the library loads and every call answers "not available".
set(FLOWNET_DIR "${APP_CPP_DIR}/flownet")
if(EXISTS "${PORT_NCNN_DIR}/lib/libncnn.a")
	# The Vulkan half (the three *_vulkan.cpp layers and the generated SPIR-V
	# headers) is deliberately not built: a CPU ncnn is what the port installs,
	# and flownet_register.h picks the CPU creators when NCNN_VULKAN is 0.
	add_library(ncnnMl SHARED
		"${APP_CPP_DIR}/ncnnMl.cpp"
		"${FLOWNET_DIR}/layer/flownetupsample.cpp"
		"${FLOWNET_DIR}/layer/flownetcorrlookup.cpp"
		"${FLOWNET_DIR}/layer/flownetcorrfused.cpp")
	target_include_directories(ncnnMl PRIVATE
		"${PORT_NCNN_DIR}/include/ncnn" "${FLOWNET_DIR}" "${FLOWNET_DIR}/layer")
	# The Android build's flags for this target, minus the NDK's static libomp.
	# __ANDROID_API__ has to match the ncnn build's, or net.h hides the
	# AAssetManager overloads ncnnMl.cpp calls (native/build_ncnn_linux.sh).
	target_compile_options(ncnnMl PRIVATE -fno-rtti -fno-exceptions -D__ANDROID_API__=9)
	set_target_properties(ncnnMl PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON
		CXX_EXTENSIONS OFF)
	find_package(OpenMP)
	if(OpenMP_CXX_FOUND)
		target_link_libraries(ncnnMl PRIVATE OpenMP::OpenMP_CXX)
	else()
		# ncnnMl.cpp guards its omp calls with #ifdef _OPENMP, so this only costs
		# threads in the custom layers.
		message(WARNING "no OpenMP for ${CMAKE_CXX_COMPILER}: ncnnMl will be single-threaded")
	endif()
	target_link_libraries(ncnnMl PRIVATE "${PORT_NCNN_DIR}/lib/libncnn.a")
	# AAssetManager_fromJava and the AAsset_* calls that read the models out of
	# the apk. atlas's libandroid.so.0 is the only implementation there is, so
	# build-atlas.sh has to have run first.
	if(NOT EXISTS "${PORT_ANDROID_LIB}")
		message(FATAL_ERROR
			"ncnnMl needs atlas's libandroid.so.0 (PORT_ANDROID_LIB=${PORT_ANDROID_LIB}); "
			"run linux-port/build-atlas.sh first")
	endif()
	target_link_libraries(ncnnMl PRIVATE "${PORT_ANDROID_LIB}")
else()
	add_library(ncnnMl SHARED "${CMAKE_CURRENT_SOURCE_DIR}/stubs/ncnn_ml_stub.cpp")
endif()

add_library(dngCreator SHARED "${APP_CPP_DIR}/dngCreator.cpp")
target_compile_definitions(dngCreator PRIVATE
	VERSION_BUILD="${VERSION_BUILD}" VERSION_NAME="${VERSION_NAME}")

# rawF16.cpp is part of liballocator.so upstream, and the fp16 raw path calls
# into it on every capture. AndroidBitmap_* comes from atlas's libandroid.so.0.
add_library(allocator SHARED
	"${APP_CPP_DIR}/allocator.cpp"
	"${APP_CPP_DIR}/rawF16.cpp")
if(EXISTS "${PORT_ANDROID_LIB}")
	target_link_libraries(allocator PRIVATE "${PORT_ANDROID_LIB}")
else()
	message(WARNING "no ${PORT_ANDROID_LIB}: allocator's bitmap entry points will not resolve")
endif()

add_library(flacRecorder SHARED "${APP_CPP_DIR}/flacRecorder.cpp")
add_library(camera2native SHARED "${APP_CPP_DIR}/native-engine.cpp")

# MediaCinemaRAW (.mcraw) raw-video container. Globbed the same way the Android
# CMakeLists does it, test/ excluded.
file(GLOB MCRAW_SOURCES
	"${APP_CPP_DIR}/mediacinemaraw/*.cpp"
	"${APP_CPP_DIR}/mediacinemaraw/src/*.cpp")
add_library(mcraw SHARED ${MCRAW_SOURCES})
target_include_directories(mcraw PRIVATE "${APP_CPP_DIR}/mediacinemaraw/include")
target_compile_options(mcraw PRIVATE -O3)

foreach(lib ${APP_JNI_LIBS})
	target_include_directories(${lib} PRIVATE
		"${APP_CPP_DIR}" "${PORT_DEPS_DIR}" "${CMAKE_BINARY_DIR}/include-deps"
		${JNI_INCLUDE_DIRS})
	target_link_libraries(${lib} PRIVATE androidlog ${CMAKE_DL_LIBS})
	# see include/port_compat.hpp: the NDK's transitive includes and Android's
	# JNIEnv** signature, both of which these sources assume
	target_compile_options(${lib} PRIVATE
		-include "${CMAKE_CURRENT_SOURCE_DIR}/include/port_compat.hpp")
endforeach()

# me.zhanghai.android.libarchive's .so, which dngCreator dlopens and dlsyms for
# the zipped-DNG path. dlsym searches a handle's dependencies too, so an empty
# library with libarchive as DT_NEEDED answers every symbol it looks up.
find_package(PkgConfig)
if(PkgConfig_FOUND)
	pkg_check_modules(LIBARCHIVE libarchive)
endif()
if(LIBARCHIVE_FOUND)
	add_library(archive-jni SHARED "${CMAKE_CURRENT_SOURCE_DIR}/stubs/libarchive_jni.c")
	set_target_properties(archive-jni PROPERTIES OUTPUT_NAME "archive-jni")
	target_link_libraries(archive-jni PRIVATE ${LIBARCHIVE_LIBRARIES})
	target_link_directories(archive-jni PRIVATE ${LIBARCHIVE_LIBRARY_DIRS})
	# --no-as-needed: nothing here references libarchive, and without it the link
	# drops the DT_NEEDED that makes the whole stub work.
	target_link_options(archive-jni PRIVATE "-Wl,--no-as-needed")
else()
	message(WARNING "no libarchive: skipping libarchive-jni.so, DngCreator's archive path will fail")
endif()
