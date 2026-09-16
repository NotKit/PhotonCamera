// Mirrors the real GeckoView call sites against the android.os stubs.
package probe

import android.os.*

class Shmem(private val d: ParcelFileDescriptor?, private val size: Int) : Parcelable {
    constructor(p: Parcel) : this(p.readFileDescriptor(), p.readInt())
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeFileDescriptor(d!!.getFileDescriptor())
        dest.writeInt(size)
        if ((flags and Parcelable.PARCELABLE_WRITE_RETURN_VALUE) == 0) {}
    }
    companion object {
        val CREATOR: Parcelable.Creator<Shmem> = object : Parcelable.Creator<Shmem> {
            override fun createFromParcel(source: Parcel): Shmem = Shmem(source)
            override fun newArray(size: Int): Array<Shmem> = TODO()
        }
    }
}

fun shmem(size: Int): ParcelFileDescriptor {
    val backing = MemoryFile(null, size)
    return ParcelFileDescriptor.dup(backing.getFileDescriptor())
}

fun bundles(): Bundle {
    val b = Bundle(3)
    b.putString("k", "v"); b.putInt("i", 1); b.putBoolean("z", true)
    b.putLong("l", 2L); b.putDouble("d", 1.0); b.putByteArray("ba", ByteArray(1))
    b.putStringArray("sa", arrayOfNulls<String>(1)); b.putBundle("n", Bundle())
    b.putCharSequence("cs", "x"); b.putIntArray("ia", intArrayOf(1))
    b.putDoubleArray("da", doubleArrayOf(1.0)); b.putBooleanArray("za", booleanArrayOf(true))
    if (b.containsKey("k")) { val ks: Set<String> = b.keySet(); println(ks.size + b.size()) }
    val v: Any? = b.get("k")
    println(v.toString() + b.getString("k") + b.getInt("i") + b.getBoolean("z"))
    val copy = Bundle(b)
    val p = Parcel.obtain()
    copy.writeToParcel(p, 0); copy.readFromParcel(p)
    return copy
}

fun threading() {
    val ui = Handler(Looper.getMainLooper())
    ui.post { }
    ui.postDelayed({ }, 100L)
    val r: Any = object { fun run() {} }; ui.post(r)
    ui.removeCallbacks({ })
    println(ui.getLooper())
    val t = HandlerThread("worker")
    t.start()
    val h = Handler(t.getLooper())
    h.sendMessageAtFrontOfQueue(Message.obtain(h))
    t.quitSafely(); t.quit()
    val idle = object : MessageQueue.IdleHandler {
        override fun queueIdle(): Boolean = true
    }
    Looper.myQueue().addIdleHandler(idle)
    Looper.myQueue().removeIdleHandler(idle)
    Looper.prepare(); Looper.loop(); println(Looper.myLooper())
}

class Codec : IBinder.DeathRecipient {
    override fun binderDied() {}
}

class W : IInterface {
    override fun asBinder(): IBinder = TODO()
}

fun binders(b: IBinder) {
    val client: IBinder = Binder()
    b.linkToDeath(Codec(), 0)
    println(b.pingBinder())
    val id = Binder.clearCallingIdentity()
    Binder.restoreCallingIdentity(id)
}

fun sdk(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 ||
    Build.MODEL == Build.MANUFACTURER + Build.DEVICE + Build.CPU_ABI + Build.HARDWARE +
        Build.FINGERPRINT + Build.BOARD + Build.BRAND + Build.DISPLAY +
        Build.SUPPORTED_ABIS[0] + Build.VERSION.CODENAME

fun sys() {
    println(SystemClock.elapsedRealtime() + SystemClock.uptimeMillis())
    println(Process.myPid() + Process.myTid() + Process.myUid())
    Process.killProcess(Process.myPid())
    println(Process.isIsolated())
    val um = UserManager()
    println(um.getSerialNumberForUser(android.os.Process.myUserHandle()))
    println(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath())
    println(Environment.getDownloadCacheDirectory().getPath())
    println(Environment.getExternalStorageDirectory().toString())
    println(Environment.getStorageDirectory().toString())
    val list = LocaleList.getDefault()
    for (i in 0 until list.size()) println(list.get(i).toLanguageTag())
    val mi = Debug.MemoryInfo()
    Debug.getMemoryInfo(mi)
    println(mi.getMemoryStat("summary.java-heap"))
    val pb = PersistableBundle()
    pb.putBoolean("x", true)
}

fun power(pm: PowerManager, v: Vibrator) {
    var wl: PowerManager.WakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tag")
    wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "t2")
    wl.acquire(); wl.release(); println(wl.isHeld())
    v.vibrate(10L); v.vibrate(longArrayOf(1, 2), -1); v.cancel()
    println(BatteryManager.EXTRA_LEVEL + BatteryManager.EXTRA_PLUGGED +
        BatteryManager.EXTRA_PRESENT + BatteryManager.EXTRA_SCALE)
}

class PostRequestTask : AsyncTask<Unit, Unit, Unit>() {
    override fun doInBackground(vararg params: Unit) {}
    override fun onPostExecute(result: Unit) {}
}
fun runTask() { PostRequestTask().execute() }

class Recv : OutcomeReceiver<String, RuntimeException> {
    override fun onResult(result: String) {}
    override fun onError(error: RuntimeException) {}
}

fun printing(cs: CancellationSignal) { println(cs.isCanceled()) }

fun exceptions() {
    try { throw RemoteException("x") }
    catch (e: DeadObjectException) {}
    catch (e: TransactionTooLargeException) {}
    catch (e: RemoteException) {}
    try { throw ParcelFormatException("bad") } catch (e: ParcelFormatException) {}
}

fun parcelGenerics(src: Parcel, dest: Parcel) {
    val sm: Shmem = src.readParcelable(null)
    dest.writeParcelable(sm, 0)
    val bundle: Bundle = src.readBundle(null)
    val name = src.readValue(null) as String?
    dest.writeParcelableArray(arrayOf<Parcelable?>(sm), 0)
    println(src.createByteArray().size + src.createIntArray().size + src.createStringArray().size)
    println(src.readByte() + src.readInt() + src.readLong() + src.readFloat() + src.readString().length)
    dest.writeByte(1); dest.writeIntArray(intArrayOf(1)); dest.writeStringArray(arrayOfNulls(1))
    dest.writeBundle(bundle); dest.writeFloat(1f); dest.writeValue(name)
    println(dest.capacity() + src.dataPosition())
}
