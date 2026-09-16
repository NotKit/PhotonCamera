#!/usr/bin/env python3
"""j2k.py - a crude, re-runnable mechanical Java -> Kotlin/Native converter.

IntelliJ's J2K is not available headless, so this is ours.  It parses Java with
tree-sitter (never regex: GeckoSession.java is 8,753 lines) and emits Kotlin
that Kotlin/Native's frontend can chew on.  Correctness is explicitly NOT a
goal - a body of TODO() is a correct answer here.  The goal is acceptance.

Run:
    /home/nekit/UT/kn-toolchain/venv/bin/python kn-run/j2k.py

See kn-run/README.md for how to re-run and how to add a post-pass rule.
"""

import argparse
import importlib
import json
import os
import pkgutil
import re
import shutil
import sys
import traceback
from collections import Counter, defaultdict

from tree_sitter import Language, Parser
import tree_sitter_java

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SRC = "/home/nekit/UT/firefox-src-kn/mobile/android/geckoview/src/main/java"
DEFAULT_OUT = "/home/nekit/UT/firefox-src-kn/mobile/android/geckoview-kn/src"
MANUAL = os.path.join(HERE, "manual")

JAVA = Language(tree_sitter_java.language())

# --------------------------------------------------------------------------
# tables

KOTLIN_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while",
}

PRIMITIVES = {
    "byte": "Byte", "short": "Short", "int": "Int", "long": "Long",
    "char": "Char", "float": "Float", "double": "Double",
    "boolean": "Boolean", "void": "Unit",
}

PRIM_ARRAY = {
    "Byte": "ByteArray", "Short": "ShortArray", "Int": "IntArray",
    "Long": "LongArray", "Char": "CharArray", "Float": "FloatArray",
    "Double": "DoubleArray", "Boolean": "BooleanArray",
}

PRIM_ARRAY_OF = {
    "ByteArray": "byteArrayOf", "ShortArray": "shortArrayOf",
    "IntArray": "intArrayOf", "LongArray": "longArrayOf",
    "CharArray": "charArrayOf", "FloatArray": "floatArrayOf",
    "DoubleArray": "doubleArrayOf", "BooleanArray": "booleanArrayOf",
}

# Java boxed / core types with a Kotlin spelling.  Deliberately shallow: the
# stub lane owns everything else.
TYPE_MAP = {
    "Integer": "Int", "Character": "Char", "Object": "Any", "Void": "Unit",
}

TO_PRIM_CAST = {
    "Byte": "toByte", "Short": "toShort", "Int": "toInt", "Long": "toLong",
    "Char": "toChar", "Float": "toFloat", "Double": "toDouble",
}

# Annotations are dropped wholesale (see README).  Nothing here survives to
# Kotlin/Native with any meaning, and @NonNull/@Nullable would only invite
# nullability work the ground rules forbid.
DROP_IMPORT_PREFIXES = (
    "androidx.annotation.",
    "org.mozilla.gecko.annotation.",
    "java.lang.annotation.",
)

# Well-known supertypes whose kind we cannot learn from the corpus.  Used to
# decide `object : T {}` (interface) vs `object : T() {}` (class).
BUILTIN_INTERFACES = {
    "Runnable", "Comparator", "Comparable", "Callable", "Iterable", "Iterator",
    "Collection", "List", "Map", "Set", "Queue", "Cloneable", "Serializable",
    "AutoCloseable", "Closeable", "CharSequence", "Appendable", "Flushable",
    "Readable", "ThreadFactory", "Executor", "ExecutorService", "Future",
    "UncaughtExceptionHandler", "Parcelable", "Creator", "Callback",
    "OnClickListener", "OnTouchListener", "OnGlobalLayoutListener",
    "Runnable2", "InvocationHandler",
}
BUILTIN_CLASSES = {
    "Object", "Thread", "Exception", "RuntimeException", "Error", "Throwable",
    "ArrayList", "HashMap", "HashSet", "LinkedList", "StringBuilder", "Number",
    "InputStream", "OutputStream", "Reader", "Writer", "Handler", "Activity",
    "Service", "BroadcastReceiver", "View", "Surface", "SurfaceView", "Binder",
    "AsyncTask", "Thread.UncaughtExceptionHandler", "TimerTask",
}

# Interface-ish name endings, the last-resort guess for `object : T`.
IFACE_SUFFIXES = ("Listener", "Delegate", "Callback", "Observer", "Handler",
                  "Consumer", "Supplier", "Function", "Watcher", "Visitor",
                  "Filter", "Provider", "Target", "Source", "Sink")


def esc(name):
    """Backtick Java identifiers that are Kotlin keywords."""
    return "`%s`" % name if name in KOTLIN_KEYWORDS else name


class Buf:
    def __init__(self):
        self.lines = []
        self.ind = 0

    def w(self, s=""):
        self.lines.append(("    " * self.ind + s) if s else "")

    def push(self):
        self.ind += 1

    def pop(self):
        self.ind -= 1

    def text(self):
        return "\n".join(self.lines)


class Unit(object):
    """One converted file.  Handed to every rule in kn-run/rules/."""

    def __init__(self, java_path, rel, package, kotlin, source, tree):
        self.java_path = java_path
        self.rel = rel                 # e.g. org/mozilla/geckoview/GeckoView
        self.package = package
        self.kotlin = kotlin           # the Kotlin text; rules rewrite this
        self.source = source           # original Java bytes
        self.tree = tree               # tree-sitter Tree of the Java
        self.imports = []              # kotlin import lines, in order
        self.notes = []                # anything a human should know
        self.meta = {}

    @property
    def out_rel(self):
        return self.rel + ".kt"


# --------------------------------------------------------------------------
# corpus index: kinds, members, enum constants.  Built in a first pass so the
# converter can answer "is this supertype an interface?" without guessing.

class Index(object):
    def __init__(self):
        self.kinds = {}            # simple name -> class|interface|enum|annotation
        self.members = defaultdict(set)   # simple name -> method names
        self.enum_consts = defaultdict(set)  # CONST -> {qualified enum path}
        self.abstract_count = Counter()   # interface -> number of abstract methods
        self.iface_extras = Counter()     # interface -> non-method members
        self.supers = defaultdict(set)    # simple name -> supertype simple names

    def inherited_members(self, name, seen=None):
        """Method names visible from `name` upwards, within the corpus."""
        base = name.split("<")[0].split(".")[-1]
        seen = seen if seen is not None else set()
        if base in seen:
            return set()
        seen.add(base)
        out = set(self.members.get(base, ()))
        for sup in self.supers.get(base, ()):
            out |= self.inherited_members(sup, seen)
        return out

    def kind_of(self, name):
        base = name.split("<")[0].strip()
        base = base.split(".")[-1]
        if base in self.kinds:
            return self.kinds[base]
        if base in BUILTIN_INTERFACES:
            return "interface"
        if base in BUILTIN_CLASSES:
            return "class"
        return None

    def is_interface(self, name, argc):
        k = self.kind_of(name)
        if k is not None:
            return k in ("interface", "annotation")
        base = name.split("<")[0].split(".")[-1]
        if base.endswith(IFACE_SUFFIXES):
            return True
        # You cannot pass constructor arguments to an interface.
        return argc == 0

    def is_sam(self, name):
        base = name.split(".")[-1]
        return (self.kinds.get(base) == "interface"
                and self.abstract_count[base] == 1
                and self.iface_extras[base] == 0)


