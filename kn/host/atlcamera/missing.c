/*
 * The pieces of atl-touch the camera backend links against and this port does
 * not have: the GStreamer and libhybris Camera1 backends, and Skia's image
 * encoders.  A NULL backend getter is the same answer atl gives when those
 * libraries fail to load.
 */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "camera_backend.h"
#include "camera_frame.h"

const struct atl_camera_backend *atl_camera_backend_gst_get(void) { return NULL; }
const struct atl_camera_backend *atl_camera_backend_hybris_get(void) { return NULL; }

#ifndef ATLCAMERA_CAMERA2NDK
const struct atl_camera_backend *atl_camera_backend_camera2ndk_get(void) { return NULL; }
#endif

/* camera_frame.c's ATL_CAMERA_DUMP_FRAMES path and the emulated JPEG stream;
 * the port has no Skia, and neither is on the RAW capture path. */
bool atl_camera_write_png(const char *path, const uint8_t *rgba, int width, int height)
{
	(void)path; (void)rgba; (void)width; (void)height;
	return false;
}

bool atl_camera_encode_jpeg(const uint8_t *rgba, int width, int height, int quality,
                            uint8_t **out, size_t *out_size)
{
	(void)rgba; (void)width; (void)height; (void)quality;
	*out = NULL;
	*out_size = 0;
	return false;
}

/* no gralloc here: a HAL buffer cannot be recorded, which only ATL_CAMERA_RECORD
 * asks for. */
#include "hardware_buffer.h"

bool atl_gralloc_lock_planes(void *ahardwarebuffer, struct atl_window_frame *frame)
{
	(void)ahardwarebuffer; (void)frame;
	return false;
}

void atl_gralloc_unlock(void *ahardwarebuffer) { (void)ahardwarebuffer; }
