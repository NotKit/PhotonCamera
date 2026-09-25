/*
 * <android/bitmap.h> for this port: every call fails.
 *
 * allocator.cpp's wrapBitmap/unlockBitmap lock a Bitmap's pixels in place.
 * A Bitmap here is a Kotlin IntArray of ARGB ints, not lockable RGBA memory,
 * so photoncam.natives.Allocator implements those two itself and never comes
 * down here.  These only satisfy the link; a caller that does reach them gets
 * the NDK's failure code and takes its own error path.
 */
#include <android/bitmap.h>

int AndroidBitmap_getInfo(JNIEnv *env, jobject jbitmap, AndroidBitmapInfo *info)
{
	return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
}

int AndroidBitmap_lockPixels(JNIEnv *env, jobject jbitmap, void **addrPtr)
{
	return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
}

int AndroidBitmap_unlockPixels(JNIEnv *env, jobject jbitmap)
{
	return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
}
