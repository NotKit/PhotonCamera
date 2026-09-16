#!/usr/bin/env python3
"""
Emit Kotlin/Native stubs for the android.util + android.graphics(+.drawable)
slice of the android.* surface that GeckoView's Java touches.

Re-runnable: it re-scrapes /home/nekit/UT/firefox-src-kn/mobile/android/geckoview/src/main/java on every run,
decides which types of this slice are actually referenced, emits only those (plus
their dependencies), and prints a coverage report naming every call-site member
it saw that no emitted declaration covers.

Run with the venv python (javalang):
  /home/nekit/UT/kn-toolchain/venv/bin/python kn-run/stubgen/util-gfx.py
"""

import os, re, sys, json, collections

SRC = "/home/nekit/UT/firefox-src-kn/mobile/android/geckoview/src/main/java"
OUTDIR = "/home/nekit/UT/firefox-src-kn/mobile/android/geckoview-kn/src/stubs"

# ---------------------------------------------------------------- scrape ----

# Types this slice owns, keyed by simple name -> package.
OWNED = {
    # android.util
    "Log": "android.util", "SparseArray": "android.util",
    "LongSparseArray": "android.util", "SparseIntArray": "android.util",
    "SparseBooleanArray": "android.util", "DisplayMetrics": "android.util",
    "Base64": "android.util", "Pair": "android.util",
    "TypedValue": "android.util", "AttributeSet": "android.util",
    # android.graphics
    "Rect": "android.graphics", "RectF": "android.graphics",
    "Bitmap": "android.graphics", "Point": "android.graphics",
    "PointF": "android.graphics", "Matrix": "android.graphics",
    "Canvas": "android.graphics", "Color": "android.graphics",
    "SurfaceTexture": "android.graphics", "PixelFormat": "android.graphics",
    "Region": "android.graphics", "Paint": "android.graphics",
    "Xfermode": "android.graphics", "PorterDuff": "android.graphics",
    "PorterDuffXfermode": "android.graphics", "BlendMode": "android.graphics",
    "Insets": "android.graphics",
    # android.graphics.drawable
    "Drawable": "android.graphics.drawable",
    "BitmapDrawable": "android.graphics.drawable",
    "ColorDrawable": "android.graphics.drawable",
    "StateListDrawable": "android.graphics.drawable",
}

IMPORT_RE = re.compile(r'^\s*import\s+(android\.(?:util|graphics)(?:\.drawable)?)\.([A-Za-z0-9_]+)\s*;', re.M)


def java_files():
    for r, _d, fs in os.walk(SRC):
        for f in fs:
            if f.endswith(".java"):
                yield os.path.join(r, f)


def scrape():
    """Return (imported types, observed members per type, parse failures)."""
    imported = collections.Counter()
    members = collections.defaultdict(collections.Counter)
    failures = []
    try:
        import javalang
    except ImportError:
        javalang = None
        failures.append("javalang not importable - member scrape skipped")

    for path in java_files():
        src = open(path, encoding="utf-8", errors="replace").read()
        for pkg, name in IMPORT_RE.findall(src):
            if OWNED.get(name) == pkg or (name in OWNED and OWNED[name] == pkg):
                imported[name] += 1
        if javalang is None:
            continue
        try:
            tree = javalang.parse.parse(src)
        except Exception:
            failures.append(path)
            # regex fallback: at least record Type.member and new Type(
            for t in OWNED:
                for m in re.findall(r'\b%s\.([A-Za-z_][A-Za-z0-9_]*)' % t, src):
                    members[t][m] += 1
                if re.search(r'\bnew\s+%s\s*[<(]' % t, src):
                    members[t]["<init>"] += 1
            continue

        env = {}
        for _p, node in tree:
            cls = type(node).__name__
            if cls in ("LocalVariableDeclaration", "FieldDeclaration", "FormalParameter"):
                tn = getattr(node.type, "name", None)
                if tn in OWNED:
                    for d in (getattr(node, "declarators", None) or []):
                        env[d.name] = tn
                    if getattr(node, "name", None):
                        env[node.name] = tn
            elif cls == "ClassCreator":
                tn = getattr(node.type, "name", None)
                if tn in OWNED:
                    members[tn]["<init>/%d" % len(node.arguments or [])] += 1
            elif cls == "MethodInvocation":
                q = node.qualifier
                if q in OWNED:
                    members[q][node.member] += 1
                elif q and q.split(".")[0] in env:
                    members[env[q.split(".")[0]]][node.member] += 1
            elif cls == "MemberReference":
                q = node.qualifier
                if q in OWNED:
                    members[q][node.member] += 1
                elif q and q.split(".")[0] in env:
                    members[env[q.split(".")[0]]][node.member] += 1
            elif cls in ("ClassDeclaration", "InterfaceDeclaration"):
                sup = ([node.extends] if getattr(node, "extends", None) else [])
                if isinstance(sup, list):
                    sup = [s for s in sup if s]
                for e in list(sup) + (getattr(node, "implements", None) or []):
                    tn = getattr(e, "name", None)
                    if tn in OWNED:
                        members[tn]["<extends>"] += 1
    return imported, members, failures


