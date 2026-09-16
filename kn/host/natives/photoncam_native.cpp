/*
 * The C ABI of photoncam_native.h, implemented by forwarding to the app's own
 * JNI entry points through jnilite's pointer-shaped JNIEnv.
 *
 * Nothing here reimplements anything: every wrapper is the marshalling that
 * Java_* expects, and the body that runs is the one in app/src/main/cpp, which
 * this lane does not edit.  The Java_* prototypes are declared
 * here rather than included, because those sources have no headers - a mismatch
 * would be a link error, and the build checks the symbol list besides.
 *
 * native-engine.cpp is the exception and is not linked at all; see the bottom.
 */
#include "photoncam_native.h"
#include "jnilite.h"

#include <stdlib.h>
#include <string.h>

#define E jnilite_env()
#define OBJ ((jobject) 0)

static inline jl_array jl_arr(const void *data, int32_t length)
{
	jl_array a;
	a.data = const_cast<void *>(data);
	a.length = (jsize) length;
	return a;
}

static inline jl_buf jl_bufOf(void *address, int64_t capacity)
{
	jl_buf b;
	b.address = address;
	b.capacity = (jlong) capacity;
	return b;
}

#define AS_ARR(p) ((jarray) (void *) (p))
#define AS_BUF(p) ((jobject) (void *) (p))
#define AS_STR(p) ((jstring) (void *) (p))

/* =======================================================================
 * Allocator
 * ===================================================================== */

#define ALLOC(name) Java_com_particlesdevs_photoncamera_util_Allocator_##name

extern "C" {
JNIEXPORT jobject JNICALL ALLOC(allocate)(JNIEnv *, jclass, jint);
JNIEXPORT jobject JNICALL ALLOC(allocateAndCopy)(JNIEnv *, jclass, jint, jobject, jint);
JNIEXPORT jobject JNICALL ALLOC(allocateAndCopyConvert)(JNIEnv *, jclass, jint, jobject, jint,
                                                        jint, jint);
JNIEXPORT jobject JNICALL ALLOC(allocateAndCopyConvertBinning)(JNIEnv *, jclass, jint, jobject,
                                                               jint, jint, jint);
JNIEXPORT jobject JNICALL ALLOC(allocateAndCopyBinning)(JNIEnv *, jclass, jint, jobject, jint,
                                                        jint, jint);
JNIEXPORT void JNICALL ALLOC(free)(JNIEnv *, jclass, jobject);
JNIEXPORT jlong JNICALL ALLOC(getMemoryCount)(JNIEnv *, jclass);
}

void *pc_alloc_allocate(int32_t capacity, int64_t *outCapacity)
{
	return jnilite_take_buffer(ALLOC(allocate)(E, 0, (jint) capacity), outCapacity);
}

void *pc_alloc_allocateAndCopy(int32_t capacity, void *origin, int64_t originCapacity,
                               int32_t offset, int64_t *outCapacity)
{
	jl_buf o = jl_bufOf(origin, originCapacity);
	return jnilite_take_buffer(
	        ALLOC(allocateAndCopy)(E, 0, (jint) capacity, AS_BUF(&o), (jint) offset),
	        outCapacity);
}

void *pc_alloc_allocateAndCopyConvert(int32_t capacity, void *origin, int64_t originCapacity,
                                      int32_t width, int32_t row_stride, int32_t offset,
                                      int64_t *outCapacity)
{
	jl_buf o = jl_bufOf(origin, originCapacity);
	return jnilite_take_buffer(
	        ALLOC(allocateAndCopyConvert)(E, 0, (jint) capacity, AS_BUF(&o), (jint) width,
	                                      (jint) row_stride, (jint) offset),
	        outCapacity);
}

void *pc_alloc_allocateAndCopyConvertBinning(int32_t capacity, void *origin,
                                             int64_t originCapacity, int32_t width,
                                             int32_t row_stride, int32_t offset,
                                             int64_t *outCapacity)
{
	jl_buf o = jl_bufOf(origin, originCapacity);
	return jnilite_take_buffer(
	        ALLOC(allocateAndCopyConvertBinning)(E, 0, (jint) capacity, AS_BUF(&o),
	                                             (jint) width, (jint) row_stride,
	                                             (jint) offset),
	        outCapacity);
}

