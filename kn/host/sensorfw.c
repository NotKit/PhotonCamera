#include "sensorfw.h"
#include "dbus_min.h"

#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define SENSORFW_BUS   "com.nokia.SensorService"
#define SENSORFW_MGR   "/SensorManager"
#define SENSORFW_IMGR  "local.SensorManager"
#define SENSORD_SOCKET "/run/sensord.sock"
#define CALL_TIMEOUT   5000

/* A couple of seconds at the rates sensorfw delivers (the gyro is the fast one
 * and its minimum interval on this hardware is 50 ms). */
#define RING 512

/* sensorfw's units -> Android's.  1 G = 9.80665 m/s^2 over a milli-G, and a
 * milli-degree per second in radians. */
#define MILLI_G_TO_MS2   (9.80665f / 1000.0f)
#define MDPS_TO_RADS     (3.14159265358979f / 180.0f / 1000.0f)

typedef struct {
	const char *plugin;      /* loadPlugin/requestSensor name */
	const char *path;        /* /SensorManager/<plugin> */
	const char *iface;       /* local.<X>Sensor */
	const char *prop;        /* the (tddd) property, read once to seed */
	float       scale;       /* sensorfw unit -> Android unit */
	unsigned    interval_ms; /* what we ask for; sensorfw clamps to what it has */
} sensor_def;

static const sensor_def DEFS[PC_SENSOR_COUNT] = {
	{ "accelerometersensor", SENSORFW_MGR "/accelerometersensor",
	  "local.AccelerometerSensor", "xyz",   MILLI_G_TO_MS2, 20 },
	{ "gyroscopesensor",     SENSORFW_MGR "/gyroscopesensor",
	  "local.GyroscopeSensor",     "value", MDPS_TO_RADS,   10 },
};

typedef struct {
	int              fd;         /* the sensord socket, -1 when not open */
	int              session;
	/* Written by the reader thread, read by whoever drains: volatile because
	 * they are status, not data -- the ring itself is under g_lock. */
	volatile int      available;
	volatile unsigned dropped;
	pc_sensor_sample ring[RING];
	unsigned         head, tail; /* free-running; head - tail == fill */
	unsigned char    pending[8192];
	size_t           pending_len;
} sensor_state;

static sensor_state    g_state[PC_SENSOR_COUNT];
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t       g_thread;
static volatile int    g_running;   /* set before the thread starts, cleared to stop it */
static int             g_started;

/* The device frame, as three signed axis picks -- PC_SENSOR_AXES="-y,x,z" and
 * so on.  It exists because sensorfw's hybris adaptor is free to negate what
 * the Android HAL reports, and Gravity.getRotation() is written against
 * Android's axes; one env var settles it on the device instead of a rebuild.
 * Default is identity, which is what eqe's accelerometer measured face up. */
static int   g_axis[3]  = { 0, 1, 2 };
static float g_sign[3]  = { 1.0f, 1.0f, 1.0f };

static void parse_axes(void)
{
	const char *s = getenv("PC_SENSOR_AXES");
	if (!s || !*s) return;
	int axis[3], i = 0;
	float sign[3];
	while (*s && i < 3) {
		float sg = 1.0f;
		if (*s == '-') { sg = -1.0f; s++; }
		else if (*s == '+') s++;
		if (*s < 'x' || *s > 'z') { fprintf(stderr, "[pc] PC_SENSOR_AXES: bad axis near '%s'\n", s); return; }
		axis[i] = *s - 'x';
		sign[i] = sg;
		i++; s++;
		while (*s == ',' || *s == ' ') s++;
	}
	if (i != 3) { fprintf(stderr, "[pc] PC_SENSOR_AXES: wanted three axes\n"); return; }
	memcpy(g_axis, axis, sizeof(axis));
	memcpy(g_sign, sign, sizeof(sign));
	fprintf(stderr, "[pc] PC_SENSOR_AXES=%s\n", getenv("PC_SENSOR_AXES"));
}

/* ------------------------------------------------------------------ D-Bus */

/* One blocking method call.  `sig` names the argument types in D-Bus letters
 * ("si" and so on) and the values follow; only the four this file sends are
 * understood.  The reply is the caller's to unref, or NULL on failure. */