# ------------------------------------------------------------ signatures ----
# Blocks are hand-written per type because these are irregular API shapes, but
# WHICH blocks land in the .kt is decided by the scrape above.
#
# Scalar parameters are typed `Number` on purpose: Java widens int->float
# silently and Kotlin does not, so `matrix.postTranslate(0, top - y)` would
# otherwise not resolve. Fields and return types keep their real types.

T = collections.namedtuple("T", "pkg deps code")

TYPES = {}

TYPES["Log"] = T("android.util", [], '''
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    fun v(tag: String?, msg: Any?): Int = TODO()
    fun v(tag: String?, msg: Any?, tr: Any?): Int = TODO()
    fun d(tag: String?, msg: Any?): Int = TODO()
    fun d(tag: String?, msg: Any?, tr: Any?): Int = TODO()
    fun i(tag: String?, msg: Any?): Int = TODO()
    fun i(tag: String?, msg: Any?, tr: Any?): Int = TODO()
    fun w(tag: String?, msg: Any?): Int = TODO()
    fun w(tag: String?, msg: Any?, tr: Any?): Int = TODO()
    fun e(tag: String?, msg: Any?): Int = TODO()
    fun e(tag: String?, msg: Any?, tr: Any?): Int = TODO()
    fun wtf(tag: String?, msg: Any?): Int = TODO()
    fun wtf(tag: String?, msg: Any?, tr: Any?): Int = TODO()
    fun isLoggable(tag: String?, level: Int): Boolean = TODO()
    fun getStackTraceString(tr: Throwable?): String = TODO()
    fun println(priority: Int, tag: String?, msg: String?): Int = TODO()
}
''')

TYPES["SparseArray"] = T("android.util", [], '''
open class SparseArray<E> {
    constructor()
    constructor(initialCapacity: Number)

    open fun get(key: Number): E = TODO()
    open fun get(key: Number, valueIfKeyNotFound: E): E = TODO()
    open fun put(key: Number, value: E) { TODO() }
    open fun append(key: Number, value: E) { TODO() }
    open fun delete(key: Number) { TODO() }
    open fun remove(key: Number) { TODO() }
    open fun removeAt(index: Number) { TODO() }
    open fun setValueAt(index: Number, value: E) { TODO() }
    open fun keyAt(index: Number): Int = TODO()
    open fun valueAt(index: Number): E = TODO()
    open fun indexOfKey(key: Number): Int = TODO()
    open fun indexOfValue(value: E): Int = TODO()
    open fun size(): Int = TODO()
    open fun clear() { TODO() }
}
''')

TYPES["LongSparseArray"] = T("android.util", [], '''
open class LongSparseArray<E> {
    constructor()
    constructor(initialCapacity: Number)

    open fun get(key: Number): E = TODO()
    open fun get(key: Number, valueIfKeyNotFound: E): E = TODO()
    open fun put(key: Number, value: E) { TODO() }
    open fun append(key: Number, value: E) { TODO() }
    open fun delete(key: Number) { TODO() }
    open fun remove(key: Number) { TODO() }
    open fun removeAt(index: Number) { TODO() }
    open fun setValueAt(index: Number, value: E) { TODO() }
    open fun keyAt(index: Number): Long = TODO()
    open fun valueAt(index: Number): E = TODO()
    open fun indexOfKey(key: Number): Int = TODO()
    open fun indexOfValue(value: E): Int = TODO()
    open fun size(): Int = TODO()
    open fun clear() { TODO() }
}
''')

