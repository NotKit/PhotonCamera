/*
 * PNG and JPEG for the Kotlin/Native port.
 *
 * On Android these are Skia's, reached through BitmapFactory and
 * Bitmap.compress; there is no Skia here and app/src/main/cpp has no image
 * codec of its own, so this file is the one piece of the natives lane that is
 * not a wrapper around the app's own C++.
 *
 * Two backends behind one ABI, picked by the build (build.sh probes for the
 * headers):
 *
 *   PHOTONCAM_CODEC_SYSTEM   libpng's simplified API + libjpeg-turbo.  The
 *                            preferred one: libjpeg's encoder is what the
 *                            app's photo output deserves, and Ubuntu Touch
 *                            ships both libraries.
 *   otherwise                vendored stb_image / stb_image_write, so a target
 *                            without those headers still builds and runs.
 *
 * Everything above the backend is shared: format sniffing, the RGBA <-> ARGB
 * repacking and the file I/O.  A backend only has to do three things, so the
 * two cannot drift apart in behaviour that callers can see.
 */
#define _GNU_SOURCE

#include "photoncam_native.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ImageCodec", __VA_ARGS__)

/* ---------------------------------------------------------------- backends */

#if PHOTONCAM_CODEC_SYSTEM

#include <jpeglib.h>
#include <png.h>
#include <setjmp.h>

const char *pc_image_backend(void) { return "libpng+libjpeg"; }

/* --- PNG, through libpng 1.6's simplified API ---------------------------- */

static uint8_t *png_decode_rgba(const uint8_t *data, size_t len, int *w, int *h)
{
	png_image image;
	memset(&image, 0, sizeof image);
	image.version = PNG_IMAGE_VERSION;
	if (!png_image_begin_read_from_memory(&image, data, len)) {
		LOGE("png: %s", image.message);
		return NULL;
	}
	image.format = PNG_FORMAT_RGBA;
	uint8_t *out = (uint8_t *) malloc(PNG_IMAGE_SIZE(image));
	if (!out) {
		png_image_free(&image);
		return NULL;
	}
	if (!png_image_finish_read(&image, NULL, out, 0, NULL)) {
		LOGE("png: %s", image.message);
		free(out);
		png_image_free(&image);
		return NULL;
	}
	*w = (int) image.width;
	*h = (int) image.height;
	return out;
}

static int png_probe(const uint8_t *data, size_t len, int *w, int *h)
{
	png_image image;
	memset(&image, 0, sizeof image);
	image.version = PNG_IMAGE_VERSION;
	if (!png_image_begin_read_from_memory(&image, data, len))
		return 0;
	*w = (int) image.width;
	*h = (int) image.height;
	png_image_free(&image);
	return 1;
}

/* --- JPEG, through libjpeg ----------------------------------------------- */

struct jpeg_bail {
	struct jpeg_error_mgr mgr;
	jmp_buf jump;
};

static void jpeg_on_error(j_common_ptr info)
{
	struct jpeg_bail *bail = (struct jpeg_bail *) info->err;
	char msg[JMSG_LENGTH_MAX];
	info->err->format_message(info, msg);
	LOGE("jpeg: %s", msg);
	longjmp(bail->jump, 1);
}

static uint8_t *jpeg_decode_rgba(const uint8_t *data, size_t len, int *w, int *h)
{
	struct jpeg_decompress_struct info;
	struct jpeg_bail bail;
	uint8_t *out = NULL;
	uint8_t *row = NULL;

	info.err = jpeg_std_error(&bail.mgr);
	bail.mgr.error_exit = jpeg_on_error;
	if (setjmp(bail.jump)) {
		jpeg_destroy_decompress(&info);
		free(out);
		free(row);
		return NULL;
	}

	jpeg_create_decompress(&info);
	jpeg_mem_src(&info, data, len);
	jpeg_read_header(&info, TRUE);
	info.out_color_space = JCS_RGB;
	jpeg_start_decompress(&info);

	*w = (int) info.output_width;
	*h = (int) info.output_height;
	out = (uint8_t *) malloc((size_t) *w * (size_t) *h * 4);
	row = (uint8_t *) malloc((size_t) *w * 3);
	if (!out || !row)
		longjmp(bail.jump, 1);

	while (info.output_scanline < info.output_height) {
		uint8_t *lines[1] = { row };
		int y = (int) info.output_scanline;
		jpeg_read_scanlines(&info, lines, 1);
		uint8_t *dst = out + (size_t) y * (size_t) *w * 4;
		for (int x = 0; x < *w; x++) {
			dst[x * 4 + 0] = row[x * 3 + 0];
			dst[x * 4 + 1] = row[x * 3 + 1];
			dst[x * 4 + 2] = row[x * 3 + 2];
			dst[x * 4 + 3] = 0xFF;
		}
	}

	jpeg_finish_decompress(&info);
	jpeg_destroy_decompress(&info);
	free(row);
	return out;
}