def index_file(idx, src, tree):
    def walk(node, path):
        for child in node.named_children:
            t = child.type
            if t in ("class_declaration", "interface_declaration",
                     "enum_declaration", "annotation_type_declaration"):
                nm = txt(src, child.child_by_field_name("name"))
                kind = {"class_declaration": "class",
                        "interface_declaration": "interface",
                        "enum_declaration": "enum",
                        "annotation_type_declaration": "annotation"}[t]
                idx.kinds[nm] = kind
                qual = path + [nm]
                for sc in child.named_children:
                    if sc.type == "superclass":
                        for k in sc.named_children:
                            idx.supers[nm].add(txt(src, k).split("<")[0].split(".")[-1])
                    elif sc.type in ("super_interfaces", "extends_interfaces"):
                        for tl in sc.named_children:
                            kids = tl.named_children if tl.type == "type_list" else [tl]
                            for k in kids:
                                idx.supers[nm].add(
                                    txt(src, k).split("<")[0].split(".")[-1])
                body = child.child_by_field_name("body")
                if body is not None:
                    for m in body.named_children:
                        if m.type == "method_declaration":
                            mn = m.child_by_field_name("name")
                            if mn is not None:
                                idx.members[nm].add(txt(src, mn))
                            if t == "interface_declaration":
                                mods = modifier_words(src, m)
                                if m.child_by_field_name("body") is None:
                                    idx.abstract_count[nm] += 1
                                elif "static" in mods:
                                    idx.iface_extras[nm] += 1
                        elif m.type in ("constant_declaration", "field_declaration"):
                            if t == "interface_declaration":
                                idx.iface_extras[nm] += 1
                        elif m.type == "enum_body_declarations":
                            for mm in m.named_children:
                                if mm.type == "method_declaration":
                                    mn = mm.child_by_field_name("name")
                                    if mn is not None:
                                        idx.members[nm].add(txt(src, mn))
                    if t == "enum_declaration":
                        for m in body.named_children:
                            if m.type == "enum_constant":
                                cn = txt(src, m.child_by_field_name("name"))
                                idx.enum_consts[cn].add(".".join(qual))
                            elif m.type == "enum_body_declarations":
                                walk(m, qual)
                    walk(body, qual)
            else:
                walk(child, path)

    walk(tree.root_node, [])


# --------------------------------------------------------------------------
# small helpers over the tree

def txt(src, node):
    if node is None:
        return ""
    return src[node.start_byte:node.end_byte].decode("utf-8", "replace")


def modifier_words(src, node):
    mods = node.child_by_field_name("modifiers")
    if mods is None:
        for c in node.children:
            if c.type == "modifiers":
                mods = c
                break
    out = set()
    if mods is None:
        return out
    for c in mods.children:
        if not c.is_named:
            out.add(txt(src, c))
        elif c.type in ("marker_annotation", "annotation"):
            continue
        else:
            out.add(txt(src, c))
    return out


def annotations_of(src, node):
    mods = None
    for c in node.children:
        if c.type == "modifiers":
            mods = c
            break
    if mods is None:
        return []
    out = []
    for c in mods.children:
        if c.type in ("marker_annotation", "annotation"):
            n = c.child_by_field_name("name")
            out.append(txt(src, n).split(".")[-1])
    return out


# --------------------------------------------------------------------------
# literals

_OCTAL = re.compile(r"^0[0-7_]+[lL]?$")


def conv_int_literal(s):
    """Java integer literal -> Kotlin.  Octal has no Kotlin spelling, and a hex
    int with the sign bit set is out of range for Kotlin's Int literal."""
    s = s.strip()
    if _OCTAL.match(s):
        suffix = "L" if s[-1] in "lL" else ""
        body = s[:-1] if suffix else s
        return str(int(body.replace("_", ""), 8)) + suffix
    low = s.lower()
    if (low.startswith("0x") or low.startswith("0b")) and not low.endswith("l"):
        base = 16 if low.startswith("0x") else 2
        try:
            v = int(s.replace("_", "")[2:], base)
        except ValueError:
            return s
        if v > 0x7FFFFFFF:
            return "%sL.toInt()" % s if v <= 0xFFFFFFFF else s + "L"
    return s


def conv_float_literal(s):
    s = s.strip()
    if s[-1] in "dD" and not s.lower().startswith("0x"):
        s = s[:-1]
    if s.startswith("."):
        s = "0" + s
    if s.endswith("."):
        s = s + "0"
    m = re.match(r"^(\d+)([fF])$", s)          # Java 1f -> Kotlin 1.0f
    if m:
        s = m.group(1) + ".0" + m.group(2)
    if re.match(r"^\d+$", s):
        s += ".0"
    return s


_ESC = re.compile(r"\\(u[0-9a-fA-F]{4}|[0-7]{1,3}|.)", re.S)


def conv_escapes(body, in_char=False):
    """Java string/char body -> Kotlin.  Kotlin has no \\f and no octal escape,
    and treats a bare $ as a template start."""
    out = []
    i = 0
    while i < len(body):
        ch = body[i]
        if ch == "\\":
            m = _ESC.match(body, i)
            if not m:
                out.append("\\\\")
                i += 1
                continue
            e = m.group(1)
            if e.startswith("u"):
                out.append("\\" + e)
            elif e == "f":
                out.append("\\u000C")
            elif e in ("n", "t", "r", "b", "\\", "'", '"', "$"):
                out.append("\\" + e)
            elif re.match(r"^[0-7]{1,3}$", e):
                out.append("\\u%04X" % int(e, 8))
            else:
                out.append("\\" + e)
            i = m.end()
            continue
        if ch == "$" and not in_char:
            out.append("\\$")
        else:
            out.append(ch)
        i += 1
    return "".join(out)


# --------------------------------------------------------------------------

