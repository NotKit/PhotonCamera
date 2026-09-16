"""Let a converted file's state initialise without detonating the file.

WHY.  Kotlin/Native initialises a file's global state on first touch.  One
`= TODO()` in a companion object therefore does not fail one member, it fails
the whole file: every later call into any member of that file throws
`FileFailedToInitializeException`.  kn-jni measured that -- 237 of 304 JNI
calls, 230 of them behind `GeckoAppShell.kt:339 var sAppNotes: String? =
TODO()`.

WHAT THIS DOES.  Rewrites *property initialisers* that are exactly `TODO()`,
and only those at type-body scope (a class/object/companion/file member, never
a local `var` inside a function body -- see SCOPE below).  Function bodies are
left alone: a `fun f() { TODO() }` throws when it is called, which is the
honest answer and is not what kills the file.

THREE ARMS, chosen by the declared type, preferring the one that invents the
least:

  primitive       `var x: Int = TODO()`     -> `= Fiat.i("id")`      = 0
                  Java's own value for a field with no initialiser IS 0/false.
                  For those rows this restores Java semantics rather than
                  inventing one; the map's `origin` column says which is which.
  nullable ref    `var x: Foo? = TODO()`    -> `= Fiat.n("id")`      = null
                  Same argument: Java's value for such a field is null.
  non-null ref    `var x: Foo = TODO()`     -> `by Fiat.unset<Foo>("id")`
                  There is no honest default for a non-null reference, so this
                  one does NOT invent a value.  The file initialises; a *read*
                  still throws NotImplementedError.  That is the whole point:
                  the file stops being collateral damage, the unimplemented
                  member stays unimplemented.

MARKING -- the part that decides whether P1's number can be trusted.  Every
rewritten declaration:
  * is listed in kn-poc/fiat-map.tsv (file, line, class, name, type, arm,
    value, origin), written by this module at exit;
  * calls into `atl.fiat.Fiat` at run time, which appends one line per event
    to $ATL_KNPOC_FIAT_OUT.  So a census bucket that moved can be told apart
    from one that moved *because a value was installed by fiat*: cross the
    served-real members against the state groups whose fiat initialisers
    actually ran.

ORDER.  995: dead last, after r99_typed_repairs (990), which itself *creates*
`= TODO()` property initialisers when it stubs a member whose type it cannot
work out.  Running before it would miss those and would record stale line
numbers.

STANDALONE.  j2k.py's rules only see files it converts from Java.  The
android.* stub tree is written by j2k/stubgen/*.py and never passes through a
rule, so this module is also runnable over an emitted tree:

    python r995_fiat_state_init.py --tree corpus/stubs --origin stub
    python r995_fiat_state_init.py --tree corpus --dry-run     # audit

It is idempotent: a rewritten line no longer matches.
"""
import argparse
import atexit
import os
import re
import sys
from collections import Counter

ORDER = 995
NAME = "fiat-state-init"
DESCRIPTION = ("property initialisers of TODO() -> a marked default, so one "
               "TODO() does not fail the whole file's state group")

LANE = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
MAP = os.path.join(LANE, "fiat-map.tsv")

# The declaration this rule rewrites.  Types never contain '='.
PROP = re.compile(
    r'^(?P<ind>[ \t]*)'
    r'(?P<mods>(?:(?:public|private|protected|internal|open|final|override|'
    r'abstract|external|@[\w.]+(?:\([^)]*\))?)[ \t]+)*)'
    r'(?P<kw>val|var)[ \t]+(?P<name>`?[A-Za-z_$][\w$]*`?)[ \t]*:[ \t]*'
    r'(?P<type>[^=]+?)[ \t]*=[ \t]*TODO\(\)[ \t]*$')

# Kotlin primitives: Java's value for a field with no initialiser.
PRIM = {
    "Boolean": ("z", "false"), "Int": ("i", "0"), "Long": ("j", "0L"),
    "Float": ("f", "0.0f"), "Double": ("d", "0.0"), "Short": ("s", "0"),
    "Byte": ("b", "0"), "Char": ("c", "'\\u0000'"),
}

FQ = "atl.fiat.Fiat"