static int jpeg_probe(const uint8_t *data, size_t len, int *w, int *h)
{
	struct jpeg_decompress_struct info;
	struct jpeg_bail bail;

	info.err = jpeg_std_error(&bail.mgr);
	bail.mgr.error_exit = jpeg_on_error;
	if (setjmp(bail.jump)) {
		jpeg_destroy_decompress(&info);
		return 0;
	}
	jpeg_create_decompress(&info);
	jpeg_mem_src(&info, data, len);
	jpeg_read_header(&info, TRUE);
	*w = (int) info.image_width;
	*h = (int) info.image_height;
	jpeg_destroy_decompress(&info);
	return 1;
}

static uint8_t *jpeg_encode_rgba(const uint8_t *rgba, int w, int h, int quality, size_t *outLen)
{
	struct jpeg_compress_struct info;
	struct jpeg_bail bail;
	uint8_t *buf = NULL;
	unsigned long size = 0;
	uint8_t *row = NULL;

	info.err = jpeg_std_error(&bail.mgr);
	bail.mgr.error_exit = jpeg_on_error;
	if (setjmp(bail.jump)) {
		jpeg_destroy_compress(&info);
		free(row);
		free(buf);
		return NULL;
	}

	jpeg_create_compress(&info);
	jpeg_mem_dest(&info, &buf, &size);
	info.image_width = (unsigned) w;
	info.image_height = (unsigned) h;
	info.input_components = 3;
	info.in_color_space = JCS_RGB;
	jpeg_set_defaults(&info);
	jpeg_set_quality(&info, quality, TRUE);
	jpeg_start_compress(&info, TRUE);

	row = (uint8_t *) malloc((size_t) w * 3);
	if (!row)
		longjmp(bail.jump, 1);

	while (info.next_scanline < info.image_height) {
		const uint8_t *src = rgba + (size_t) info.next_scanline * (size_t) w * 4;
		for (int x = 0; x < w; x++) {
			row[x * 3 + 0] = src[x * 4 + 0];
			row[x * 3 + 1] = src[x * 4 + 1];
			row[x * 3 + 2] = src[x * 4 + 2];
		}
		uint8_t *lines[1] = { row };
		jpeg_write_scanlines(&info, lines, 1);
	}

	jpeg_finish_compress(&info);
	jpeg_destroy_compress(&info);
	free(row);
	*outLen = (size_t) size;
	return buf; /* libjpeg malloc'd it; pc_image_free_bytes frees it */
}

#else /* vendored */

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#define STBI_NO_STDIO
#include "third_party/stb_image.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STBI_WRITE_NO_STDIO
#include "third_party/stb_image_write.h"

const char *pc_image_backend(void) { return "stb"; }

static uint8_t *png_decode_rgba(const uint8_t *data, size_t len, int *w, int *h)
{
	int comp = 0;
	uint8_t *out = stbi_load_from_memory(data, (int) len, w, h, &comp, 4);
	if (!out)
		LOGE("stbi: %s", stbi_failure_reason());
	return out;
}

static int png_probe(const uint8_t *data, size_t len, int *w, int *h)
{
	int comp = 0;
	return stbi_info_from_memory(data, (int) len, w, h, &comp);
}

