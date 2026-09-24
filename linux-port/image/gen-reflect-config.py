#!/usr/bin/env python3
"""Register the classes Android and the JNI libraries reach by *name*.

    gen-reflect-config.py <out-reflect.json> <out-jni.json> <natives|-> <jar>...

`natives` is a comma-separated list of .so files, or `-`.

Whole families of class are invisible to the tracing agent and to the
closed-world analysis alike, because nothing in the bytecode names them: every
**View** the layout inflater builds from a layout XML, every **Fragment**
`FragmentFactory.loadFragmentClass` builds, every **ViewModel**
`ViewModelProvider` builds from a class literal, every **Preference** the
preference inflater builds, and the component types the manifest names. A miss
is a `ClassNotFoundException` at the moment a user opens that screen, which is
the worst place to find one.

So the class hierarchy is read out of the jars and every descendant of `ROOTS`
is registered with all its declared constructors -- `allDeclaredConstructors`
rather than a guessed `(Context, AttributeSet)`, because the inflater picks
between three constructor shapes and a guess registers the wrong one silently.
The roots count as descendants of themselves: a layout may carry a literal
`<View/>` for a spacer, so the inflater builds `View` itself.

The second family is **span array types**: `Spannable.getSpans()` does
`Array.newInstance(type, n)`, and an array class instantiated reflectively has
to be registered for unsafe allocation.

The third is the classes with **@Tunable fields**: TunableInjector writes their
defaults and saved values through `getDeclaredFields()`, which in the image
returns nothing for an unregistered class -- no error, the fields just stay 0.
A trace only registers the tunable classes it happened to instantiate.

The fourth is JNI only: **the class names the native libraries carry**.
`FindClass` takes an internal name out of the binary's string table, so the
names are all there to be read -- from atlas's own
`libtranslation_layer_main.so`, which drives the whole framework from C, and
from the app's five libraries, whose callbacks into
`com.particlesdevs.photoncamera.*` are `GetMethodID` lookups. A JNI miss is a
`NoSuchMethodError` rather than a caught `NoSuchMethodException`, so it is
fatal where a reflective miss is not. Names the class path does not have are
dropped; a JDK name is registered with its constructors only, because
`allDeclaredMethods` on `java.lang.Class` makes `getClassLoader` reachable and
the class loader drags a `JarFile` into the image heap, which the builder
refuses outright.

The families and the reasoning are the ones firefox-atl's image lane
established (`~/UT/firefox-atl/jvm-run/image/NOTES.md`, walls 7 and 10-11);
what is PhotonCamera's here is the root set and the native prefix list.

Generated at build time rather than committed: a dependency bump moves the set,
and a stale list is wrong exactly where it matters.
"""
import json
import re
import struct
import sys
import zipfile

# Everything Android builds from a name rather than from a `new`.
ROOTS = (
    "android/view/View",                # the layout inflater, from a layout XML
    "androidx/fragment/app/Fragment",   # FragmentFactory
    "androidx/lifecycle/ViewModel",     # ViewModelProvider, from a class literal
    "androidx/preference/Preference",   # the preference inflater, from an XML
    "android/app/Activity",             # the manifest
    "android/app/Service",
    "android/app/Application",
    "android/content/BroadcastReceiver",
    "android/content/ContentProvider",
    "androidx/room/RoomDatabase",       # Room, from "<the DB class>_Impl"
    # CoordinatorLayout.parseBehavior: app:layout_behavior is a class NAME in
    # the layout, built with Class.forName + getConstructor(Context, AttributeSet).
    "androidx/coordinatorlayout/widget/CoordinatorLayout$Behavior",
)

SPAN_ROOTS = ("android/text/style/CharacterStyle", "android/text/style/ParagraphStyle",
              "android/text/ParcelableSpan", "android/text/NoCopySpan",
              "android/text/style/UpdateAppearance")

# androidx navigation's generated argument classes, reached through NavArgsLazy
# by their *methods*: fromBundle is static, so constructors alone are not enough.
NAVARGS_ROOTS = ("androidx/navigation/NavArgs",)

# A class that annotates a field with @Tunable has this descriptor in its
# constant pool.
TUNABLE = b"Lcom/particlesdevs/photoncamera/settings/annotations/Tunable;"

# An internal class name in a binary's string table: FindClass's argument.
# com/particlesdevs is the app's own package, which its five JNI libraries call
# back into; the rest is what atlas's natives drive the framework through.
NATIVE_NAME = re.compile(rb"(?:java|javax|android|androidx|com/particlesdevs)"
                         rb"(?:/[A-Za-z_$][A-Za-z0-9_$]*)+")

