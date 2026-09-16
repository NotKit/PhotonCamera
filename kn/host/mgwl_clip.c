/* mgwl_clip - see mgwl_clip.h. Core wl_data_device, no extension, no new dep. */
#define _GNU_SOURCE

#include "mgwl_clip.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <wayland-client.h>

#define LOG(...) do { fprintf(stderr, "[mgwl-clip] " __VA_ARGS__); fputc('\n', stderr); } while (0)

/* A paste is a deliberate press and the peer is a local process; anything
 * slower than this is a peer that is not answering, and the render thread must
 * not wait on it. */
#define READ_TIMEOUT_MS 600
#define WRITE_TIMEOUT_MS 600

/* The flavours we PUBLISH. The first is the one every modern toolkit asks for;
 * the other two are what an older client (and Xwayland's bridge) looks for. */
static const char *const OFFER_MIMES[] = {
	"text/plain;charset=utf-8",
	"text/plain",
	"UTF8_STRING",
};
#define N_OFFER_MIMES ((int)(sizeof OFFER_MIMES / sizeof OFFER_MIMES[0]))

/* The flavours we ACCEPT, best first. */
static const char *const WANT_MIMES[] = {
	"text/plain;charset=utf-8",
	"text/plain;charset=UTF-8",
	"UTF8_STRING",
	"text/plain",
	"TEXT",
	"STRING",
};
#define N_WANT_MIMES ((int)(sizeof WANT_MIMES / sizeof WANT_MIMES[0]))

/* Per-offer bookkeeping, hung off the wl_data_offer as its user data: the
 * compositor may have several offers alive at once (a drag and the selection),
 * and which mime each carries is announced before we are told what it is for. */
typedef struct {
	int  rank;              /* index into WANT_MIMES, or -1 */
	char mime[64];
} offer_info;

static struct {
	struct wl_display             *display;
	struct wl_data_device_manager *ddm;
	struct wl_data_device         *dd;

	struct wl_data_source *source;    /* ours, while we own the selection */
	char                  *own_text;

	struct wl_data_offer *offer;      /* the current selection's, or NULL */
	unsigned last_serial;
	unsigned sel_serial;
} C;

static char g_last_error[256];

const char *mgwl_clip_last_error(void) { return g_last_error; }

static void set_error(const char *msg) {
	snprintf(g_last_error, sizeof g_last_error, "%s", msg);
	LOG("%s", g_last_error);
}

/* ---------------------------------------------------------- our data source */

static void source_target(void *d, struct wl_data_source *s, const char *mime) {
	(void)d; (void)s; (void)mime;   /* drag and drop only */
}

/* Somebody pasted. Write the bytes and close: the pipe's EOF is what tells the
 * reader the transfer ended, so an unclosed fd is a peer hanging for ever. */
static void source_send(void *d, struct wl_data_source *s, const char *mime, int32_t fd) {
	(void)d; (void)s; (void)mime;
	const char *text = C.own_text ? C.own_text : "";
	size_t len = strlen(text), off = 0;
	int flags = fcntl(fd, F_GETFL, 0);
	if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
	while (off < len) {
		struct pollfd pfd = { .fd = fd, .events = POLLOUT };
		int n = poll(&pfd, 1, WRITE_TIMEOUT_MS);
		if (n <= 0) { LOG("send(%s): reader stalled after %zu/%zu bytes", mime, off, len); break; }
		if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) break;
		ssize_t w = write(fd, text + off, len - off);
		if (w > 0) { off += (size_t)w; continue; }
		if (w < 0 && (errno == EAGAIN || errno == EINTR)) continue;
		break;
	}
	close(fd);
}

/* We lost the selection: another client set one, or the compositor dropped
 * ours. The source is dead from here and destroying it is the owner's job. */