static DBusMessage *call(DBusConnection *bus, const char *path, const char *iface,
                         const char *method, const char *sig, ...)
{
	DBusMessage *msg = dbus_message_new_method_call(SENSORFW_BUS, path, iface, method);
	if (!msg) return NULL;

	DBusMessageIter it;
	dbus_message_iter_init_append(msg, &it);
	va_list ap;
	va_start(ap, sig);
	for (const char *p = sig; p && *p; p++) {
		if (*p == 's') {
			const char *v = va_arg(ap, const char *);
			dbus_message_iter_append_basic(&it, DBUS_TYPE_STRING, &v);
		} else if (*p == 'i') {
			dbus_int32_t v = (dbus_int32_t)va_arg(ap, int);
			dbus_message_iter_append_basic(&it, DBUS_TYPE_INT32, &v);
		} else if (*p == 'u') {
			dbus_uint32_t v = (dbus_uint32_t)va_arg(ap, unsigned);
			dbus_message_iter_append_basic(&it, DBUS_TYPE_UINT32, &v);
		} else if (*p == 'b') {
			dbus_bool_t v = (dbus_bool_t)va_arg(ap, int);
			dbus_message_iter_append_basic(&it, DBUS_TYPE_BOOLEAN, &v);
		} else if (*p == 'x') {
			int64_t v = va_arg(ap, int64_t);
			dbus_message_iter_append_basic(&it, DBUS_TYPE_INT64, &v);
		}
	}
	va_end(ap);

	DBusError err;
	dbus_error_init(&err);
	DBusMessage *reply = dbus_connection_send_with_reply_and_block(bus, msg, CALL_TIMEOUT, &err);
	dbus_message_unref(msg);
	if (dbus_error_is_set(&err)) {
		fprintf(stderr, "[pc] sensorfw %s.%s: %s\n", iface, method, err.message ? err.message : "?");
		dbus_error_free(&err);
		return NULL;
	}
	return reply;
}

static int reply_int(DBusMessage *reply, int fallback)
{
	if (!reply) return fallback;
	DBusMessageIter it;
	int v = fallback;
	if (dbus_message_iter_init(reply, &it) &&
	    dbus_message_iter_get_arg_type(&it) == DBUS_TYPE_INT32) {
		dbus_int32_t tmp = 0;
		dbus_message_iter_get_basic(&it, &tmp);
		v = (int)tmp;
	}
	dbus_message_unref(reply);
	return v;
}

/* org.freedesktop.DBus.Properties.Get of the sensor's (tddd) value.  It is the
 * SEED: sensorfw drops a socket push whose sample equals the last one, so a
 * phone lying still streams nothing at all and Gravity would have no vector
 * until the first movement. */
static int read_property(DBusConnection *bus, const sensor_def *def,
                         int64_t *ts_us, double out[3])
{
	DBusMessage *reply = call(bus, def->path, "org.freedesktop.DBus.Properties", "Get",
	                          "ss", def->iface, def->prop);
	if (!reply) return 0;

	int ok = 0;
	DBusMessageIter it, var, st;
	if (dbus_message_iter_init(reply, &it) &&
	    dbus_message_iter_get_arg_type(&it) == DBUS_TYPE_VARIANT) {
		dbus_message_iter_recurse(&it, &var);
		if (dbus_message_iter_get_arg_type(&var) == DBUS_TYPE_STRUCT) {
			dbus_message_iter_recurse(&var, &st);
			if (dbus_message_iter_get_arg_type(&st) == DBUS_TYPE_UINT64) {
				uint64_t t = 0;
				dbus_message_iter_get_basic(&st, &t);
				*ts_us = (int64_t)t;
				ok = 1;
				for (int i = 0; i < 3 && ok; i++) {
					if (!dbus_message_iter_next(&st) ||
					    dbus_message_iter_get_arg_type(&st) != DBUS_TYPE_DOUBLE)
						ok = 0;
					else
						dbus_message_iter_get_basic(&st, &out[i]);
				}
			}
		}
	}
	dbus_message_unref(reply);
	return ok;
}

/* ------------------------------------------------------------------- ring */

static void push(sensor_state *st, const sensor_def *def,
                 int64_t ts_us, float raw[3])
{
	float v[3];
	for (int i = 0; i < 3; i++) v[i] = g_sign[i] * raw[g_axis[i]] * def->scale;

	pthread_mutex_lock(&g_lock);
	if (st->head - st->tail >= RING) { st->tail++; st->dropped++; }
	pc_sensor_sample *s = &st->ring[st->head % RING];
	s->timestamp_ns = ts_us * 1000;
	s->x = v[0]; s->y = v[1]; s->z = v[2];
	st->head++;
	pthread_mutex_unlock(&g_lock);
}

/* ----------------------------------------------------------------- socket */

/* The handshake, and it is EXACTLY four bytes.  sensorfw's own SocketReader
 * writes a leading '\0' first; send that and the server still answers with the
 * '\n' tag and then never pushes a single sample -- it has read the zero as the
 * low byte of the session id.  The bare int32 is what streams. */
