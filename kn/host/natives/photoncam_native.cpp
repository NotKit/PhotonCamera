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

extern "C" JNIEXPORT void JNICALL DNG(freeDNG)(JNIEnv *, jobject, jobject);

void pc_dng_freeDNG(void *dngData, int64_t size)
{
	if (!dngData)
		return;
	jl_buf b = jl_bufOf(dngData, size);
	DNG(freeDNG)(E, OBJ, AS_BUF(&b));
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
                               float sigma, void *out)
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
int32_t pc_kernelnet_nativeRun(int64_t, const float *, int32_t, int32_t, float, void *)
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

/* =======================================================================
 * Allocator, continued: the crop copies, fp16 raw, bit packing, YUV tiles
 * ===================================================================== */

extern "C" {
JNIEXPORT jobject JNICALL ALLOC(allocateAndCopyCrop)(JNIEnv *, jclass, jint, jint, jobject, jint,
                                                     jint);
JNIEXPORT jobject JNICALL ALLOC(allocateAndCopyCropBinning)(JNIEnv *, jclass, jint, jint, jobject,
                                                            jint, jint);
JNIEXPORT jobject JNICALL ALLOC(createF16)(JNIEnv *, jclass, jobject, jint, jint, jint,
                                           jfloatArray);
JNIEXPORT jobject JNICALL ALLOC(createU16FromF16)(JNIEnv *, jclass, jobject, jint, jint, jint,
                                                  jfloatArray);
JNIEXPORT jobject JNICALL ALLOC(packBits)(JNIEnv *, jclass, jobject, jint, jint, jboolean);
JNIEXPORT void JNICALL ALLOC(unpack16)(JNIEnv *, jclass, jobject, jobject, jint, jint);
JNIEXPORT void JNICALL ALLOC(rgbaToYuv420Tile)(JNIEnv *, jclass, jobject, jint, jint, jint, jint,
                                               jint, jint, jobject, jint, jint, jobject, jint,
                                               jint, jobject, jint, jint);
}

void *pc_alloc_allocateAndCopyCrop(int32_t cropWidthBytes, int32_t cropHeight, void *origin,
                                   int64_t originCapacity, int32_t row_stride, int32_t offset,
                                   int64_t *outCapacity)
{
	jl_buf o = jl_bufOf(origin, originCapacity);
	return jnilite_take_buffer(
	        ALLOC(allocateAndCopyCrop)(E, 0, (jint) cropWidthBytes, (jint) cropHeight,
	                                   AS_BUF(&o), (jint) row_stride, (jint) offset),
	        outCapacity);
}

void *pc_alloc_allocateAndCopyCropBinning(int32_t cropWidth, int32_t cropHeight, void *origin,
                                          int64_t originCapacity, int32_t row_stride,
                                          int32_t offset, int64_t *outCapacity)
{
	jl_buf o = jl_bufOf(origin, originCapacity);
	return jnilite_take_buffer(
	        ALLOC(allocateAndCopyCropBinning)(E, 0, (jint) cropWidth, (jint) cropHeight,
	                                          AS_BUF(&o), (jint) row_stride, (jint) offset),
	        outCapacity);
}

void *pc_alloc_createF16(void *origin, int64_t originCapacity, int32_t width, int32_t height,
                         int32_t whiteLevel, const float *blackLevel, int32_t blackLevelCount,
                         int64_t *outCapacity)
{
	jl_buf o = jl_bufOf(origin, originCapacity);
	jl_array bl = jl_arr(blackLevel, blackLevelCount);
	return jnilite_take_buffer(
	        ALLOC(createF16)(E, 0, AS_BUF(&o), (jint) width, (jint) height, (jint) whiteLevel,
	                         (jfloatArray) AS_ARR(blackLevel ? &bl : 0)),
	        outCapacity);
}

void *pc_alloc_createU16FromF16(void *origin, int64_t originCapacity, int32_t width,
                                int32_t height, int32_t whiteLevel, const float *blackLevel,
                                int32_t blackLevelCount, int64_t *outCapacity)
{
	jl_buf o = jl_bufOf(origin, originCapacity);
	jl_array bl = jl_arr(blackLevel, blackLevelCount);
	return jnilite_take_buffer(
	        ALLOC(createU16FromF16)(E, 0, AS_BUF(&o), (jint) width, (jint) height,
	                                (jint) whiteLevel,
	                                (jfloatArray) AS_ARR(blackLevel ? &bl : 0)),
	        outCapacity);
}

void *pc_alloc_packBits(void *src, int64_t srcCapacity, int32_t pixels, int32_t bits,
                        int32_t verify, int64_t *outCapacity)
{
	jl_buf s = jl_bufOf(src, srcCapacity);
	return jnilite_take_buffer(
	        ALLOC(packBits)(E, 0, src ? AS_BUF(&s) : 0, (jint) pixels, (jint) bits,
	                        verify ? JNI_TRUE : JNI_FALSE),
	        outCapacity);
}

