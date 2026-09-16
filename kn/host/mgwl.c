/* mgwl - see mgwl.h. Core Wayland + EGL/GLES + input glue. */
#define _GNU_SOURCE
#define WL_EGL_PLATFORM 1

#include "mgwl.h"

#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include <wayland-client.h>
#include <wayland-egl.h>
#include <EGL/egl.h>
#include <xkbcommon/xkbcommon.h>

/* We don't link libGLESv2 — skiko owns all real GL. The one GL string we log is
 * fetched through eglGetProcAddress so libmgwl.so's only deps stay EGL + wayland
 * + xkbcommon (all present in the arm64 cross container). */
#define MGWL_GL_RENDERER 0x1F01
typedef const unsigned char *(*gl_get_string_fn)(unsigned);

#include "xdg-shell-client-protocol.h"
#include "mgwl_clip.h"   /* the system clipboard: a wl_data_device on this seat */

#define LOGE(...) do { fprintf(stderr, "[mgwl] " __VA_ARGS__); fputc('\n', stderr); } while (0)

#define MAX_TOUCH 10

struct mgwl {
    struct wl_display    *display;
    struct wl_registry   *registry;
    struct wl_compositor *compositor;
    struct xdg_wm_base   *wm_base;
    struct wl_seat       *seat;
    struct wl_output     *output;
    struct wl_shm        *shm;
    struct wl_data_device_manager *ddm;

    struct wl_surface    *surface;
    struct xdg_surface   *xdg_surface;
    struct xdg_toplevel  *xdg_toplevel;
    struct wl_egl_window *egl_window;
    struct wl_callback   *frame_cb;

    EGLDisplay egl_display;
    EGLConfig  egl_config;
    EGLContext egl_context;
    EGLSurface egl_surface;

    struct wl_touch    *touch;
    struct wl_pointer  *pointer;
    struct wl_keyboard *keyboard;

    struct xkb_context *xkb_ctx;
    struct xkb_keymap  *xkb_keymap;
    struct xkb_state   *xkb_state;

    double   ptr_x, ptr_y;
    unsigned ptr_buttons;

    mgwl_callbacks cb;
    void          *user;

    int width, height;
    int scale;
    int configured;
    int activated;
    int should_close;
    int es_version;
};

/* ---------------------------------------------------------------- xdg_wm_base */

static void wm_base_ping(void *data, struct xdg_wm_base *b, uint32_t serial) {
    (void)data;
    xdg_wm_base_pong(b, serial);
}
static const struct xdg_wm_base_listener wm_base_listener = { wm_base_ping };

/* ---------------------------------------------------------------- xdg_surface */

static void xdg_surface_configure(void *data, struct xdg_surface *s, uint32_t serial) {
    mgwl *m = data;
    xdg_surface_ack_configure(s, serial);
    if (!m->configured) {
        m->configured = 1;
        if (m->cb.configure) m->cb.configure(m->user, m->width, m->height);
    }
}
static const struct xdg_surface_listener xdg_surface_listener = { xdg_surface_configure };

/* --------------------------------------------------------------- xdg_toplevel */

static void toplevel_configure(void *data, struct xdg_toplevel *t, int32_t w, int32_t h,
                               struct wl_array *states) {
    (void)t;
    mgwl *m = data;
    if (w > 0 && h > 0 && (w != m->width || h != m->height)) {
        m->width = w;
        m->height = h;
        if (m->egl_window)
            wl_egl_window_resize(m->egl_window, w * m->scale, h * m->scale, 0, 0);
        if (m->configured && m->cb.configure) m->cb.configure(m->user, w, h);
    }

    int active = 0;
    uint32_t *st;
    wl_array_for_each(st, states)
        if (*st == XDG_TOPLEVEL_STATE_ACTIVATED) active = 1;
    if (active != m->activated) {
        m->activated = active;
        if (m->cb.activated) m->cb.activated(m->user, active);
    }
}
static void toplevel_close(void *data, struct xdg_toplevel *t) {
    (void)t;
    mgwl *m = data;
    m->should_close = 1;
    if (m->cb.close) m->cb.close(m->user);
}
static void toplevel_configure_bounds(void *d, struct xdg_toplevel *t, int32_t w, int32_t h) {
    (void)d; (void)t; (void)w; (void)h;
}
static void toplevel_wm_capabilities(void *d, struct xdg_toplevel *t, struct wl_array *c) {
    (void)d; (void)t; (void)c;
}
static const struct xdg_toplevel_listener toplevel_listener = {
    toplevel_configure, toplevel_close, toplevel_configure_bounds, toplevel_wm_capabilities,
};