_GETTER = re.compile(r'\b(get|set)\s*\(')
_TYPE_OPEN = re.compile(r'(^|[^\w.])(class|object|interface)\b')
_ROWS = []          # every rewrite, this process
_STATS = Counter()


# ---------------------------------------------------------------- scoping
def _strip_noise(line):
    """Brace counting has to ignore braces in strings and comments."""
    line = re.sub(r'"""(?:.|\n)*?"""', '""', line)
    line = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
    line = re.sub(r"'(?:\\.|[^'\\])*'", "''", line)
    line = re.sub(r'//.*$', '', line)
    return line


def _scopes(lines):
    """-> per-line list of the open block stack, innermost last.

    Each entry is 'type' (class/object/interface/companion body) or 'code'
    (function body, init block, getter, lambda).  A property at type scope is
    state -- global if its innermost type block is an object/companion or the
    file itself, per-instance if it is a class.  A `var` at 'code' scope is a
    local variable and this rule must not touch it: defaulting a local silently
    substitutes a value inside a body that then returns, which is exactly the
    failure mode the ground rules call out.
    """
    out, stack = [], []
    for raw in lines:
        out.append(list(stack))
        line = _strip_noise(raw)
        opens = line.count("{")
        closes = line.count("}")
        if opens:
            kind = "type" if _TYPE_OPEN.search(line.split("{")[0]) else "code"
            obj = ("object" in line.split("{")[0].split()) or \
                  ("companion" in line.split("{")[0].split())
            for _ in range(opens):
                stack.append((kind, obj))
        for _ in range(min(closes, len(stack))):
            stack.pop()
    return out


# ---------------------------------------------------------------- the rewrite
def rewrite_text(text, rel, java_noinit=None, java_init=None, origin="corpus"):
    """-> (new text, [row dicts]).  Idempotent."""
    lines = text.split("\n")
    scopes = _scopes(lines)
    rows = []
    for i, line in enumerate(lines):
        m = PROP.match(line)
        if not m:
            continue
        stack = scopes[i]
        if stack and stack[-1][0] != "type":
            _STATS["skipped-local"] += 1
            continue
        glob = (not stack) or stack[-1][1]
        ty = m.group("type").strip()
        # `val x: Int get() = TODO()` is a computed getter, not an initialiser:
        # it does not run at state-init time and it is already scoped to the
        # member. The regex sees `Int get()` as the type; leave it alone.
        if _GETTER.search(ty):
            _STATS["skipped-getter"] += 1
            continue
        name = m.group("name").strip("`")
        ident = "%s:%d:%s" % (rel, i + 1, name)
        if ty in PRIM:
            fn, val = PRIM[ty]
            arm, value = "prim", val
            new = "%s%s%s %s: %s = %s.%s(\"%s\")" % (
                m.group("ind"), m.group("mods"), m.group("kw"),
                m.group("name"), ty, FQ, fn, ident)
        elif ty.endswith("?"):
            arm, value = "null", "null"
            new = "%s%s%s %s: %s = %s.n(\"%s\")" % (
                m.group("ind"), m.group("mods"), m.group("kw"),
                m.group("name"), ty, FQ, ident)
        else:
            # No value invented.  The delegate exists at init time; reading it
            # throws NotImplementedError, the same class of failure as before,
            # but scoped to the member instead of the file.
            arm, value = "deferred-throw", "-"
            fn = "unset" if m.group("kw") == "var" else "unsetVal"
            new = "%s%s%s %s: %s by %s.%s<%s>(\"%s\")" % (
                m.group("ind"), m.group("mods"), m.group("kw"),
                m.group("name"), ty, FQ, fn, ty, ident)
        lines[i] = new
        # origin: did Java itself have a value here?  j2k emits `= TODO()` for
        # a field with NO initialiser (j2k.py emit_field, `val is None`), and
        # r99_typed_repairs re-stubs a member whose initialiser it could not
        # type.  The first is Java's own default; the second is a discarded
        # value and the default IS made up.
        if java_noinit is None:
            org = origin
        elif name in java_noinit:
            org = "java-default"
        elif java_init and name in java_init:
            org = "java-value-discarded"
        else:
            org = "unknown"
        rows.append(dict(id=ident, file=rel, line=i + 1, name=name, type=ty,
                         kw=m.group("kw"), arm=arm, value=value,
                         scope="global" if glob else "instance", origin=org))
        _STATS["rewrote-" + arm] += 1
        _STATS["origin-" + org] += 1
    return "\n".join(lines), rows


