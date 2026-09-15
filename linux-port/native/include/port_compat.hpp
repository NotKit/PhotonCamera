/*
 * Force-included into every C++ translation unit of the desktop build
 * (-include port_compat.hpp). It papers over the ways app/src/main/cpp depends
 * on the NDK toolchain rather than on standard C++, so those sources stay
 * untouched (BRINGUP_NOTES.md).
 */
#ifndef LINUX_PORT_COMPAT_HPP
#define LINUX_PORT_COMPAT_HPP

/*
 * 1. The NDK's libc++ pulls these in transitively; libstdc++ does not, so
 *    allocator.cpp uses uint8_t/uint16_t and dngCreator.cpp std::memcpy without
 *    including anything.
 */
#include <algorithm>
#include <climits>
#include <cstdint>
#include <cstring>
#include <memory>

/*
 * 2. Android types JavaVM::AttachCurrentThread's first parameter as JNIEnv**,
 *    the JDK as void**. C++ rejects the implicit conversion, and the call is in
 *    native-engine.cpp and flacRecorder.cpp. Insert the cast at the call sites
 *    rather than patching the app sources. The macro must come after <jni.h> so
 *    it does not rewrite the declaration itself.
 */
#include <jni.h>
#define AttachCurrentThread(penv, args) AttachCurrentThread((void **) (penv), (args))
#define AttachCurrentThreadAsDaemon(penv, args) AttachCurrentThreadAsDaemon((void **) (penv), (args))

#endif /* LINUX_PORT_COMPAT_HPP */
