#!/usr/bin/env python3
"""Generate Kotlin/Native stubs for the androidx.* surface GeckoView imports.

Re-runnable against a fresh mozilla-central: it re-scrapes the Java tree, then
emits the stub files. If the scrape finds an androidx type this generator does
not cover, it says so loudly on stderr and exits non-zero.

Kotlin allows exactly ONE package per file, so the "one file" this produces is
really one file per androidx package: androidx.kt (androidx.annotation, the
biggest slice by site count) plus androidx.<pkg>.kt siblings.

  usage: python3 stubgen/androidx.py [--java DIR] [--out DIR] [--report]
"""

import argparse
import os
import re
import sys
from collections import Counter

JAVA_DEFAULT = "/home/nekit/UT/firefox-src-kn/mobile/android/geckoview/src/main/java"
OUT_DEFAULT = "/home/nekit/UT/firefox-src-kn/mobile/android/geckoview-kn/src/stubs"

# ---------------------------------------------------------------- scrape ----

REF = re.compile(r"androidx\.[A-Za-z0-9_.]+")


def scrape(java_dir):
    """Return Counter{fully.qualified.Type: sites} over the Java tree."""
    hits = Counter()
    files = Counter()
    for root, _dirs, names in os.walk(java_dir):
        for n in names:
            if not n.endswith(".java"):
                continue
            path = os.path.join(root, n)
            with open(path, encoding="utf-8", errors="replace") as f:
                text = f.read()
            seen = set()
            for m in REF.finditer(text):
                ref = m.group(0).rstrip(".;")
                # Trim to the first capitalised segment: androidx.media3.common.C
                # from androidx.media3.common.C.TRACK_TYPE_AUDIO, and drop pure
                # package prefixes like "androidx.credentials".
                parts = ref.split(".")
                idx = next((i for i, p in enumerate(parts) if p[:1].isupper()), None)
                if idx is None:
                    continue
                # keep one nested level (MappingTrackSelector.MappedTrackInfo)
                end = idx + 1
                if end < len(parts) and parts[end][:1].isupper():
                    end += 1
                ty = ".".join(parts[:end])
                hits[ty] += 1
                seen.add(ty)
            for ty in seen:
                files[ty] += 1
    return hits, files


# ------------------------------------------------------- covered surface ----

# Types this generator emits. Nested names are listed by their outer type; the
# scraper's fully-qualified form is what we compare against.
COVERED = {
    # androidx.annotation (recommend the codemod DROP these; stubs are a net)
    "androidx.annotation.NonNull", "androidx.annotation.Nullable",
    "androidx.annotation.AnyThread", "androidx.annotation.UiThread",
    "androidx.annotation.IntDef", "androidx.annotation.StringDef",
    "androidx.annotation.LongDef", "androidx.annotation.OptIn",
    "androidx.annotation.RequiresApi", "androidx.annotation.RequiresOptIn",
    "androidx.annotation.Keep", "androidx.annotation.GuardedBy",
    "androidx.annotation.VisibleForTesting",
    "androidx.annotation.ChecksSdkIntAtLeast",
    # androidx.collection
    "androidx.collection.SimpleArrayMap", "androidx.collection.ArrayMap",
    "androidx.collection.ArraySet",
    # androidx.core
    "androidx.core.view.OnApplyWindowInsetsListener",
    "androidx.core.view.WindowInsetsCompat", "androidx.core.view.ViewCompat",
    "androidx.core.os.ParcelCompat", "androidx.core.content.ContextCompat",
    "androidx.core.content.res.ResourcesCompat", "androidx.core.graphics.Insets",
    # androidx.lifecycle
    "androidx.lifecycle.Lifecycle", "androidx.lifecycle.LifecycleObserver",
    "androidx.lifecycle.OnLifecycleEvent",
    "androidx.lifecycle.ProcessLifecycleOwner",
    "androidx.lifecycle.LifecycleOwner",
    # androidx.media3.common
    "androidx.media3.common.C", "androidx.media3.common.Format",
    "androidx.media3.common.MediaItem", "androidx.media3.common.MimeTypes",
    "androidx.media3.common.PlaybackException",
    "androidx.media3.common.PlaybackParameters",
    "androidx.media3.common.Player", "androidx.media3.common.Timeline",
    "androidx.media3.common.TrackGroup", "androidx.media3.common.Tracks",
    "androidx.media3.common.util", "androidx.media3.common.Util",
    # androidx.media3.decoder / datasource
    "androidx.media3.decoder.DecoderInputBuffer",
    "androidx.media3.datasource.BaseDataSource",
    "androidx.media3.datasource.DataSpec",
    "androidx.media3.datasource.DefaultDataSource",
    "androidx.media3.datasource.DefaultHttpDataSource",
    "androidx.media3.datasource.HttpDataSource",
    "androidx.media3.datasource.HttpUtil",
    "androidx.media3.datasource.TransferListener",
    # androidx.media3.exoplayer
    "androidx.media3.exoplayer.BaseRenderer",
    "androidx.media3.exoplayer.DefaultLoadControl",
    "androidx.media3.exoplayer.ExoPlaybackException",
    "androidx.media3.exoplayer.ExoPlayer",
    "androidx.media3.exoplayer.FormatHolder",
    "androidx.media3.exoplayer.RendererCapabilities",
    "androidx.media3.exoplayer.RenderersFactory",
    "androidx.media3.exoplayer.audio", "androidx.media3.exoplayer.hls",
    "androidx.media3.exoplayer.mediacodec", "androidx.media3.exoplayer.source",
    "androidx.media3.exoplayer.trackselection",
    "androidx.media3.exoplayer.upstream",
}

