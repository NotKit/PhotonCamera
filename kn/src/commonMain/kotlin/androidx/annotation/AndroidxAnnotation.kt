/* androidx.annotation: source-retention markers only. */
package androidx.annotation

@Retention(AnnotationRetention.SOURCE)
annotation class Nullable

@Retention(AnnotationRetention.SOURCE)
annotation class NonNull

@Retention(AnnotationRetention.SOURCE)
annotation class StringRes

@Retention(AnnotationRetention.SOURCE)
annotation class DrawableRes

@Retention(AnnotationRetention.SOURCE)
annotation class ColorRes

@Retention(AnnotationRetention.SOURCE)
annotation class ColorInt

@Retention(AnnotationRetention.SOURCE)
annotation class ArrayRes

@Retention(AnnotationRetention.SOURCE)
annotation class RawRes

@Retention(AnnotationRetention.SOURCE)
annotation class IdRes

@Retention(AnnotationRetention.SOURCE)
annotation class LayoutRes

@Retention(AnnotationRetention.SOURCE)
annotation class RequiresApi(val value: Int = 1, val api: Int = 1)

@Retention(AnnotationRetention.SOURCE)
annotation class RequiresPermission(vararg val value: String)

@Retention(AnnotationRetention.SOURCE)
annotation class VisibleForTesting

@Retention(AnnotationRetention.SOURCE)
annotation class MainThread

@Retention(AnnotationRetention.SOURCE)
annotation class WorkerThread

@Retention(AnnotationRetention.SOURCE)
annotation class UiThread

@Retention(AnnotationRetention.SOURCE)
annotation class CallSuper

@Retention(AnnotationRetention.SOURCE)
annotation class Keep

@Retention(AnnotationRetention.SOURCE)
annotation class FloatRange(val from: Double = 0.0, val to: Double = 0.0)

@Retention(AnnotationRetention.SOURCE)
annotation class IntRange(val from: Long = 0L, val to: Long = 0L)
