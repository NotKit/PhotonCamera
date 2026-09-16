/*
 * jnilite - a JNIEnv whose objects are plain pointers.
 *
 * Kotlin/Native has no JNI, but app/src/main/cpp is 64 Java_* entry points and
 * must not be edited.  Outside native-engine.cpp (the ART hidden-
 * API bypass, which is stubbed off) those entry points touch exactly fourteen
 * JNI functions, and every one of them is data-plane: direct buffers, primitive
 * array pinning, UTF strings.  None of it needs a VM.
 *
 * So instead of duplicating the bodies, this supplies a JNIEnv of our own whose
 * jobject/jarray/jstring are pointers we choose:
 *
 *     jstring        const char *, NUL-terminated, caller-owned
 *     jarray         jl_array *   { data, length }
 *     ByteBuffer     jl_buf *     { address, capacity }
 *     AssetManager   AAssetManager * (see asset_dir.cpp)
 *
 * Get*ArrayElements hands back the pointer itself, so Release* is a no-op and
 * no copy-back is needed - the "pinned" memory was never a copy.  The env holds
 * no state, so one shared instance is safe on every thread.
 */
#ifndef PHOTONCAM_JNILITE_H
#define PHOTONCAM_JNILITE_H

#include <jni.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* What a jarray points to. */
typedef struct jl_array {
	void *data;
	jsize length;
} jl_array;

/* What a direct ByteBuffer jobject points to. */
typedef struct jl_buf {
	void *address;
	jlong capacity;
} jl_buf;

/* The shared env.  Never NULL. */
JNIEnv *jnilite_env(void);

/*
 * Unpack a jobject that came out of NewDirectByteBuffer and release the
 * descriptor (not the memory it describes).  Returns the address.
 */
void *jnilite_take_buffer(jobject buffer, int64_t *out_capacity);

#ifdef __cplusplus
}
#endif

#endif /* PHOTONCAM_JNILITE_H */
