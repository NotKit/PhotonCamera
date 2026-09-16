/* The vendored NDK camera headers declare one JNI bridge
 * (ACameraMetadata_fromCameraMetadata); the backend never calls it, and this
 * port has no JVM.  Two opaque names are all those declarations need. */
#ifndef ATLCAMERA_JNI_H
#define ATLCAMERA_JNI_H
typedef void JNIEnv;
typedef void *jobject;
#endif