TYPES["SparseIntArray"] = T("android.util", [], '''
open class SparseIntArray {
    constructor()
    constructor(initialCapacity: Number)
    open fun get(key: Number): Int = TODO()
    open fun get(key: Number, valueIfKeyNotFound: Number): Int = TODO()
    open fun put(key: Number, value: Number) { TODO() }
    open fun delete(key: Number) { TODO() }
    open fun keyAt(index: Number): Int = TODO()
    open fun valueAt(index: Number): Int = TODO()
    open fun indexOfKey(key: Number): Int = TODO()
    open fun size(): Int = TODO()
    open fun clear() { TODO() }
}
''')

TYPES["SparseBooleanArray"] = T("android.util", [], '''
open class SparseBooleanArray {
    constructor()
    constructor(initialCapacity: Number)
    open fun get(key: Number): Boolean = TODO()
    open fun get(key: Number, valueIfKeyNotFound: Boolean): Boolean = TODO()
    open fun put(key: Number, value: Boolean) { TODO() }
    open fun delete(key: Number) { TODO() }
    open fun keyAt(index: Number): Int = TODO()
    open fun valueAt(index: Number): Boolean = TODO()
    open fun indexOfKey(key: Number): Int = TODO()
    open fun size(): Int = TODO()
    open fun clear() { TODO() }
}
''')

TYPES["DisplayMetrics"] = T("android.util", [], '''
open class DisplayMetrics {
    var widthPixels: Int = 0
    var heightPixels: Int = 0
    var density: Float = 0f
    var densityDpi: Int = 0
    var scaledDensity: Float = 0f
    var xdpi: Float = 0f
    var ydpi: Float = 0f

    open fun setTo(o: DisplayMetrics?) { TODO() }
    open fun setToDefaults() { TODO() }

    companion object {
        const val DENSITY_DEFAULT = 160
    }
}
''')

TYPES["Base64"] = T("android.util", [], '''
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    fun decode(str: String?, flags: Int): ByteArray = TODO()
    fun decode(input: ByteArray?, flags: Int): ByteArray = TODO()
    fun decode(input: ByteArray?, offset: Int, len: Int, flags: Int): ByteArray = TODO()
    fun encode(input: ByteArray?, flags: Int): ByteArray = TODO()
    fun encode(input: ByteArray?, offset: Int, len: Int, flags: Int): ByteArray = TODO()
    fun encodeToString(input: ByteArray?, flags: Int): String = TODO()
    fun encodeToString(input: ByteArray?, offset: Int, len: Int, flags: Int): String = TODO()
}
''')

TYPES["Pair"] = T("android.util", [], '''
open class Pair<F, S>(val first: F, val second: S) {
    companion object {
        fun <A, B> create(a: A, b: B): Pair<A, B> = TODO()
    }
}
''')

TYPES["TypedValue"] = T("android.util", ["DisplayMetrics"], '''
open class TypedValue {
    var type: Int = 0
    var data: Int = 0
    var resourceId: Int = 0
    var string: CharSequence? = null

    open fun getDimension(metrics: DisplayMetrics?): Float = TODO()
    open fun getFloat(): Float = TODO()
    open fun coerceToString(): CharSequence? = TODO()

    companion object {
        const val COMPLEX_UNIT_PX = 0
        const val COMPLEX_UNIT_DIP = 1
        const val COMPLEX_UNIT_SP = 2
        const val TYPE_NULL = 0
        fun applyDimension(unit: Int, value: Float, metrics: DisplayMetrics?): Float = TODO()
        fun complexToDimension(data: Int, metrics: DisplayMetrics?): Float = TODO()
        fun complexToDimensionPixelSize(data: Int, metrics: DisplayMetrics?): Int = TODO()
    }
}
''')

TYPES["AttributeSet"] = T("android.util", [], '''
interface AttributeSet {
    fun getAttributeCount(): Int
    fun getAttributeName(index: Int): String?
    fun getAttributeValue(index: Int): String?
    fun getAttributeValue(namespace: String?, name: String?): String?
}
''')

# ------------------------------------------------------------- graphics ----

