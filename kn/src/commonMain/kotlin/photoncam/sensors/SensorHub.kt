/* android.hardware.SensorManager's data source on this port.
 *
 * gen-atlas/android/hardware/SensorManager.kt delegates its four methods here
 * (atlas-fixups.sh puts them in), so nothing in the converted app changes: the
 * accelerometer reaches Gravity, TouchFocus, SpotWhiteBalanceHelper, ParseExif
 * and CaptureController's JPEG_ORIENTATION exactly as it does on Android, and
 * the gyroscope reaches Gyro's tripod detection and burst shakiness.
 *
 * WHAT THIS PORT CAN AND CANNOT SERVE.  host/sensorfw.c opens the two sensors
 * Ubuntu Touch's sensorfwd will give us: the accelerometer and the gyroscope.
 * TYPE_GRAVITY has no sensorfw counterpart -- on Android it is a fused,
 * low-passed accelerometer, and that is what [gravityOf] makes of ours.
 *
 * A TYPE THIS PORT CANNOT SERVE STILL GETS A Sensor, and [register] answers
 * false for it, which is exactly what the stub this replaces did.  It must not
 * answer null: j2k's nullability pass wrote `getDefaultSensor(TYPE_ROTATION_
 * VECTOR)!!` in Gyro's constructor, so a null there is a throw that takes the
 * whole of PhotonCamera.initModules with it -- SettingsManager included.
 *
 * WHICH THREAD.  Android delivers on the main looper, and so does this: the C
 * side's reader thread only fills a ring, and [drain] -- called from the window's
 * main-loop pass, beside Looper.pump -- is what calls onSensorChanged.  Kotlin
 * never runs on a pthread this process did not give a runtime to.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package photoncam.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlin.math.exp
import photoncam.sensorfw.PC_SENSOR_ACCELEROMETER
import photoncam.sensorfw.PC_SENSOR_GYROSCOPE
import photoncam.sensorfw.pc_sensor_sample
import photoncam.sensorfw.pc_sensorfw_available
import photoncam.sensorfw.pc_sensorfw_dropped
import photoncam.sensorfw.pc_sensorfw_read
import photoncam.sensorfw.pc_sensorfw_start
import photoncam.sensorfw.pc_sensorfw_stop

object SensorHub {
	/** What one drain pass takes off the C ring, per sensor. Two seconds of the
	 *  fastest stream sensorfw has managed here, so a stalled frame catches up
	 *  rather than dropping. */
	private const val BATCH = 256

	/** The gravity low-pass, as a time constant rather than a fixed alpha: the
	 *  delivered rate is the device's to choose (eqe gives 50 Hz), and a
	 *  per-sample alpha would mean a different filter on every phone. */
	private const val GRAVITY_TAU_S = 0.25

	private class Registration(
		val listener: SensorEventListener,
		val sensor: Sensor,
		val type: Int,
	)

	private val registrations = mutableListOf<Registration>()
	private val sensors = mutableMapOf<Int, Sensor>()
	private var started = false

	/** One event per type, reused. Android's come from a pool and are reused the
	 *  same way, which is why `Gravity.mGravity = sensorEvent.values` and
	 *  `Gyro.mAngles = sensorEvent.values` keep working: they hold the array the
	 *  next sample overwrites, there as here. */
	private val events = mutableMapOf<Int, SensorEvent>()

	private var gravityX = 0f
	private var gravityY = 0f
	private var gravityZ = 0f
	private var gravitySeeded = false
	private var gravityStamp = 0L

	private var reportedDrops = 0u

	/** The types this port can answer for, and the sensorfw stream behind each.
	 *  The constants are a C enum, which cinterop gives as UInt. */
	private val ACCEL = PC_SENSOR_ACCELEROMETER.toInt()
	private val GYRO = PC_SENSOR_GYROSCOPE.toInt()

	private fun streamFor(type: Int): Int? = when (type) {
		Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY -> ACCEL
		Sensor.TYPE_GYROSCOPE -> GYRO
		else -> null
	}

	fun defaultSensor(type: Int): Sensor = sensors.getOrPut(type) { Sensor(type) }

	fun sensorList(type: Int): List<Sensor> =
		if (type == Sensor.TYPE_ALL) listOf(
			defaultSensor(Sensor.TYPE_ACCELEROMETER),
			defaultSensor(Sensor.TYPE_GRAVITY),
			defaultSensor(Sensor.TYPE_GYROSCOPE),
		) else listOf(defaultSensor(type))

	/** samplingPeriodUs is ignored: sensorfw arbitrates the rate across every
	 *  client on the device and on eqe does not honour setInterval at all, so
	 *  host/sensorfw.c asks once per session and takes what it is given. */
	fun register(listener: SensorEventListener?, sensor: Sensor?): Boolean {
		if (listener == null || sensor == null) return false
		val type = sensor.getType()
		if (streamFor(type) == null) return false
		if (registrations.any { it.listener === listener && it.type == type }) return true
		registrations.add(Registration(listener, sensor, type))
		if (!started) {
			started = pc_sensorfw_start() == 0
			if (!started) return false
		}
		return true
	}

	/** A null sensor removes every registration this listener holds, which is
	 *  what SensorManager.unregisterListener(listener) means. */
	fun unregister(listener: SensorEventListener?, sensor: Sensor?) {
		if (listener == null) return
		val type = sensor?.getType()
		registrations.removeAll { it.listener === listener && (type == null || it.type == type) }
		if (registrations.isEmpty() && started) {
			pc_sensorfw_stop()
			started = false
			gravitySeeded = false
		}
	}

	/** Hand every sample that has arrived to whoever asked for that type. Called
	 *  once per pass of the window's loop; see HostMainLoop. */
	fun drain() {
		if (!started || registrations.isEmpty()) return
		// Snapshotted once per pass: a listener may unregister itself from
		// inside onSensorChanged, which would otherwise shift the list under
		// the walk.  It still sees out the samples already in this batch, as
		// it would on Android, where they are queued before the call.
		live.clear()
		live.addAll(registrations)
		memScoped {
			val buf = allocArray<pc_sensor_sample>(BATCH)
			// The accelerometer feeds two types, so it is read once and
			// dispatched twice -- raw for TYPE_ACCELEROMETER, low-passed for
			// TYPE_GRAVITY. Reading it per type would give each half the samples.
			val accel = pc_sensorfw_read(ACCEL, buf, BATCH)
			for (i in 0 until accel) {
				val s = buf[i]
				dispatch(Sensor.TYPE_ACCELEROMETER, s.timestamp_ns, s.x, s.y, s.z)
				val g = gravityOf(s.timestamp_ns, s.x, s.y, s.z)
				dispatch(Sensor.TYPE_GRAVITY, s.timestamp_ns, g[0], g[1], g[2])
			}
			val gyro = pc_sensorfw_read(GYRO, buf, BATCH)
			for (i in 0 until gyro) {
				val s = buf[i]
				dispatch(Sensor.TYPE_GYROSCOPE, s.timestamp_ns, s.x, s.y, s.z)
			}
		}
		val dropped = pc_sensorfw_dropped(ACCEL) + pc_sensorfw_dropped(GYRO)
		if (dropped > reportedDrops) {
			reportedDrops = dropped
			println("[pc] sensors: $dropped samples dropped -- the drain is behind")
		}
	}

	/** Android's TYPE_GRAVITY out of a raw accelerometer: a one-pole low pass,
	 *  seeded with the first sample so the first reading is the real vector and
	 *  not a ramp up from zero -- Gravity.getRotation() reads it immediately and
	 *  a near-zero vector answers 270. */
	private val gravity = FloatArray(3)

	private fun gravityOf(timestampNs: Long, x: Float, y: Float, z: Float): FloatArray {
		if (!gravitySeeded) {
			gravityX = x; gravityY = y; gravityZ = z
			gravitySeeded = true
		} else {
			val dt = ((timestampNs - gravityStamp).coerceAtLeast(0L)) / 1_000_000_000.0
			val alpha = exp(-dt / GRAVITY_TAU_S).toFloat()
			gravityX = alpha * gravityX + (1f - alpha) * x
			gravityY = alpha * gravityY + (1f - alpha) * y
			gravityZ = alpha * gravityZ + (1f - alpha) * z
		}
		gravityStamp = timestampNs
		gravity[0] = gravityX; gravity[1] = gravityY; gravity[2] = gravityZ
		return gravity
	}

	private val live = mutableListOf<Registration>()

	/** Types whose first sample has been logged. A session that opens and a
	 *  sample that arrives are different facts, and only the second one says the
	 *  socket, the frame decode and the units are right. */
	private val announced = mutableSetOf<Int>()

	private fun dispatch(type: Int, timestampNs: Long, x: Float, y: Float, z: Float) {
		var prepared = false
		for (reg in live) {
			if (reg.type == type && announced.add(type))
				println("[pc] sensors: first type=$type sample ($x, $y, $z)")
			if (reg.type != type) continue
			val event = events.getOrPut(type) { SensorEvent(FloatArray(3), reg.sensor) }
			if (!prepared) {
				var v = event.values
				if (v == null || v.size < 3) { v = FloatArray(3); event.values = v }
				v[0] = x; v[1] = y; v[2] = z
				event.sensor = reg.sensor
				event.timestamp = timestampNs
				prepared = true
			}
			reg.listener.onSensorChanged(event)
		}
	}

	/** Whether sensorfw actually gave us the stream behind a type, for a log
	 *  line that can tell "no sensor" from "sensor reads zero". */
	fun available(type: Int): Boolean {
		val stream = streamFor(type) ?: return false
		return pc_sensorfw_available(stream) != 0
	}
}