# Types only ever named inside comments / string literals; nothing to stub.
COMMENT_ONLY = {
    "androidx.media3.exoplayer.audio.MediaCodecAudioRenderer",
    "androidx.media3.datasource.DefaultHttpDataSource",
}


def normalise(ty):
    """Fold sub-package-qualified hits onto the outer type we cover."""
    for prefix in (
        "androidx.media3.common.util.", "androidx.media3.exoplayer.audio.",
        "androidx.media3.exoplayer.hls.", "androidx.media3.exoplayer.mediacodec.",
        "androidx.media3.exoplayer.source.",
        "androidx.media3.exoplayer.trackselection.",
        "androidx.media3.exoplayer.upstream.",
    ):
        if ty.startswith(prefix):
            return prefix.rstrip(".")
    return ty


# --------------------------------------------------------------- emitter ----

HEADER = (
    "// GENERATED by kn-run/stubgen/androidx.py -- do not edit.\n"
    "// Kotlin/Native stubs for the androidx.* surface GeckoView imports.\n"
    "// Signatures come from GeckoView's own call sites; bodies are TODO().\n"
    "@file:Suppress(\"UNUSED_PARAMETER\", \"unused\", \"ClassName\")\n\n"
)

FILES = {}

# ---- androidx.annotation ---------------------------------------------------
# The codemod SHOULD drop every one of these (see the report): they are pure
# markers, and @IntDef/@OptIn do not even survive the Java->Kotlin syntax
# translation. These stubs exist only so a leftover marker still resolves.
FILES["androidx.kt"] = r'''
package androidx.annotation

import kotlin.reflect.KClass

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPE,
        AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.CONSTRUCTOR,
        AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class NonNull

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPE,
        AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.CONSTRUCTOR,
        AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class Nullable

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD)
annotation class AnyThread

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD)
annotation class UiThread

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD)
annotation class Keep

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class GuardedBy(val value: String)

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.FIELD, AnnotationTarget.CONSTRUCTOR)
annotation class VisibleForTesting(val otherwise: Int = 2)

// @RequiresApi is written both positionally and as api = ...
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD)
annotation class RequiresApi(val value: Int = 1, val api: Int = 1)

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class ChecksSdkIntAtLeast(
    val api: Int = -1,
    val codename: String = "",
    val parameter: Int = -1,
    val lambda: Int = -1,
)

// NOTE: Java writes @IntDef(value = {A, B}) over *int* constants but androidx
// declares long[]. Kotlin does not widen Int -> Long in an annotation argument,
// so `value` is Int here. This only matters if the codemod keeps @IntDef, which
// it should not.
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class IntDef(vararg val value: Int, val flag: Boolean = false, val open: Boolean = false)

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class LongDef(vararg val value: Long, val flag: Boolean = false, val open: Boolean = false)

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class StringDef(vararg val value: String, val open: Boolean = false)

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class RequiresOptIn(val level: Int = 0)

// Shadows kotlin.OptIn when explicitly imported. androidx spells the marker
// `markerClass = X::class`; kotlin.OptIn spells it positionally.
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD, AnnotationTarget.FILE)
annotation class OptIn(vararg val markerClass: KClass<out Annotation>)
'''

# ---- androidx.collection ---------------------------------------------------
FILES["androidx.collection.kt"] = r'''
package androidx.collection

// GeckoBundle / GeckoResult / GeckoAppShell drive this surface:
// keyAt, valueAt, ensureCapacity, containsKey, get, put, remove, size, isEmpty,
// clear. Recovered from ATL's android.util.ArrayMap reimplementation.
open class SimpleArrayMap<K, V> {
    constructor()
    constructor(capacity: Int)
    constructor(map: SimpleArrayMap<K, V>?)

    open fun clear(): Unit = TODO()
    open fun ensureCapacity(minimumCapacity: Int): Unit = TODO()
    open fun containsKey(key: K): Boolean = TODO()
    open fun containsValue(value: V): Boolean = TODO()
    open fun get(key: K): V? = TODO()
    open fun getOrDefault(key: Any?, defaultValue: V): V = TODO()
    open fun keyAt(index: Int): K = TODO()
    open fun valueAt(index: Int): V = TODO()
    open fun setValueAt(index: Int, value: V): V = TODO()
    open fun isEmpty(): Boolean = TODO()
    open fun put(key: K, value: V): V? = TODO()
    open fun putAll(array: SimpleArrayMap<out K, out V>): Unit = TODO()
    open fun putIfAbsent(key: K, value: V): V? = TODO()
    open fun remove(key: K): V? = TODO()
    open fun remove(key: K, value: V): Boolean = TODO()
    open fun removeAt(index: Int): V = TODO()
    open fun replace(key: K, value: V): V? = TODO()
    open fun indexOfKey(key: K): Int = TODO()
    open fun size(): Int = TODO()
}

// RuntimeSettings hands an ArrayMap to Collections.unmodifiableMap and Autofill
// stores one in a Map<String, String> field, so this has to BE a Map. Delegation
// is used rather than subclassing SimpleArrayMap: Kotlin cannot carry both a
// size() function and MutableMap's size property on one class.
open class ArrayMap<K, V>(private val backing: MutableMap<K, V> = LinkedHashMap()) :
    MutableMap<K, V> by backing {
    constructor(capacity: Int) : this(LinkedHashMap())

    open fun keyAt(index: Int): K = TODO()
    open fun valueAt(index: Int): V = TODO()
    open fun removeAt(index: Int): V = TODO()
    open fun ensureCapacity(minimumCapacity: Int): Unit = TODO()
}

// GeckoProcessManager: add()/remove() plus iteration.
open class ArraySet<E>(private val backing: MutableSet<E> = LinkedHashSet()) :
    MutableSet<E> by backing {
    constructor(capacity: Int) : this(LinkedHashSet())

    open fun valueAt(index: Int): E = TODO()
    open fun removeAt(index: Int): E = TODO()
    open fun indexOf(key: Any?): Int = TODO()
    open fun ensureCapacity(minimumCapacity: Int): Unit = TODO()
}
'''