static int open_stream(int session)
{
	int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (fd < 0) return -1;
	struct sockaddr_un addr;
	memset(&addr, 0, sizeof(addr));
	addr.sun_family = AF_UNIX;
	strncpy(addr.sun_path, SENSORD_SOCKET, sizeof(addr.sun_path) - 1);
	if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		fprintf(stderr, "[pc] sensorfw: %s: %s\n", SENSORD_SOCKET, strerror(errno));
		close(fd);
		return -1;
	}
	int32_t id = (int32_t)session;
	if (write(fd, &id, sizeof(id)) != (ssize_t)sizeof(id)) { close(fd); return -1; }

	/* The server acknowledges with one '\n'; without it the session was not
	 * accepted and nothing would ever arrive. */
	struct pollfd p = { fd, POLLIN, 0 };
	char tag = 0;
	if (poll(&p, 1, 2000) != 1 || read(fd, &tag, 1) != 1 || tag != '\n') {
		fprintf(stderr, "[pc] sensorfw: no socket tag for session %d\n", session);
		close(fd);
		return -1;
	}
	return fd;
}

/* A frame is `uint32 count` and then count records of
 *     { uint64 timestamp_us; float x, y, z; uint32 pad }
 * -- 24 bytes each, the tail padding being what the C++ struct's 8-byte
 * alignment adds.  Short reads are real: consume whole frames only. */
#define RECORD 24

static void consume(sensor_state *st, const sensor_def *def)
{
	size_t off = 0;
	for (;;) {
		if (st->pending_len - off < 4) break;
		uint32_t count;
		memcpy(&count, st->pending + off, 4);
		size_t need = 4 + (size_t)count * RECORD;
		if (count == 0 || count > 4096) {           /* not a frame we understand */
			fprintf(stderr, "[pc] sensorfw %s: frame count %u, dropping buffer\n",
			        def->plugin, count);
			off = st->pending_len;
			break;
		}
		if (st->pending_len - off < need) break;

		const unsigned char *rec = st->pending + off + 4;
		for (uint32_t i = 0; i < count; i++, rec += RECORD) {
			uint64_t ts;
			float xyz[3];
			memcpy(&ts, rec, 8);
			memcpy(xyz, rec + 8, 12);
			push(st, def, (int64_t)ts, xyz);
		}
		off += need;
	}
	if (off) {
		memmove(st->pending, st->pending + off, st->pending_len - off);
		st->pending_len -= off;
	}
}

/* ------------------------------------------------------------------ thread */

static int open_sensor(DBusConnection *bus, int kind)
{
	const sensor_def *def = &DEFS[kind];
	sensor_state *st = &g_state[kind];

	DBusMessage *r = call(bus, SENSORFW_MGR, SENSORFW_IMGR, "loadPlugin", "s", def->plugin);
	if (!r) return 0;
	dbus_message_unref(r);

	/* THE CONNECTION IS THE SESSION'S LIFETIME.  sensorfw releases every session
	 * a client owns when that client's bus connection goes away, so this one is
	 * held open by the thread for as long as the sensors are wanted -- a
	 * call-per-process probe gets a session id, gets the socket tag, and then
	 * waits forever for data that will never come. */
	st->session = reply_int(call(bus, SENSORFW_MGR, SENSORFW_IMGR, "requestSensor",
	                             "sx", def->plugin, (int64_t)getpid()), -1);
	if (st->session < 0) return 0;

	st->fd = open_stream(st->session);
	if (st->fd < 0) return 0;

	/* setInterval takes a SIGNED int for the milliseconds, and sensorfw clamps
	 * it to an interval the hardware actually has.  setDownsampling(false) is
	 * what stops it thinning the stream before it reaches us. */
	r = call(bus, def->path, def->iface, "setInterval", "ii", st->session, (int)def->interval_ms);
	if (r) dbus_message_unref(r);
	r = call(bus, def->path, def->iface, "setDownsampling", "ib", st->session, 0);
	if (r) dbus_message_unref(r);
	r = call(bus, def->path, def->iface, "start", "i", st->session);
	if (!r) { close(st->fd); st->fd = -1; return 0; }
	dbus_message_unref(r);

	int64_t ts = 0;
	double seed[3];
	if (read_property(bus, def, &ts, seed)) {
		float raw[3] = { (float)seed[0], (float)seed[1], (float)seed[2] };
		push(st, def, ts, raw);
	}
	st->available = 1;
	fprintf(stderr, "[pc] sensorfw %s: session %d, %u ms\n",
	        def->plugin, st->session, def->interval_ms);
	return 1;
}

