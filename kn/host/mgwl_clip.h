/*
 * mgwl_clip - the SYSTEM clipboard: this window as a real Wayland selection
 * owner, and a reader of whatever another application put there.
 *
 * WHAT WAS HERE BEFORE.  Nothing.  `mgwl.c` bound no `wl_data_device_manager`,
 * so "copy" was this window writing a string into its own memory
 * (`fenixkn/pagectl/PageZoom.kt`'s `clip`): nothing this browser copied could
 * be pasted anywhere else, and nothing another application copied could be
 * pasted in.  That is what bounded row C's Paste to "what this window last
 * copied" -- UI-PARITY.md row M.
 *
 * WHAT A SELECTION IS.  Core Wayland, no extension: the compositor keeps one
 * `wl_data_source` per seat as the selection, and every clipboard transfer is a
 * PIPE between two clients that the compositor only introduces.
 *
 *   copy   wl_data_device_manager.create_data_source, offer a mime type per
 *          flavour, wl_data_device.set_selection(source, serial).  Later, when
 *          somebody pastes, the compositor sends US `wl_data_source.send(mime,
 *          fd)` and we write the bytes into fd and close it.
 *   paste  the compositor announces `wl_data_device.data_offer` + one
 *          `wl_data_offer.offer(mime)` per flavour, then names the current one
 *          with `wl_data_device.selection(offer)`.  We pipe(), call
 *          `wl_data_offer.receive(mime, fd)`, close our write end and read.
 *
 * THE SERIAL IS THE PART THAT GOES SILENTLY WRONG.  `set_selection` carries the
 * serial of an input event, and a compositor drops the request when it is
 * invented or stale -- with no protocol error, so the copy simply never
 * happens.  mgwl.c calls mgwl_clip_note_serial() from every serial-bearing
 * input event (pointer button, touch down/up, key, and the enter/leave that
 * carry one too), and a copy uses the newest, which is the press that caused
 * it.  A copy before any input at all is refused rather than sent with a zero.
 *
 * THREADING.  Same rule as the rest of mgwl: everything here runs on the thread
 * that calls mgwl_pump().  The listeners fire from inside it; mgwl_clip_get_text
 * blocks on a pipe with a bounded timeout and dispatches nothing, which is what
 * keeps it out of a re-entrant Compose event dispatch.
 */
#ifndef MGWL_CLIP_H
#define MGWL_CLIP_H

#ifdef __cplusplus
extern "C" {
#endif

/* Forward declarations rather than <wayland-client.h>: this header is read by
 * Kotlin/Native's cinterop, which never calls the two functions below. */
struct wl_display;
struct wl_data_device_manager;
struct wl_seat;

/* Called by mgwl.c once the registry has answered. Safe with NULLs, and then
 * mgwl_clip_available() is 0 and every call is a no-op. */
void mgwl_clip_bind(struct wl_display *display, struct wl_data_device_manager *ddm,
                    struct wl_seat *seat);

/* Called by mgwl.c for every input event that carries a serial. */
void mgwl_clip_note_serial(unsigned serial);

/* Is there a selection at all on this compositor? 1 yes, 0 no. */
int mgwl_clip_available(void);

/*
 * Become the selection owner, offering `utf8` as text.  Returns 0 on success,
 * -1 when there is no data device or no input serial yet (the reason is in
 * mgwl_clip_last_error).  The text is copied; the caller keeps its own.
 */
int mgwl_clip_set_text(const char *utf8);

/*
 * A counter the compositor's own `selection` events drive.  It changes when
 * the selection does -- including when it is cleared, and including our own
 * copies -- and never otherwise, so a caller can poll this every frame for
 * free and read the text only when it has moved.
 */
unsigned mgwl_clip_selection_serial(void);

/* Does the current selection offer something we can read as text? 1/0. */
int mgwl_clip_have_text(void);

/*
 * Read the selection into `buf` (always NUL-terminated when cap > 0).  Returns
 * the number of bytes written, 0 when there is no text, -1 on error.  A read of
 * OUR OWN selection is answered out of memory rather than through the pipe:
 * serving that send would need this same thread to dispatch Wayland while it is
 * blocked reading, which is a deadlock.
 */
int mgwl_clip_get_text(char *buf, int cap);

/* Why the last call failed, or "" when it did not. Static buffer. */
const char *mgwl_clip_last_error(void);

/* Drop the selection and the data device. Optional; only for a clean exit. */
void mgwl_clip_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif /* MGWL_CLIP_H */