# ---- androidx.core ---------------------------------------------------------
FILES["androidx.core.graphics.kt"] = r'''
package androidx.core.graphics

// GeckoDisplay reads .bottom off getInsets()/getSystemWindowInsets().
// Shape recovered from ATL's android.graphics.Insets / android.view.WindowInsets.
open class Insets {
    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0

    companion object {
        val NONE: Insets get() = TODO()
        fun of(left: Int, top: Int, right: Int, bottom: Int): Insets = TODO()
    }
}
'''

FILES["androidx.core.view.kt"] = r'''
package androidx.core.view

import androidx.core.graphics.Insets

// GeckoView.Display implements this AND passes lambdas to ViewCompat, so it must
// be a fun interface.
fun interface OnApplyWindowInsetsListener {
    fun onApplyWindowInsets(view: android.view.View, insets: WindowInsetsCompat): WindowInsetsCompat
}

open class WindowInsetsCompat {
    open fun toWindowInsets(): android.view.WindowInsets? = TODO()
    open fun getInsets(typeMask: Int): Insets = TODO()
    open fun getInsetsIgnoringVisibility(typeMask: Int): Insets = TODO()
    open fun isVisible(typeMask: Int): Boolean = TODO()
    open fun getSystemWindowInsets(): Insets = TODO()
    open fun isConsumed(): Boolean = TODO()

    object Type {
        fun ime(): Int = TODO()
        fun navigationBars(): Int = TODO()
        fun statusBars(): Int = TODO()
        fun systemBars(): Int = TODO()
        fun displayCutout(): Int = TODO()
    }

    companion object {
        val CONSUMED: WindowInsetsCompat get() = TODO()
        fun toWindowInsetsCompat(insets: android.view.WindowInsets?): WindowInsetsCompat = TODO()
        fun toWindowInsetsCompat(insets: android.view.WindowInsets?, view: android.view.View?):
            WindowInsetsCompat = TODO()
    }
}

object ViewCompat {
    fun isAttachedToWindow(view: Any?): Boolean = TODO()
    fun setOnApplyWindowInsetsListener(view: Any?, listener: OnApplyWindowInsetsListener?): Unit = TODO()
    fun requestApplyInsets(view: Any?): Unit = TODO()
}
'''

FILES["androidx.core.os.kt"] = r'''
package androidx.core.os

// WebNotification: readParcelableArrayTyped(in, loader, WebNotificationAction.class).
// The ClassLoader/Class arguments are typed Any? because java.lang.ClassLoader and
// java.lang.Class have no Kotlin/Native equivalent.
object ParcelCompat {
    fun readParcelableArrayTyped(
        parcel: android.os.Parcel,
        loader: Any?,
        clazz: Any?,
    ): Array<android.os.Parcelable>? = TODO()

    fun readParcelable(parcel: android.os.Parcel, loader: Any?, clazz: Any?): Any? = TODO()
    fun readBoolean(parcel: android.os.Parcel): Boolean = TODO()
}
'''

FILES["androidx.core.content.kt"] = r'''
package androidx.core.content

object ContextCompat {
    fun startForegroundService(context: Any?, intent: Any?): Unit = TODO()
    fun getSystemService(context: Any?, serviceClass: Any?): Any? = TODO()
    fun checkSelfPermission(context: Any?, permission: String): Int = TODO()
}
'''

FILES["androidx.core.content.res.kt"] = r'''
package androidx.core.content.res

// GeckoAppShell assigns the result to android.graphics.drawable.Drawable.
object ResourcesCompat {
    fun getDrawable(res: Any?, id: Int, theme: Any?): android.graphics.drawable.Drawable? = TODO()
    fun getColor(res: Any?, id: Int, theme: Any?): Int = TODO()
}
'''

# ---- androidx.lifecycle ----------------------------------------------------
FILES["androidx.lifecycle.kt"] = r'''
package androidx.lifecycle

// GeckoRuntime.LifecycleListener implements LifecycleObserver and annotates its
// methods with @OnLifecycleEvent(Lifecycle.Event.ON_*).
interface LifecycleObserver

interface LifecycleOwner {
    fun getLifecycle(): Lifecycle
}

open class Lifecycle {
    open fun addObserver(observer: LifecycleObserver): Unit = TODO()
    open fun removeObserver(observer: LifecycleObserver): Unit = TODO()
    open fun getCurrentState(): State = TODO()

    enum class Event {
        ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY
    }

    enum class State {
        DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED
    }
}

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
annotation class OnLifecycleEvent(val value: Lifecycle.Event)

open class ProcessLifecycleOwner : LifecycleOwner {
    override fun getLifecycle(): Lifecycle = TODO()

    companion object {
        fun get(): LifecycleOwner = TODO()
    }
}
'''