void pc_alloc_unpack16(void *dst, int64_t dstCapacity, void *packed, int64_t packedCapacity,
                       int32_t pixels, int32_t bits)
{
	jl_buf d = jl_bufOf(dst, dstCapacity);
	jl_buf p = jl_bufOf(packed, packedCapacity);
	ALLOC(unpack16)(E, 0, dst ? AS_BUF(&d) : 0, packed ? AS_BUF(&p) : 0, (jint) pixels,
	                (jint) bits);
}

void pc_alloc_rgbaToYuv420Tile(void *src, int64_t srcCapacity, int32_t fullWidth,
                               int32_t fullHeight, int32_t tileX, int32_t tileY,
                               int32_t tileWidth, int32_t tileHeight,
                               void *yPlane, int64_t yCapacity, int32_t yRowStride,
                               int32_t yPixelStride,
                               void *uPlane, int64_t uCapacity, int32_t uvRowStride,
                               int32_t uPixelStride,
                               void *vPlane, int64_t vCapacity, int32_t vRowStride,
                               int32_t vPixelStride)
{
	jl_buf s = jl_bufOf(src, srcCapacity);
	jl_buf y = jl_bufOf(yPlane, yCapacity);
	jl_buf u = jl_bufOf(uPlane, uCapacity);
	jl_buf v = jl_bufOf(vPlane, vCapacity);
	ALLOC(rgbaToYuv420Tile)(E, 0, AS_BUF(&s), (jint) fullWidth, (jint) fullHeight, (jint) tileX,
	                        (jint) tileY, (jint) tileWidth, (jint) tileHeight, AS_BUF(&y),
	                        (jint) yRowStride, (jint) yPixelStride, AS_BUF(&u),
	                        (jint) uvRowStride, (jint) uPixelStride, AS_BUF(&v),
	                        (jint) vRowStride, (jint) vPixelStride);
}

/* =======================================================================
 * HalideAlignment
 *
 * The kernels throw nothing here: jnilite's FindClass answers NULL, so
 * align_jni.cpp's throw_kernel_error only logs, and the error code or the NULL
 * result is what the caller sees.
 * ===================================================================== */

#define HALIDE(name) Java_com_particlesdevs_photoncamera_processing_cpu_HalideAlignment_##name

#if PHOTONCAM_WITH_HALIDE

extern "C" {
JNIEXPORT jlong JNICALL HALIDE(nInit)(JNIEnv *, jclass, jint, jint);
JNIEXPORT jint JNICALL HALIDE(nBase)(JNIEnv *, jclass, jlong, jobject, jfloat);
JNIEXPORT jfloatArray JNICALL HALIDE(nAlignFrame)(JNIEnv *, jclass, jlong, jobject, jfloat);
JNIEXPORT void JNICALL HALIDE(nRelease)(JNIEnv *, jclass, jlong);
JNIEXPORT void JNICALL HALIDE(nSetThreads)(JNIEnv *, jclass, jint);
}

int32_t pc_halide_available(void) { return 1; }

int64_t pc_halide_nInit(int32_t rawW, int32_t rawH)
{
	return (int64_t) HALIDE(nInit)(E, 0, (jint) rawW, (jint) rawH);
}

int32_t pc_halide_nBase(int64_t ctx, void *raw, int64_t rawCapacity, float white)
{
	jl_buf r = jl_bufOf(raw, rawCapacity);
	return (int32_t) HALIDE(nBase)(E, 0, (jlong) ctx, AS_BUF(&r), (jfloat) white);
}

int32_t pc_halide_nAlignFrame(int64_t ctx, void *raw, int64_t rawCapacity, float white,
                              float **out)
{
	jl_buf r = jl_bufOf(raw, rawCapacity);
	jfloatArray a = HALIDE(nAlignFrame)(E, 0, (jlong) ctx, AS_BUF(&r), (jfloat) white);
	if (!a) {
		*out = NULL;
		return -1;
	}
	/* NewFloatArray's block: the descriptor with the elements behind it. */
	jl_array *arr = (jl_array *) (void *) a;
	*out = (float *) arr->data;
	return (int32_t) arr->length;
}

void pc_halide_freeResult(float *result)
{
	if (result)
		jnilite_free_array((jarray) (void *) ((jl_array *) (void *) result - 1));
}

void pc_halide_nRelease(int64_t ctx)
{
	HALIDE(nRelease)(E, 0, (jlong) ctx);
}

void pc_halide_nSetThreads(int32_t n)
{
	HALIDE(nSetThreads)(E, 0, (jint) n);
}

#else /* !PHOTONCAM_WITH_HALIDE */

