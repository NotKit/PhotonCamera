/* java.lang.reflect.Type: a marker only.  Gson's TypeToken implements it so
 * the Java can keep declaring `Type listType = new TypeToken<...>(){}.getType()`.
 * The reflection surface proper -- Field, Method, Class.getDeclaredFields --
 * is deliberately ABSENT: Kotlin/Native has no reflection, and every walker
 * that wanted one is a seam (see the platform lane's report). */
package java.lang.reflect

interface Type