class Converter(object):
    def __init__(self, src, path, idx, unit):
        self.src = src
        self.path = path
        self.idx = idx
        self.unit = unit
        self.warn = unit.notes
        self.ret_stack = []      # lambda return labels; None marks a fun boundary
        self.used_labels = set()
        self.lambda_n = 0
        self.cls_stack = []      # enclosing named classes, for `this@Outer`

    def t(self, node):
        return txt(self.src, node)

    def note(self, msg):
        if msg not in self.warn:
            self.warn.append(msg)

    # ---------------------------------------------------------------- types

    def ty(self, node, nullable=False):
        s = self._ty(node)
        return s + "?" if nullable else s

    def _ty(self, node):
        if node is None:
            return "Any"
        t = node.type
        if t in ("integral_type", "floating_point_type"):
            return PRIMITIVES.get(self.t(node).strip(), "Int")
        if t == "boolean_type":
            return "Boolean"
        if t == "void_type":
            return "Unit"
        if t == "type_identifier":
            n = self.t(node)
            return TYPE_MAP.get(n, esc(n))
        if t == "scoped_type_identifier":
            parts = [self.t(c) for c in node.named_children
                     if c.type in ("type_identifier", "scoped_type_identifier",
                                   "generic_type", "identifier")]
            if len(node.named_children) and node.named_children[0].type == "generic_type":
                # Outer<T>.Inner -> Outer.Inner; Kotlin has no such spelling
                base = self._ty(node.named_children[0]).split("<")[0]
                rest = [self.t(c) for c in node.named_children[1:]]
                return ".".join([base] + rest)
            return ".".join(parts) if parts else self.t(node)
        if t == "generic_type":
            base = self._ty(node.named_children[0])
            args = None
            for c in node.named_children:
                if c.type == "type_arguments":
                    args = c
            if args is None:
                return base
            return base + self.type_args(args)
        if t == "array_type":
            elem = self._ty(node.child_by_field_name("element"))
            dims = node.child_by_field_name("dimensions")
            n = max(1, self.t(dims).count("["))
            return self.wrap_array(elem, n)
        if t == "annotated_type":
            for c in node.named_children:
                if c.type not in ("marker_annotation", "annotation"):
                    return self._ty(c)
            return "Any"
        if t == "wildcard":
            kids = [c for c in node.named_children
                    if c.type not in ("marker_annotation", "annotation")]
            body = self.t(node)
            if "extends" in body and kids:
                return "out " + self._ty(kids[-1])
            if "super" in body and kids:
                return "in " + self._ty(kids[-1])
            return "*"
        return self.t(node) or "Any"

    def nullable_init(self, val):
        """Cheap nullability: only where the initializer itself says null."""
        if val is None:
            return False
        if val.type == "null_literal":
            return True
        if val.type == "ternary_expression":
            for f in ("consequence", "alternative"):
                k = val.child_by_field_name(f)
                if k is not None and k.type == "null_literal":
                    return True
        if val.type == "cast_expression":
            v = val.child_by_field_name("value")
            return v is not None and v.type == "null_literal"
        return False

    def wrap_array(self, elem, n=1):
        out = PRIM_ARRAY.get(elem) if n >= 1 else None
        cur = out if out else "Array<%s>" % elem
        for _ in range(n - 1):
            cur = "Array<%s>" % cur
        return cur

    def type_args(self, node):
        args = [self._ty(c) for c in node.named_children
                if c.type not in ("marker_annotation", "annotation")]
        if not args:
            return ""
        return "<" + ", ".join(args) + ">"

    def type_params(self, node):
        if node is None:
            return ""
        out = []
        for tp in node.named_children:
            if tp.type != "type_parameter":
                continue
            name = None
            bound = None
            for c in tp.named_children:
                if c.type in ("marker_annotation", "annotation"):
                    continue
                if c.type == "type_identifier" and name is None:
                    name = self.t(c)
                elif c.type == "type_bound":
                    kids = [k for k in c.named_children]
                    if kids:
                        bound = self._ty(kids[0])
            if name is None:
                continue
            out.append(name + (" : " + bound if bound else ""))
        return "<" + ", ".join(out) + ">" if out else ""

    # ------------------------------------------------------------ expressions

    def e(self, node):
        if node is None:
            return ""
        t = node.type
        fn = getattr(self, "e_" + t, None)
        if fn:
            return fn(node)
        # unknown expression: pass the Java through and flag it
        self.note("unhandled expression node '%s'" % t)
        return self.t(node)

    def e_identifier(self, n):
        return esc(self.t(n))

    def e_this(self, n):
        return "this"

    def e_super(self, n):
        return "super"

    def e_null_literal(self, n):
        return "null"

    def e_true(self, n):
        return "true"

    def e_false(self, n):
        return "false"

    def e_decimal_integer_literal(self, n):
        return conv_int_literal(self.t(n))

    e_hex_integer_literal = e_decimal_integer_literal
    e_octal_integer_literal = e_decimal_integer_literal
    e_binary_integer_literal = e_decimal_integer_literal

    def e_decimal_floating_point_literal(self, n):
        return conv_float_literal(self.t(n))

    e_hex_floating_point_literal = e_decimal_floating_point_literal

    def e_string_literal(self, n):
        raw = self.t(n)
        if raw.startswith('"""'):
            return raw
        return '"' + conv_escapes(raw[1:-1]) + '"'

    def e_character_literal(self, n):
        raw = self.t(n)
        return "'" + conv_escapes(raw[1:-1], in_char=True) + "'"

    def e_parenthesized_expression(self, n):
        inner = self.real(n.named_children)
        return "(" + self.e(inner[0]) + ")" if inner else "()"

    def e_field_access(self, n):
        obj = n.child_by_field_name("object")
        fld = n.child_by_field_name("field")
        if fld is not None and fld.type == "this":
            return "this@" + self.t(obj).split(".")[-1]
        name = esc(self.t(fld))
        return "%s.%s" % (self.e(obj), name)

    def e_scoped_identifier(self, n):
        return ".".join(esc(p) for p in self.t(n).split("."))

    def e_class_literal(self, n):
        base = self.t(n).rsplit(".class", 1)[0].strip()
        dims = 0
        while base.endswith("[]"):
            base = base[:-2].strip()
            dims += 1
        if dims:
            base = TYPE_MAP.get(base, base)
            if base in PRIMITIVES:
                base = PRIMITIVES[base]
            # `Foo[].class`: Kotlin forbids type arguments on a class
            # literal, so an array class comes from a stub helper instead.
            return "knArrayClass<%s>()" % self.wrap_array(base, dims)
        base = TYPE_MAP.get(base, base)
        if base in PRIMITIVES:
            base = PRIMITIVES[base]
        return base + "::class.java"

    def e_method_invocation(self, n):
        obj = n.child_by_field_name("object")
        name = self.t(n.child_by_field_name("name"))
        targs = ""
        for c in n.children:
            if c.type == "type_arguments" and c.start_byte < n.child_by_field_name("name").start_byte:
                targs = self.type_args(c)
        args = self.args(n.child_by_field_name("arguments"))
        call = "%s%s(%s)" % (esc(name), targs, args)
        if obj is None:
            return call
        return "%s.%s" % (self.e(obj), call)

    @staticmethod
    def real(nodes):
        return [c for c in nodes if c.type not in ("line_comment", "block_comment")]

    def args(self, node):
        if node is None:
            return ""
        return ", ".join(self.e(c) for c in self.real(node.named_children))

    def e_line_comment(self, n):
        return ""

    def e_block_comment(self, n):
        return ""

    def e_object_creation_expression(self, n):
        ty = None
        for c in n.children:
            if c.type in ("type_identifier", "scoped_type_identifier",
                          "generic_type", "array_type"):
                ty = c
        tyname = self._ty(ty)
        arglist = n.child_by_field_name("arguments")
        args = self.args(arglist)
        argc = len(arglist.named_children) if arglist is not None else 0
        body = None
        for c in n.named_children:
            if c.type == "class_body":
                body = c
        if body is None:
            return "%s(%s)" % (tyname, args)
        # anonymous class
        iface = self.idx.is_interface(tyname, argc)
        # Java's `new Foo(this) { }` means the *enclosing* instance.  Bare
        # `this` in a Kotlin object-expression supertype call binds elsewhere
        # (the supertype's companion), so label it.
        head = tyname if (iface and argc == 0) else "%s(%s)" % (tyname, self.qualify_this(args))
        b = Buf()
        b.w("object : %s {" % head)
        b.push()
        self.emit_body(body, b, ClassCtx(name="<anon>", kind="anon",
                                         supertypes=[tyname], final=True))
        b.pop()
        b.w("}")
        return "\n".join(b.lines).strip()

    _BARE_THIS = re.compile(r"(?<![\w.@])this(?![\w@])")

    def qualify_this(self, text):
        outer = None
        for nm in reversed(self.cls_stack):
            if not nm.startswith("<"):
                outer = nm
                break
        if outer is None or "this" not in text:
            return text
        return self._BARE_THIS.sub("this@" + outer, text)

    def e_array_creation_expression(self, n):
        elem = self._ty(n.child_by_field_name("type"))
        init = n.child_by_field_name("value")
        dims_expr = [c for c in n.named_children if c.type == "dimensions_expr"]
        extra = n.child_by_field_name("dimensions")
        nd = len(dims_expr)
        if extra is not None and extra.type == "dimensions":
            nd += self.t(extra).count("[")
        nd = max(1, nd)
        if init is not None:
            return self.array_init(init, elem, max(1, nd))
        if not dims_expr:
            return "%s(0)" % self.wrap_array(elem)
        sizes = [self.e(d.named_children[0]) for d in dims_expr if d.named_children]
        return self.new_array(elem, sizes, nd)

    def new_array(self, elem, sizes, nd):
        if not sizes:
            return "%s(0)" % self.wrap_array(elem)
        inner_dims = nd - 1
        if len(sizes) == 1:
            k = self.wrap_array(elem, nd)
            if k in PRIM_ARRAY_OF:
                return "%s(%s)" % (k, sizes[0])
            inner = self.wrap_array(elem, max(1, inner_dims)) if inner_dims else elem
            return "arrayOfNulls<%s>(%s) as %s" % (inner, sizes[0], k)
        rest = self.new_array(elem, sizes[1:], nd - 1)
        return "Array(%s) { %s }" % (sizes[0], rest)

    def array_init(self, node, elem, nd):
        k = self.wrap_array(elem, nd)
        parts = []
        for c in self.real(node.named_children):
            if c.type == "array_initializer":
                parts.append(self.array_init(c, elem, nd - 1))
            else:
                parts.append(self.e(c))
        ctor = PRIM_ARRAY_OF.get(k, "arrayOf")
        return "%s(%s)" % (ctor, ", ".join(parts))

    def e_array_access(self, n):
        return "%s[%s]" % (self.e(n.child_by_field_name("array")),
                           self.e(n.child_by_field_name("index")))

    def e_cast_expression(self, n):
        ty = self._ty(n.child_by_field_name("type"))
        val = self.e(n.child_by_field_name("value"))
        if ty in TO_PRIM_CAST:
            return "(%s).%s()" % (val, TO_PRIM_CAST[ty])
        if ty == "Boolean":
            return val
        return "(%s as %s)" % (val, ty)

    def e_instanceof_expression(self, n):
        left = self.e(n.child_by_field_name("left"))
        right = n.child_by_field_name("right")
        ty = self._ty(right)
        binder = n.child_by_field_name("name")
        if binder is not None:
            self.note("instanceof pattern binding dropped near '%s'" % self.t(n)[:40])
        return "(%s is %s)" % (left, ty)

    def e_ternary_expression(self, n):
        return "if (%s) %s else %s" % (
            self.e(n.child_by_field_name("condition")),
            self.e(n.child_by_field_name("consequence")),
            self.e(n.child_by_field_name("alternative")))

    BITWISE = {"&": "and", "|": "or", "^": "xor", "<<": "shl", ">>": "shr",
               ">>>": "ushr"}

    def e_binary_expression(self, n):
        left = n.child_by_field_name("left")
        right = n.child_by_field_name("right")
        op = None
        for c in n.children:
            if not c.is_named and c.start_byte >= left.end_byte:
                op = self.t(c)
                break
        l, r = self.e(left), self.e(right)
        if op in self.BITWISE:
            return "(%s %s %s)" % (l, self.BITWISE[op], r)
        return "%s %s %s" % (l, op, r)

    def e_unary_expression(self, n):
        operand = n.child_by_field_name("operand")
        op = self.t(n)[:len(self.t(n)) - len(self.t(operand))].strip()
        v = self.e(operand)
        if op == "~":
            return "(%s).inv()" % v
        if op == "+":
            return v
        return "%s%s" % (op, v)

    def e_update_expression(self, n):
        kids = [c for c in n.children]
        first = kids[0]
        if first.is_named:
            return "%s%s" % (self.e(first), self.t(kids[-1]))
        return "%s%s" % (self.t(first), self.e(kids[-1]))

    COMPOUND = {">>>=": "ushr", ">>=": "shr", "<<=": "shl", "&=": "and",
                "|=": "or", "^=": "xor"}

    def e_assignment_expression(self, n):
        left = n.child_by_field_name("left")
        right = n.child_by_field_name("right")
        op = None
        for c in n.children:
            if not c.is_named and c.start_byte >= left.end_byte:
                op = self.t(c)
                break
        l, r = self.e(left), self.e(right)
        if op in self.COMPOUND:
            return "%s = (%s %s %s)" % (l, l, self.COMPOUND[op], r)
        return "%s %s %s" % (l, op, r)

    def e_lambda_expression(self, n):
        params = n.child_by_field_name("parameters")
        body = n.child_by_field_name("body")
        names = []
        if params is not None:
            if params.type == "identifier":
                names = [esc(self.t(params))]
            else:
                for c in params.named_children:
                    if c.type == "formal_parameter":
                        names.append(esc(self.t(c.child_by_field_name("name"))))
                    elif c.type == "identifier":
                        names.append(esc(self.t(c)))
        head = (", ".join(names) + " -> ") if names else ""
        if body is not None and body.type == "block":
            self.lambda_n += 1
            label = "j2k%d" % self.lambda_n
            self.ret_stack.append(label)
            b = Buf()
            b.push()
            self.block_stmts(body, b)
            self.ret_stack.pop()
            inner = "\n".join(b.lines)
            pre = label + "@" if label in self.used_labels else ""
            if not inner:
                return "%s{ %s }" % (pre, head)
            return "%s{ %s\n%s\n}" % (pre, head, inner)
        return "{ %s%s }" % (head, self.e(body))

    def e_method_reference(self, n):
        raw = self.t(n)
        lhs, _, rhs = raw.partition("::")
        lhs, rhs = lhs.strip(), rhs.strip()
        if rhs == "new":
            dims = 0
            while lhs.endswith("[]"):
                lhs = lhs[:-2].strip()
                dims += 1
            if dims:
                elem = TYPE_MAP.get(lhs, lhs)
                elem = PRIMITIVES.get(elem, elem)
                k = self.wrap_array(elem, dims)
                if k in PRIM_ARRAY_OF:
                    return "{ n -> %s(n) }" % k
                return "{ n -> arrayOfNulls<%s>(n) }" % self.wrap_array(elem, dims - 1) \
                    if dims > 1 else "{ n -> arrayOfNulls<%s>(n) }" % elem
            return "::" + lhs
        return "%s::%s" % (lhs, rhs)

    def e_switch_expression(self, n):
        b = Buf()
        self.switch(n, b, as_expr=True)
        return "\n".join(b.lines).strip()

    def e_explicit_constructor_invocation(self, n):
        return self.t(n).rstrip(";")

    def e_array_initializer(self, n):
        return "arrayOf(%s)" % ", ".join(self.e(c) for c in self.real(n.named_children))

    def e_element_value_array_initializer(self, n):
        return "arrayOf(%s)" % ", ".join(self.e(c) for c in n.named_children)

    def e_marker_annotation(self, n):
        return ""

    def e_annotation(self, n):
        return ""

    def e_template_expression(self, n):
        return self.t(n)

    # ------------------------------------------------------------- statements

    def emit_comment(self, node, b):
        """Kotlin block comments nest, so javadoc containing '/*' would swallow
        the rest of the file.  Line comments cannot."""
        for ln in self.t(node).split("\n"):
            b.w("// " + ln.strip().lstrip("/*").rstrip("*/").strip())

    def block_stmts(self, node, b):
        for c in node.named_children:
            self.s(c, b)

    def as_block(self, node, b, head):
        """Emit `head { ... }` for any statement node."""
        if node is None:
            b.w(head + " {}")
            return
        b.w(head + " {")
        b.push()
        if node.type == "block":
            self.block_stmts(node, b)
        else:
            self.s(node, b)
        b.pop()
        b.w("}")

    def s(self, node, b):
        t = node.type
        fn = getattr(self, "s_" + t, None)
        if fn:
            fn(node, b)
            return
        if t in ("line_comment", "block_comment"):
            self.emit_comment(node, b)
            return
        if t in ("class_declaration", "interface_declaration", "enum_declaration",
                 "annotation_type_declaration"):
            self.emit_type(node, b, ClassCtx(name="<local>", kind="class"))
            return
        self.note("unhandled statement node '%s'" % t)
        b.w("// J2K: unhandled %s" % t)

    def s_expression_statement(self, node, b):
        kids = node.named_children
        if not kids:
            return
        for ln in self.e(kids[0]).split("\n"):
            b.w(ln)

    def s_local_variable_declaration(self, node, b):
        mods = modifier_words(self.src, node)
        kw = "val" if "final" in mods else "var"
        base = node.child_by_field_name("type")
        for d in node.named_children:
            if d.type != "variable_declarator":
                continue
            name = esc(self.t(d.child_by_field_name("name")))
            dims = None
            for c in d.named_children:
                if c.type == "dimensions":
                    dims = c
            ty = self._ty(base)
            if dims is not None:
                ty = self.wrap_array(ty, self.t(dims).count("["))
            val = d.child_by_field_name("value")
            if val is None:
                b.w("var %s: %s = TODO()" % (name, ty))
                continue
            if val.type == "array_initializer":
                elem = self._ty(base)
                nd = 1
                if base.type == "array_type":
                    elem = self._ty(base.child_by_field_name("element"))
                    nd = max(1, self.t(base.child_by_field_name("dimensions")).count("["))
                rhs = self.array_init(val, elem, nd)
            else:
                rhs = self.e(val)
            if self.nullable_init(val):
                ty += "?"
            lines = rhs.split("\n")
            b.w("%s %s: %s = %s" % (kw, name, ty, lines[0]))
            for ln in lines[1:]:
                b.w(ln)

    def s_if_statement(self, node, b):
        cond = self.e(node.child_by_field_name("condition"))
        if not cond.startswith("("):
            cond = "(" + cond + ")"
        cons = node.child_by_field_name("consequence")
        alt = node.child_by_field_name("alternative")
        self.as_block(cons, b, "if " + cond)
        while alt is not None:
            if alt.type == "if_statement":
                c2 = self.e(alt.child_by_field_name("condition"))
                if not c2.startswith("("):
                    c2 = "(" + c2 + ")"
                # rewrite the trailing '}' into '} else if (...) {'
                b.lines[-1] = b.lines[-1] + " else if " + c2 + " {"
                b.push()
                inner = alt.child_by_field_name("consequence")
                if inner is not None and inner.type == "block":
                    self.block_stmts(inner, b)
                elif inner is not None:
                    self.s(inner, b)
                b.pop()
                b.w("}")
                alt = alt.child_by_field_name("alternative")
            else:
                b.lines[-1] = b.lines[-1] + " else {"
                b.push()
                if alt.type == "block":
                    self.block_stmts(alt, b)
                else:
                    self.s(alt, b)
                b.pop()
                b.w("}")
                alt = None

    def s_while_statement(self, node, b):
        cond = self.e(node.child_by_field_name("condition"))
        if not cond.startswith("("):
            cond = "(" + cond + ")"
        self.as_block(node.child_by_field_name("body"), b, "while " + cond)

    def s_do_statement(self, node, b):
        b.w("do {")
        b.push()
        body = node.child_by_field_name("body")
        if body is not None and body.type == "block":
            self.block_stmts(body, b)
        elif body is not None:
            self.s(body, b)
        b.pop()
        b.w("} while (%s)" % self.e(node.child_by_field_name("condition")).strip("()"))

    def s_for_statement(self, node, b):
        # Kotlin has no C-style for; a scoped while is the mechanical equivalent.
        b.w("run {")
        b.push()
        inits = [c for i, c in enumerate(node.children)
                 if node.field_name_for_child(i) == "init"]
        for c in inits:
            if c.type == "local_variable_declaration":
                self.s_local_variable_declaration(c, b)
            elif c.is_named:
                b.w(self.e(c))
        cond = node.child_by_field_name("condition")
        c = self.e(cond) if cond is not None else "true"
        b.w("while (%s) {" % c)
        b.push()
        body = node.child_by_field_name("body")
        if body is not None and body.type == "block":
            self.block_stmts(body, b)
        elif body is not None:
            self.s(body, b)
        for i, ch in enumerate(node.children):
            if node.field_name_for_child(i) == "update":
                b.w(self.e(ch))
        b.pop()
        b.w("}")
        b.pop()
        b.w("}")

    def s_enhanced_for_statement(self, node, b):
        name = esc(self.t(node.child_by_field_name("name")))
        val = self.e(node.child_by_field_name("value"))
        self.as_block(node.child_by_field_name("body"), b,
                      "for (%s in %s)" % (name, val))

    def s_return_statement(self, node, b):
        kids = self.real(node.named_children)
        lbl = self.ret_stack[-1] if self.ret_stack else None
        kw = "return@" + lbl if lbl else "return"
        if lbl:
            self.used_labels.add(lbl)
        if not kids:
            b.w(kw)
            return
        lines = self.e(kids[0]).split("\n")
        b.w(kw + " " + lines[0])
        for ln in lines[1:]:
            b.w(ln)

    def s_throw_statement(self, node, b):
        kids = self.real(node.named_children)
        b.w("throw " + (self.e(kids[0]) if kids else "RuntimeException()"))

    def s_break_statement(self, node, b):
        kids = [c for c in node.named_children]
        b.w("break@" + self.t(kids[0]) if kids else "break")

    def s_continue_statement(self, node, b):
        kids = [c for c in node.named_children]
        b.w("continue@" + self.t(kids[0]) if kids else "continue")

    def s_block(self, node, b):
        b.w("run {")
        b.push()
        self.block_stmts(node, b)
        b.pop()
        b.w("}")

    def s_labeled_statement(self, node, b):
        kids = [c for c in node.named_children]
        label = self.t(kids[0])
        sub = Buf()
        if len(kids) > 1:
            self.s(kids[1], sub)
        if sub.lines:
            sub.lines[0] = label + "@ " + sub.lines[0]
        for ln in sub.lines:
            b.w(ln)

    def s_synchronized_statement(self, node, b):
        # Kotlin/Native has no JVM monitor; the block survives, the lock does not.
        b.w("// J2K: synchronized dropped (no JVM monitors on Kotlin/Native)")
        body = node.child_by_field_name("body")
        b.w("run {")
        b.push()
        if body is not None:
            self.block_stmts(body, b)
        b.pop()
        b.w("}")

    def s_assert_statement(self, node, b):
        b.w("// J2K: assert dropped")

    def s_try_statement(self, node, b):
        self._try(node, b, None)

    def s_try_with_resources_statement(self, node, b):
        self._try(node, b, node.child_by_field_name("resources"))

    def _try(self, node, b, resources):
        b.w("try {")
        b.push()
        if resources is not None:
            for r in resources.named_children:
                if r.type != "resource":
                    continue
                nm = r.child_by_field_name("name")
                if nm is None:
                    b.w(self.e(r.named_children[-1]))
                    continue
                ty = self._ty(r.child_by_field_name("type"))
                b.w("val %s: %s = %s" % (esc(self.t(nm)), ty,
                                         self.e(r.child_by_field_name("value"))))
        body = node.child_by_field_name("body")
        if body is not None:
            self.block_stmts(body, b)
        b.pop()
        b.w("}")
        for c in node.named_children:
            if c.type == "catch_clause":
                p = c.child_by_field_name("parameter")
                if p is None:
                    for k in c.named_children:
                        if k.type == "catch_formal_parameter":
                            p = k
                nm = esc(self.t(p.child_by_field_name("name")))
                ctype = None
                for k in p.named_children:
                    if k.type == "catch_type":
                        ctype = k
                types = [self._ty(x) for x in ctype.named_children] if ctype else ["Exception"]
                ty = types[0] if len(types) == 1 else "Exception"
                if len(types) > 1:
                    self.note("multi-catch collapsed to Exception")
                b.lines[-1] += " catch (%s: %s) {" % (nm, ty)
                b.push()
                cb = c.child_by_field_name("body")
                if cb is not None:
                    self.block_stmts(cb, b)
                b.pop()
                b.w("}")
            elif c.type == "finally_clause":
                b.lines[-1] += " finally {"
                b.push()
                for k in c.named_children:
                    if k.type == "block":
                        self.block_stmts(k, b)
                b.pop()
                b.w("}")

    def s_switch_expression(self, node, b):
        self.switch(node, b, as_expr=False)

    # ---------------------------------------------------------------- switch

    def case_label(self, label):
        """Qualify bare enum constants: Kotlin `when` needs Enum.CONST."""
        parts = []
        for c in label.named_children:
            if c.type == "modifiers":
                continue
            txt_c = self.e(c)
            if c.type == "identifier":
                owners = self.idx.enum_consts.get(self.t(c))
                if owners and len(owners) == 1:
                    owner = list(owners)[0].split(".")[-1]
                    txt_c = "%s.%s" % (owner, self.t(c))
            parts.append(txt_c)
        if not parts:
            return None          # `default`
        return ", ".join(parts)

    def switch(self, node, b, as_expr):
        subj = self.e(node.child_by_field_name("condition"))
        if not subj.startswith("("):
            subj = "(" + subj + ")"
        b.w("when " + subj + " {")
        b.push()
        body = node.child_by_field_name("body")
        pending = []
        for grp in body.named_children:
            if grp.type == "switch_block_statement_group":
                labels = []
                stmts = []
                for c in grp.named_children:
                    if c.type == "switch_label":
                        labels.append(self.case_label(c))
                    else:
                        stmts.append(c)
                pending.extend(labels)
                if not stmts:
                    continue        # empty fallthrough: fold into the next group
                head = "else" if any(l is None for l in pending) else \
                    ", ".join(l for l in pending if l)
                pending = []
                b.w("%s -> {" % head)
                b.push()
                for st in stmts:
                    if st.type == "break_statement" and not st.named_children:
                        continue
                    self.s(st, b)
                b.pop()
                b.w("}")
            elif grp.type == "switch_rule":
                labels = [self.case_label(c) for c in grp.named_children
                          if c.type == "switch_label"]
                head = "else" if any(l is None for l in labels) else \
                    ", ".join(l for l in labels if l)
                rest = [c for c in grp.named_children if c.type != "switch_label"]
                b.w("%s -> {" % head)
                b.push()
                for st in rest:
                    if st.type == "block":
                        self.block_stmts(st, b)
                    elif st.type == "expression_statement":
                        self.s(st, b)
                    elif st.is_named:
                        self.s(st, b) if st.type.endswith("statement") else b.w(self.e(st))
                b.pop()
                b.w("}")
        if pending:
            b.w("else -> {}")
        b.pop()
        b.w("}")

    # ------------------------------------------------------------ declarations

    def visibility(self, mods, ctx):
        if "private" in mods:
            return "private "
        if "protected" in mods:
            # `protected` is illegal inside an object / companion object.
            if ctx.kind in ("anon", "companion") or ctx.in_companion:
                return ""
            return "protected "
        return ""

    def emit_type(self, node, b, ctx, force_static=False):
        t = node.type
        if t == "class_declaration":
            self.emit_class(node, b, ctx, force_static)
        elif t == "interface_declaration":
            self.emit_interface(node, b, ctx)
        elif t == "enum_declaration":
            self.emit_enum(node, b, ctx)
        elif t == "annotation_type_declaration":
            self.emit_annotation_type(node, b, ctx)

    def supers(self, node, has_ctor):
        out = []
        sup = node.child_by_field_name("superclass")
        if sup is not None:
            kids = [c for c in sup.named_children]
            base = self._ty(kids[0]) if kids else "Any"
            out.append(base if has_ctor else base + "()")
        for c in node.named_children:
            if c.type in ("super_interfaces", "extends_interfaces"):
                for tl in c.named_children:
                    if tl.type == "type_list":
                        for x in tl.named_children:
                            out.append(self._ty(x))
                    else:
                        out.append(self._ty(tl))
        return out

    def emit_class(self, node, b, ctx, force_static=False):
        mods = modifier_words(self.src, node)
        name = esc(self.t(node.child_by_field_name("name")))
        tp = self.type_params(node.child_by_field_name("type_parameters"))
        body = node.child_by_field_name("body")
        ctors = [c for c in body.named_children
                 if c.type == "constructor_declaration"] if body else []
        sup = self.supers(node, bool(ctors))
        kw = []
        kw.append(self.visibility(mods, ctx).strip())
        if "abstract" in mods:
            kw.append("abstract")
        elif "final" not in mods and ctx.kind != "anon":
            kw.append("open")
        inner = (not force_static and "static" not in mods
                 and ctx.kind == "class" and ctx.depth > 0)
        if inner:
            kw.append("inner")
        head = " ".join(w for w in kw if w)
        head = (head + " " if head else "") + "class " + name + tp
        if sup:
            head += " : " + ", ".join(sup)
        b.w(head + " {")
        b.push()
        sub = ClassCtx(name=name, kind="class", supertypes=sup,
                       final="final" in mods, depth=ctx.depth + 1)
        self.emit_body(body, b, sub)
        b.pop()
        b.w("}")

    def emit_interface(self, node, b, ctx):
        mods = modifier_words(self.src, node)
        name = esc(self.t(node.child_by_field_name("name")))
        tp = self.type_params(node.child_by_field_name("type_parameters"))
        body = node.child_by_field_name("body")
        sup = self.supers(node, True)
        raw = self.t(node.child_by_field_name("name"))
        sam = self.idx.is_sam(raw) and not sup
        head = self.visibility(mods, ctx)
        head += ("fun " if sam else "") + "interface " + name + tp
        if sup:
            head += " : " + ", ".join(sup)
        b.w(head + " {")
        b.push()
        self.emit_body(body, b, ClassCtx(name=name, kind="interface",
                                         supertypes=sup, depth=ctx.depth + 1))
        b.pop()
        b.w("}")

    def emit_enum(self, node, b, ctx):
        mods = modifier_words(self.src, node)
        name = esc(self.t(node.child_by_field_name("name")))
        body = node.child_by_field_name("body")
        sup = self.supers(node, True)
        consts = [c for c in body.named_children if c.type == "enum_constant"]
        decls = None
        for c in body.named_children:
            if c.type == "enum_body_declarations":
                decls = c
        ctors = [c for c in decls.named_children
                 if c.type == "constructor_declaration"] if decls else []
        primary, secondary = None, []
        for c in ctors:
            cb = c.child_by_field_name("body")
            first = cb.named_children[0] if (cb and cb.named_children) else None
            delegates = (first is not None
                         and first.type == "explicit_constructor_invocation"
                         and self.t(first).lstrip().startswith("this"))
            if primary is None and not delegates:
                primary = c
            else:
                secondary.append(c)
        params = ""
        if primary is not None:
            params = "(" + self.params(primary.child_by_field_name("parameters")) + ")"
        head = self.visibility(mods, ctx) + "enum class " + name + params
        if sup:
            head += " : " + ", ".join(sup)
        b.w(head + " {")
        b.push()
        for i, c in enumerate(consts):
            cname = esc(self.t(c.child_by_field_name("name")))
            a = c.child_by_field_name("arguments")
            argtxt = "(" + self.args(a) + ")" if a is not None else ""
            cbody = c.child_by_field_name("body")
            tail = "," if i < len(consts) - 1 else ";"
            if cbody is None:
                b.w(cname + argtxt + tail)
            else:
                b.w(cname + argtxt + " {")
                b.push()
                self.emit_body(cbody, b, ClassCtx(name=cname, kind="anon",
                                                  supertypes=[self.t(node.child_by_field_name("name"))],
                                                  depth=ctx.depth + 1))
                b.pop()
                b.w("}" + tail)
        if not consts:
            b.w(";")
        sub = ClassCtx(name=name, kind="enum", supertypes=sup, depth=ctx.depth + 1)
        if primary is not None:
            pb = primary.child_by_field_name("body")
            init = Buf()
            init.ind = b.ind + 1
            if pb is not None:
                for st in pb.named_children:
                    if st.type == "explicit_constructor_invocation":
                        continue
                    self.s(st, init)
            if init.lines:
                b.w("init {")
                for ln in init.lines:
                    b.lines.append(ln)
                b.w("}")
        for c in secondary:
            self.emit_ctor(c, b, sub, ["dummy"])
        if decls is not None:
            self.emit_body(decls, b, sub, skip_ctors=True)
        b.pop()
        b.w("}")

    def emit_annotation_type(self, node, b, ctx):
        mods = modifier_words(self.src, node)
        name = esc(self.t(node.child_by_field_name("name")))
        body = node.child_by_field_name("body")
        params = []
        extras = 0
        for c in body.named_children if body else []:
            if c.type == "annotation_type_element_declaration":
                ty = self._ty(c.child_by_field_name("type"))
                pname = esc(self.t(c.child_by_field_name("name")))
                dflt = c.child_by_field_name("value")
                d = ""
                if dflt is not None:
                    if dflt.type == "element_value_array_initializer":
                        ctor = PRIM_ARRAY_OF.get(ty, "arrayOf")
                        d = " = %s(%s)" % (ctor, ", ".join(
                            self.e(x) for x in dflt.named_children))
                    else:
                        d = " = " + self.e(dflt)
                params.append("val %s: %s%s" % (pname, ty, d))
            elif c.type not in ("line_comment", "block_comment"):
                extras += 1
        if extras:
            self.note("dropped %d member(s) from @interface %s" % (extras, name))
        head = self.visibility(mods, ctx) + "annotation class " + name
        b.w(head + "(" + ", ".join(params) + ")")

    def params(self, node):
        if node is None:
            return ""
        out = []
        for p in node.named_children:
            if p.type == "formal_parameter":
                ty = self._ty(p.child_by_field_name("type"))
                dims = None
                for c in p.named_children:
                    if c.type == "dimensions":
                        dims = c
                if dims is not None:
                    ty = self.wrap_array(ty, self.t(dims).count("["))
                out.append("%s: %s" % (esc(self.t(p.child_by_field_name("name"))), ty))
            elif p.type == "spread_parameter":
                kids = [c for c in p.named_children
                        if c.type not in ("modifiers", "marker_annotation",
                                          "annotation", "line_comment",
                                          "block_comment")]
                ty = self._ty(kids[0])
                nm = None
                for c in kids:
                    if c.type == "variable_declarator":
                        nm = self.t(c.child_by_field_name("name"))
                out.append("vararg %s: %s" % (esc(nm or "args"), ty))
        return ", ".join(out)

    def emit_ctor(self, node, b, ctx, supertypes):
        mods = modifier_words(self.src, node)
        ps = self.params(node.child_by_field_name("parameters"))
        body = node.child_by_field_name("body")
        deleg = ""
        stmts = []
        if body is not None:
            seen = False
            for st in body.named_children:
                if st.type in ("line_comment", "block_comment"):
                    continue
                if not seen and st.type == "explicit_constructor_invocation":
                    seen = True
                    ctor = st.child_by_field_name("constructor")
                    kw = self.t(ctor) if ctor is not None else "super"
                    a = self.args(st.child_by_field_name("arguments"))
                    deleg = " : %s(%s)" % ("this" if kw == "this" else "super", a)
                    continue
                seen = True
                stmts.append(st)
        b.w("%sconstructor(%s)%s {" % (self.visibility(mods, ctx), ps, deleg))
        b.push()
        self.ret_stack.append(None)
        for st in stmts:
            self.s(st, b)
        self.ret_stack.pop()
        b.pop()
        b.w("}")

    def emit_method(self, node, b, ctx, in_companion=False):
        mods = modifier_words(self.src, node)
        anns = annotations_of(self.src, node)
        name_node = node.child_by_field_name("name")
        name = esc(self.t(name_node))
        tp = self.type_params(node.child_by_field_name("type_parameters"))
        ps = self.params(node.child_by_field_name("parameters"))
        ret = self._ty(node.child_by_field_name("type"))
        body = node.child_by_field_name("body")
        is_native = "native" in mods
        is_abstract = body is None and not is_native

        raw_name = self.t(name_node)
        nparams = len(self.real(node.child_by_field_name("parameters").named_children)) \
            if node.child_by_field_name("parameters") is not None else 0
        override = "Override" in anns
        # Java lets you omit @Override; Kotlin does not let you omit `override`.
        if not override and ctx.kind in ("anon", "class", "enum") \
                and "static" not in mods and "private" not in mods:
            for sup in ctx.supertypes:
                if raw_name in self.idx.inherited_members(sup.rstrip("()")):
                    override = True
                    break
        if not override and ctx.kind in ("anon", "class", "enum"):
            if (raw_name, nparams) in (("toString", 0), ("hashCode", 0),
                                       ("equals", 1)):
                override = True
        if raw_name == "equals" and nparams == 1:
            ps = "other: Any?"
        kw = []
        vis = self.visibility(mods, ctx).strip()
        if vis and not override:
            kw.append(vis)
        if override:
            kw.append("override")
        elif is_abstract and ctx.kind in ("class", "enum"):
            kw.append("abstract")
        elif (ctx.kind in ("class", "enum") and not in_companion and "final" not in mods
              and "private" not in mods and not ctx.final):
            kw.append("open")
        head = " ".join(kw)
        head = (head + " " if head else "") + "fun " + (tp + " " if tp else "") + name
        head += "(" + ps + ")"
        if ret != "Unit":
            head += ": " + ret
        if is_abstract:
            b.w(head)
            return
        if is_native:
            b.w(head + " {")
            b.push()
            b.w('TODO("native")')
            b.pop()
            b.w("}")
            return
        b.w(head + " {")
        b.push()
        self.ret_stack.append(None)
        self.block_stmts(body, b)
        self.ret_stack.pop()
        b.pop()
        b.w("}")

    def emit_field(self, node, b, ctx, in_companion=False):
        mods = modifier_words(self.src, node)
        base = node.child_by_field_name("type")
        const_ok = node.type == "constant_declaration" or "static" in mods
        for d in node.named_children:
            if d.type != "variable_declarator":
                continue
            name = esc(self.t(d.child_by_field_name("name")))
            dims = None
            for c in d.named_children:
                if c.type == "dimensions":
                    dims = c
            ty = self._ty(base)
            if dims is not None:
                ty = self.wrap_array(ty, self.t(dims).count("["))
            val = d.child_by_field_name("value")
            kw = "val" if ("final" in mods or node.type == "constant_declaration") else "var"
            if val is None:
                rhs = "TODO()"
                kw = "var"          # assigned in a constructor we cannot see into
            elif val.type == "array_initializer":
                elem = self._ty(base)
                nd = 1
                if base.type == "array_type":
                    elem = self._ty(base.child_by_field_name("element"))
                    nd = max(1, self.t(base.child_by_field_name("dimensions")).count("["))
                rhs = self.array_init(val, elem, nd)
            else:
                rhs = self.e(val)
                if self.nullable_init(val):
                    ty += "?"
            vis = self.visibility(mods, ctx)
            lines = rhs.split("\n")
            b.w("%s%s %s: %s = %s" % (vis, kw, name, ty, lines[0]))
            for ln in lines[1:]:
                b.w(ln)

    # ------------------------------------------------------------------ body

    def emit_body(self, body, b, ctx, skip_ctors=False):
        if body is None:
            return
        self.cls_stack.append(ctx.name)
        try:
            self._emit_body(body, b, ctx, skip_ctors)
        finally:
            self.cls_stack.pop()

    def _emit_body(self, body, b, ctx, skip_ctors=False):
        inst = Buf()
        inst.ind = b.ind
        static = Buf()
        static.ind = b.ind + 1
        inits = Buf()
        inits.ind = b.ind + 1
        ctors = [c for c in body.named_children
                 if c.type == "constructor_declaration"]
        comp_ctx = ClassCtx(name=ctx.name, kind="companion", depth=ctx.depth,
                            in_companion=True)
        supers = ctx.supertypes
        for c in body.named_children:
            t = c.type
            mods = modifier_words(self.src, c)
            is_static = "static" in mods or ctx.kind == "interface"
            if t in ("line_comment", "block_comment"):
                self.emit_comment(c, inst)
            elif t in ("field_declaration", "constant_declaration"):
                if "static" in mods or t == "constant_declaration":
                    self.emit_field(c, static, comp_ctx, in_companion=True)
                else:
                    self.emit_field(c, inst, ctx)
            elif t == "method_declaration":
                if "static" in mods:
                    self.emit_method(c, static, comp_ctx, in_companion=True)
                else:
                    self.emit_method(c, inst, ctx)
            elif t == "constructor_declaration":
                if not skip_ctors:
                    self.emit_ctor(c, inst, ctx, supers)
            elif t in ("class_declaration", "interface_declaration",
                       "enum_declaration", "annotation_type_declaration"):
                self.emit_type(c, inst, ctx)
            elif t == "static_initializer":
                sub = Buf()
                sub.ind = static.ind + 1
                for k in c.named_children:
                    if k.type == "block":
                        self.block_stmts(k, sub)
                static.w("init {")
                static.lines.extend(sub.lines)
                static.w("}")
            elif t == "block":
                sub = Buf()
                sub.ind = inits.ind + 1
                self.block_stmts(c, sub)
                inits.w("init {")
                inits.lines.extend(sub.lines)
                inits.w("}")
            elif t == "enum_body_declarations":
                self.emit_body(c, b, ctx, skip_ctors)
        b.lines.extend(inst.lines)
        b.lines.extend(inits.lines)
        if static.lines:
            b.w("companion object {")
            b.lines.extend(static.lines)
            b.w("}")