# ---------------------------------------------------------------- java side
def _java_field_names(tree, src):
    """(names with no initialiser, names with one), from the Java AST."""
    noinit, init = set(), set()
    stack = [tree.root_node]
    while stack:
        n = stack.pop()
        if n.type in ("field_declaration", "constant_declaration"):
            for d in n.named_children:
                if d.type != "variable_declarator":
                    continue
                nm = d.child_by_field_name("name")
                if nm is None:
                    continue
                who = src[nm.start_byte:nm.end_byte].decode("utf8", "replace")
                (init if d.child_by_field_name("value") is not None
                 else noinit).add(who)
        stack.extend(n.named_children)
    return noinit, init


# ---------------------------------------------------------------- rule API
def transform(unit, ctx):
    try:
        noinit, init = _java_field_names(unit.tree, unit.source)
    except Exception:                                    # noqa: BLE001
        noinit, init = None, None
    rel = unit.out_rel if hasattr(unit, "out_rel") else unit.rel + ".kt"
    new, rows = rewrite_text(unit.kotlin, rel, noinit, init)
    if rows:
        unit.kotlin = new
        _ROWS.extend(rows)
        ctx["stats"]["fiat-state-init"] += len(rows)


# ---------------------------------------------------------------- map file
def _write_map(rows, path, mode="w"):
    new = mode == "w" or not os.path.exists(path)
    with open(path, mode) as fh:
        if new:
            fh.write("# kn-poc/fiat-map.tsv -- every property initialiser this "
                     "lane replaced.\n")
            fh.write("# arm: prim = Java's own default for a field with no "
                     "initialiser; null = same, for a reference;\n"
                     "#      deferred-throw = no value invented, the read "
                     "throws NotImplementedError instead of the file.\n")
            fh.write("# origin: java-default = Java had no initialiser here, "
                     "so the value is Java's;\n"
                     "#         java-value-discarded = Java HAD one and "
                     "r99_typed_repairs threw it away -- the value IS made up;\n"
                     "#         stub = a synthetic android.* stub, never had a "
                     "value.  unknown = name not found in the Java.\n")
            fh.write("\t".join(("id", "file", "line", "name", "type", "kw",
                                "arm", "value", "scope", "origin")) + "\n")
        for r in rows:
            fh.write("\t".join(str(r[k]) for k in
                               ("id", "file", "line", "name", "type", "kw",
                                "arm", "value", "scope", "origin")) + "\n")


@atexit.register
def _flush():
    if _ROWS:
        _write_map(_ROWS, MAP, "w")
        sys.stderr.write("[fiat] %d property initialisers rewritten -> %s\n"
                         % (len(_ROWS), MAP))
        for k, v in sorted(_STATS.items()):
            sys.stderr.write("[fiat]   %-24s %d\n" % (k, v))


# ---------------------------------------------------------------- standalone
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tree", required=True)
    ap.add_argument("--origin", default="stub")
    ap.add_argument("--append-map", default=MAP)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    rows, touched = [], 0
    for d, _, fs in os.walk(a.tree):
        for f in sorted(fs):
            if not f.endswith(".kt"):
                continue
            p = os.path.join(d, f)
            rel = os.path.relpath(p, a.tree)
            text = open(p).read()
            new, rs = rewrite_text(text, rel, origin=a.origin)
            if rs:
                touched += 1
                rows.extend(rs)
                if not a.dry_run:
                    open(p, "w").write(new)
    print("[fiat] %s: %d files, %d property initialisers%s"
          % (a.tree, touched, len(rows), " (dry run)" if a.dry_run else ""))
    for k, v in sorted(_STATS.items()):
        print("[fiat]   %-24s %d" % (k, v))
    if rows and not a.dry_run:
        _write_map(rows, a.append_map, "a")
    _ROWS[:] = []            # the atexit flush is for the rule path only
    return 0


if __name__ == "__main__":
    sys.exit(main())