TYPES["Rect"] = T("android.graphics", [], '''
open class Rect {
    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0

    constructor()
    constructor(left: Number, top: Number, right: Number, bottom: Number)
    constructor(r: Rect?)

    open fun isEmpty(): Boolean = TODO()
    open fun width(): Int = TODO()
    open fun height(): Int = TODO()
    open fun centerX(): Int = TODO()
    open fun centerY(): Int = TODO()
    open fun exactCenterX(): Float = TODO()
    open fun exactCenterY(): Float = TODO()
    open fun setEmpty() { TODO() }
    open fun set(left: Number, top: Number, right: Number, bottom: Number) { TODO() }
    open fun set(src: Rect?) { TODO() }
    open fun offset(dx: Number, dy: Number) { TODO() }
    open fun offsetTo(newLeft: Number, newTop: Number) { TODO() }
    open fun inset(dx: Number, dy: Number) { TODO() }
    open fun contains(x: Number, y: Number): Boolean = TODO()
    open fun contains(left: Number, top: Number, right: Number, bottom: Number): Boolean = TODO()
    open fun contains(r: Rect?): Boolean = TODO()
    open fun intersect(left: Number, top: Number, right: Number, bottom: Number): Boolean = TODO()
    open fun intersect(r: Rect?): Boolean = TODO()
    open fun setIntersect(a: Rect?, b: Rect?): Boolean = TODO()
    open fun union(left: Number, top: Number, right: Number, bottom: Number) { TODO() }
    open fun union(r: Rect?) { TODO() }
    open fun sort() { TODO() }
    open fun scale(scale: Number) { TODO() }
    open fun toShortString(): String = TODO()
    open fun flattenToString(): String = TODO()
}
''')

TYPES["RectF"] = T("android.graphics", ["Rect"], '''
open class RectF {
    var left: Float = 0f
    var top: Float = 0f
    var right: Float = 0f
    var bottom: Float = 0f

    constructor()
    constructor(left: Number, top: Number, right: Number, bottom: Number)
    constructor(r: RectF?)
    constructor(r: Rect?)

    open fun isEmpty(): Boolean = TODO()
    open fun width(): Float = TODO()
    open fun height(): Float = TODO()
    open fun centerX(): Float = TODO()
    open fun centerY(): Float = TODO()
    open fun setEmpty() { TODO() }
    open fun set(left: Number, top: Number, right: Number, bottom: Number) { TODO() }
    open fun set(src: RectF?) { TODO() }
    open fun set(src: Rect?) { TODO() }
    open fun offset(dx: Number, dy: Number) { TODO() }
    open fun offsetTo(newLeft: Number, newTop: Number) { TODO() }
    open fun inset(dx: Number, dy: Number) { TODO() }
    open fun contains(x: Number, y: Number): Boolean = TODO()
    open fun contains(left: Number, top: Number, right: Number, bottom: Number): Boolean = TODO()
    open fun contains(r: RectF?): Boolean = TODO()
    open fun intersect(left: Number, top: Number, right: Number, bottom: Number): Boolean = TODO()
    open fun intersect(r: RectF?): Boolean = TODO()
    open fun setIntersect(a: RectF?, b: RectF?): Boolean = TODO()
    open fun round(dst: Rect?) { TODO() }
    open fun roundOut(dst: Rect?) { TODO() }
    open fun union(left: Number, top: Number, right: Number, bottom: Number) { TODO() }
    open fun union(r: RectF?) { TODO() }
    open fun sort() { TODO() }
    open fun toShortString(): String = TODO()
}
''')

TYPES["Point"] = T("android.graphics", [], '''
open class Point {
    var x: Int = 0
    var y: Int = 0

    constructor()
    constructor(x: Number, y: Number)
    constructor(src: Point?)

    open fun set(x: Number, y: Number) { TODO() }
    open fun negate() { TODO() }
    open fun offset(dx: Number, dy: Number) { TODO() }
    open fun equals(x: Number, y: Number): Boolean = TODO()
}
''')

