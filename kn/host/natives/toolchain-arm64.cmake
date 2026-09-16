# CMake toolchain for the arm64 cross build: a multiarch root, so there is no
# CMAKE_SYSROOT -- the target's headers and libraries sit beside the host's.
#
# The compiler is clang with a --target rather than aarch64-linux-gnu-g++,
# which is not installed here; PORT_CC/PORT_CXX override both.
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

set(CMAKE_C_COMPILER   "$ENV{PORT_CC}")
set(CMAKE_CXX_COMPILER "$ENV{PORT_CXX}")
set(CMAKE_C_COMPILER_TARGET   aarch64-linux-gnu)
set(CMAKE_CXX_COMPILER_TARGET aarch64-linux-gnu)
if(NOT "$ENV{PORT_AR}" STREQUAL "")
	set(CMAKE_AR "$ENV{PORT_AR}")
endif()

# ncnn is built as a static library and nothing here links an executable, but
# CMake's compiler check links one by default -- and this multiarch root has
# libstdc++.so.6 without the libstdc++.so the linker wants, so that check fails
# on a toolchain that compiles perfectly well.  Checking with a static library
# instead is the documented cross-compile answer.
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

# Host tools (python, ncnn's own code generators) must stay findable; every
# target library this build needs is passed as an explicit path.
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)

# ncnn probes the compiler for ARM features; a cross clang answers for the
# target, so nothing here has to be told about NEON by hand.