static uint8_t *jpeg_decode_rgba(const uint8_t *d, size_t n, int *w, int *h)
{
	return png_decode_rgba(d, n, w, h); /* stbi sniffs the format itself */
}

static int jpeg_probe(const uint8_t *d, size_t n, int *w, int *h)
{
	return png_probe(d, n, w, h);
}

struct stb_sink {
	uint8_t *data;
	size_t len;
	int failed;
};

static void stb_collect(void *context, void *data, int size)
{
	struct stb_sink *sink = (struct stb_sink *) context;
	if (sink->failed || size <= 0)
		return;
	uint8_t *grown = (uint8_t *) realloc(sink->data, sink->len + (size_t) size);
	if (!grown) {
		sink->failed = 1;
		return;
	}
	memcpy(grown + sink->len, data, (size_t) size);
	sink->data = grown;
	sink->len += (size_t) size;
}

static uint8_t *jpeg_encode_rgba(const uint8_t *rgba, int w, int h, int quality, size_t *outLen)
{
	/* stb writes 4-component input as RGB for JPEG, dropping alpha itself. */
	struct stb_sink sink = { NULL, 0, 0 };
	if (!stbi_write_jpg_to_func(stb_collect, &sink, w, h, 4, rgba, quality) || sink.failed) {
		free(sink.data);
		return NULL;
	}
	*outLen = sink.len;
	return sink.data;
}

#endif /* PHOTONCAM_CODEC_SYSTEM */

/* ------------------------------------------------------------------ shared */

static int is_png(const uint8_t *d, size_t n)
{
	static const uint8_t magic[8] = { 137, 'P', 'N', 'G', '\r', '\n', 26, '\n' };
	return n >= 8 && memcmp(d, magic, 8) == 0;
}

static int is_jpeg(const uint8_t *d, size_t n)
{
	return n >= 3 && d[0] == 0xFF && d[1] == 0xD8 && d[2] == 0xFF;
}

/* RGBA bytes -> 0xAARRGGBB host-order ints, which is what Bitmap holds. */
static uint32_t *rgba_to_argb(uint8_t *rgba, int w, int h)
{
	size_t n = (size_t) w * (size_t) h;
	uint32_t *out = (uint32_t *) malloc(n * sizeof(uint32_t));
	if (!out) {
		free(rgba);
		return NULL;
	}
	for (size_t i = 0; i < n; i++) {
		out[i] = ((uint32_t) rgba[i * 4 + 3] << 24) | ((uint32_t) rgba[i * 4 + 0] << 16) |
		         ((uint32_t) rgba[i * 4 + 1] << 8) | (uint32_t) rgba[i * 4 + 2];
	}
	free(rgba);
	return out;
}

static uint8_t *argb_to_rgba(const uint32_t *argb, int w, int h)
{
	size_t n = (size_t) w * (size_t) h;
	uint8_t *out = (uint8_t *) malloc(n * 4);
	if (!out)
		return NULL;
	for (size_t i = 0; i < n; i++) {
		uint32_t p = argb[i];
		out[i * 4 + 0] = (uint8_t) (p >> 16);
		out[i * 4 + 1] = (uint8_t) (p >> 8);
		out[i * 4 + 2] = (uint8_t) p;
		out[i * 4 + 3] = (uint8_t) (p >> 24);
	}
	return out;
}

static uint8_t *read_whole_file(const char *path, size_t *outLen)
{
	FILE *f = fopen(path, "rb");
	if (!f)
		return NULL;
	if (fseek(f, 0, SEEK_END) != 0) {
		fclose(f);
		return NULL;
	}
	long size = ftell(f);
	if (size < 0) {
		fclose(f);
		return NULL;
	}
	rewind(f);
	uint8_t *data = (uint8_t *) malloc((size_t) size ? (size_t) size : 1);
	if (!data) {
		fclose(f);
		return NULL;
	}
	size_t got = fread(data, 1, (size_t) size, f);
	fclose(f);
	if (got != (size_t) size) {
		free(data);
		return NULL;
	}
	*outLen = got;
	return data;
}

/* --------------------------------------------------------------- the ABI */

