#!/usr/bin/env python3
"""sensorfw, over exactly the protocol host/sensorfw.c speaks.  Run it ON THE
DEVICE; it needs nothing but python3-dbus, which Ubuntu Touch has.

    python3 probe-sensorfw.py [seconds] [accel|gyro]

WHAT IT IS FOR.  The axis signs are the one thing no source in this tree can
answer: sensorfw's hybris adaptor is free to negate what the Android HAL
reports, and Gravity.getRotation() is written against Android's axes (x right,
y up, z out of the screen).  So this prints the vector AND the rotation
Gravity would derive from it -- hold the phone upright and it must say 90,
turn it left-side-up and it must say 0, right-side-up 270, upside down 180.
If it does not, PC_SENSOR_AXES (host/sensorfw.c) remaps without a rebuild:
e.g. PC_SENSOR_AXES=-x,-y,-z.

THREE THINGS THAT COST A SESSION EACH, all of them load-bearing in the C:
  * ONE D-BUS CONNECTION for the whole session.  sensorfw releases every
    session a client owns the moment that client's bus connection drops, so a
    gdbus-per-call probe gets a session id, gets the socket tag, and then waits
    forever for data that never comes.
  * THE SOCKET HANDSHAKE IS A BARE int32.  sensorfw's own SocketReader writes a
    leading '\\0' first; send that and the server still answers with its '\\n'
    tag and then never pushes a sample.
  * setDownsampling(false).  Left on, the stream arrives at about 1 Hz.
"""
import os, socket, struct, sys, time
import dbus

SENSORS = {
    "accel": ("accelerometersensor", "local.AccelerometerSensor", "xyz",
              9.80665 / 1000.0, "m/s^2"),
    "gyro":  ("gyroscopesensor", "local.GyroscopeSensor", "value",
              3.141592653589793 / 180.0 / 1000.0, "rad/s"),
}

def gravity_rotation(x, y, z):
    """Gravity.getRotation(), line for line."""
    if z > 9.0:
        return 90
    if abs(x) > abs(y):
        return 0 if x > 0 else 180
    return 90 if y > 1.5 else 270

def main():
    secs = float(sys.argv[1]) if len(sys.argv) > 1 else 20
    which = sys.argv[2] if len(sys.argv) > 2 else "accel"
    plugin, iface, prop, scale, unit = SENSORS[which]

    bus = dbus.SystemBus()
    mgr = dbus.Interface(bus.get_object("com.nokia.SensorService", "/SensorManager"),
                         "local.SensorManager")
    mgr.loadPlugin(plugin)
    session = int(mgr.requestSensor(plugin, dbus.Int64(os.getpid())))

    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect("/run/sensord.sock")
    sock.sendall(struct.pack("i", session))
    sock.settimeout(3.0)
    if sock.recv(1) != b"\n":
        raise SystemExit("sensord did not accept session %d" % session)

    obj = bus.get_object("com.nokia.SensorService", "/SensorManager/" + plugin)
    sensor = dbus.Interface(obj, iface)
    sensor.setInterval(dbus.Int32(session), dbus.Int32(10))
    sensor.setDownsampling(dbus.Int32(session), dbus.Boolean(False))
    sensor.start(dbus.Int32(session))
    print("session %d on %s, interval %d ms" % (session, plugin, int(obj.Get(iface, "interval"))))

    sock.settimeout(0.5)
    end, n, last = time.time() + secs, 0, 0.0
    try:
        while time.time() < end:
            try:
                buf = sock.recv(262144)
            except socket.timeout:
                continue
            if not buf:
                break
            off = 0
            while off + 4 <= len(buf):
                count = struct.unpack_from("I", buf, off)[0]
                if count == 0 or off + 4 + count * 24 > len(buf):
                    break
                for i in range(count):
                    ts, x, y, z = struct.unpack_from("<Qfff", buf, off + 4 + i * 24)
                    n += 1
                off += 4 + count * 24
            x, y, z = x * scale, y * scale, z * scale
            now = time.time()
            if now - last < 0.25:
                continue
            last = now
            line = "%10.3f %10.3f %10.3f  %s" % (x, y, z, unit)
            if which == "accel":
                rot = gravity_rotation(x, y, z)
                line += "   Gravity.getRotation()=%3d  JPEG_ORIENTATION=%3d" % (
                    rot, (90 + rot + 270) % 360)
            print(line)
    finally:
        print("%d samples in %.1f s = %.1f Hz" % (n, secs, n / secs))
        sensor.stop(dbus.Int32(session))
        mgr.releaseSensor(plugin, dbus.Int32(session), dbus.Int64(os.getpid()))
        sock.close()

if __name__ == "__main__":
    main()