# ---- androidx.media3.common ------------------------------------------------
FILES["androidx.media3.common.kt"] = r'''
package androidx.media3.common

object C {
    const val TRACK_TYPE_UNKNOWN = -1
    const val TRACK_TYPE_DEFAULT = 0
    const val TRACK_TYPE_AUDIO = 1
    const val TRACK_TYPE_VIDEO = 2
    const val TRACK_TYPE_TEXT = 3
    const val TRACK_TYPE_IMAGE = 4
    const val TRACK_TYPE_METADATA = 5
    const val TRACK_TYPE_CAMERA_MOTION = 6
    const val TRACK_TYPE_NONE = -2

    const val FORMAT_UNSUPPORTED_TYPE = 0
    const val FORMAT_UNSUPPORTED_SUBTYPE = 1
    const val FORMAT_UNSUPPORTED_DRM = 2
    const val FORMAT_EXCEEDS_CAPABILITIES = 3
    const val FORMAT_HANDLED = 4

    const val RESULT_END_OF_INPUT = -1
    const val RESULT_MAX_LENGTH_EXCEEDED = -2
    const val RESULT_NOTHING_READ = -3
    const val RESULT_BUFFER_READ = -4
    const val RESULT_FORMAT_READ = -5

    const val LENGTH_UNSET = -1L
    const val TIME_UNSET = Long.MIN_VALUE + 1
    const val INDEX_UNSET = -1
    const val POSITION_UNSET = -1
    const val DEFAULT_BUFFER_SEGMENT_SIZE = 64 * 1024

    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE)
    annotation class TrackType

    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE)
    annotation class FormatSupport
}

object MimeTypes {
    const val VIDEO_H264 = "video/avc"
    const val VIDEO_H265 = "video/hevc"
    const val VIDEO_VP8 = "video/x-vnd.on2.vp8"
    const val VIDEO_VP9 = "video/x-vnd.on2.vp9"
    const val VIDEO_AV1 = "video/av01"
    const val AUDIO_RAW = "audio/raw"
    const val AUDIO_AAC = "audio/mp4a-latm"
    const val AUDIO_MPEG = "audio/mpeg"

    fun isAudio(mimeType: String?): Boolean = TODO()
    fun isVideo(mimeType: String?): Boolean = TODO()
    fun isText(mimeType: String?): Boolean = TODO()
    fun getTrackType(mimeType: String?): Int = TODO()
    fun getMediaMimeType(codec: String?): String? = TODO()
}

// Fields, not getters: GeckoHls*Renderer reads format.width / .sampleMimeType /
// .initializationData directly.
open class Format {
    var id: String? = null
    var label: String? = null
    var sampleMimeType: String? = null
    var containerMimeType: String? = null
    var codecs: String? = null
    var bitrate: Int = NO_VALUE
    var width: Int = NO_VALUE
    var height: Int = NO_VALUE
    var frameRate: Float = NO_VALUE.toFloat()
    var rotationDegrees: Int = NO_VALUE
    var stereoMode: Int = NO_VALUE
    var maxInputSize: Int = NO_VALUE
    var sampleRate: Int = NO_VALUE
    var channelCount: Int = NO_VALUE
    var pcmEncoding: Int = NO_VALUE
    var initializationData: List<ByteArray> = emptyList()
    // Only ever compared by identity in GeckoHlsRendererBase.handleDrmInitChanged.
    var drmInitData: Any? = null

    open fun buildUpon(): Builder = TODO()

    open class Builder {
        open fun build(): Format = TODO()
    }

    companion object {
        const val NO_VALUE = -1
    }
}

open class PlaybackParameters {
    var speed: Float = 1.0f
    var pitch: Float = 1.0f

    companion object {
        val DEFAULT: PlaybackParameters get() = TODO()
    }
}

// Thrown / caught, so it has to be a Throwable.
open class PlaybackException(
    message: String? = null,
    cause: Throwable? = null,
    val errorCode: Int = 0,
) : Exception(message, cause) {
    companion object {
        const val ERROR_CODE_UNSPECIFIED = 1000
        const val ERROR_CODE_IO_UNSPECIFIED = 2000
        const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2001
        const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2002
        const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2004
        const val ERROR_CODE_IO_FILE_NOT_FOUND = 2005
        const val ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE = 2008
        const val ERROR_CODE_DECODER_INIT_FAILED = 4001
        const val ERROR_CODE_DECODING_FAILED = 4003
    }
}

open class MediaItem {
    companion object {
        fun fromUri(uri: android.net.Uri): MediaItem = TODO()
        fun fromUri(uri: String): MediaItem = TODO()
    }
}

open class TrackGroup {
    var length: Int = 0
    var id: String = ""
    var type: Int = C.TRACK_TYPE_UNKNOWN

    open fun getFormat(index: Int): Format = TODO()
    open fun indexOf(format: Format): Int = TODO()
}

open class Tracks {
    open fun isEmpty(): Boolean = TODO()
    open fun getGroups(): List<Group> = TODO()
    open fun containsType(trackType: Int): Boolean = TODO()
    open fun isTypeSelected(trackType: Int): Boolean = TODO()
    open fun isTypeSupported(trackType: Int): Boolean = TODO()

    open class Group {
        var length: Int = 0

        open fun getMediaTrackGroup(): TrackGroup = TODO()
        open fun getType(): Int = TODO()
        open fun isSelected(): Boolean = TODO()
        open fun isSupported(): Boolean = TODO()
        open fun isTrackSelected(trackIndex: Int): Boolean = TODO()
        open fun isTrackSupported(trackIndex: Int): Boolean = TODO()
        open fun getTrackSupport(trackIndex: Int): Int = TODO()
        open fun getTrackFormat(trackIndex: Int): Format = TODO()
    }

    companion object {
        val EMPTY: Tracks get() = TODO()
    }
}

open class Timeline {
    open fun isEmpty(): Boolean = TODO()
    open fun getWindowCount(): Int = TODO()
    open fun getPeriodCount(): Int = TODO()
    open fun getWindow(windowIndex: Int, window: Window): Window = TODO()
    open fun getPeriod(periodIndex: Int, period: Period): Period = TODO()

    open class Window {
        var isDynamic: Boolean = false
        var isSeekable: Boolean = false
        var isLive: Boolean = false
        var isPlaceholder: Boolean = false
        var durationUs: Long = C.TIME_UNSET
        var defaultPositionUs: Long = 0L
        var positionInFirstPeriodUs: Long = 0L

        open fun getDurationUs(): Long = TODO()
        open fun getDurationMs(): Long = TODO()
    }

    open class Period {
        var durationUs: Long = C.TIME_UNSET

        open fun getDurationUs(): Long = TODO()
        open fun getDurationMs(): Long = TODO()
    }
}

// GeckoHlsPlayer implements ExoPlayer.Listener, which extends Player.Listener.
// Every callback needs a default body so the transpiled overrides are legal.
interface Player {
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun getPlaybackState(): Int
    fun getPlayWhenReady(): Boolean
    fun setPlayWhenReady(playWhenReady: Boolean)
    fun getDuration(): Long
    fun getCurrentPosition(): Long
    fun getBufferedPosition(): Long
    fun seekTo(positionMs: Long)
    fun prepare()
    fun stop()
    fun release()

    open class PositionInfo {
        var positionMs: Long = 0L
        var contentPositionMs: Long = 0L
        var mediaItemIndex: Int = 0
        var periodIndex: Int = 0
    }

    interface Listener {
        fun onIsLoadingChanged(isLoading: Boolean) {}
        fun onPlaybackStateChanged(state: Int) {}
        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
        fun onPositionDiscontinuity(
            oldPosition: PositionInfo,
            newPosition: PositionInfo,
            reason: Int,
        ) {}
        fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onTracksChanged(tracks: Tracks) {}
        fun onTimelineChanged(timeline: Timeline, reason: Int) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
    }

    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE)
    annotation class State

    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE)
    annotation class DiscontinuityReason

    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE)
    annotation class TimelineChangeReason

    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4

        const val DISCONTINUITY_REASON_AUTO_TRANSITION = 0
        const val DISCONTINUITY_REASON_SEEK = 1
        const val TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED = 0
        const val TIMELINE_CHANGE_REASON_SOURCE_UPDATE = 1
    }
}
'''

