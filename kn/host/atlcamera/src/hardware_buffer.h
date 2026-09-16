/* atl's HardwareBuffer header is JNI-shaped; camera_record.c only needs the
 * gralloc plane lock out of it, and this port has no gralloc (missing.c). */
#ifndef ATL_HARDWARE_BUFFER_H
#define ATL_HARDWARE_BUFFER_H

#include <stdbool.h>

#include "window_frame.h"

bool atl_gralloc_lock_planes(void *ahardwarebuffer, struct atl_window_frame *frame);
void atl_gralloc_unlock(void *ahardwarebuffer);

#endif