TYPES["PointF"] = T("android.graphics", ["Point"], '''
open class PointF {
    var x: Float = 0f
    var y: Float = 0f

    constructor()
    constructor(x: Number, y: Number)
    constructor(p: Point?)
    constructor(p: PointF?)

    open fun set(x: Number, y: Number) { TODO() }
    open fun set(p: PointF?) { TODO() }
    open fun negate() { TODO() }
    open fun offset(dx: Number, dy: Number) { TODO() }
    open fun length(): Float = TODO()
    open fun equals(x: Number, y: Number): Boolean = TODO()
}
''')

TYPES["Bitmap"] = T("android.graphics", [], '''
open class Bitmap {
    enum class Config {
        ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE, RGBA_1010102
    }

    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    open fun getWidth(): Int = TODO()
    open fun getHeight(): Int = TODO()
    open fun getConfig(): Config? = TODO()
    open fun getByteCount(): Int = TODO()
    open fun getRowBytes(): Int = TODO()
    open fun isRecycled(): Boolean = TODO()
    open fun recycle() { TODO() }
    open fun eraseColor(c: Number) { TODO() }
    open fun setHasAlpha(hasAlpha: Boolean) { TODO() }
    open fun copyPixelsToBuffer(buffer: Any?) { TODO() }
    open fun copyPixelsFromBuffer(buffer: Any?) { TODO() }
    open fun compress(format: CompressFormat?, quality: Number, stream: Any?): Boolean = TODO()

    companion object {
        fun createBitmap(width: Number, height: Number, config: Config?): Bitmap = TODO()
        fun createBitmap(src: Bitmap?): Bitmap = TODO()
        fun createBitmap(colors: IntArray?, width: Number, height: Number, config: Config?): Bitmap = TODO()
        fun createScaledBitmap(src: Bitmap?, dstWidth: Number, dstHeight: Number, filter: Boolean): Bitmap = TODO()
    }
}
''')

TYPES["Matrix"] = T("android.graphics", ["RectF"], '''
open class Matrix {
    constructor()
    constructor(src: Matrix?)

    open fun reset() { TODO() }
    open fun set(src: Matrix?) { TODO() }
    open fun isIdentity(): Boolean = TODO()
    open fun setScale(sx: Number, sy: Number): Boolean = TODO()
    open fun setScale(sx: Number, sy: Number, px: Number, py: Number): Boolean = TODO()
    open fun setTranslate(dx: Number, dy: Number): Boolean = TODO()
    open fun postScale(sx: Number, sy: Number): Boolean = TODO()
    open fun postTranslate(dx: Number, dy: Number): Boolean = TODO()
    open fun preScale(sx: Number, sy: Number): Boolean = TODO()
    open fun preTranslate(dx: Number, dy: Number): Boolean = TODO()
    open fun postConcat(other: Matrix?): Boolean = TODO()
    open fun preConcat(other: Matrix?): Boolean = TODO()
    open fun invert(inverse: Matrix?): Boolean = TODO()
    open fun mapRect(dst: RectF?, src: RectF?): Boolean = TODO()
    open fun mapRect(rect: RectF?): Boolean = TODO()
    open fun mapPoints(pts: FloatArray?) { TODO() }
    open fun mapPoints(dst: FloatArray?, src: FloatArray?) { TODO() }
    open fun getValues(values: FloatArray?) { TODO() }
    open fun setValues(values: FloatArray?) { TODO() }
}
''')

TYPES["Canvas"] = T("android.graphics", ["Bitmap", "Rect", "RectF", "Paint", "Matrix"], '''
open class Canvas {
    constructor()
    constructor(bitmap: Bitmap?)

    open fun getWidth(): Int = TODO()
    open fun getHeight(): Int = TODO()
    open fun save(): Int = TODO()
    open fun save(saveFlags: Number): Int = TODO()
    open fun restore() { TODO() }
    open fun restoreToCount(saveCount: Number) { TODO() }
    open fun getSaveCount(): Int = TODO()
    open fun translate(dx: Number, dy: Number) { TODO() }
    open fun scale(sx: Number, sy: Number) { TODO() }
    open fun rotate(degrees: Number) { TODO() }
    open fun concat(matrix: Matrix?) { TODO() }
    open fun clipRect(rect: Rect?): Boolean = TODO()
    open fun clipRect(rect: RectF?): Boolean = TODO()
    open fun drawColor(color: Number) { TODO() }
    open fun drawRect(rect: RectF?, paint: Paint?) { TODO() }
    open fun drawBitmap(bitmap: Bitmap?, left: Number, top: Number, paint: Paint?) { TODO() }
    open fun drawBitmap(bitmap: Bitmap?, src: Rect?, dst: RectF?, paint: Paint?) { TODO() }
}
''')