FILES["androidx.media3.common.util.kt"] = r'''
package androidx.media3.common.util

// @UnstableApi is a marker only. The codemod should drop it along with the
// @OptIn(markerClass = UnstableApi.class) that always accompanies it.
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD, AnnotationTarget.FILE,
        AnnotationTarget.ANNOTATION_CLASS)
annotation class UnstableApi

object Util {
    fun inferContentType(uri: Any?): Int = TODO()
    fun inferContentTypeForUriAndMimeType(uri: Any?, mimeType: String?): Int = TODO()
    fun getUserAgent(context: Any?, applicationName: String): String = TODO()
    fun usToMs(timeUs: Long): Long = TODO()
    fun msToUs(timeMs: Long): Long = TODO()
}
'''

# ---- androidx.media3.decoder ----------------------------------------------
FILES["androidx.media3.decoder.kt"] = r'''
package androidx.media3.decoder

// data is nullable: GeckoHlsVideoRenderer null-checks it and GeckoHlsRendererBase
// assigns a nullable ByteBuffer into it. Call sites that dereference it need !!.
open class DecoderInputBuffer(bufferReplacementMode: Int = BUFFER_REPLACEMENT_MODE_DISABLED) {
    var data: java.nio.ByteBuffer? = null
    var timeUs: Long = 0L
    var cryptoInfo: CryptoInfo = CryptoInfo()

    open fun clear(): Unit = TODO()
    open fun flip(): Unit = TODO()
    open fun ensureSpaceForWrite(length: Int): Unit = TODO()
    open fun isEndOfStream(): Boolean = TODO()
    open fun isKeyFrame(): Boolean = TODO()
    open fun isEncrypted(): Boolean = TODO()
    open fun isDecodeOnly(): Boolean = TODO()
    open fun setFlags(flags: Int): Unit = TODO()

    companion object {
        const val BUFFER_REPLACEMENT_MODE_DISABLED = 0
        const val BUFFER_REPLACEMENT_MODE_NORMAL = 1
        const val BUFFER_REPLACEMENT_MODE_DIRECT = 2

        fun newNoDataInstance(): DecoderInputBuffer = TODO()
    }
}

// GeckoHls*Renderer feeds getFrameworkCryptoInfo() straight into
// android.media.MediaCodec.CryptoInfo.
open class CryptoInfo {
    var numSubSamples: Int = 0
    var iv: ByteArray? = null
    var key: ByteArray? = null

    open fun getFrameworkCryptoInfo(): android.media.MediaCodec.CryptoInfo = TODO()
}
'''

