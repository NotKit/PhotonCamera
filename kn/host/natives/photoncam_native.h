/*
 * photoncam_native.h - the C ABI the Kotlin/Native port calls the app's own
 * native code through.
 *
 * app/src/main/cpp is 64 JNI entry points and is never edited.
 * Kotlin/Native has no JNI, so each entry point gets a plain-C wrapper here.
 * The wrappers (photoncam_native.cpp) forward to the very same Java_* functions
 * through jnilite's pointer-shaped JNIEnv, so the bodies below the ABI are the
 * app's, unmodified and undivided.
 *
 * Conventions, uniform across the five libraries:
 *   - a Java `long` handle stays an int64_t handle; 0 means "none".
 *   - a Java array becomes a pointer plus an element count.
 *   - a Java direct ByteBuffer becomes an address plus a capacity in bytes.
 *   - a Java String becomes a NUL-terminated const char *, owned by the caller.
 *   - a Java boolean becomes an int32_t, 0 or 1.
 * Nothing in this header mentions jni.h, and nothing in it may.
 */
#ifndef PHOTONCAM_NATIVE_H
#define PHOTONCAM_NATIVE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------------
 * com.particlesdevs.photoncamera.util.Allocator  (allocator.cpp)
 *
 * Every allocate* returns the address of a malloc'd block and writes its size
 * to *outCapacity - which is not always the `capacity` argument: the convert
 * and binning variants size the output from the geometry instead, and that
 * arithmetic stays in allocator.cpp where it belongs.  pc_alloc_free takes the
 * address back.
 * ---------------------------------------------------------------------- */
void *pc_alloc_allocate(int32_t capacity, int64_t *outCapacity);
void *pc_alloc_allocateAndCopy(int32_t capacity, void *origin, int64_t originCapacity,
                               int32_t offset, int64_t *outCapacity);
void *pc_alloc_allocateAndCopyConvert(int32_t capacity, void *origin, int64_t originCapacity,
                                      int32_t width, int32_t row_stride, int32_t offset,
                                      int64_t *outCapacity);
void *pc_alloc_allocateAndCopyConvertBinning(int32_t capacity, void *origin,
                                             int64_t originCapacity, int32_t width,
                                             int32_t row_stride, int32_t offset,
                                             int64_t *outCapacity);
void *pc_alloc_allocateAndCopyBinning(int32_t capacity, void *origin, int64_t originCapacity,
                                      int32_t width, int32_t height, int32_t row_stride,
                                      int64_t *outCapacity);
void pc_alloc_free(void *address, int64_t capacity);
int64_t pc_alloc_getMemoryCount(void);

/* ------------------------------------------------------------------------
 * com.particlesdevs.photoncamera.processing.DngCreator  (dngCreator.cpp)
 * ---------------------------------------------------------------------- */
int64_t pc_dng_create(void);
void pc_dng_destroy(int64_t nativePtr);

/* Returns the DNG's address and writes its size to *outSize; NULL on failure.
 * The block belongs to the DngCreator and dies with it. */
void *pc_dng_createDNG(int64_t nativePtr, int32_t width, int32_t height,
                       void *rawImageData, int64_t rawCapacity, int64_t *outSize);

void pc_dng_setOrientation(int64_t nativePtr, int32_t orientation);
void pc_dng_setWhiteLevel(int64_t nativePtr, double whiteLevel);
void pc_dng_setBlackLevel(int64_t nativePtr, const int16_t *blackLevel, int32_t count);
void pc_dng_setColorMatrix1(int64_t nativePtr, const double *matrix, int32_t count);
void pc_dng_setColorMatrix2(int64_t nativePtr, const double *matrix, int32_t count);
void pc_dng_setForwardMatrix1(int64_t nativePtr, const double *matrix, int32_t count);
void pc_dng_setForwardMatrix2(int64_t nativePtr, const double *matrix, int32_t count);
void pc_dng_setCameraCalibration1(int64_t nativePtr, const double *matrix, int32_t count);
void pc_dng_setCameraCalibration2(int64_t nativePtr, const double *matrix, int32_t count);
void pc_dng_setAsShotNeutral(int64_t nativePtr, const double *neutral, int32_t count);
void pc_dng_setAsShotWhiteXY(int64_t nativePtr, double x, double y);
void pc_dng_setAnalogBalance(int64_t nativePtr, const double *balance, int32_t count);
void pc_dng_setCalibrationIlluminant1(int64_t nativePtr, int16_t illuminant);
void pc_dng_setCalibrationIlluminant2(int64_t nativePtr, int16_t illuminant);
void pc_dng_setUniqueCameraModel(int64_t nativePtr, const char *model);
void pc_dng_setDescription(int64_t nativePtr, const char *desc);
void pc_dng_setSoftware(int64_t nativePtr, const char *soft);
void pc_dng_setIso(int64_t nativePtr, int16_t iso);
void pc_dng_setExposureTime(int64_t nativePtr, double exposureTime);
void pc_dng_setCFAPattern(int64_t nativePtr, int32_t pattern);
void pc_dng_setGainMap(int64_t nativePtr, const float *gainMap, int32_t count,
                       int32_t xmin, int32_t ymin, int32_t xmax, int32_t ymax,
                       int32_t width, int32_t height);
