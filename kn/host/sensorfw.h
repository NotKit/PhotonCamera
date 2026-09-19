/* The phone's accelerometer and gyroscope, as Android's SensorManager wants them.
 *
 * Ubuntu Touch has no /dev/input node for either -- on a Halium device both live
 * behind the Android sensor HAL, and sensorfwd is the only thing that talks to
 * it.  So this is a sensorfw client: com.nokia.SensorService on the SYSTEM bus
 * hands out a session per sensor, and the samples themselves stream over
 * /run/sensord.sock.  See scripts/probe-sensorfw.py, which is the same protocol
 * in twenty lines of Python and is how every constant below was established.
 *
 * The thread is ours and Kotlin never runs on it: Kotlin/Native would have to
 * attach a runtime to a pthread this file created.  Samples land in a ring here
 * and photoncam.sensors.SensorHub drains it from the main loop, which is the
 * thread Android delivers SensorEvents on anyway.
 */
#ifndef PC_SENSORFW_H
#define PC_SENSORFW_H

#include <stdint.h>

enum {
	PC_SENSOR_ACCELEROMETER = 0,
	PC_SENSOR_GYROSCOPE     = 1,
	PC_SENSOR_COUNT         = 2
};

typedef struct {
	/* Android's SensorEvent.timestamp: nanoseconds on the boot clock.  sensorfw
	 * counts microseconds on the same clock, so this is its value times 1000. */
	int64_t timestamp_ns;
	/* Android's units and Android's axes: m/s^2 for the accelerometer, rad/s for
	 * the gyroscope.  sensorfw answers milli-G and milli-degrees/second. */
	float x, y, z;
} pc_sensor_sample;

/* Open the sessions and start the reader thread.  Returns 0 if the thread
 * started -- a sensor that sensorfw will not give us is reported by
 * pc_sensorfw_available() once the thread has had a chance to ask.  Calling it
 * twice is a no-op. */
int  pc_sensorfw_start(void);

/* Stop the sensors and release the sessions.  Leaving them running keeps the
 * hardware powered for the life of the process. */
void pc_sensorfw_stop(void);

/* Whether sensorfw gave us this sensor.  0 until the session is up. */
int  pc_sensorfw_available(int kind);

/* Copy out up to `max` samples that have arrived since the last call, oldest
 * first, and return how many.  Samples the ring dropped are lost silently:
 * the ring holds a couple of seconds at the rates sensorfw delivers. */
int  pc_sensorfw_read(int kind, pc_sensor_sample *out, int max);

/* How many samples the ring has had to drop, for the log line that says the
 * drain is not keeping up. */
unsigned pc_sensorfw_dropped(int kind);

#endif