# ---- androidx.media3.datasource -------------------------------------------
FILES["androidx.media3.datasource.kt"] = r'''
package androidx.media3.datasource

// HttpChannelDataSource references HttpDataSourceException, RequestProperties,
// InvalidResponseCodeException and InvalidContentTypeException UNQUALIFIED,
// because Java inherits an implemented interface's nested types into scope and
// Kotlin does not. They are therefore declared top-level here; the codemod must
// add the matching imports to the transpiled HttpChannelDataSource.kt.

interface TransferListener {
    fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytes: Int) {}
    fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
}

interface DataSource {
    fun addTransferListener(transferListener: TransferListener)
    fun open(dataSpec: DataSpec): Long
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun getUri(): android.net.Uri?
    fun close()

    interface Factory {
        fun createDataSource(): DataSource
    }
}

open class DataSpec {
    var uri: android.net.Uri = TODO()
    var position: Long = 0L
    var length: Long = 0L
    var httpRequestHeaders: Map<String, String> = emptyMap()
    var flags: Int = 0
    var httpMethod: Int = 0

    open fun isFlagSet(flag: Int): Boolean = TODO()

    open class Builder {
        open fun build(): DataSpec = TODO()
    }

    companion object {
        const val FLAG_ALLOW_GZIP = 1 shl 0
        const val FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN = 1 shl 1
        const val FLAG_ALLOW_CACHE_FRAGMENTATION = 1 shl 2
    }
}

open class BaseDataSource(isNetwork: Boolean) : DataSource {
    override fun addTransferListener(transferListener: TransferListener): Unit = TODO()
    override fun open(dataSpec: DataSpec): Long = TODO()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = TODO()
    override fun getUri(): android.net.Uri? = TODO()
    override fun close(): Unit = TODO()

    protected open fun transferInitializing(dataSpec: DataSpec): Unit = TODO()
    protected open fun transferStarted(dataSpec: DataSpec): Unit = TODO()
    protected open fun bytesTransferred(bytesTransferred: Int): Unit = TODO()
    protected open fun transferEnded(): Unit = TODO()
}

// HttpChannelDataSource extends BaseDataSource AND implements HttpDataSource, so
// the DataSource members are re-declared here with identical signatures.
interface HttpDataSource : DataSource {
    fun setRequestProperty(name: String, value: String)
    fun clearRequestProperty(name: String)
    fun clearAllRequestProperties()
    fun getResponseCode(): Int
    fun getResponseHeaders(): Map<String, List<String>>

    interface Factory : DataSource.Factory {
        override fun createDataSource(): HttpDataSource
        fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): Factory
    }
}

open class RequestProperties {
    open fun set(name: String, value: String): Unit = TODO()
    open fun setAll(properties: Map<String, String>): Unit = TODO()
    open fun clearAndSet(properties: Map<String, String>): Unit = TODO()
    open fun remove(name: String): Unit = TODO()
    open fun clear(): Unit = TODO()
    open fun getSnapshot(): Map<String, String> = TODO()
}

// Extends kotlin.Exception rather than java.io.IOException: there is no
// java.io on Kotlin/Native and nothing catches it as an IOException.
open class HttpDataSourceException : Exception {
    constructor(dataSpec: DataSpec?, errorCode: Int, type: Int) : super()
    constructor(message: String?, dataSpec: DataSpec?, errorCode: Int, type: Int) : super(message)
    constructor(
        message: String?,
        cause: Throwable?,
        dataSpec: DataSpec?,
        errorCode: Int,
        type: Int,
    ) : super(message, cause)

    companion object {
        const val TYPE_OPEN = 1
        const val TYPE_READ = 2
        const val TYPE_CLOSE = 3

        fun createForIOException(
            cause: Any?,
            dataSpec: DataSpec?,
            type: Int,
        ): HttpDataSourceException = TODO()
    }
}

open class InvalidResponseCodeException(
    val responseCode: Int,
    val responseMessage: String?,
    cause: HttpDataSourceException?,
    val headerFields: Map<String, List<String>>,
    dataSpec: DataSpec?,
    val responseBody: ByteArray,
) : HttpDataSourceException(null, cause, dataSpec, 0, TYPE_OPEN)

open class InvalidContentTypeException(
    val contentType: String,
    dataSpec: DataSpec?,
) : HttpDataSourceException(null, dataSpec, 0, TYPE_OPEN)

// Statically imported as `buildRangeRequestHeader`; an `object` keeps
// `import androidx.media3.datasource.HttpUtil.buildRangeRequestHeader` legal.
object HttpUtil {
    fun buildRangeRequestHeader(position: Long, length: Long): String? = TODO()
    fun getDocumentSize(contentRangeHeader: String?): Long = TODO()
    fun getContentLength(contentLengthHeader: String?, contentRangeHeader: String?): Long = TODO()
}

open class DefaultDataSource : DataSource {
    override fun addTransferListener(transferListener: TransferListener): Unit = TODO()
    override fun open(dataSpec: DataSpec): Long = TODO()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = TODO()
    override fun getUri(): android.net.Uri? = TODO()
    override fun close(): Unit = TODO()

    open class Factory(context: Any?, baseDataSourceFactory: Any? = null) : DataSource.Factory {
        override fun createDataSource(): DataSource = TODO()
        open fun setTransferListener(transferListener: TransferListener?): Factory = TODO()
    }
}
'''

