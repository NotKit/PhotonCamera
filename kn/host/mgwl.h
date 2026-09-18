/*
 * mgwl - minimal raw-Wayland host for the MonoGram JVM/Compose app on Ubuntu Touch.
 *
 * A small C glue library: connects to Mir 1.8 (Lomiri), maps one fullscreen
 * xdg_wm_base toplevel, gives it a hardware GLES context via wl_egl_window + EGL
 * (hybris handles android_wlegl underneath), and delivers touch/pointer/keyboard
 * input through callbacks. Driven over JNI by the Kotlin ComposeScene host; also
 * usable standalone (mgwl_test.c) to draw a triangle for on-device bring-up.
 *
 * All Wayland dispatch happens on whatever thread calls mgwl_pump(); callbacks
 * fire synchronously inside that call. Keep everything (render + pump) on one
 * thread — libwayland is not casually thread-safe and the EGL context is bound
 * per-thread.
 */
#ifndef MGWL_H
#define MGWL_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct mgwl mgwl;

/* Touch phases. */
enum {
    MGWL_TOUCH_DOWN   = 0,
    MGWL_TOUCH_MOVE   = 1,
    MGWL_TOUCH_UP     = 2,
    MGWL_TOUCH_CANCEL = 3,
};

/* Pointer event kinds. */
enum {
    MGWL_PTR_ENTER  = 0,
    MGWL_PTR_LEAVE  = 1,
    MGWL_PTR_MOTION = 2,
    MGWL_PTR_BUTTON = 3,
    MGWL_PTR_AXIS   = 4,
};

/* Pointer button bitmask (matches Compose's primary/secondary/tertiary order). */
enum {
    MGWL_BTN_LEFT   = 1 << 0,
    MGWL_BTN_RIGHT  = 1 << 1,
    MGWL_BTN_MIDDLE = 1 << 2,
};

/*
 * Event sink. Every field may be NULL. Called from inside mgwl_pump() on the
 * pumping thread. Coordinates are in surface-local logical pixels.
 */
typedef struct {
    void (*configure)(void *user, int width, int height);
    void (*close)(void *user);
    /* id: per-contact slot; phase: MGWL_TOUCH_*. */
    void (*touch)(void *user, int id, double x, double y, int phase);
    /* kind: MGWL_PTR_*; buttons: current MGWL_BTN_* mask; for AXIS, x/y carry scroll delta. */
    void (*pointer)(void *user, int kind, double x, double y, unsigned buttons);
    /* keysym: xkb keysym; utf32: committed codepoint (0 if none); pressed: 1/0; mods: MGWL_MOD_*. */
    void (*key)(void *user, unsigned keysym, unsigned utf32, int pressed, unsigned mods);
    /* wl_surface.frame fired — safe to draw the next frame. */
    void (*frame)(void *user);
    /* Toplevel activation changed: active=1 when focused, 0 when the compositor
     * backgrounds us (app switched away, screen locked). */
    void (*activated)(void *user, int active);
} mgwl_callbacks;

/* Modifier bitmask reported with key events. */
enum {
    MGWL_MOD_SHIFT = 1 << 0,
    MGWL_MOD_CTRL  = 1 << 1,
    MGWL_MOD_ALT   = 1 << 2,
    MGWL_MOD_LOGO  = 1 << 3,
};

/*
 * Connect to the compositor. socket_path may be an absolute path to the Wayland
 * socket (confined UT apps must pass one — XDG_RUNTIME_DIR is remapped) or NULL
 * to use WAYLAND_DISPLAY/XDG_RUNTIME_DIR. Binds the globals we need. Returns NULL
 * on failure.
 */
mgwl *mgwl_create(const char *socket_path);

/*
 * Create the toplevel + EGL/GLES context. width/height are the initial size hint
 * (the compositor usually overrides via configure and fullscreens us). es_version
 * is 2 or 3. Returns 0 on success, negative on failure.
 */
int mgwl_create_window(mgwl *m, const char *app_id, const char *title,
                       int width, int height, int es_version);

void mgwl_set_callbacks(mgwl *m, const mgwl_callbacks *cb, void *user);

/* Bind/unbind the EGL context on the calling thread. */
int  mgwl_make_current(mgwl *m);
void mgwl_swap_buffers(mgwl *m);

int  mgwl_width(mgwl *m);
int  mgwl_height(mgwl *m);
/* Buffer scale the compositor advertised (round(gu/8) on Lomiri); >=1. */
int  mgwl_scale(mgwl *m);

/*
 * Dispatch pending Wayland events, blocking up to timeout_ms for new ones
 * (0 = non-blocking, <0 = block indefinitely). Callbacks fire from here.
 */
void mgwl_pump(mgwl *m, int timeout_ms);

/* Ask the compositor for a frame callback; the frame callback fires once. */
void mgwl_request_frame(mgwl *m);

int  mgwl_should_close(mgwl *m);

/* EGL/GL info strings for logging (valid after mgwl_make_current). */
const char *mgwl_egl_vendor(mgwl *m);
/* The window's EGLDisplay.  There is one EGL display per process on Android
 * and hybris means it here too: the app's offscreen GL has to share this one. */
void *mgwl_egl_display(mgwl *m);
const char *mgwl_gl_renderer(mgwl *m);

void mgwl_destroy(mgwl *m);

#ifdef __cplusplus
}
#endif

#endif /* MGWL_H */