/* No kernels for this architecture, as on the app's armeabi-v7a. */
int32_t pc_halide_available(void) { return 0; }
int64_t pc_halide_nInit(int32_t, int32_t) { return 0; }
int32_t pc_halide_nBase(int64_t, void *, int64_t, float) { return -1; }
int32_t pc_halide_nAlignFrame(int64_t, void *, int64_t, float, float **out)
{
	*out = NULL;
	return -1;
}
void pc_halide_freeResult(float *) {}
void pc_halide_nRelease(int64_t) {}
void pc_halide_nSetThreads(int32_t) {}

#endif /* PHOTONCAM_WITH_HALIDE */

/* =======================================================================
 * McrawWriter
 * ===================================================================== */

#define MCRAW(name) Java_com_particlesdevs_photoncamera_util_McrawWriter_##name

extern "C" {
JNIEXPORT jlong JNICALL MCRAW(nativeCreate)(JNIEnv *, jclass, jint, jstring);
JNIEXPORT jint JNICALL MCRAW(nativeEncode)(JNIEnv *, jclass, jobject, jint, jint, jint, jboolean,
                                           jint, jint, jboolean, jobject);
JNIEXPORT jint JNICALL MCRAW(nativeWriteFrame)(JNIEnv *, jclass, jlong, jobject, jint, jlong,
                                               jstring);
JNIEXPORT jlong JNICALL MCRAW(nativeFrameCount)(JNIEnv *, jclass, jlong);
JNIEXPORT void JNICALL MCRAW(nativeWriteAudio)(JNIEnv *, jclass, jlong, jshortArray, jint, jlong);
JNIEXPORT void JNICALL MCRAW(nativeWriteGyro)(JNIEnv *, jclass, jlong, jlongArray, jfloatArray,
                                              jfloatArray, jfloatArray, jint);
JNIEXPORT void JNICALL MCRAW(nativeClose)(JNIEnv *, jclass, jlong);
}

int64_t pc_mcraw_nativeCreate(int32_t fd, const char *metadata)
{
	return (int64_t) MCRAW(nativeCreate)(E, 0, (jint) fd, AS_STR(metadata));
}

int32_t pc_mcraw_nativeEncode(void *plane, int64_t planeCapacity, int32_t width, int32_t height,
                              int32_t stride, int32_t raw10, int32_t cropTop, int32_t cropHeight,
                              int32_t bin, void *outputSlot, int64_t outputCapacity)
{
	jl_buf p = jl_bufOf(plane, planeCapacity);
	jl_buf o = jl_bufOf(outputSlot, outputCapacity);
	return (int32_t) MCRAW(nativeEncode)(E, 0, AS_BUF(&p), (jint) width, (jint) height,
	                                     (jint) stride, raw10 ? JNI_TRUE : JNI_FALSE,
	                                     (jint) cropTop, (jint) cropHeight,
	                                     bin ? JNI_TRUE : JNI_FALSE, AS_BUF(&o));
}

int32_t pc_mcraw_nativeWriteFrame(int64_t ptr, void *encoded, int64_t encodedCapacity,
                                  int32_t length, int64_t timestampNs, const char *metadata)
{
	jl_buf e = jl_bufOf(encoded, encodedCapacity);
	return (int32_t) MCRAW(nativeWriteFrame)(E, 0, (jlong) ptr, AS_BUF(&e), (jint) length,
	                                         (jlong) timestampNs, AS_STR(metadata));
}

int64_t pc_mcraw_nativeFrameCount(int64_t ptr)
{
	return (int64_t) MCRAW(nativeFrameCount)(E, 0, (jlong) ptr);
}

void pc_mcraw_nativeWriteAudio(int64_t ptr, const int16_t *samples, int32_t samplesLength,
                               int32_t count, int64_t timestampNs)
{
	jl_array a = jl_arr(samples, samplesLength);
	MCRAW(nativeWriteAudio)(E, 0, (jlong) ptr, (jshortArray) AS_ARR(samples ? &a : 0),
	                        (jint) count, (jlong) timestampNs);
}

void pc_mcraw_nativeWriteGyro(int64_t ptr, const int64_t *timestamps, const float *x,
                              const float *y, const float *z, int32_t count)
{
	jl_array t = jl_arr(timestamps, count);
	jl_array ax = jl_arr(x, count);
	jl_array ay = jl_arr(y, count);
	jl_array az = jl_arr(z, count);
	MCRAW(nativeWriteGyro)(E, 0, (jlong) ptr, (jlongArray) AS_ARR(&t), (jfloatArray) AS_ARR(&ax),
	                       (jfloatArray) AS_ARR(&ay), (jfloatArray) AS_ARR(&az), (jint) count);
}

void pc_mcraw_nativeClose(int64_t ptr)
{
	MCRAW(nativeClose)(E, 0, (jlong) ptr);
}