TYPES["Color"] = T("android.graphics", [], '''
open class Color {
    companion object {
        const val BLACK: Int = -0x1000000
        const val DKGRAY: Int = -0xbbbbbc
        const val GRAY: Int = -0x777778
        const val LTGRAY: Int = -0x333334
        const val WHITE: Int = -0x1
        const val RED: Int = -0x10000
        const val GREEN: Int = -0xff0100
        const val BLUE: Int = -0xffff01
        const val YELLOW: Int = -0x100
        const val CYAN: Int = -0xff0001
        const val MAGENTA: Int = -0xff01
        const val TRANSPARENT: Int = 0

        fun alpha(color: Int): Int = TODO()
        fun red(color: Int): Int = TODO()
        fun green(color: Int): Int = TODO()
        fun blue(color: Int): Int = TODO()
        fun rgb(red: Number, green: Number, blue: Number): Int = TODO()
        fun argb(alpha: Number, red: Number, green: Number, blue: Number): Int = TODO()
        fun parseColor(colorString: String?): Int = TODO()
    }
}
''')

TYPES["SurfaceTexture"] = T("android.graphics", [], '''
open class SurfaceTexture {
    interface OnFrameAvailableListener {
        fun onFrameAvailable(surfaceTexture: SurfaceTexture?)
    }

    class OutOfResourceException : Exception {
        constructor()
        constructor(name: String?)
    }

    constructor(texName: Number)
    constructor(texName: Number, singleBufferMode: Boolean)
    constructor(singleBufferMode: Boolean)

    open fun setOnFrameAvailableListener(listener: OnFrameAvailableListener?) { TODO() }
    open fun setDefaultBufferSize(width: Number, height: Number) { TODO() }
    open fun updateTexImage() { TODO() }
    open fun releaseTexImage() { TODO() }
    open fun attachToGLContext(texName: Number) { TODO() }
    open fun detachFromGLContext() { TODO() }
    open fun getTransformMatrix(mtx: FloatArray?) { TODO() }
    open fun getTimestamp(): Long = TODO()
    open fun release() { TODO() }
    open fun isReleased(): Boolean = TODO()
    protected open fun finalize() { TODO() }
}
''')

TYPES["PixelFormat"] = T("android.graphics", [], '''
open class PixelFormat {
    var bytesPerPixel: Int = 0
    var bitsPerPixel: Int = 0

    companion object {
        const val UNKNOWN = 0
        const val TRANSLUCENT = -3
        const val TRANSPARENT = -2
        const val OPAQUE = -1
        const val RGBA_8888 = 1
        const val RGBX_8888 = 2
        const val RGB_888 = 3
        const val RGB_565 = 4

        fun getPixelFormatInfo(format: Int, info: PixelFormat?) { TODO() }
        fun formatHasAlpha(format: Int): Boolean = TODO()
    }
}
''')

TYPES["Region"] = T("android.graphics", ["Rect"], '''
open class Region {
    constructor()
    constructor(region: Region?)
    constructor(r: Rect?)
    constructor(left: Number, top: Number, right: Number, bottom: Number)

    open fun isEmpty(): Boolean = TODO()
    open fun isRect(): Boolean = TODO()
    open fun getBounds(): Rect = TODO()
    open fun setEmpty() { TODO() }
    open fun set(region: Region?): Boolean = TODO()
    open fun set(r: Rect?): Boolean = TODO()
    open fun contains(x: Number, y: Number): Boolean = TODO()
    open fun union(r: Rect?): Boolean = TODO()
}
''')

TYPES["Xfermode"] = T("android.graphics", [], '''
open class Xfermode
''')

TYPES["PorterDuff"] = T("android.graphics", [], '''
open class PorterDuff {
    enum class Mode {
        CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT,
        SRC_ATOP, DST_ATOP, XOR, DARKEN, LIGHTEN, MULTIPLY, SCREEN, ADD, OVERLAY
    }
}
''')

TYPES["PorterDuffXfermode"] = T("android.graphics", ["Xfermode", "PorterDuff"], '''
open class PorterDuffXfermode(mode: PorterDuff.Mode?) : Xfermode()
''')

