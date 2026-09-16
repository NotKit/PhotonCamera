/* Gson's field-name annotation.  It is source-only here: nothing reflects. */
package com.google.gson.annotations

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
annotation class SerializedName(val value: String, vararg val alternate: String)

@Retention(AnnotationRetention.SOURCE)
annotation class Expose

@Retention(AnnotationRetention.SOURCE)
annotation class JsonAdapter(val value: kotlin.reflect.KClass<*>)