class ClassCtx(object):
    def __init__(self, name, kind, supertypes=None, final=False, depth=0,
                 in_companion=False):
        self.name = name
        self.kind = kind            # class | interface | enum | anon | companion
        self.supertypes = supertypes or []
        self.final = final
        self.depth = depth
        self.in_companion = in_companion


# --------------------------------------------------------------------------
# file level

def convert_file(path, src, tree, idx, rel):
    root = tree.root_node
    package = ""
    imports = []
    for c in root.named_children:
        if c.type == "package_declaration":
            package = txt(src, c).replace("package", "", 1).strip().rstrip(";").strip()
        elif c.type == "import_declaration":
            raw = txt(src, c).strip().rstrip(";")
            raw = raw.replace("import", "", 1).strip()
            static = raw.startswith("static ")
            if static:
                raw = raw[len("static "):].strip()
            if any(raw.startswith(p) for p in DROP_IMPORT_PREFIXES):
                continue
            imports.append(("import " + raw, static))

    unit = Unit(path, rel, package, "", src, tree)
    conv = Converter(src, path, idx, unit)
    b = Buf()
    b.w("// Generated by kn-run/j2k.py from")
    b.w("//   %s" % rel + ".java")
    b.w("// Do not edit: regenerate instead.  See kn-run/README.md.")
    b.w()
    if package:
        b.w("package %s" % package)
        b.w()
    for imp, static in imports:
        b.w(imp)
        unit.imports.append(imp)
    if imports:
        b.w()
    top = ClassCtx(name="<file>", kind="file", depth=0)
    for c in root.named_children:
        if c.type in ("class_declaration", "interface_declaration",
                      "enum_declaration", "annotation_type_declaration"):
            conv.emit_type(c, b, top, force_static=True)
            b.w()
        elif c.type in ("line_comment", "block_comment"):
            continue
    unit.kotlin = b.text()
    unit.meta["static_imports"] = sum(1 for _, s in imports if s)
    return unit


