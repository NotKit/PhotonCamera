package photoncam.camera

/**
 * The type token a camera2 Key carries.  AOSP uses java.lang.Class, which
 * Kotlin/Native does not have; the marshalling in CameraMetadataNative only
 * ever compares tokens for equality, so an interned name is all of it that is
 * real.  kn/atlas-fixups.sh rewrites `Foo::class.java` in the converted key
 * tables into `MetaType.of("Foo")`, and an array token is "Foo[]".
 */
class MetaType private constructor(val name: String) {
	/** the spelling AOSP's Class.getName() would give; the simple name here */
	fun getName(): String = name

	override fun toString(): String = name

	companion object {
		private val interned = HashMap<String, MetaType>()

		/**
		 * The token for a Kotlin class, for the app's own `new Key<>(name,
		 * Foo.class)`.  A Kotlin array class is erased to Array, so only the
		 * primitive arrays a vendor key ever uses come back as array tokens.
		 */
		fun ofClass(type: kotlin.reflect.KClass<*>?): MetaType {
			val simple = type?.simpleName ?: "Any"
			return of(when (simple) {
				"Integer" -> "Int"
				"IntArray" -> "Int[]"
				"ByteArray" -> "Byte[]"
				"LongArray" -> "Long[]"
				"FloatArray" -> "Float[]"
				"DoubleArray" -> "Double[]"
				else -> simple
			})
		}

		fun of(name: String): MetaType {
			val known = interned[name]
			if (known != null)
				return known
			val made = MetaType(name)
			interned[name] = made
			return made
		}
	}
}