uint32_t *pc_image_decode_memory(const void *data, int64_t length, int32_t *outWidth,
                                 int32_t *outHeight)
{
	const uint8_t *bytes = (const uint8_t *) data;
	size_t len = (size_t) length;
	if (!bytes || length <= 0)
		return NULL;

	int w = 0, h = 0;
	uint8_t *rgba = NULL;
	if (is_png(bytes, len))
		rgba = png_decode_rgba(bytes, len, &w, &h);
	else if (is_jpeg(bytes, len))
		rgba = jpeg_decode_rgba(bytes, len, &w, &h);
	else
		LOGE("not a PNG or JPEG (first bytes %02x %02x)", bytes[0], len > 1 ? bytes[1] : 0);

	if (!rgba || w <= 0 || h <= 0) {
		free(rgba);
		return NULL;
	}
	uint32_t *argb = rgba_to_argb(rgba, w, h);
	if (argb) {
		if (outWidth)
			*outWidth = w;
		if (outHeight)
			*outHeight = h;
	}
	return argb;
}

uint32_t *pc_image_decode_file(const char *path, int32_t *outWidth, int32_t *outHeight)
{
	if (!path)
		return NULL;
	size_t len = 0;
	uint8_t *data = read_whole_file(path, &len);
	if (!data) {
		LOGE("cannot read %s", path);
		return NULL;
	}
	uint32_t *argb = pc_image_decode_memory(data, (int64_t) len, outWidth, outHeight);
	free(data);
	return argb;
}

int32_t pc_image_probe_memory(const void *data, int64_t length, int32_t *outWidth,
                              int32_t *outHeight)
{
	const uint8_t *bytes = (const uint8_t *) data;
	size_t len = (size_t) length;
	if (!bytes || length <= 0)
		return 0;

	int w = 0, h = 0, ok = 0;
	if (is_png(bytes, len))
		ok = png_probe(bytes, len, &w, &h);
	else if (is_jpeg(bytes, len))
		ok = jpeg_probe(bytes, len, &w, &h);
	if (!ok)
		return 0;
	if (outWidth)
		*outWidth = w;
	if (outHeight)
		*outHeight = h;
	return 1;
}

int32_t pc_image_probe_file(const char *path, int32_t *outWidth, int32_t *outHeight)
{
	if (!path)
		return 0;
	size_t len = 0;
	uint8_t *data = read_whole_file(path, &len);
	if (!data)
		return 0;
	int32_t ok = pc_image_probe_memory(data, (int64_t) len, outWidth, outHeight);
	free(data);
	return ok;
}

void pc_image_free(uint32_t *pixels)
{
	free(pixels);
}

uint8_t *pc_image_encode_jpeg_memory(const uint32_t *pixels, int32_t width, int32_t height,
                                     int32_t quality, int64_t *outLength)
{
	if (!pixels || width <= 0 || height <= 0)
		return NULL;
	if (quality < 0)
		quality = 0;
	if (quality > 100)
		quality = 100;

	uint8_t *rgba = argb_to_rgba(pixels, width, height);
	if (!rgba)
		return NULL;
	size_t len = 0;
	uint8_t *jpeg = jpeg_encode_rgba(rgba, width, height, quality, &len);
	free(rgba);
	if (!jpeg)
		return NULL;
	if (outLength)
		*outLength = (int64_t) len;
	return jpeg;
}

int32_t pc_image_encode_jpeg_file(const uint32_t *pixels, int32_t width, int32_t height,
                                  int32_t quality, const char *path)
{
	if (!path)
		return 0;
	int64_t len = 0;
	uint8_t *jpeg = pc_image_encode_jpeg_memory(pixels, width, height, quality, &len);
	if (!jpeg)
		return 0;

	FILE *f = fopen(path, "wb");
	if (!f) {
		LOGE("cannot write %s", path);
		pc_image_free_bytes(jpeg);
		return 0;
	}
	size_t put = fwrite(jpeg, 1, (size_t) len, f);
	int ok = fclose(f) == 0 && put == (size_t) len;
	pc_image_free_bytes(jpeg);
	return ok ? 1 : 0;
}

void pc_image_free_bytes(uint8_t *data)
{
	free(data);
}
