/* Just enough of libdbus-1 to declare what host/sensorfw.c calls.
 *
 * WHY NOT THE REAL HEADERS.  libdbus-1.so.3 is already on this binary's link
 * line (build.gradle.kts commonLibs) and already inside the click, but
 * libdbus-1-DEV is in neither the host's package set nor deps/sysroot-arm64 --
 * and adding it to fetch-deps.sh's WANT_PKGS reassembles the whole sysroot for
 * one header tree.  So the declarations live here, the same bargain
 * host/vendor/wayland-client-protocol.h already makes.
 *
 * The two structs are COPIED VERBATIM from dbus-errors.h and dbus-message.h.
 * They are safe to copy because D-Bus publishes their layout on purpose: the
 * caller allocates both on its own stack, so their size and shape are part of
 * the ABI and cannot change without an soname bump.  Nothing else here is a
 * struct, and nothing here is a reimplementation -- these are prototypes for
 * the library that is linked in.
 */
#ifndef PC_DBUS_MIN_H
#define PC_DBUS_MIN_H

typedef struct DBusConnection DBusConnection;
typedef struct DBusMessage DBusMessage;
typedef unsigned int dbus_bool_t;
typedef unsigned int dbus_uint32_t;
typedef int dbus_int32_t;

typedef struct DBusError {
	const char *name;
	const char *message;
	unsigned int dummy1 : 1, dummy2 : 1, dummy3 : 1, dummy4 : 1, dummy5 : 1;
	void *padding1;
} DBusError;

typedef struct DBusMessageIter {
	void *dummy1;
	void *dummy2;
	dbus_uint32_t dummy3;
	int dummy4, dummy5, dummy6, dummy7, dummy8, dummy9, dummy10, dummy11;
	int pad1;
	void *pad2;
	void *pad3;
} DBusMessageIter;

#define DBUS_BUS_SYSTEM    1
#define DBUS_TYPE_BOOLEAN  ((int)'b')
#define DBUS_TYPE_INT32    ((int)'i')
#define DBUS_TYPE_UINT32   ((int)'u')
#define DBUS_TYPE_INT64    ((int)'x')
#define DBUS_TYPE_UINT64   ((int)'t')
#define DBUS_TYPE_DOUBLE   ((int)'d')
#define DBUS_TYPE_STRING   ((int)'s')
#define DBUS_TYPE_VARIANT  ((int)'v')
#define DBUS_TYPE_STRUCT   ((int)'r')

void         dbus_error_init(DBusError *error);
void         dbus_error_free(DBusError *error);
dbus_bool_t  dbus_error_is_set(const DBusError *error);

DBusConnection *dbus_bus_get_private(int type, DBusError *error);
void         dbus_connection_close(DBusConnection *connection);
void         dbus_connection_unref(DBusConnection *connection);
void         dbus_connection_set_exit_on_disconnect(DBusConnection *connection,
                                                    dbus_bool_t exit_on_disconnect);
DBusMessage *dbus_connection_send_with_reply_and_block(DBusConnection *connection,
                                                       DBusMessage *message,
                                                       int timeout_milliseconds,
                                                       DBusError *error);

DBusMessage *dbus_message_new_method_call(const char *bus_name, const char *path,
                                          const char *iface, const char *method);
void         dbus_message_unref(DBusMessage *message);
void         dbus_message_iter_init_append(DBusMessage *message, DBusMessageIter *iter);
dbus_bool_t  dbus_message_iter_append_basic(DBusMessageIter *iter, int type, const void *value);
dbus_bool_t  dbus_message_iter_init(DBusMessage *message, DBusMessageIter *iter);
int          dbus_message_iter_get_arg_type(DBusMessageIter *iter);
void         dbus_message_iter_get_basic(DBusMessageIter *iter, void *value);
void         dbus_message_iter_recurse(DBusMessageIter *iter, DBusMessageIter *sub);
dbus_bool_t  dbus_message_iter_next(DBusMessageIter *iter);

#endif