# constant-pool tags whose entries are a fixed number of bytes after the tag
FIXED = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
         15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
WIDE = (5, 6)  # long and double take two constant-pool slots


def read_class(data):
    """(this_class, [superclass and interfaces]) or None."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return None
    count = struct.unpack_from(">H", data, 8)[0]
    utf8 = {}
    classref = {}
    i, pos = 1, 10
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, pos)[0]
            utf8[i] = data[pos + 2:pos + 2 + n].decode("utf-8", "replace")
            pos += 2 + n
        elif tag in FIXED:
            if tag == 7:
                classref[i] = struct.unpack_from(">H", data, pos)[0]
            pos += FIXED[tag]
        else:
            return None  # an unknown tag means the rest of the offsets are junk
        i += 2 if tag in WIDE else 1
    this_i, super_i = struct.unpack_from(">HH", data, pos + 2)
    this_name = utf8.get(classref.get(this_i))
    if not this_name:
        return None
    parents = []
    if super_i:
        sup = utf8.get(classref.get(super_i))
        if sup:
            parents.append(sup)
    n_ifaces = struct.unpack_from(">H", data, pos + 6)[0]
    for k in range(n_ifaces):
        iface = utf8.get(classref.get(struct.unpack_from(">H", data, pos + 8 + 2 * k)[0]))
        if iface:
            parents.append(iface)
    return this_name, parents


def native_classes(spec):
    """The internal class names FindClass could be called with."""
    names = set()
    if spec == "-":
        return names
    for path in spec.split(","):
        if not path:
            continue
        with open(path, "rb") as f:
            names |= {m.group().decode() for m in NATIVE_NAME.finditer(f.read())}
    return names


def main():
    out, out_jni, natives, jars = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
    parents = {}
    tunable = set()
    for jar in jars:
        try:
            zf = zipfile.ZipFile(jar)
        except (zipfile.BadZipFile, IsADirectoryError, FileNotFoundError):
            continue
        with zf:
            for name in zf.namelist():
                if not name.endswith(".class"):
                    continue
                data = zf.read(name)
                info = read_class(data)
                if info:
                    parents[info[0]] = info[1]
                    if TUNABLE in data:
                        tunable.add(info[0])

    def descends_from(name, roots, seen=None):
        """Does name reach any of roots through extends or implements?"""
        if name in roots:
            return True
        seen = seen if seen is not None else set()
        if name in seen:
            return False
        seen.add(name)
        return any(descends_from(p, roots, seen) for p in parents.get(name, ()))

    picked = sorted(c for c in parents if descends_from(c, ROOTS))
    entries = [{"name": c.replace("/", "."), "allDeclaredConstructors": True}
               for c in picked]

    spans = sorted(c for c in parents
                   if c.rsplit("/", 1)[-1].endswith("Span")
                   or descends_from(c, SPAN_ROOTS))
    entries += [{"name": c.replace("/", ".") + "[]", "unsafeAllocated": True}
                for c in spans]

    navargs = sorted(c for c in parents
                     if c not in NAVARGS_ROOTS and descends_from(c, NAVARGS_ROOTS))
    entries += [{"name": c.replace("/", "."), "allDeclaredMethods": True,
                 "allDeclaredConstructors": True}
                for c in navargs]

    tunables = sorted(tunable)
    entries += [{"name": c.replace("/", "."), "allDeclaredFields": True}
                for c in tunables]

    # A name the class path does not have would be registered as unresolvable;
    # java.* is not on the class path but is always there.
    named = {c for c in native_classes(natives)
             if c in parents or c.startswith(("java/", "javax/"))}
    jdk = {c for c in named if c.startswith(("java/", "javax/"))}
    app = named - jdk
    jni = [{"name": c.replace("/", "."), "allDeclaredMethods": True,
            "allDeclaredFields": True, "allDeclaredConstructors": True}
           for c in sorted(app)]
    # ThrowNew looks up (String) after FindClass, so the constructors come too;
    # they do not pull the class loader in the way the methods do.
    jni += [{"name": c.replace("/", "."), "allDeclaredConstructors": True}
            for c in sorted(jdk)]
    entries += jni
    for path, data in ((out, entries), (out_jni, jni)):
        with open(path, "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")
    print(f"reflection: {len(picked)} name-instantiated classes, "
          f"{len(spans)} span array types, {len(navargs)} navigation Args "
          f"classes, {len(tunables)} classes with @Tunable fields, {len(app)} classes and {len(jdk)} JDK names the natives "
          f"call FindClass with, out of {len(parents)} classes")


if __name__ == "__main__":
    main()