/* ---------------------------------------------------------------------- frame */

static const struct wl_callback_listener frame_listener;
static void frame_done(void *data, struct wl_callback *cb, uint32_t t) {
    (void)t;
    mgwl *m = data;
    if (cb) wl_callback_destroy(cb);
    m->frame_cb = NULL;
    if (m->cb.frame) m->cb.frame(m->user);
}
static const struct wl_callback_listener frame_listener = { frame_done };

void mgwl_request_frame(mgwl *m) {
    if (m->frame_cb || !m->surface) return;
    m->frame_cb = wl_surface_frame(m->surface);
    wl_callback_add_listener(m->frame_cb, &frame_listener, m);
    wl_surface_commit(m->surface);
}

/* ---------------------------------------------------------------------- touch */

static void touch_down(void *data, struct wl_touch *t, uint32_t serial, uint32_t time,
                       struct wl_surface *surf, int32_t id, wl_fixed_t x, wl_fixed_t y) {
    (void)t; (void)time; (void)surf;
    mgwl *m = data;
    mgwl_clip_note_serial(serial);
    if (m->cb.touch) m->cb.touch(m->user, id, wl_fixed_to_double(x), wl_fixed_to_double(y),
                                 MGWL_TOUCH_DOWN);
}
static void touch_up(void *data, struct wl_touch *t, uint32_t serial, uint32_t time, int32_t id) {
    (void)t; (void)time;
    mgwl *m = data;
    mgwl_clip_note_serial(serial);
    if (m->cb.touch) m->cb.touch(m->user, id, 0, 0, MGWL_TOUCH_UP);
}
static void touch_motion(void *data, struct wl_touch *t, uint32_t time, int32_t id,
                         wl_fixed_t x, wl_fixed_t y) {
    (void)t; (void)time;
    mgwl *m = data;
    if (m->cb.touch) m->cb.touch(m->user, id, wl_fixed_to_double(x), wl_fixed_to_double(y),
                                 MGWL_TOUCH_MOVE);
}
static void touch_frame(void *data, struct wl_touch *t) { (void)data; (void)t; }
static void touch_cancel(void *data, struct wl_touch *t) {
    (void)t;
    mgwl *m = data;
    if (m->cb.touch) m->cb.touch(m->user, -1, 0, 0, MGWL_TOUCH_CANCEL);
}
static void touch_shape(void *d, struct wl_touch *t, int32_t id, wl_fixed_t a, wl_fixed_t b) {
    (void)d; (void)t; (void)id; (void)a; (void)b;
}
static void touch_orientation(void *d, struct wl_touch *t, int32_t id, wl_fixed_t o) {
    (void)d; (void)t; (void)id; (void)o;
}
static const struct wl_touch_listener touch_listener = {
    .down = touch_down, .up = touch_up, .motion = touch_motion, .frame = touch_frame,
    .cancel = touch_cancel, .shape = touch_shape, .orientation = touch_orientation,
};

/* -------------------------------------------------------------------- pointer */