static void source_cancelled(void *d, struct wl_data_source *s) {
	(void)d;
	if (C.source == s) {
		C.source = NULL;
		free(C.own_text);
		C.own_text = NULL;
	}
	wl_data_source_destroy(s);
}
static void source_dnd_drop(void *d, struct wl_data_source *s) { (void)d; (void)s; }
static void source_dnd_finished(void *d, struct wl_data_source *s) { (void)d; (void)s; }
static void source_action(void *d, struct wl_data_source *s, uint32_t a) { (void)d; (void)s; (void)a; }

static const struct wl_data_source_listener source_listener = {
	.target = source_target, .send = source_send, .cancelled = source_cancelled,
	.dnd_drop_performed = source_dnd_drop, .dnd_finished = source_dnd_finished,
	.action = source_action,
};

/* --------------------------------------------------------- somebody else's */

static void offer_mime(void *data, struct wl_data_offer *o, const char *mime) {
	(void)o;
	offer_info *in = data;
	if (!in || !mime) return;
	for (int i = 0; i < N_WANT_MIMES; ++i) {
		if (strcmp(mime, WANT_MIMES[i]) != 0) continue;
		if (in->rank >= 0 && in->rank <= i) return;   /* we already have a better one */
		in->rank = i;
		snprintf(in->mime, sizeof in->mime, "%s", mime);
		return;
	}
}
static void offer_source_actions(void *d, struct wl_data_offer *o, uint32_t a) { (void)d; (void)o; (void)a; }
static void offer_action(void *d, struct wl_data_offer *o, uint32_t a) { (void)d; (void)o; (void)a; }
static const struct wl_data_offer_listener offer_listener = {
	.offer = offer_mime, .source_actions = offer_source_actions, .action = offer_action,
};

static void drop_offer(void) {
	if (!C.offer) return;
	offer_info *in = wl_data_offer_get_user_data(C.offer);
	free(in);
	wl_data_offer_destroy(C.offer);
	C.offer = NULL;
}

/* ---------------------------------------------------------- the data device */

/* A new offer, for a selection or for a drag: which is not said yet. */
static void dd_data_offer(void *d, struct wl_data_device *dev, struct wl_data_offer *o) {
	(void)d; (void)dev;
	offer_info *in = calloc(1, sizeof *in);
	if (in) in->rank = -1;
	wl_data_offer_add_listener(o, &offer_listener, in);
}

/* Drag and drop, which this window does not take part in: refuse the mime and
 * destroy the offer here rather than at `leave`, so its bookkeeping cannot be
 * mistaken for the selection's and cannot leak. */
static void dd_enter(void *d, struct wl_data_device *dev, uint32_t serial, struct wl_surface *s,
                     wl_fixed_t x, wl_fixed_t y, struct wl_data_offer *o) {
	(void)d; (void)dev; (void)s; (void)x; (void)y;
	if (!o) return;
	wl_data_offer_accept(o, serial, NULL);
	free(wl_data_offer_get_user_data(o));
	wl_data_offer_destroy(o);
}
static void dd_leave(void *d, struct wl_data_device *dev) { (void)d; (void)dev; }
static void dd_motion(void *d, struct wl_data_device *dev, uint32_t t, wl_fixed_t x, wl_fixed_t y) {
	(void)d; (void)dev; (void)t; (void)x; (void)y;
}
static void dd_drop(void *d, struct wl_data_device *dev) { (void)d; (void)dev; }

/* THE selection changed -- ours or anybody's. A NULL offer is the clipboard
 * being emptied, which is as real an answer as a string. */
static void dd_selection(void *d, struct wl_data_device *dev, struct wl_data_offer *o) {
	(void)d; (void)dev;
	drop_offer();
	C.offer = o;
	C.sel_serial++;
}

static const struct wl_data_device_listener dd_listener = {
	.data_offer = dd_data_offer, .enter = dd_enter, .leave = dd_leave,
	.motion = dd_motion, .drop = dd_drop, .selection = dd_selection,
};

/* ------------------------------------------------------------------- public */