void *pc_alloc_allocateAndCopyBinning(int32_t capacity, void *origin, int64_t originCapacity,
                                      int32_t width, int32_t height, int32_t row_stride,
                                      int64_t *outCapacity)
{
	jl_buf o = jl_bufOf(origin, originCapacity);
	return jnilite_take_buffer(
	        ALLOC(allocateAndCopyBinning)(E, 0, (jint) capacity, AS_BUF(&o), (jint) width,
	                                      (jint) height, (jint) row_stride),
	        outCapacity);
}

void pc_alloc_free(void *address, int64_t capacity)
{
	if (!address)
		return;
	jl_buf b = jl_bufOf(address, capacity);
	ALLOC(free)(E, 0, AS_BUF(&b));
}

int64_t pc_alloc_getMemoryCount(void)
{
	return (int64_t) ALLOC(getMemoryCount)(E, 0);
}

/* =======================================================================
 * DngCreator
 *
 * Thirty-nine entry points of five shapes.  The macros declare the Java_*
 * prototype and define the wrapper together, so the two cannot drift.
 * ===================================================================== */

#define DNG(name) Java_com_particlesdevs_photoncamera_processing_DngCreator_##name

#define DNG_VOID_DOUBLE(nm)                                                                  \
	extern "C" JNIEXPORT void JNICALL DNG(nm)(JNIEnv *, jobject, jlong, jdouble);        \
	void pc_dng_##nm(int64_t p, double v) { DNG(nm)(E, OBJ, (jlong) p, (jdouble) v); }

#define DNG_VOID_INT(nm)                                                                     \
	extern "C" JNIEXPORT void JNICALL DNG(nm)(JNIEnv *, jobject, jlong, jint);           \
	void pc_dng_##nm(int64_t p, int32_t v) { DNG(nm)(E, OBJ, (jlong) p, (jint) v); }

#define DNG_VOID_SHORT(nm)                                                                   \
	extern "C" JNIEXPORT void JNICALL DNG(nm)(JNIEnv *, jobject, jlong, jshort);         \
	void pc_dng_##nm(int64_t p, int16_t v) { DNG(nm)(E, OBJ, (jlong) p, (jshort) v); }

#define DNG_VOID_BOOL(nm)                                                                    \
	extern "C" JNIEXPORT void JNICALL DNG(nm)(JNIEnv *, jobject, jlong, jboolean);       \
	void pc_dng_##nm(int64_t p, int32_t v)                                               \
	{                                                                                    \
		DNG(nm)(E, OBJ, (jlong) p, v ? JNI_TRUE : JNI_FALSE);                        \
	}

#define DNG_VOID_STRING(nm)                                                                  \
	extern "C" JNIEXPORT void JNICALL DNG(nm)(JNIEnv *, jobject, jlong, jstring);        \
	void pc_dng_##nm(int64_t p, const char *s) { DNG(nm)(E, OBJ, (jlong) p, AS_STR(s)); }

#define DNG_VOID_DOUBLE_ARRAY(nm)                                                            \
	extern "C" JNIEXPORT void JNICALL DNG(nm)(JNIEnv *, jobject, jlong, jdoubleArray);   \
	void pc_dng_##nm(int64_t p, const double *v, int32_t n)                              \
	{                                                                                    \
		jl_array a = jl_arr(v, n);                                                   \
		DNG(nm)(E, OBJ, (jlong) p, (jdoubleArray) AS_ARR(v ? &a : 0));               \
	}

extern "C" {
JNIEXPORT jlong JNICALL DNG(create)(JNIEnv *, jobject);
JNIEXPORT void JNICALL DNG(destroy)(JNIEnv *, jobject, jlong);
JNIEXPORT jobject JNICALL DNG(createDNG)(JNIEnv *, jobject, jlong, jint, jint, jobject);
JNIEXPORT void JNICALL DNG(setBlackLevel)(JNIEnv *, jobject, jlong, jshortArray);
JNIEXPORT void JNICALL DNG(setAsShotWhiteXY)(JNIEnv *, jobject, jlong, jdouble, jdouble);
JNIEXPORT void JNICALL DNG(setGainMap)(JNIEnv *, jobject, jlong, jfloatArray, jint, jint, jint,
                                       jint, jint, jint);
JNIEXPORT void JNICALL DNG(setTimeCode)(JNIEnv *, jobject, jlong, jbyteArray);
JNIEXPORT void JNICALL DNG(writeFile)(JNIEnv *, jobject, jlong, jobject, jobject, jstring, jint);
JNIEXPORT void JNICALL DNG(openArchive)(JNIEnv *, jobject, jlong, jstring);
JNIEXPORT void JNICALL DNG(openArchiveByFd)(JNIEnv *, jobject, jlong, jint);
JNIEXPORT void JNICALL DNG(closeArchive)(JNIEnv *, jobject, jlong);
}