void pc_dng_setAperture(int64_t nativePtr, double aperture);
void pc_dng_setFocalLength(int64_t nativePtr, double focalLength);
void pc_dng_setMake(int64_t nativePtr, const char *make);
void pc_dng_setModel(int64_t nativePtr, const char *model);
void pc_dng_setTimeCode(int64_t nativePtr, const int8_t *timecode, int32_t count);
void pc_dng_setDateTime(int64_t nativePtr, const char *datetime);
void pc_dng_setNoiseProfile(int64_t nativePtr, const double *noiseProfile, int32_t count);
void pc_dng_setFrameRate(int64_t nativePtr, double frameRate);
void pc_dng_setCompression(int64_t nativePtr, int32_t useCompression);
void pc_dng_setBitsPerSample(int64_t nativePtr, int32_t bps);
void pc_dng_setBinning(int64_t nativePtr, int32_t binning);
void pc_dng_writeFile(int64_t nativePtr, void *dngBuffer, int64_t dngCapacity,
                      void *raw, int64_t rawCapacity, const char *path, int32_t offset);
void pc_dng_openArchive(int64_t nativePtr, const char *path);
void pc_dng_openArchiveByFd(int64_t nativePtr, int32_t fd);
void pc_dng_closeArchive(int64_t nativePtr);

/* ------------------------------------------------------------------------
 * com.particlesdevs.photoncamera.util.FlacAudioRecorder  (flacRecorder.cpp)
 * ---------------------------------------------------------------------- */
int64_t pc_flac_nativeOpenFd(int32_t fd, int32_t sampleRate, int32_t channels,
                             int32_t bitDepth, int32_t blockSize);
void pc_flac_nativeWriteFrame(int64_t ctx, const int16_t *samples, int32_t sampleCount,
                              int32_t frameCount, int32_t channels);
void pc_flac_nativeClose(int64_t ctx);

/* ------------------------------------------------------------------------
 * com.particlesdevs.photoncamera.processing.ml.*  (ncnnMl.cpp)
 *
 * The Java passes an android AssetManager; there is none here, so the models
 * are read from a directory instead (stubs/asset_dir.c).  assetRoot is that
 * directory and paramPath stays relative to it, exactly as the asset path was.
 * Returns 0 when ncnn was not linked in or the model failed to load.
 * ---------------------------------------------------------------------- */
int32_t pc_ncnn_available(void);
int32_t pc_ncnn_nativeEnsureInit(void);

int64_t pc_flownet_nativeCreate(const char *assetRoot, const char *paramPath);
int32_t pc_flownet_nativeRun(int64_t handle, const float *baseRgba, const float *alterRgba,
                             int32_t width, int32_t height, float *flowOut);
void pc_flownet_nativeDestroy(int64_t handle);

int64_t pc_kernelnet_nativeCreate(const char *assetRoot, const char *paramPath);
int32_t pc_kernelnet_nativeRun(int64_t handle, const float *gray, int32_t width,
                               int32_t height, float sigma, float *out);
void pc_kernelnet_nativeDestroy(int64_t handle);

/* ------------------------------------------------------------------------
 * photoncam.natives.ImageCodec  (image_codec.c)
 *
 * PNG and JPEG, which nothing in app/src/main/cpp does - on Android these are
 * Skia's, through BitmapFactory and Bitmap.compress.  Pixels are ARGB_8888
 * packed 0xAARRGGBB in host order, one uint32_t each, row-major and tightly
 * packed: exactly android.graphics.Bitmap's IntArray on this port.
 *
 * The backend is chosen at build time - libpng + libjpeg-turbo when their
 * headers are there, and a vendored stb_image/stb_image_write otherwise;
 * pc_image_backend() says which, and the two answer the same ABI.
 * ---------------------------------------------------------------------- */

/* "libpng+libjpeg" or "stb". */
const char *pc_image_backend(void);

/* Decode PNG or JPEG (sniffed, not guessed from the name) to ARGB_8888.
 * Returns width*height uint32_t to free with pc_image_free, or NULL. */
uint32_t *pc_image_decode_file(const char *path, int32_t *outWidth, int32_t *outHeight);
uint32_t *pc_image_decode_memory(const void *data, int64_t length,
                                 int32_t *outWidth, int32_t *outHeight);

/* Dimensions only, no pixels: BitmapFactory.Options.inJustDecodeBounds.
 * Returns 1 on success. */
int32_t pc_image_probe_file(const char *path, int32_t *outWidth, int32_t *outHeight);
int32_t pc_image_probe_memory(const void *data, int64_t length,
                              int32_t *outWidth, int32_t *outHeight);

void pc_image_free(uint32_t *pixels);

/* Encode ARGB_8888 to baseline JPEG at `quality` (0-100).  The alpha channel
 * is dropped, as it is on Android.  Returns 1 / the buffer, or 0 / NULL. */
int32_t pc_image_encode_jpeg_file(const uint32_t *pixels, int32_t width, int32_t height,
                                  int32_t quality, const char *path);
uint8_t *pc_image_encode_jpeg_memory(const uint32_t *pixels, int32_t width, int32_t height,
                                     int32_t quality, int64_t *outLength);

void pc_image_free_bytes(uint8_t *data);

/* ------------------------------------------------------------------------
 * com.particlesdevs.photoncamera.api.NativeEngine  (native-engine.cpp)
 *
 * An ART hidden-API bypass: it walks the runtime's own structures to hand back
 * java.lang.reflect objects.  There is no ART and no reflection here, so the
 * library is not built at all and this always answers "not available" - the
 * Java's own fallback path is the one that runs.
 * ---------------------------------------------------------------------- */
int32_t pc_nativeengine_available(void);
void pc_nativeengine_nativeInitialize(void);

#ifdef __cplusplus
}
#endif

#endif /* PHOTONCAM_NATIVE_H */
