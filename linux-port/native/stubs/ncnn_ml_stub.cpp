/*
 * libncnnMl.so without ncnn.
 *
 * Mirrors the fallback app/src/main/cpp/CMakeLists.txt generates when the ncnn
 * prebuilt for an ABI is missing: the library exists and loads, so the static
 * initializers in FlowNetNcnnProcessor / KernelNetNcnnProcessor succeed, and
 * every entry point answers "not available" instead of crashing.
 *
 * ncnn ships in this repository as an Android per-ABI static library only, so
 * there is nothing to link on x86_64 until native/build_ncnn_linux.sh builds it
 * from source (BRINGUP_NOTES.md).
 */
#include <jni.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_NcnnMl_nativeEnsureInit(JNIEnv *, jclass)
{
	return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_FlowNetNcnnProcessor_nativeCreate(
	JNIEnv *, jclass, jobject, jstring)
{
	return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_FlowNetNcnnProcessor_nativeRun(
	JNIEnv *, jclass, jlong, jobject, jobject, jint, jint, jobject)
{
	return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_FlowNetNcnnProcessor_nativeDestroy(
	JNIEnv *, jclass, jlong)
{
}

JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_KernelNetNcnnProcessor_nativeCreate(
	JNIEnv *, jclass, jobject, jstring)
{
	return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_KernelNetNcnnProcessor_nativeRun(
	JNIEnv *, jclass, jlong, jobject, jint, jint, jfloat, jobject)
{
	return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_KernelNetNcnnProcessor_nativeDestroy(
	JNIEnv *, jclass, jlong)
{
}

}