void mgwl_clip_bind(struct wl_display *display, struct wl_data_device_manager *ddm,
                    struct wl_seat *seat) {
	C.display = display;
	C.ddm = ddm;
	if (!display || !ddm || !seat) {
		set_error("no wl_data_device_manager on this compositor");
		return;
	}
	C.dd = wl_data_device_manager_get_data_device(ddm, seat);
	if (!C.dd) { set_error("get_data_device failed"); return; }
	wl_data_device_add_listener(C.dd, &dd_listener, NULL);
	g_last_error[0] = '\0';
}

void mgwl_clip_note_serial(unsigned serial) {
	if (serial) C.last_serial = serial;
}

int mgwl_clip_available(void) { return C.dd != NULL; }

unsigned mgwl_clip_selection_serial(void) { return C.sel_serial; }

int mgwl_clip_set_text(const char *utf8) {
	if (!C.dd) { set_error("no data device: this compositor has no selection"); return -1; }
	if (!C.last_serial) {
		/* Not a warning to be ignored: a compositor refuses an invented serial
		 * silently, so a copy sent with 0 would look exactly like a copy that
		 * worked. */
		set_error("no input serial yet -- a copy must follow a real press");
		return -1;
	}
	free(C.own_text);
	C.own_text = strdup(utf8 ? utf8 : "");
	if (!C.own_text) { set_error("out of memory"); return -1; }

	if (C.source) { wl_data_source_destroy(C.source); C.source = NULL; }
	C.source = wl_data_device_manager_create_data_source(C.ddm);
	if (!C.source) { set_error("create_data_source failed"); return -1; }
	wl_data_source_add_listener(C.source, &source_listener, NULL);
	for (int i = 0; i < N_OFFER_MIMES; ++i)
		wl_data_source_offer(C.source, OFFER_MIMES[i]);
	wl_data_device_set_selection(C.dd, C.source, C.last_serial);
	wl_display_flush(C.display);
	g_last_error[0] = '\0';
	return 0;
}

int mgwl_clip_have_text(void) {
	if (C.own_text && C.source) return 1;
	if (!C.offer) return 0;
	offer_info *in = wl_data_offer_get_user_data(C.offer);
	return in && in->rank >= 0;
}

int mgwl_clip_get_text(char *buf, int cap) {
	if (!buf || cap <= 0) return -1;
	buf[0] = '\0';
	if (!C.dd) { set_error("no data device"); return -1; }

	/* Ours: answered out of memory. Going through the pipe would need this
	 * thread to dispatch our own source_send while it is blocked on the read. */
	if (C.source && C.own_text) {
		int n = snprintf(buf, (size_t)cap, "%s", C.own_text);
		return n < 0 ? -1 : (n >= cap ? cap - 1 : n);
	}
	if (!C.offer) return 0;
	offer_info *in = wl_data_offer_get_user_data(C.offer);
	if (!in || in->rank < 0) return 0;

	int fds[2];
	if (pipe2(fds, O_CLOEXEC) != 0) { set_error("pipe2 failed"); return -1; }
	wl_data_offer_receive(C.offer, in->mime, fds[1]);
	wl_display_flush(C.display);
	close(fds[1]);   /* the owner writes into its copy; ours must not hold EOF open */

	int got = 0;
	for (;;) {
		struct pollfd pfd = { .fd = fds[0], .events = POLLIN };
		int n = poll(&pfd, 1, READ_TIMEOUT_MS);
		if (n < 0 && errno == EINTR) continue;
		if (n <= 0) { set_error("the selection owner did not answer"); break; }
		ssize_t r = read(fds[0], buf + got, (size_t)(cap - 1 - got));
		if (r < 0 && (errno == EAGAIN || errno == EINTR)) continue;
		if (r <= 0) break;                       /* 0 is EOF: the whole transfer */
		got += (int)r;
		if (got >= cap - 1) break;
	}
	close(fds[0]);
	buf[got] = '\0';
	return got;
}

void mgwl_clip_shutdown(void) {
	drop_offer();
	if (C.source) { wl_data_source_destroy(C.source); C.source = NULL; }
	free(C.own_text); C.own_text = NULL;
	if (C.dd) { wl_data_device_destroy(C.dd); C.dd = NULL; }
}