static void pointer_enter(void *data, struct wl_pointer *p, uint32_t serial,
                          struct wl_surface *surf, wl_fixed_t x, wl_fixed_t y) {
    (void)p; (void)surf;
    mgwl *m = data;
    mgwl_clip_note_serial(serial);
    m->ptr_x = wl_fixed_to_double(x);
    m->ptr_y = wl_fixed_to_double(y);
    if (m->cb.pointer) m->cb.pointer(m->user, MGWL_PTR_ENTER, m->ptr_x, m->ptr_y, m->ptr_buttons);
}
static void pointer_leave(void *data, struct wl_pointer *p, uint32_t serial,
                          struct wl_surface *surf) {
    (void)p; (void)serial; (void)surf;
    mgwl *m = data;
    if (m->cb.pointer) m->cb.pointer(m->user, MGWL_PTR_LEAVE, m->ptr_x, m->ptr_y, m->ptr_buttons);
}
static void pointer_motion(void *data, struct wl_pointer *p, uint32_t time,
                           wl_fixed_t x, wl_fixed_t y) {
    (void)p; (void)time;
    mgwl *m = data;
    m->ptr_x = wl_fixed_to_double(x);
    m->ptr_y = wl_fixed_to_double(y);
    if (m->cb.pointer) m->cb.pointer(m->user, MGWL_PTR_MOTION, m->ptr_x, m->ptr_y, m->ptr_buttons);
}
static void pointer_button(void *data, struct wl_pointer *p, uint32_t serial, uint32_t time,
                           uint32_t button, uint32_t state) {
    (void)p; (void)time;
    mgwl *m = data;
    /* THE SERIAL A COPY WILL CARRY.  A selection set with an invented or stale
     * serial is dropped by the compositor with no error at all, so the newest
     * real one is kept here -- at a copy that is the press that caused it. */
    mgwl_clip_note_serial(serial);
    unsigned bit = 0;
    switch (button) {                 /* linux/input-event-codes.h */
        case 0x110: bit = MGWL_BTN_LEFT;   break;  /* BTN_LEFT */
        case 0x111: bit = MGWL_BTN_RIGHT;  break;  /* BTN_RIGHT */
        case 0x112: bit = MGWL_BTN_MIDDLE; break;  /* BTN_MIDDLE */
        default: break;
    }
    if (state) m->ptr_buttons |= bit; else m->ptr_buttons &= ~bit;
    if (m->cb.pointer) m->cb.pointer(m->user, MGWL_PTR_BUTTON, m->ptr_x, m->ptr_y, m->ptr_buttons);
}
static void pointer_axis(void *data, struct wl_pointer *p, uint32_t time, uint32_t axis,
                         wl_fixed_t value) {
    (void)p; (void)time;
    mgwl *m = data;
    double v = wl_fixed_to_double(value);
    /* axis 0 = vertical, 1 = horizontal. Report scroll delta in x/y. */
    if (m->cb.pointer)
        m->cb.pointer(m->user, MGWL_PTR_AXIS, axis == 1 ? v : 0, axis == 0 ? v : 0, m->ptr_buttons);
}
static void pointer_frame(void *d, struct wl_pointer *p) { (void)d; (void)p; }
static void pointer_axis_source(void *d, struct wl_pointer *p, uint32_t s) { (void)d; (void)p; (void)s; }
static void pointer_axis_stop(void *d, struct wl_pointer *p, uint32_t t, uint32_t a) {
    (void)d; (void)p; (void)t; (void)a;
}
static void pointer_axis_discrete(void *d, struct wl_pointer *p, uint32_t a, int32_t v) {
    (void)d; (void)p; (void)a; (void)v;
}
static const struct wl_pointer_listener pointer_listener = {
    .enter = pointer_enter, .leave = pointer_leave, .motion = pointer_motion,
    .button = pointer_button, .axis = pointer_axis, .frame = pointer_frame,
    .axis_source = pointer_axis_source, .axis_stop = pointer_axis_stop,
    .axis_discrete = pointer_axis_discrete,
};

/* ------------------------------------------------------------------- keyboard */