TYPES["BlendMode"] = T("android.graphics", [], '''
enum class BlendMode {
    CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT,
    SRC_ATOP, DST_ATOP, XOR, PLUS, MODULATE, SCREEN, OVERLAY, DARKEN, LIGHTEN,
    COLOR_DODGE, COLOR_BURN, HARD_LIGHT, SOFT_LIGHT, DIFFERENCE, EXCLUSION,
    MULTIPLY, HUE, SATURATION, COLOR, LUMINOSITY
}
''')

TYPES["Paint"] = T("android.graphics", ["Xfermode", "BlendMode"], '''
open class Paint {
    constructor()
    constructor(flags: Number)
    constructor(paint: Paint?)

    open fun setColor(color: Number) { TODO() }
    open fun getColor(): Int = TODO()
    open fun setAlpha(a: Number) { TODO() }
    open fun getAlpha(): Int = TODO()
    open fun setAntiAlias(aa: Boolean) { TODO() }
    open fun setXfermode(xfermode: Xfermode?): Xfermode? = TODO()
    open fun setBlendMode(blendmode: BlendMode?) { TODO() }
    open fun setStrokeWidth(width: Number) { TODO() }
    open fun reset() { TODO() }

    companion object {
        const val ANTI_ALIAS_FLAG = 0x01
        const val FILTER_BITMAP_FLAG = 0x02
    }
}
''')

TYPES["Insets"] = T("android.graphics", ["Rect"], '''
open class Insets {
    val left: Int = 0
    val top: Int = 0
    val right: Int = 0
    val bottom: Int = 0

    open fun toRect(): Rect = TODO()

    companion object {
        val NONE: Insets = TODO()
        fun of(left: Number, top: Number, right: Number, bottom: Number): Insets = TODO()
        fun of(r: Rect?): Insets = TODO()
        fun add(a: Insets?, b: Insets?): Insets = TODO()
        fun subtract(a: Insets?, b: Insets?): Insets = TODO()
        fun max(a: Insets?, b: Insets?): Insets = TODO()
        fun min(a: Insets?, b: Insets?): Insets = TODO()
    }
}
''')

# ------------------------------------------------------------- drawable ----

TYPES["Drawable"] = T("android.graphics.drawable", ["Canvas", "Rect", "ColorFilter"], '''
abstract class Drawable {
    interface Callback {
        fun invalidateDrawable(who: Drawable?)
        fun scheduleDrawable(who: Drawable?, what: Any?, whenMs: Long)
        fun unscheduleDrawable(who: Drawable?, what: Any?)
    }

    abstract fun draw(canvas: Canvas?)

    open fun setBounds(left: Number, top: Number, right: Number, bottom: Number) { TODO() }
    open fun setBounds(bounds: Rect?) { TODO() }
    open fun getBounds(): Rect = TODO()
    open fun getIntrinsicWidth(): Int = TODO()
    open fun getIntrinsicHeight(): Int = TODO()
    open fun setAlpha(alpha: Int) { TODO() }
    open fun setColorFilter(cf: ColorFilter?) { TODO() }
    open fun getOpacity(): Int = TODO()
    open fun setState(stateSet: IntArray?): Boolean = TODO()
    open fun getState(): IntArray = TODO()
    open fun invalidateSelf() { TODO() }
    open fun mutate(): Drawable = TODO()
    open fun setCallback(cb: Callback?) { TODO() }
}
''')

TYPES["ColorFilter"] = T("android.graphics", [], '''
open class ColorFilter
''')

TYPES["BitmapDrawable"] = T("android.graphics.drawable", ["Drawable", "Bitmap", "Canvas"], '''
open class BitmapDrawable : Drawable {
    constructor()
    constructor(bitmap: Bitmap?)

    open fun getBitmap(): Bitmap = TODO()
    open fun setGravity(gravity: Number) { TODO() }
    override fun draw(canvas: Canvas?) { TODO() }
}
''')

TYPES["ColorDrawable"] = T("android.graphics.drawable", ["Drawable", "Canvas"], '''
open class ColorDrawable : Drawable {
    constructor()
    constructor(color: Number)

    open fun getColor(): Int = TODO()
    open fun setColor(color: Number) { TODO() }
    override fun draw(canvas: Canvas?) { TODO() }
}
''')