# --------------------------------------------------------------------------
# rules

def load_rules(only=None):
    """Discover kn-run/rules/*.py post-passes.  Order: module ORDER, then name."""
    import rules as rules_pkg
    found = []
    for mod in pkgutil.iter_modules(rules_pkg.__path__):
        if mod.name.startswith("_"):
            continue
        if only and mod.name not in only:
            continue
        m = importlib.import_module("rules." + mod.name)
        if not hasattr(m, "transform"):
            continue
        found.append((getattr(m, "ORDER", 500), mod.name, m))
    found.sort(key=lambda x: (x[0], x[1]))
    return found


# --------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--only", default=None,
                    help="substring filter on the java path (for iterating)")
    ap.add_argument("--rules", default=None,
                    help="comma-separated rule module names; default is all")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--no-clean", action="store_true")
    args = ap.parse_args()

    sys.path.insert(0, HERE)
    parser = Parser(JAVA)

    files = []
    for d, _, fs in os.walk(args.src):
        for f in sorted(fs):
            if f.endswith(".java"):
                p = os.path.join(d, f)
                if args.only and args.only not in p:
                    continue
                files.append(p)
    files.sort()

    idx = Index()
    parsed = []
    parse_fail = []
    for p in files:
        try:
            src = open(p, "rb").read()
            tree = parser.parse(src)
            if tree.root_node.has_error:
                parse_fail.append((p, "tree-sitter ERROR node"))
                continue
            parsed.append((p, src, tree))
            index_file(idx, src, tree)
        except Exception as ex:                      # noqa: BLE001
            parse_fail.append((p, repr(ex)))

    rule_mods = load_rules(set(args.rules.split(",")) if args.rules else None)
    ctx = {"index": idx, "src_root": args.src, "out_root": args.out,
           "stats": Counter()}

    if not args.no_clean and os.path.isdir(args.out):
        for d, _, fs in os.walk(args.out):
            for f in fs:
                if f.endswith(".kt") and "/stubs/" not in os.path.join(d, f):
                    os.remove(os.path.join(d, f))

    units = []
    convert_fail = []
    for p, src, tree in parsed:
        rel = os.path.relpath(p, args.src)[:-len(".java")]
        try:
            unit = convert_file(p, src, tree, idx, rel)
        except Exception:                            # noqa: BLE001
            convert_fail.append((p, traceback.format_exc().strip().split("\n")[-1]))
            continue
        for _, nm, m in rule_mods:
            try:
                m.transform(unit, ctx)
            except Exception:                        # noqa: BLE001
                unit.notes.append("rule %s failed: %s" % (
                    nm, traceback.format_exc().strip().split("\n")[-1]))
        units.append(unit)

    written = 0
    lines_out = 0
    for u in units:
        dest = os.path.join(args.out, u.out_rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w") as fh:
            fh.write(u.kotlin.rstrip() + "\n")
        written += 1
        lines_out += u.kotlin.count("\n") + 1

    # manual/ overlay: quarantine for files the codemod cannot handle
    overlaid = []
    if os.path.isdir(MANUAL):
        for d, _, fs in os.walk(MANUAL):
            for f in fs:
                if not f.endswith(".kt"):
                    continue
                srcf = os.path.join(d, f)
                rel = os.path.relpath(srcf, MANUAL)
                dest = os.path.join(args.out, rel)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                shutil.copyfile(srcf, dest)
                overlaid.append(rel)

    notes = Counter()
    for u in units:
        for n in u.notes:
            notes[re.sub(r" near '.*", "", n)] += 1

    report = {
        "javaFiles": len(files),
        "parsed": len(parsed),
        "parseFailures": [{"file": p, "why": w} for p, w in parse_fail],
        "convertFailures": [{"file": p, "why": w} for p, w in convert_fail],
        "kotlinFiles": written,
        "kotlinLines": lines_out,
        "manualOverlay": overlaid,
        "rules": [nm for _, nm, _ in rule_mods],
        "notes": notes.most_common(),
    }
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print("java files      : %d" % report["javaFiles"])
        print("parsed          : %d" % report["parsed"])
        print("kotlin written  : %d (%d lines)" % (written, lines_out))
        print("rules applied   : %s" % ", ".join(report["rules"]) or "-")
        print("manual overlay  : %d" % len(overlaid))
        for p, w in parse_fail:
            print("PARSE FAIL  %s  %s" % (p, w))
        for p, w in convert_fail:
            print("CONVERT FAIL %s  %s" % (p, w))
        if notes:
            print("notes:")
            for n, c in notes.most_common(20):
                print("  %5d  %s" % (c, n))
    return 0 if not convert_fail and not parse_fail else 1


if __name__ == "__main__":
    sys.exit(main())
