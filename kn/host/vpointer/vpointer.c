/* kn-app's copy of ~/.local/share/atl-dev-tools/wl-vpointer/vpointer.c, with
 * three commands added: `at <x> <y>` (absolute motion, so a tap can be aimed at
 * a link without counting relative steps), `axis <dx> <dy>` (a wheel, which is
 * the ACTION_SCROLL the document actually moves under) and `drag`.
 *
 * Why a virtual pointer at all: the headless sway display.sh brings up has no
 * input devices (WLR_LIBINPUT_NO_DEVICES=1), so it advertises no pointer
 * capability and there is nothing to move.  A zwlr_virtual_pointer_v1 gives the
 * COMPOSITOR a device; every event after that is a real one, routed by sway to
 * whichever surface is focused, and it arrives at wlpost.c's wl_pointer
 * listener exactly as a finger would.  This is not injection into the app.
 *
 * Minimal persistent wlr-virtual-pointer client: holds one connection open
 * across an entire sequence of moves/clicks so the seat's pointer capability
 * doesn't flicker off between actions (which is what happens when using
 * wlrctl's one-shot-per-invocation model against a compositor with no real
 * input devices, e.g. WLR_LIBINPUT_NO_DEVICES=1). Commands read from argv:
 *   move <dx> <dy>       relative motion
 *   click                BTN_LEFT down+up
 *   down / up            BTN_LEFT press / release only
 *   sleep <ms>
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wayland-client.h>
#include "wlr-virtual-pointer-unstable-v1-client-protocol.h"

static struct wl_seat *seat = NULL;
static struct zwlr_virtual_pointer_manager_v1 *manager = NULL;
static struct zwlr_virtual_pointer_v1 *pointer = NULL;

static uint32_t now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
                             const char *interface, uint32_t version)
{
	(void)data;
	if (strcmp(interface, wl_seat_interface.name) == 0)
		seat = wl_registry_bind(registry, name, &wl_seat_interface, 1);
	else if (strcmp(interface, zwlr_virtual_pointer_manager_v1_interface.name) == 0)
		manager = wl_registry_bind(registry, name, &zwlr_virtual_pointer_manager_v1_interface, 1);
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name)
{
	(void)data; (void)registry; (void)name;
}

static const struct wl_registry_listener registry_listener = {
	.global = registry_global,
	.global_remove = registry_global_remove,
};

static uint32_t extent_w = 720, extent_h = 1440;   /* desktop-run/sway.cfg's output */

int main(int argc, char **argv)
{
	struct wl_display *display = wl_display_connect(NULL);
	if (!display) {
		fprintf(stderr, "failed to connect to wayland display\n");
		return 1;
	}
	struct wl_registry *registry = wl_display_get_registry(display);
	wl_registry_add_listener(registry, &registry_listener, NULL);
	wl_display_roundtrip(display);

	if (!seat || !manager) {
		fprintf(stderr, "missing wl_seat or zwlr_virtual_pointer_manager_v1\n");
		return 1;
	}

	pointer = zwlr_virtual_pointer_manager_v1_create_virtual_pointer(manager, seat);
	wl_display_roundtrip(display);

	int i = 1;
	while (i < argc) {
		if (strcmp(argv[i], "move") == 0 && i + 2 < argc) {
			double dx = atof(argv[i + 1]);
			double dy = atof(argv[i + 2]);
			zwlr_virtual_pointer_v1_motion(pointer, now_ms(), wl_fixed_from_double(dx), wl_fixed_from_double(dy));
			zwlr_virtual_pointer_v1_frame(pointer);
			i += 3;
		} else if (strcmp(argv[i], "click") == 0) {
			zwlr_virtual_pointer_v1_button(pointer, now_ms(), 0x110 /* BTN_LEFT */, 1);
			zwlr_virtual_pointer_v1_frame(pointer);
			wl_display_flush(display);
			struct timespec ts = {0, 60 * 1000000};
			nanosleep(&ts, NULL);
			zwlr_virtual_pointer_v1_button(pointer, now_ms(), 0x110, 0);
			zwlr_virtual_pointer_v1_frame(pointer);
			i += 1;
		} else if (strcmp(argv[i], "down") == 0) {
			zwlr_virtual_pointer_v1_button(pointer, now_ms(), 0x110, 1);
			zwlr_virtual_pointer_v1_frame(pointer);
			i += 1;
		} else if (strcmp(argv[i], "up") == 0) {
			zwlr_virtual_pointer_v1_button(pointer, now_ms(), 0x110, 0);
			zwlr_virtual_pointer_v1_frame(pointer);
			i += 1;
		} else if (strcmp(argv[i], "at") == 0 && i + 2 < argc) {
			/* absolute, in the extent below -- the compositor's output size,
			 * which is what motion_absolute is scaled against */
			uint32_t x = (uint32_t)atoi(argv[i + 1]);
			uint32_t y = (uint32_t)atoi(argv[i + 2]);
			zwlr_virtual_pointer_v1_motion_absolute(pointer, now_ms(), x, y,
			                                        extent_w, extent_h);
			zwlr_virtual_pointer_v1_frame(pointer);
			i += 3;
		} else if (strcmp(argv[i], "axis") == 0 && i + 2 < argc) {
			/* a wheel.  Wayland's sign is the compositor's: positive is
			 * toward the user, i.e. the content scrolls DOWN. */
			double dx = atof(argv[i + 1]);
			double dy = atof(argv[i + 2]);
			if (dy != 0) {
				zwlr_virtual_pointer_v1_axis(pointer, now_ms(),
				    WL_POINTER_AXIS_VERTICAL_SCROLL, wl_fixed_from_double(dy));
			}
			if (dx != 0) {
				zwlr_virtual_pointer_v1_axis(pointer, now_ms(),
				    WL_POINTER_AXIS_HORIZONTAL_SCROLL, wl_fixed_from_double(dx));
			}
			zwlr_virtual_pointer_v1_frame(pointer);
			i += 3;
		} else if (strcmp(argv[i], "extent") == 0 && i + 2 < argc) {
			extent_w = (uint32_t)atoi(argv[i + 1]);
			extent_h = (uint32_t)atoi(argv[i + 2]);
			i += 3;
		} else if (strcmp(argv[i], "sleep") == 0 && i + 1 < argc) {
			long ms = atol(argv[i + 1]);
			struct timespec ts = { ms / 1000, (ms % 1000) * 1000000 };
			nanosleep(&ts, NULL);
			i += 2;
		} else {
			fprintf(stderr, "unknown command: %s\n", argv[i]);
			return 1;
		}
		wl_display_flush(display);
	}

	/* keep the connection (and thus the pointer capability) alive briefly
	 * after the last event so the compositor has time to dispatch it to
	 * clients before we tear the virtual pointer down. */
	struct timespec ts = {0, 150 * 1000000};
	nanosleep(&ts, NULL);

	zwlr_virtual_pointer_v1_destroy(pointer);
	wl_display_roundtrip(display);
	wl_display_disconnect(display);
	return 0;
}