# ---- androidx.media3.exoplayer --------------------------------------------
FILES["androidx.media3.exoplayer.kt"] = r'''
package androidx.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.source.MediaSource

interface Renderer

// RendererCapabilities is only ever touched statically, so an object is enough.
object RendererCapabilities {
    const val ADAPTIVE_NOT_SUPPORTED = 0
    const val ADAPTIVE_NOT_SEAMLESS = 1 shl 3
    const val ADAPTIVE_SEAMLESS = 1 shl 4
    const val TUNNELING_NOT_SUPPORTED = 0
    const val TUNNELING_SUPPORTED = 1 shl 5

    fun create(formatSupport: Int): Int = TODO()
    fun create(formatSupport: Int, adaptiveSupport: Int, tunnelingSupport: Int): Int = TODO()

    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE)
    annotation class AdaptiveSupport
}

open class FormatHolder {
    // GeckoHls*Renderer passes this straight to onInputFormatChanged(Format).
    lateinit var format: Format
}

// GeckoHlsVideoRenderer says bare ADAPTIVE_NOT_SEAMLESS, inheriting it from
// RendererCapabilities the Java way. Kotlin does not inherit interface constants,
// so BaseRenderer carries them as members.
// Deliberately does NOT declare onStreamChanged() or getFormat(): GeckoView
// declares both without @Override, and a matching member here would become an
// accidental-override error.
open class BaseRenderer(trackType: Int) : Renderer {
    protected val ADAPTIVE_NOT_SUPPORTED: Int = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    protected val ADAPTIVE_NOT_SEAMLESS: Int = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
    protected val ADAPTIVE_SEAMLESS: Int = RendererCapabilities.ADAPTIVE_SEAMLESS
    protected val TUNNELING_NOT_SUPPORTED: Int = RendererCapabilities.TUNNELING_NOT_SUPPORTED

    open fun getName(): String = TODO()
    open fun getIndex(): Int = TODO()
    open fun getTrackType(): Int = TODO()
    open fun getState(): Int = TODO()
    open fun isReady(): Boolean = TODO()
    open fun isEnded(): Boolean = TODO()
    open fun supportsFormat(format: Format): Int = TODO()
    open fun supportsMixedMimeTypeAdaptation(): Int = TODO()
    open fun render(positionUs: Long, elapsedRealtimeUs: Long): Unit = TODO()

    protected open fun readSource(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int,
    ): Int = TODO()

    protected open fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean): Unit = TODO()
    protected open fun onDisabled(): Unit = TODO()
    protected open fun onStarted(): Unit = TODO()
    protected open fun onStopped(): Unit = TODO()
    protected open fun onReset(): Unit = TODO()
    protected open fun onPositionReset(
        positionUs: Long,
        joining: Boolean,
        sampleStreamIsResetToKeyFrame: Boolean,
    ): Unit = TODO()
}

open class ExoPlaybackException : PlaybackException {
    constructor() : super()
    constructor(message: String?, cause: Throwable?, errorCode: Int) : super(message, cause, errorCode)

    companion object {
        const val TYPE_SOURCE = 0
        const val TYPE_RENDERER = 1
        const val TYPE_UNEXPECTED = 2
        const val ERROR_CODE_DECODER_INIT_FAILED = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED

        // Matches GeckoHls{Video,Audio}Renderer's 8-argument call exactly.
        fun createForRenderer(
            cause: Throwable,
            rendererName: String,
            rendererIndex: Int,
            rendererFormat: Format?,
            rendererFormatSupport: Int,
            mediaPeriodId: Any?,
            isRecoverable: Boolean,
            errorCode: Int,
        ): ExoPlaybackException = TODO()

        fun createForSource(cause: Any?, errorCode: Int): ExoPlaybackException = TODO()
    }
}

open class DefaultLoadControl {
    open class Builder {
        open fun setAllocator(allocator: Any?): Builder = TODO()
        open fun setBufferDurationsMs(
            minBufferMs: Int,
            maxBufferMs: Int,
            bufferForPlaybackMs: Int,
            bufferForPlaybackAfterRebufferMs: Int,
        ): Builder = TODO()
        open fun setPrioritizeTimeOverSizeThresholds(value: Boolean): Builder = TODO()
        open fun build(): DefaultLoadControl = TODO()
    }

    companion object {
        const val DEFAULT_MIN_BUFFER_MS = 50000
        const val DEFAULT_MAX_BUFFER_MS = 50000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 2500
        const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5000
    }
}

// Passed as a 5-argument lambda in createExoPlayer(), so it must be a fun
// interface. The event-listener parameters are Any? -- GeckoView ignores them.
fun interface RenderersFactory {
    fun createRenderers(
        eventHandler: Any?,
        videoRendererEventListener: Any?,
        audioRendererEventListener: Any?,
        textRendererOutput: Any?,
        metadataRendererOutput: Any?,
    ): Array<out Renderer>
}

interface ExoPlayer : Player {
    fun setMediaSource(mediaSource: MediaSource)
    fun setRenderersFactory(renderersFactory: RenderersFactory)

    // GeckoHlsPlayer implements ExoPlayer.Listener.
    interface Listener : Player.Listener

    class Builder(context: Any?) {
        fun setRenderersFactory(renderersFactory: RenderersFactory): Builder = TODO()
        fun setTrackSelector(trackSelector: Any?): Builder = TODO()
        fun setLoadControl(loadControl: Any?): Builder = TODO()
        fun setBandwidthMeter(bandwidthMeter: Any?): Builder = TODO()
        fun setLooper(looper: Any?): Builder = TODO()
        fun build(): ExoPlayer = TODO()
    }
}
'''

FILES["androidx.media3.exoplayer.source.kt"] = r'''
package androidx.media3.exoplayer.source

import androidx.media3.common.TrackGroup

interface MediaSource

interface SampleStream {
    companion object {
        const val FLAG_PEEK = 1 shl 0
        const val FLAG_REQUIRE_FORMAT = 1 shl 1
        const val FLAG_OMIT_SAMPLE_DATA = 1 shl 2
    }
}

open class TrackGroupArray {
    var length: Int = 0

    open fun get(index: Int): TrackGroup = TODO()
    open fun indexOf(group: TrackGroup): Int = TODO()
    open fun isEmpty(): Boolean = TODO()

    companion object {
        val EMPTY: TrackGroupArray get() = TODO()
    }
}
'''

FILES["androidx.media3.exoplayer.hls.kt"] = r'''
package androidx.media3.exoplayer.hls

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource

open class HlsMediaSource : MediaSource {
    open class Factory(dataSourceFactory: Any?) {
        open fun createMediaSource(mediaItem: MediaItem): MediaSource = TODO()
        open fun setAllowChunklessPreparation(value: Boolean): Factory = TODO()
    }
}
'''

