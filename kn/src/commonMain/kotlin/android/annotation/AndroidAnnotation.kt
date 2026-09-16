/* android.annotation / androidx.annotation: source-retention markers.  They
 * carry no behaviour on any platform, so an empty annotation class is real. */
package android.annotation

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD,
    AnnotationTarget.EXPRESSION, AnnotationTarget.LOCAL_VARIABLE)
@Retention(AnnotationRetention.SOURCE)
annotation class SuppressLint(vararg val value: String)

@Retention(AnnotationRetention.SOURCE)
annotation class TargetApi(val value: Int)

@Retention(AnnotationRetention.SOURCE)
annotation class Nullable

@Retention(AnnotationRetention.SOURCE)
annotation class NonNull