static unsigned xkb_mods(mgwl *m) {
    unsigned r = 0;
    if (!m->xkb_state) return 0;
    if (xkb_state_mod_name_is_active(m->xkb_state, XKB_MOD_NAME_SHIFT, XKB_STATE_MODS_EFFECTIVE) > 0)
        r |= MGWL_MOD_SHIFT;
    if (xkb_state_mod_name_is_active(m->xkb_state, XKB_MOD_NAME_CTRL, XKB_STATE_MODS_EFFECTIVE) > 0)
        r |= MGWL_MOD_CTRL;
    if (xkb_state_mod_name_is_active(m->xkb_state, XKB_MOD_NAME_ALT, XKB_STATE_MODS_EFFECTIVE) > 0)
        r |= MGWL_MOD_ALT;
    if (xkb_state_mod_name_is_active(m->xkb_state, XKB_MOD_NAME_LOGO, XKB_STATE_MODS_EFFECTIVE) > 0)
        r |= MGWL_MOD_LOGO;
    return r;
}

static void kb_keymap(void *data, struct wl_keyboard *k, uint32_t format, int32_t fd, uint32_t size) {
    (void)k;
    mgwl *m = data;
    if (format != WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1) { close(fd); return; }
    char *map = mmap(NULL, size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (map == MAP_FAILED) { close(fd); return; }
    if (!m->xkb_ctx) m->xkb_ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    struct xkb_keymap *km = xkb_keymap_new_from_string(m->xkb_ctx, map,
        XKB_KEYMAP_FORMAT_TEXT_V1, XKB_KEYMAP_COMPILE_NO_FLAGS);
    munmap(map, size);
    close(fd);
    if (!km) return;
    struct xkb_state *st = xkb_state_new(km);
    if (!st) { xkb_keymap_unref(km); return; }
    if (m->xkb_state) xkb_state_unref(m->xkb_state);
    if (m->xkb_keymap) xkb_keymap_unref(m->xkb_keymap);
    m->xkb_keymap = km;
    m->xkb_state = st;
}
static void kb_enter(void *d, struct wl_keyboard *k, uint32_t s, struct wl_surface *sf,
                     struct wl_array *keys) {
    (void)d; (void)k; (void)sf; (void)keys;
    mgwl_clip_note_serial(s);
}
static void kb_leave(void *d, struct wl_keyboard *k, uint32_t s, struct wl_surface *sf) {
    (void)d; (void)k; (void)s; (void)sf;
}
static void kb_key(void *data, struct wl_keyboard *k, uint32_t serial, uint32_t time,
                   uint32_t key, uint32_t state) {
    (void)k; (void)time;
    mgwl *m = data;
    mgwl_clip_note_serial(serial);
    if (!m->xkb_state) return;
    xkb_keycode_t code = key + 8;   /* evdev -> xkb */
    xkb_keysym_t sym = xkb_state_key_get_one_sym(m->xkb_state, code);
    uint32_t utf32 = state ? xkb_state_key_get_utf32(m->xkb_state, code) : 0;
    if (m->cb.key) m->cb.key(m->user, sym, utf32, state ? 1 : 0, xkb_mods(m));
}
static void kb_modifiers(void *data, struct wl_keyboard *k, uint32_t serial,
                         uint32_t dep, uint32_t lat, uint32_t lock, uint32_t group) {
    (void)k; (void)serial;
    mgwl *m = data;
    if (m->xkb_state)
        xkb_state_update_mask(m->xkb_state, dep, lat, lock, 0, 0, group);
}
static void kb_repeat(void *d, struct wl_keyboard *k, int32_t rate, int32_t delay) {
    (void)d; (void)k; (void)rate; (void)delay;
}
static const struct wl_keyboard_listener keyboard_listener = {
    .keymap = kb_keymap, .enter = kb_enter, .leave = kb_leave, .key = kb_key,
    .modifiers = kb_modifiers, .repeat_info = kb_repeat,
};

/* ----------------------------------------------------------------------- seat */

static void seat_capabilities(void *data, struct wl_seat *seat, uint32_t caps) {
    mgwl *m = data;
    if ((caps & WL_SEAT_CAPABILITY_TOUCH) && !m->touch) {
        m->touch = wl_seat_get_touch(seat);
        wl_touch_add_listener(m->touch, &touch_listener, m);
    }
    if ((caps & WL_SEAT_CAPABILITY_POINTER) && !m->pointer) {
        m->pointer = wl_seat_get_pointer(seat);
        wl_pointer_add_listener(m->pointer, &pointer_listener, m);
    }
    if ((caps & WL_SEAT_CAPABILITY_KEYBOARD) && !m->keyboard) {
        m->keyboard = wl_seat_get_keyboard(seat);
        wl_keyboard_add_listener(m->keyboard, &keyboard_listener, m);
    }
}
static void seat_name(void *d, struct wl_seat *s, const char *name) { (void)d; (void)s; (void)name; }
static const struct wl_seat_listener seat_listener = { seat_capabilities, seat_name };

/* --------------------------------------------------------------------- output */

static void output_geometry(void *d, struct wl_output *o, int32_t x, int32_t y, int32_t pw,
                            int32_t ph, int32_t sub, const char *make, const char *model,
                            int32_t transform) {
    (void)d; (void)o; (void)x; (void)y; (void)pw; (void)ph; (void)sub; (void)make; (void)model;
    (void)transform;
}
static void output_mode(void *d, struct wl_output *o, uint32_t f, int32_t w, int32_t h, int32_t r) {
    (void)d; (void)o; (void)f; (void)w; (void)h; (void)r;
}
static void output_done(void *d, struct wl_output *o) { (void)d; (void)o; }
static void output_scale(void *data, struct wl_output *o, int32_t factor) {
    (void)o; (void)data;
    /* Buffer scale is pinned to 1 (the proven Lomiri mapping): we render at physical
     * pixels and take UI density from GRID_UNIT_PX instead. Applying a >1 scale here
     * without wl_surface.set_buffer_scale would desync the surface, so just log it. */
    LOGE("wl_output scale=%d (ignored; buffer scale pinned to 1)", factor);
}
static void output_name(void *d, struct wl_output *o, const char *n) { (void)d; (void)o; (void)n; }
static void output_description(void *d, struct wl_output *o, const char *n) { (void)d; (void)o; (void)n; }
static const struct wl_output_listener output_listener = {
    .geometry = output_geometry, .mode = output_mode, .done = output_done,
    .scale = output_scale, .name = output_name, .description = output_description,
};

/* ------------------------------------------------------------------- registry */

static void registry_global(void *data, struct wl_registry *reg, uint32_t name,
                            const char *iface, uint32_t version) {
    mgwl *m = data;
    if (!strcmp(iface, wl_compositor_interface.name)) {
        m->compositor = wl_registry_bind(reg, name, &wl_compositor_interface,
                                         version < 4 ? version : 4);
    } else if (!strcmp(iface, xdg_wm_base_interface.name)) {
        m->wm_base = wl_registry_bind(reg, name, &xdg_wm_base_interface, 1);
        xdg_wm_base_add_listener(m->wm_base, &wm_base_listener, m);
    } else if (!strcmp(iface, wl_seat_interface.name)) {
        m->seat = wl_registry_bind(reg, name, &wl_seat_interface, version < 6 ? version : 6);
        wl_seat_add_listener(m->seat, &seat_listener, m);
    } else if (!strcmp(iface, wl_output_interface.name)) {
        m->output = wl_registry_bind(reg, name, &wl_output_interface, version < 3 ? version : 3);
        wl_output_add_listener(m->output, &output_listener, m);
    } else if (!strcmp(iface, wl_shm_interface.name)) {
        m->shm = wl_registry_bind(reg, name, &wl_shm_interface, 1);
    } else if (!strcmp(iface, wl_data_device_manager_interface.name)) {
        /* v3 is what a wl_data_offer's action events need; a compositor with
         * less gives us what it has and the clipboard still works. */
        m->ddm = wl_registry_bind(reg, name, &wl_data_device_manager_interface,
                                  version < 3 ? version : 3);
    }
}
static void registry_global_remove(void *d, struct wl_registry *r, uint32_t name) {
    (void)d; (void)r; (void)name;
}
static const struct wl_registry_listener registry_listener = {
    registry_global, registry_global_remove,
};

/* ------------------------------------------------------------------ lifecycle */

mgwl *mgwl_create(const char *socket_path) {
    mgwl *m = calloc(1, sizeof(*m));
    if (!m) return NULL;
    m->scale = 1;
    m->activated = -1;   /* unknown until the first configure, so it always reports */
    m->width = 720;
    m->height = 1440;

    m->display = wl_display_connect(socket_path);
    if (!m->display) {
        LOGE("wl_display_connect(%s) failed: %s", socket_path ? socket_path : "<default>",
             strerror(errno));
        free(m);
        return NULL;
    }
    m->registry = wl_display_get_registry(m->display);
    wl_registry_add_listener(m->registry, &registry_listener, m);
    wl_display_roundtrip(m->display);   /* globals */
    wl_display_roundtrip(m->display);   /* seat capabilities, output scale */

    if (!m->compositor || !m->wm_base) {
        LOGE("missing globals: compositor=%p wm_base=%p", (void *)m->compositor, (void *)m->wm_base);
        mgwl_destroy(m);
        return NULL;
    }
    /* The seat is bound by now (two roundtrips above), which is what the data
     * device hangs off. A compositor with no wl_data_device_manager -- Mir's
     * answer is untested -- leaves the clipboard reporting unavailable rather
     * than failing the window. */
    mgwl_clip_bind(m->display, m->ddm, m->seat);
    LOGE("connected: compositor=%d wm_base=%d seat=%d output=%d scale=%d clipboard=%d",
         !!m->compositor, !!m->wm_base, !!m->seat, !!m->output, m->scale,
         mgwl_clip_available());
    return m;
}

static int egl_setup(mgwl *m, int es_version) {
    m->egl_display = eglGetDisplay((EGLNativeDisplayType)m->display);
    if (m->egl_display == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed"); return -1; }
    EGLint major, minor;
    if (!eglInitialize(m->egl_display, &major, &minor)) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        return -1;
    }
    LOGE("EGL %d.%d vendor=%s", major, minor, eglQueryString(m->egl_display, EGL_VENDOR));

    if (!eglBindAPI(EGL_OPENGL_ES_API)) { LOGE("eglBindAPI failed"); return -1; }

    const EGLint cfg_attribs[] = {
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 8,
        EGL_NONE,
    };
    EGLint n = 0;
    if (!eglChooseConfig(m->egl_display, cfg_attribs, &m->egl_config, 1, &n) || n < 1) {
        LOGE("eglChooseConfig failed: 0x%x", eglGetError());
        return -1;
    }
    const EGLint ctx_attribs[] = { EGL_CONTEXT_CLIENT_VERSION, es_version, EGL_NONE };
    m->egl_context = eglCreateContext(m->egl_display, m->egl_config, EGL_NO_CONTEXT, ctx_attribs);
    if (m->egl_context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext(ES%d) failed: 0x%x", es_version, eglGetError());
        return -1;
    }
    return 0;
}

int mgwl_create_window(mgwl *m, const char *app_id, const char *title,
                       int width, int height, int es_version) {
    if (width > 0)  m->width = width;
    if (height > 0) m->height = height;
    m->es_version = es_version;

    m->surface = wl_compositor_create_surface(m->compositor);
    m->xdg_surface = xdg_wm_base_get_xdg_surface(m->wm_base, m->surface);
    xdg_surface_add_listener(m->xdg_surface, &xdg_surface_listener, m);
    m->xdg_toplevel = xdg_surface_get_toplevel(m->xdg_surface);
    xdg_toplevel_add_listener(m->xdg_toplevel, &toplevel_listener, m);
    if (app_id) xdg_toplevel_set_app_id(m->xdg_toplevel, app_id);
    if (title)  xdg_toplevel_set_title(m->xdg_toplevel, title);
    /* qtmir fullscreens app surfaces anyway; ask explicitly too. */
    xdg_toplevel_set_fullscreen(m->xdg_toplevel, NULL);

    wl_surface_commit(m->surface);
    /* Wait for the first xdg_surface.configure so we have a real size. */
    while (!m->configured && !m->should_close)
        wl_display_dispatch(m->display);

    if (egl_setup(m, es_version) != 0) return -1;

    m->egl_window = wl_egl_window_create(m->surface, m->width * m->scale, m->height * m->scale);
    if (!m->egl_window) { LOGE("wl_egl_window_create failed"); return -1; }
    m->egl_surface = eglCreateWindowSurface(m->egl_display, m->egl_config,
                                            (EGLNativeWindowType)m->egl_window, NULL);
    if (m->egl_surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return -1;
    }
    if (!eglMakeCurrent(m->egl_display, m->egl_surface, m->egl_surface, m->egl_context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return -1;
    }
    eglSwapInterval(m->egl_display, 1);
    LOGE("window %dx%d (scale %d) GL_RENDERER=%s", m->width, m->height, m->scale,
         mgwl_gl_renderer(m));
    return 0;
}

void mgwl_set_callbacks(mgwl *m, const mgwl_callbacks *cb, void *user) {
    m->cb = *cb;
    m->user = user;
}

int mgwl_make_current(mgwl *m) {
    return eglMakeCurrent(m->egl_display, m->egl_surface, m->egl_surface, m->egl_context) ? 0 : -1;
}
void mgwl_swap_buffers(mgwl *m) {
    eglSwapBuffers(m->egl_display, m->egl_surface);
}

int mgwl_width(mgwl *m)  { return m->width; }
int mgwl_height(mgwl *m) { return m->height; }
int mgwl_scale(mgwl *m)  { return m->scale; }
int mgwl_should_close(mgwl *m) { return m->should_close; }

const char *mgwl_egl_vendor(mgwl *m) {
    return m->egl_display ? eglQueryString(m->egl_display, EGL_VENDOR) : "";
}
const char *mgwl_gl_renderer(mgwl *m) {
    (void)m;
    gl_get_string_fn f = (gl_get_string_fn)eglGetProcAddress("glGetString");
    const char *r = f ? (const char *)f(MGWL_GL_RENDERER) : NULL;
    return r ? r : "";
}

void mgwl_pump(mgwl *m, int timeout_ms) {
    struct wl_display *d = m->display;
    while (wl_display_prepare_read(d) != 0)
        wl_display_dispatch_pending(d);
    if (wl_display_flush(d) < 0 && errno != EAGAIN) {
        wl_display_cancel_read(d);
        m->should_close = 1;
        return;
    }
    struct pollfd pfd = { .fd = wl_display_get_fd(d), .events = POLLIN };
    int n = poll(&pfd, 1, timeout_ms);
    if (n > 0 && (pfd.revents & POLLIN)) {
        wl_display_read_events(d);
        wl_display_dispatch_pending(d);
    } else {
        wl_display_cancel_read(d);
        if (n < 0 && errno != EINTR) m->should_close = 1;
        if (pfd.revents & (POLLERR | POLLHUP)) m->should_close = 1;
    }
}

void mgwl_destroy(mgwl *m) {
    if (!m) return;
    mgwl_clip_shutdown();
    if (m->egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(m->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (m->egl_surface != EGL_NO_SURFACE) eglDestroySurface(m->egl_display, m->egl_surface);
        if (m->egl_context != EGL_NO_CONTEXT) eglDestroyContext(m->egl_display, m->egl_context);
        eglTerminate(m->egl_display);
    }
    if (m->egl_window) wl_egl_window_destroy(m->egl_window);
    if (m->xkb_state)  xkb_state_unref(m->xkb_state);
    if (m->xkb_keymap) xkb_keymap_unref(m->xkb_keymap);
    if (m->xkb_ctx)    xkb_context_unref(m->xkb_ctx);
    if (m->xdg_toplevel) xdg_toplevel_destroy(m->xdg_toplevel);
    if (m->xdg_surface)  xdg_surface_destroy(m->xdg_surface);
    if (m->surface)      wl_surface_destroy(m->surface);
    if (m->display)      wl_display_disconnect(m->display);
    free(m);
}
