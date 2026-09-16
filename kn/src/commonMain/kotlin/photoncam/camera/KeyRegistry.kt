package photoncam.camera

/**
 * The Key constants of CaptureRequest, CaptureResult and CameraCharacteristics.
 * AOSP (and atlas) enumerate them with getDeclaredFields(); Kotlin/Native has
 * no reflection, so each Key registers itself as it is constructed and
 * keysByName() reads the register back.
 *
 * First writer wins: the constants are built during class initialisation, so
 * the untyped keys getKeys() makes later never displace them.
 */
object KeyRegistry {
	private val byOwner = HashMap<String, HashMap<String, Any>>()

	fun put(owner: String, name: String?, key: Any) {
		if (name == null)
			return
		val map = byOwner.getOrPut(owner) { HashMap() }
		if (!map.containsKey(name))
			map[name] = key
	}

	@Suppress("UNCHECKED_CAST")
	fun <K> snapshot(owner: String): HashMap<String, K> =
		HashMap(byOwner[owner] ?: HashMap()) as HashMap<String, K>
}
