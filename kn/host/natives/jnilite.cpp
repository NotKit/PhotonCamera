/* See jnilite.h. */
#include "jnilite.h"

#include <stdlib.h>
#include <string.h>

#define AS_ARRAY(a) ((jl_array *) (void *) (a))
#define AS_BUF(b) ((jl_buf *) (void *) (b))

static void *jl_elements(JNIEnv *, jarray a, jboolean *isCopy)
{
	if (isCopy)
		*isCopy = JNI_FALSE;
	return a ? AS_ARRAY(a)->data : NULL;
}

/* The elements were the array, so there is nothing to commit or to abort. */
static void jl_release(JNIEnv *, jarray, void *, jint) {}

static jsize jl_GetArrayLength(JNIEnv *, jarray a)
{
	return a ? AS_ARRAY(a)->length : 0;
}

static void *jl_GetDirectBufferAddress(JNIEnv *, jobject b)
{
	return b ? AS_BUF(b)->address : NULL;
}

static jlong jl_GetDirectBufferCapacity(JNIEnv *, jobject b)
{
	return b ? AS_BUF(b)->capacity : -1;
}

static jobject jl_NewDirectByteBuffer(JNIEnv *, void *address, jlong capacity)
{
	jl_buf *b = (jl_buf *) malloc(sizeof(jl_buf));
	if (!b)
		return NULL;
	b->address = address;
	b->capacity = capacity;
	return (jobject) (void *) b;
}

static const char *jl_GetStringUTFChars(JNIEnv *, jstring s, jboolean *isCopy)
{
	if (isCopy)
		*isCopy = JNI_FALSE;
	return (const char *) (const void *) s;
}

static void jl_ReleaseStringUTFChars(JNIEnv *, jstring, const char *) {}

static jsize jl_GetStringUTFLength(JNIEnv *, jstring s)
{
	return s ? (jsize) strlen((const char *) (const void *) s) : 0;
}

/*
 * A caller that keeps the result has to free it; nothing in the four live
 * libraries calls this (only native-engine.cpp, which is stubbed off).
 */
static jstring jl_NewStringUTF(JNIEnv *, const char *bytes)
{
	return bytes ? (jstring) (void *) strdup(bytes) : NULL;
}

/* No VM, so no pending exception, ever. */
static jboolean jl_ExceptionCheck(JNIEnv *) { return JNI_FALSE; }
static jthrowable jl_ExceptionOccurred(JNIEnv *) { return NULL; }
static void jl_ExceptionClear(JNIEnv *) {}
static void jl_ExceptionDescribe(JNIEnv *) {}
static jint jl_ThrowNew(JNIEnv *, jclass, const char *) { return -1; }

/* The object plane does not exist here.  Answering NULL makes every caller
 * take its own failure path rather than dereference something invented. */
static jclass jl_FindClass(JNIEnv *, const char *) { return NULL; }
static jclass jl_GetObjectClass(JNIEnv *, jobject) { return NULL; }
static jobject jl_NewGlobalRef(JNIEnv *, jobject o) { return o; }
static void jl_DeleteGlobalRef(JNIEnv *, jobject) {}
static void jl_DeleteLocalRef(JNIEnv *, jobject) {}

static const struct JNINativeInterface_ *jl_interface(void)
{
	static struct JNINativeInterface_ iface;
	static int filled = 0;
	if (filled)
		return &iface;
	memset(&iface, 0, sizeof(iface));

	iface.GetArrayLength = jl_GetArrayLength;
	iface.GetBooleanArrayElements = (jboolean *(JNICALL *)(JNIEnv *, jbooleanArray, jboolean *)) jl_elements;
	iface.GetByteArrayElements = (jbyte *(JNICALL *)(JNIEnv *, jbyteArray, jboolean *)) jl_elements;
	iface.GetShortArrayElements = (jshort *(JNICALL *)(JNIEnv *, jshortArray, jboolean *)) jl_elements;
	iface.GetIntArrayElements = (jint *(JNICALL *)(JNIEnv *, jintArray, jboolean *)) jl_elements;
	iface.GetLongArrayElements = (jlong *(JNICALL *)(JNIEnv *, jlongArray, jboolean *)) jl_elements;
	iface.GetFloatArrayElements = (jfloat *(JNICALL *)(JNIEnv *, jfloatArray, jboolean *)) jl_elements;
	iface.GetDoubleArrayElements = (jdouble *(JNICALL *)(JNIEnv *, jdoubleArray, jboolean *)) jl_elements;
	iface.GetPrimitiveArrayCritical = (void *(JNICALL *)(JNIEnv *, jarray, jboolean *)) jl_elements;

	iface.ReleaseBooleanArrayElements = (void (JNICALL *)(JNIEnv *, jbooleanArray, jboolean *, jint)) jl_release;
	iface.ReleaseByteArrayElements = (void (JNICALL *)(JNIEnv *, jbyteArray, jbyte *, jint)) jl_release;
	iface.ReleaseShortArrayElements = (void (JNICALL *)(JNIEnv *, jshortArray, jshort *, jint)) jl_release;
	iface.ReleaseIntArrayElements = (void (JNICALL *)(JNIEnv *, jintArray, jint *, jint)) jl_release;
	iface.ReleaseLongArrayElements = (void (JNICALL *)(JNIEnv *, jlongArray, jlong *, jint)) jl_release;
	iface.ReleaseFloatArrayElements = (void (JNICALL *)(JNIEnv *, jfloatArray, jfloat *, jint)) jl_release;
	iface.ReleaseDoubleArrayElements = (void (JNICALL *)(JNIEnv *, jdoubleArray, jdouble *, jint)) jl_release;
	iface.ReleasePrimitiveArrayCritical = (void (JNICALL *)(JNIEnv *, jarray, void *, jint)) jl_release;

	iface.GetDirectBufferAddress = jl_GetDirectBufferAddress;
	iface.GetDirectBufferCapacity = jl_GetDirectBufferCapacity;
	iface.NewDirectByteBuffer = jl_NewDirectByteBuffer;

	iface.GetStringUTFChars = jl_GetStringUTFChars;
	iface.ReleaseStringUTFChars = jl_ReleaseStringUTFChars;
	iface.GetStringUTFLength = jl_GetStringUTFLength;
	iface.NewStringUTF = jl_NewStringUTF;

	iface.ExceptionCheck = jl_ExceptionCheck;
	iface.ExceptionOccurred = jl_ExceptionOccurred;
	iface.ExceptionClear = jl_ExceptionClear;
	iface.ExceptionDescribe = jl_ExceptionDescribe;
	iface.ThrowNew = jl_ThrowNew;

	iface.FindClass = jl_FindClass;
	iface.GetObjectClass = jl_GetObjectClass;
	iface.NewGlobalRef = jl_NewGlobalRef;
	iface.DeleteGlobalRef = jl_DeleteGlobalRef;
	iface.DeleteLocalRef = jl_DeleteLocalRef;

	filled = 1;
	return &iface;
}

extern "C" JNIEnv *jnilite_env(void)
{
	static JNIEnv env;
	env.functions = jl_interface();
	return &env;
}

extern "C" void *jnilite_take_buffer(jobject buffer, int64_t *out_capacity)
{
	if (!buffer) {
		if (out_capacity)
			*out_capacity = 0;
		return NULL;
	}
	jl_buf *b = AS_BUF(buffer);
	void *address = b->address;
	if (out_capacity)
		*out_capacity = (int64_t) b->capacity;
	free(b);
	return address;
}