int64_t pc_dng_create(void)
{
	return (int64_t) DNG(create)(E, OBJ);
}

void pc_dng_destroy(int64_t p)
{
	DNG(destroy)(E, OBJ, (jlong) p);
}

void *pc_dng_createDNG(int64_t p, int32_t width, int32_t height, void *rawImageData,
                       int64_t rawCapacity, int64_t *outSize)
{
	jl_buf raw = jl_bufOf(rawImageData, rawCapacity);
	jobject out = DNG(createDNG)(E, OBJ, (jlong) p, (jint) width, (jint) height,
	                             AS_BUF(rawImageData ? &raw : 0));
	return jnilite_take_buffer(out, outSize);
}

void pc_dng_setBlackLevel(int64_t p, const int16_t *v, int32_t n)
{
	jl_array a = jl_arr(v, n);
	DNG(setBlackLevel)(E, OBJ, (jlong) p, (jshortArray) AS_ARR(v ? &a : 0));
}

void pc_dng_setAsShotWhiteXY(int64_t p, double x, double y)
{
	DNG(setAsShotWhiteXY)(E, OBJ, (jlong) p, (jdouble) x, (jdouble) y);
}

void pc_dng_setGainMap(int64_t p, const float *v, int32_t n, int32_t xmin, int32_t ymin,
                       int32_t xmax, int32_t ymax, int32_t width, int32_t height)
{
	jl_array a = jl_arr(v, n);
	DNG(setGainMap)(E, OBJ, (jlong) p, (jfloatArray) AS_ARR(v ? &a : 0), (jint) xmin,
	                (jint) ymin, (jint) xmax, (jint) ymax, (jint) width, (jint) height);
}

void pc_dng_setTimeCode(int64_t p, const int8_t *v, int32_t n)
{
	jl_array a = jl_arr(v, n);
	DNG(setTimeCode)(E, OBJ, (jlong) p, (jbyteArray) AS_ARR(v ? &a : 0));
}

void pc_dng_writeFile(int64_t p, void *dngBuffer, int64_t dngCapacity, void *raw,
                      int64_t rawCapacity, const char *path, int32_t offset)
{
	jl_buf d = jl_bufOf(dngBuffer, dngCapacity);
	jl_buf r = jl_bufOf(raw, rawCapacity);
	DNG(writeFile)(E, OBJ, (jlong) p, AS_BUF(dngBuffer ? &d : 0), AS_BUF(raw ? &r : 0),
	               AS_STR(path), (jint) offset);
}

void pc_dng_openArchive(int64_t p, const char *path)
{
	DNG(openArchive)(E, OBJ, (jlong) p, AS_STR(path));
}

void pc_dng_openArchiveByFd(int64_t p, int32_t fd)
{
	DNG(openArchiveByFd)(E, OBJ, (jlong) p, (jint) fd);
}

void pc_dng_closeArchive(int64_t p)
{
	DNG(closeArchive)(E, OBJ, (jlong) p);
}

DNG_VOID_INT(setOrientation)
DNG_VOID_DOUBLE(setWhiteLevel)
DNG_VOID_DOUBLE_ARRAY(setColorMatrix1)
DNG_VOID_DOUBLE_ARRAY(setColorMatrix2)
DNG_VOID_DOUBLE_ARRAY(setForwardMatrix1)
DNG_VOID_DOUBLE_ARRAY(setForwardMatrix2)
DNG_VOID_DOUBLE_ARRAY(setCameraCalibration1)
DNG_VOID_DOUBLE_ARRAY(setCameraCalibration2)
DNG_VOID_DOUBLE_ARRAY(setAsShotNeutral)
DNG_VOID_DOUBLE_ARRAY(setAnalogBalance)
DNG_VOID_DOUBLE_ARRAY(setNoiseProfile)
DNG_VOID_SHORT(setCalibrationIlluminant1)
DNG_VOID_SHORT(setCalibrationIlluminant2)
DNG_VOID_SHORT(setIso)
DNG_VOID_STRING(setUniqueCameraModel)
DNG_VOID_STRING(setDescription)
DNG_VOID_STRING(setSoftware)
DNG_VOID_STRING(setMake)
DNG_VOID_STRING(setModel)
DNG_VOID_STRING(setDateTime)
DNG_VOID_DOUBLE(setExposureTime)
DNG_VOID_DOUBLE(setAperture)
DNG_VOID_DOUBLE(setFocalLength)
DNG_VOID_DOUBLE(setFrameRate)
DNG_VOID_INT(setCFAPattern)
DNG_VOID_INT(setBitsPerSample)
DNG_VOID_BOOL(setCompression)
DNG_VOID_BOOL(setBinning)

