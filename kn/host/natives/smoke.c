/*
 * Proof that photoncam_native.h is a working C ABI over the app's JNI code:
 * allocate/copy/free through allocator.cpp and a DngCreator through
 * dngCreator.cpp, with no JVM anywhere.  Exits 0 when every check holds.
 *
 *   kn/host/natives/smoke.sh
 */
#include "photoncam_native.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(cond)                                                                          \
	do {                                                                                 \
		if (!(cond)) {                                                               \
			fprintf(stderr, "smoke: FAILED %s (line %d)\n", #cond, __LINE__);    \
			return 1;                                                            \
		}                                                                            \
	} while (0)

int main(void)
{
	/* Allocator: a direct buffer, a copy of it at an offset, then both back. */
	int64_t cap = 0;
	int64_t before = pc_alloc_getMemoryCount();
	unsigned char *a = (unsigned char *) pc_alloc_allocate(1024, &cap);
	CHECK(a != NULL);
	CHECK(cap == 1024);
	CHECK(pc_alloc_getMemoryCount() == before + 1024);
	memset(a, 0xA5, 1024);

	unsigned char *b = (unsigned char *) pc_alloc_allocateAndCopy(512, a, 1024, 256, &cap);
	CHECK(b != NULL);
	CHECK(cap == 512);
	CHECK(b[0] == 0xA5 && b[511] == 0xA5);

	pc_alloc_free(b, 512);
	pc_alloc_free(a, 1024);
	CHECK(pc_alloc_getMemoryCount() == before);

	/* DngCreator: create, set a tag through each marshalling shape, destroy. */
	int64_t dng = pc_dng_create();
	CHECK(dng != 0);
	const double colorMatrix[9] = { 1, 0, 0, 0, 1, 0, 0, 0, 1 };
	const int16_t blackLevel[4] = { 64, 64, 64, 64 };
	pc_dng_setColorMatrix1(dng, colorMatrix, 9);   /* double[] */
	pc_dng_setBlackLevel(dng, blackLevel, 4);      /* short[]  */
	pc_dng_setWhiteLevel(dng, 1023.0);             /* double   */
	pc_dng_setIso(dng, 100);                       /* short    */
	pc_dng_setOrientation(dng, 1);                 /* int      */
	pc_dng_setBinning(dng, 0);                     /* boolean  */
	pc_dng_setModel(dng, "photoncamera-kn");       /* String   */
	pc_dng_destroy(dng);

	/* ImageCodec: a real PNG out of the app's own resources, decoded, probed,
	 * re-encoded as JPEG and decoded again. */
	int32_t w = 0, h = 0;
	uint32_t *pixels = pc_image_decode_file(SMOKE_PNG, &w, &h);
	CHECK(pixels != NULL);
	CHECK(w > 0 && h > 0);

	int32_t pw = 0, ph = 0;
	CHECK(pc_image_probe_file(SMOKE_PNG, &pw, &ph) == 1);
	CHECK(pw == w && ph == h);

	int64_t jpegLen = 0;
	uint8_t *jpeg = pc_image_encode_jpeg_memory(pixels, w, h, 90, &jpegLen);
	CHECK(jpeg != NULL);
	CHECK(jpegLen > 4);
	CHECK(jpeg[0] == 0xFF && jpeg[1] == 0xD8 && jpeg[2] == 0xFF); /* SOI */

	int32_t jw = 0, jh = 0;
	uint32_t *back = pc_image_decode_memory(jpeg, jpegLen, &jw, &jh);
	CHECK(back != NULL);
	CHECK(jw == w && jh == h);

	CHECK(pc_image_encode_jpeg_file(pixels, w, h, 90, SMOKE_JPEG) == 1);

	pc_image_free(back);
	pc_image_free_bytes(jpeg);
	pc_image_free(pixels);

	printf("smoke: ok (ncnn=%d, nativeEngine=%d, codec=%s, %dx%d png -> %lld byte jpeg)\n",
	       pc_ncnn_available(), pc_nativeengine_available(), pc_image_backend(), w, h,
	       (long long) jpegLen);
	return 0;
}
