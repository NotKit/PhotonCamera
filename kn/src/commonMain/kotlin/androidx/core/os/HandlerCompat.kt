package androidx.core.os

object HandlerCompat {
    /** "Async" is a message-queue hint Android uses for vsync barriers; this
     *  queue has no barriers, so the async Handler is the plain one. */
    fun createAsync(looper: android.os.Looper): android.os.Handler = android.os.Handler(looper)
}