/* =======================================================================
 * FlacAudioRecorder
 * ===================================================================== */

#define FLAC(name) Java_com_particlesdevs_photoncamera_util_FlacAudioRecorder_##name

extern "C" {
JNIEXPORT jlong JNICALL FLAC(nativeOpenFd)(JNIEnv *, jobject, jint, jint, jint, jint, jint);
JNIEXPORT void JNICALL FLAC(nativeWriteFrame)(JNIEnv *, jobject, jlong, jshortArray, jint, jint);
JNIEXPORT void JNICALL FLAC(nativeClose)(JNIEnv *, jobject, jlong);
}

int64_t pc_flac_nativeOpenFd(int32_t fd, int32_t sampleRate, int32_t channels, int32_t bitDepth,
                             int32_t blockSize)
{
	return (int64_t) FLAC(nativeOpenFd)(E, OBJ, (jint) fd, (jint) sampleRate,
	                                    (jint) channels, (jint) bitDepth, (jint) blockSize);
}

void pc_flac_nativeWriteFrame(int64_t ctx, const int16_t *samples, int32_t sampleCount,
                              int32_t frameCount, int32_t channels)
{
	jl_array a = jl_arr(samples, sampleCount);
	FLAC(nativeWriteFrame)(E, OBJ, (jlong) ctx, (jshortArray) AS_ARR(samples ? &a : 0),
	                       (jint) frameCount, (jint) channels);
}

void pc_flac_nativeClose(int64_t ctx)
{
	FLAC(nativeClose)(E, OBJ, (jlong) ctx);
}

/* =======================================================================
 * ncnn: FlowNet and KernelNet
 *
 * ncnnMl.cpp reaches its models through an AAssetManager, which here is a
 * directory (stubs/asset_dir.c).  The manager is opened per create() and kept
 * alive for the net's lifetime, because ncnn's DataReaderFromAndroidAsset holds
 * the AAsset open past load_param.
 * ===================================================================== */

#define ML(cls, name) Java_com_particlesdevs_photoncamera_processing_ml_##cls##_##name

#if PHOTONCAM_WITH_NCNN

struct AAssetManager;
extern "C" AAssetManager *pc_assets_open_dir(const char *root);
extern "C" void pc_assets_close_dir(AAssetManager *mgr);

/*
 * ncnnMl.cpp's only use of the JNIEnv it is handed for the manager is this
 * call, so jnilite does not have to model an AssetManager object at all: the
 * jobject already is the AAssetManager.
 */
extern "C" JNIEXPORT AAssetManager *AAssetManager_fromJava(JNIEnv *, jobject assetManager)
{
	return (AAssetManager *) (void *) assetManager;
}

extern "C" {
JNIEXPORT jboolean JNICALL ML(NcnnMl, nativeEnsureInit)(JNIEnv *, jclass);
JNIEXPORT jlong JNICALL ML(FlowNetNcnnProcessor, nativeCreate)(JNIEnv *, jclass, jobject,
                                                               jstring);
JNIEXPORT jboolean JNICALL ML(FlowNetNcnnProcessor, nativeRun)(JNIEnv *, jclass, jlong, jobject,
                                                               jobject, jint, jint, jobject);
JNIEXPORT void JNICALL ML(FlowNetNcnnProcessor, nativeDestroy)(JNIEnv *, jclass, jlong);
JNIEXPORT jlong JNICALL ML(KernelNetNcnnProcessor, nativeCreate)(JNIEnv *, jclass, jobject,
                                                                 jstring);
JNIEXPORT jboolean JNICALL ML(KernelNetNcnnProcessor, nativeRun)(JNIEnv *, jclass, jlong, jobject,
                                                                 jint, jint, jfloat, jobject);
JNIEXPORT void JNICALL ML(KernelNetNcnnProcessor, nativeDestroy)(JNIEnv *, jclass, jlong);
}