static void close_sensor(DBusConnection *bus, int kind)
{
	const sensor_def *def = &DEFS[kind];
	sensor_state *st = &g_state[kind];
	if (st->session >= 0) {
		DBusMessage *r = call(bus, def->path, def->iface, "stop", "i", st->session);
		if (r) dbus_message_unref(r);
		r = call(bus, SENSORFW_MGR, SENSORFW_IMGR, "releaseSensor",
		         "six", def->plugin, st->session, (int64_t)getpid());
		if (r) dbus_message_unref(r);
	}
	if (st->fd >= 0) close(st->fd);
	st->fd = -1;
	st->session = -1;
	st->available = 0;
}

static void *reader(void *unused)
{
	(void)unused;
	DBusError err;
	dbus_error_init(&err);
	DBusConnection *bus = dbus_bus_get_private(DBUS_BUS_SYSTEM, &err);
	if (!bus) {
		fprintf(stderr, "[pc] sensorfw: no system bus: %s\n",
		        dbus_error_is_set(&err) && err.message ? err.message : "?");
		if (dbus_error_is_set(&err)) dbus_error_free(&err);
		g_running = 0;
		return NULL;
	}
	/* Without this, a bus that goes away takes the whole process with it. */
	dbus_connection_set_exit_on_disconnect(bus, 0);

	int any = 0;
	for (int k = 0; k < PC_SENSOR_COUNT; k++) any |= open_sensor(bus, k);
	if (!any)
		fprintf(stderr, "[pc] sensorfw: no sensor opened -- the app runs with none,"
		                " as it did before this existed\n");

	while (g_running) {
		struct pollfd fds[PC_SENSOR_COUNT];
		int n = 0, map[PC_SENSOR_COUNT];
		for (int k = 0; k < PC_SENSOR_COUNT; k++)
			if (g_state[k].fd >= 0) { fds[n].fd = g_state[k].fd; fds[n].events = POLLIN;
			                          fds[n].revents = 0; map[n] = k; n++; }
		if (n == 0) { usleep(200000); continue; }

		int ready = poll(fds, n, 200);
		if (ready < 0) { if (errno == EINTR) continue; break; }
		for (int i = 0; i < n; i++) {
			if (!(fds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
			sensor_state *st = &g_state[map[i]];
			ssize_t got = read(st->fd, st->pending + st->pending_len,
			                   sizeof(st->pending) - st->pending_len);
			if (got > 0) {
				st->pending_len += (size_t)got;
				consume(st, &DEFS[map[i]]);
			} else if (got == 0 || (got < 0 && errno != EINTR && errno != EAGAIN)) {
				fprintf(stderr, "[pc] sensorfw %s: stream closed\n", DEFS[map[i]].plugin);
				close(st->fd);
				st->fd = -1;
				st->available = 0;
			}
			/* A buffer that never yields a frame would wedge the loop; the
			 * frame check in consume() empties it instead. */
			if (st->pending_len == sizeof(st->pending)) st->pending_len = 0;
		}
	}

	for (int k = 0; k < PC_SENSOR_COUNT; k++) close_sensor(bus, k);
	dbus_connection_close(bus);
	dbus_connection_unref(bus);
	return NULL;
}

/* -------------------------------------------------------------------- API */

int pc_sensorfw_start(void)
{
	if (g_started) return 0;
	parse_axes();
	/* A full reset, not just the two handles: stop() leaves a half-read frame
	 * in pending[], and starting again on top of it decodes garbage. */
	for (int k = 0; k < PC_SENSOR_COUNT; k++) {
		memset(&g_state[k], 0, sizeof(g_state[k]));
		g_state[k].fd = -1;
		g_state[k].session = -1;
	}
	g_running = 1;
	if (pthread_create(&g_thread, NULL, reader, NULL) != 0) {
		g_running = 0;
		fprintf(stderr, "[pc] sensorfw: cannot start the reader thread\n");
		return -1;
	}
	g_started = 1;
	return 0;
}

void pc_sensorfw_stop(void)
{
	if (!g_started) return;
	g_running = 0;
	pthread_join(g_thread, NULL);
	g_started = 0;
}

int pc_sensorfw_available(int kind)
{
	if (kind < 0 || kind >= PC_SENSOR_COUNT) return 0;
	return g_state[kind].available;
}

int pc_sensorfw_read(int kind, pc_sensor_sample *out, int max)
{
	if (kind < 0 || kind >= PC_SENSOR_COUNT || max <= 0) return 0;
	sensor_state *st = &g_state[kind];
	int n = 0;
	pthread_mutex_lock(&g_lock);
	while (st->tail != st->head && n < max)
		out[n++] = st->ring[st->tail++ % RING];
	pthread_mutex_unlock(&g_lock);
	return n;
}

unsigned pc_sensorfw_dropped(int kind)
{
	if (kind < 0 || kind >= PC_SENSOR_COUNT) return 0;
	return g_state[kind].dropped;
}
