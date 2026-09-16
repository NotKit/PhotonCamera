/*
 * Stand-in for me.zhanghai.android.libarchive's libarchive-jni.so.
 *
 * dngCreator.cpp dlopens that name and resolves the archive_* entry points with
 * dlsym. dlsym searches the handle's dependency chain, so this translation unit
 * stays empty on purpose: everything comes from the system libarchive linked in
 * as a DT_NEEDED (see native/CMakeLists.txt).
 */