int32_t pc_ncnn_available(void) { return 1; }

int32_t pc_ncnn_nativeEnsureInit(void)
{
	return ML(NcnnMl, nativeEnsureInit)(E, 0) == JNI_TRUE ? 1 : 0;
}

int64_t pc_flownet_nativeCreate(const char *assetRoot, const char *paramPath)
{
	AAssetManager *mgr = pc_assets_open_dir(assetRoot ? assetRoot : ".");
	if (!mgr)
		return 0;
	jlong h = ML(FlowNetNcnnProcessor, nativeCreate)(E, 0, (jobject) (void *) mgr,
	                                                 AS_STR(paramPath));
	if (h == 0)
		pc_assets_close_dir(mgr);
	return (int64_t) h;
}

int32_t pc_flownet_nativeRun(int64_t handle, const float *baseRgba, const float *alterRgba,
                             int32_t width, int32_t height, float *flowOut)
{
	jl_buf b = jl_bufOf(const_cast<float *>(baseRgba), 0);
	jl_buf a = jl_bufOf(const_cast<float *>(alterRgba), 0);
	jl_buf o = jl_bufOf(flowOut, 0);
	return ML(FlowNetNcnnProcessor, nativeRun)(E, 0, (jlong) handle, AS_BUF(&b), AS_BUF(&a),
	                                           (jint) width, (jint) height,
	                                           AS_BUF(&o)) == JNI_TRUE
	               ? 1
	               : 0;
}

void pc_flownet_nativeDestroy(int64_t handle)
{
	ML(FlowNetNcnnProcessor, nativeDestroy)(E, 0, (jlong) handle);
}

int64_t pc_kernelnet_nativeCreate(const char *assetRoot, const char *paramPath)
{
	AAssetManager *mgr = pc_assets_open_dir(assetRoot ? assetRoot : ".");
	if (!mgr)
		return 0;
	jlong h = ML(KernelNetNcnnProcessor, nativeCreate)(E, 0, (jobject) (void *) mgr,
	                                                   AS_STR(paramPath));
	if (h == 0)
		pc_assets_close_dir(mgr);
	return (int64_t) h;
}

int32_t pc_kernelnet_nativeRun(int64_t handle, const float *gray, int32_t width, int32_t height,
                               float sigma, float *out)
{
	jl_buf g = jl_bufOf(const_cast<float *>(gray), 0);
	jl_buf o = jl_bufOf(out, 0);
	return ML(KernelNetNcnnProcessor, nativeRun)(E, 0, (jlong) handle, AS_BUF(&g),
	                                             (jint) width, (jint) height, (jfloat) sigma,
	                                             AS_BUF(&o)) == JNI_TRUE
	               ? 1
	               : 0;
}

void pc_kernelnet_nativeDestroy(int64_t handle)
{
	ML(KernelNetNcnnProcessor, nativeDestroy)(E, 0, (jlong) handle);
}

#else /* !PHOTONCAM_WITH_NCNN */

/* Same answer the Android CMakeLists' generated stub gives for an ABI with no
 * ncnn prebuilt: everything loads, every call says "not available". */
int32_t pc_ncnn_available(void) { return 0; }
int32_t pc_ncnn_nativeEnsureInit(void) { return 0; }
int64_t pc_flownet_nativeCreate(const char *, const char *) { return 0; }
int32_t pc_flownet_nativeRun(int64_t, const float *, const float *, int32_t, int32_t, float *)
{
	return 0;
}
void pc_flownet_nativeDestroy(int64_t) {}
int64_t pc_kernelnet_nativeCreate(const char *, const char *) { return 0; }
int32_t pc_kernelnet_nativeRun(int64_t, const float *, int32_t, int32_t, float, float *)
{
	return 0;
}
void pc_kernelnet_nativeDestroy(int64_t) {}

#endif /* PHOTONCAM_WITH_NCNN */

/* =======================================================================
 * NativeEngine
 *
 * native-engine.cpp reads ART's internal ArtMethod/ArtField tables to hand the
 * app java.lang.reflect objects for hidden camera2 members.  Off Android there
 * is no ART, no hidden API and no reflection to bypass, so the library is not
 * compiled and this reports "not available".  NativeEngine.java already treats
 * that as the signal to use its plain-Java fallback.
 * ===================================================================== */

int32_t pc_nativeengine_available(void)
{
	return 0;
}

void pc_nativeengine_nativeInitialize(void)
{
}