TYPES["StateListDrawable"] = T("android.graphics.drawable", ["Drawable", "Canvas"], '''
open class StateListDrawable : Drawable {
    constructor()

    open fun addState(stateSet: IntArray?, drawable: Drawable?) { TODO() }
    open fun getStateCount(): Int = TODO()
    open fun getStateDrawable(index: Number): Drawable? = TODO()
    override fun draw(canvas: Canvas?) { TODO() }
}
''')

# Types the drawable/graphics blocks pull in implicitly and that must exist even
# if GeckoView never names them.
IMPLICIT = ["Xfermode", "ColorFilter"]

HEADER = """// GENERATED by kn-run/stubgen/util-gfx.py -- do not edit by hand.
// android.util + android.graphics(+.drawable) stubs for the Kotlin/Native lane.
// Signatures are derived from GeckoView call sites; where a call site was
// ambiguous the shape was read off ATL's Java reimplementation in
// atlas/src/api-impl/android/. Bodies are TODO(): nothing here ever runs.
//
// Scalar params are `Number` on purpose -- Java widens int->float implicitly
// and Kotlin does not, so transpiled `matrix.postTranslate(0, a - b)` still
// resolves.
@file:Suppress("unused", "UNUSED_PARAMETER", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
"""

PKG_FILES = {
    "android.graphics": "util-gfx.kt",
    "android.util": "util-gfx-util.kt",
    "android.graphics.drawable": "util-gfx-drawable.kt",
}

# Kotlin allows one package header per file, so the slice lands as three files.
PKG_IMPORTS = {
    "android.graphics.drawable": ["android.graphics.Bitmap", "android.graphics.Canvas",
                                  "android.graphics.ColorFilter", "android.graphics.Rect"],
}


def resolve(names):
    out, stack = set(), list(names)
    while stack:
        n = stack.pop()
        if n in out or n not in TYPES:
            continue
        out.add(n)
        stack.extend(TYPES[n].deps)
    return out


def main():
    imported, members, failures = scrape()
    wanted = set(imported) | set(IMPLICIT)
    # anything with observed members but no import (inner/derived use) counts too
    wanted |= {t for t, c in members.items() if c}
    emit = resolve(n for n in wanted if n in TYPES)

    os.makedirs(OUTDIR, exist_ok=True)
    bypkg = collections.defaultdict(list)
    for name in sorted(emit):
        bypkg[TYPES[name].pkg].append(name)

    written = []
    for pkg, fname in PKG_FILES.items():
        names = bypkg.get(pkg, [])
        path = os.path.join(OUTDIR, fname)
        if not names:
            if os.path.exists(path):
                os.remove(path)
            continue
        parts = [HEADER, "package %s\n" % pkg]
        for imp in PKG_IMPORTS.get(pkg, []):
            parts.append("import %s" % imp)
        if PKG_IMPORTS.get(pkg):
            parts.append("")
        for n in names:
            parts.append(TYPES[n].code.strip() + "\n")
        open(path, "w").write("\n".join(parts))
        written.append((path, len(names)))

    # ------------------------------------------------------------ report ---
    print("== emitted")
    for path, n in written:
        print("   %-70s %2d types" % (path, n))
    print("== types emitted: %d (%d imported by GeckoView, %d implicit deps)"
          % (len(emit), len(set(imported) & set(TYPES)), len(emit) - len(set(imported) & set(TYPES))))

    print("== uncovered call-site members (scraped but not declared)")
    gaps = 0
    for t in sorted(members):
        if t not in emit:
            continue
        decl = TYPES[t].code
        for m, cnt in sorted(members[t].items()):
            if m.startswith("<"):
                continue
            if re.search(r'\b(fun|val|var)\s+%s\b' % re.escape(m), decl) or \
               re.search(r'\b%s\b' % re.escape(m), decl):
                continue
            print("   %s.%s  (%d sites)" % (t, m, cnt))
            gaps += 1
    if not gaps:
        print("   (none)")
    if failures:
        print("== java files javalang could not parse (regex fallback used): %d" % len(failures))
        for f in failures:
            print("   " + f)


if __name__ == "__main__":
    main()