FILES["androidx.media3.exoplayer.mediacodec.kt"] = r'''
package androidx.media3.exoplayer.mediacodec

import androidx.media3.common.Format

open class MediaCodecInfo {
    var name: String = ""
    var mimeType: String? = null
    var adaptive: Boolean = false
    var tunneling: Boolean = false
    var secure: Boolean = false
    var hardwareAccelerated: Boolean = false

    open fun isFormatSupported(context: Any?, format: Format): Boolean = TODO()
    open fun isAudioSampleRateSupportedV21(sampleRate: Int): Boolean = TODO()
    open fun isAudioChannelCountSupportedV21(channelCount: Int): Boolean = TODO()
}

interface MediaCodecSelector {
    fun getDecoderInfos(
        mimeType: String?,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo>

    companion object {
        val DEFAULT: MediaCodecSelector get() = TODO()
    }
}

object MediaCodecUtil {
    fun getDecoderInfos(
        mimeType: String?,
        secure: Boolean,
        tunneling: Boolean,
    ): List<MediaCodecInfo> = TODO()

    // Caught by GeckoHls{Audio,Video}Renderer.supportsFormat.
    open class DecoderQueryException(message: String? = null, cause: Throwable? = null) :
        Exception(message, cause)
}
'''

FILES["androidx.media3.exoplayer.trackselection.kt"] = r'''
package androidx.media3.exoplayer.trackselection

import androidx.media3.exoplayer.source.TrackGroupArray

open class AdaptiveTrackSelection {
    open class Factory {
        open fun createTrackSelection(): Any? = TODO()
    }
}

open class MappingTrackSelector {
    open fun getCurrentMappedTrackInfo(): MappedTrackInfo? = TODO()

    open class MappedTrackInfo {
        open fun getRendererCount(): Int = TODO()
        open fun getRendererType(rendererIndex: Int): Int = TODO()
        open fun getTrackGroups(rendererIndex: Int): TrackGroupArray = TODO()
        open fun getUnmappedTrackGroups(): TrackGroupArray = TODO()
        open fun getAdaptiveSupport(
            rendererIndex: Int,
            groupIndex: Int,
            includeCapabilitiesExceededTracks: Boolean,
        ): Int = TODO()
        open fun getTrackSupport(rendererIndex: Int, groupIndex: Int, trackIndex: Int): Int = TODO()
    }
}

open class DefaultTrackSelector(context: Any?, trackSelectionFactory: Any? = null) :
    MappingTrackSelector()
'''

FILES["androidx.media3.exoplayer.upstream.kt"] = r'''
package androidx.media3.exoplayer.upstream

open class DefaultAllocator(trimOnReset: Boolean, individualAllocationSize: Int)

open class DefaultBandwidthMeter {
    open class Builder(context: Any?) {
        open fun setInitialBitrateEstimate(estimate: Long): Builder = TODO()
        open fun build(): DefaultBandwidthMeter = TODO()
    }
}
'''

# A throwaway file, written outside the stub tree, that satisfies the android.*
# and java.nio references so this slice can be compiled in isolation.
SOLO_DEPS = r'''
// NOT part of the stub tree. Written to /tmp so this slice can be klib-compiled
// on its own; the real declarations come from the other stub groups.
package android.view
open class View { open fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets? = TODO() }
open class WindowInsets
'''

SOLO_DEPS_EXTRA = {
    "solo-android-os.kt": (
        "package android.os\n"
        "interface Parcelable\n"
        "open class Parcel\n"
    ),
    "solo-android-gfx.kt": (
        "package android.graphics.drawable\n"
        "open class Drawable\n"
    ),
    "solo-android-net.kt": (
        "package android.net\n"
        "open class Uri { companion object { fun parse(s: String): Uri = TODO() } }\n"
    ),
    "solo-android-media.kt": (
        "package android.media\n"
        "open class MediaCodec { open class CryptoInfo }\n"
    ),
    "solo-java-nio.kt": (
        "package java.nio\n"
        "open class ByteBuffer\n"
    ),
}


def emit(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    written = []
    for name, body in FILES.items():
        path = os.path.join(out_dir, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(HEADER)
            f.write(body.lstrip("\n"))
        written.append(path)
    return written


def emit_solo_deps(tmp_dir):
    os.makedirs(tmp_dir, exist_ok=True)
    paths = []
    p = os.path.join(tmp_dir, "solo-android-view.kt")
    with open(p, "w", encoding="utf-8") as f:
        f.write(SOLO_DEPS.lstrip("\n"))
    paths.append(p)
    for name, body in SOLO_DEPS_EXTRA.items():
        p = os.path.join(tmp_dir, name)
        with open(p, "w", encoding="utf-8") as f:
            f.write(body)
        paths.append(p)
    return paths


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--java", default=JAVA_DEFAULT)
    ap.add_argument("--out", default=OUT_DEFAULT)
    ap.add_argument("--solo-deps", default="/tmp/androidx-solo-deps")
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()

    hits, files = scrape(args.java)
    observed = {normalise(t) for t in hits}
    missing = sorted(t for t in observed if t not in COVERED and t not in COMMENT_ONLY)

    if args.report:
        for ty, n in hits.most_common():
            print(f"{n:5d} sites  {files[ty]:3d} files  {ty}")

    written = emit(args.out)
    deps = emit_solo_deps(args.solo_deps)

    print(f"scraped {len(hits)} androidx types over {args.java}")
    print(f"emitted {len(written)} stub files into {args.out}")
    print(f"solo-compile deps in {args.solo_deps} ({len(deps)} files, NOT part of the build)")

    if missing:
        print("\nUNCOVERED androidx types (extend this generator):", file=sys.stderr)
        for t in missing:
            print(f"  {t}  ({hits[t]} sites)", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
