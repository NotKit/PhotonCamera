"""A type-scope initialiser that reaches through `::class.java` cannot run.

WHY.  Kotlin/Native has no `java.lang.Class`.  j2k emits Java's `Foo.class` as
`Foo::class.java`, and corpus/stubs supplies the bridge it needs as

    val <T : Any> kotlin.reflect.KClass<T>.java: java.lang.Class<T> get() = TODO()

so every such expression throws when it is evaluated.  Inside a function body
that is the honest answer and this rule leaves it alone.  At TYPE SCOPE it is
not: Kotlin/Native runs those initialisers in the file's `$init_global`, so one
of them fails the whole file and every later JNI call into any member of that
file returns `FileFailedToInitializeException`.  That is r995's mechanism
again, arriving through an expression rather than through a `= TODO()`.

WHAT THIS DOES.  Turns the initialiser into a `by FiatDefer.defer(id) { ... }`
delegate: the same expression, evaluated on first READ instead of at state-init
time.  No value is invented, nothing is answered, and reading the property
still throws exactly what it threw before.  The file initialises.

The one site in the corpus today is GeckoThread.kt's

    val clsLoader: ClassLoader? = GeckoThread::class.java!!.getClassLoader()!!

which the lane's ground rules already name as a trap, and which the bridge
serves out of overrides.tsv rather than out of Kotlin -- so deferring it costs
no JNI answer.

SCOPE.  `val` only: a `Lazy` delegate has no setter.  A `var` that matched
would be reported and left alone.  Single-line initialisers only.

ORDER 998: after r99_typed_repairs (990), which may itself rewrite these lines,
and after r995 (995), which is the `= TODO()` pattern on other lines.

Rewrites are listed in kn-poc/fiat-deferred.tsv.  This module writes its OWN
map rather than appending to fiat-map.tsv, because r995 truncates that file
from its own atexit handler and atexit runs last-registered-first.
"""
import atexit
import os
import re
import sys
from collections import Counter

try:
    from rules import r995_fiat_state_init as _r995
except ImportError:                                      # standalone
    import r995_fiat_state_init as _r995

ORDER = 998
NAME = "fiat-reflective-init"
DESCRIPTION = ("type-scope initialisers that go through the ::class.java shim "
               "-> a deferred delegate, so the throw fails the member not the file")

LANE = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
MAP = os.path.join(LANE, "fiat-deferred.tsv")

PROP = re.compile(
    r'^(?P<ind>[ \t]*)'
    r'(?P<mods>(?:(?:public|private|protected|internal|open|final|override|'
    r'@[\w.]+(?:\([^)]*\))?)[ \t]+)*)'
    r'(?P<kw>val|var)[ \t]+(?P<name>`?[A-Za-z_$][\w$]*`?)[ \t]*:[ \t]*'
    r'(?P<type>[^=]+?)[ \t]*=[ \t]*(?P<init>.+?)[ \t]*$')

# The reflection shim, in the two spellings j2k emits for it.
REFLECT = re.compile(r'(?<![\w.])(?:\w[\w.$<>]*::class[ \t]*\.[ \t]*java\b'
                     r'|knArrayClass[ \t]*[<(])')

FQ = "atl.fiat.FiatDefer"

_ROWS = []
_STATS = Counter()


def rewrite_text(text, rel):
    lines = text.split("\n")
    scopes = _r995._scopes(lines)
    rows = []
    for i, line in enumerate(lines):
        m = PROP.match(line)
        if not m:
            continue
        init = m.group("init")
        if not REFLECT.search(init):
            continue
        stack = scopes[i]
        if stack and stack[-1][0] != "type":
            _STATS["skipped-local"] += 1          # a body may throw; that is honest
            continue
        ty = m.group("type").strip()
        if _r995._GETTER.search(ty):
            _STATS["skipped-getter"] += 1         # already per-member
            continue
        if m.group("kw") != "val":
            _STATS["skipped-var-no-lazy-setter"] += 1
            continue
        name = m.group("name").strip("`")
        ident = "%s:%d:%s" % (rel, i + 1, name)
        lines[i] = '%s%sval %s: %s by %s.defer<%s>("%s") { %s }' % (
            m.group("ind"), m.group("mods"), m.group("name"), ty, FQ, ty,
            ident, init)
        rows.append(dict(id=ident, file=rel, line=i + 1, name=name, type=ty,
                         scope="global" if ((not stack) or stack[-1][1])
                               else "instance", expr=init))
        _STATS["deferred"] += 1
    return "\n".join(lines), rows


def transform(unit, ctx):
    rel = unit.out_rel if hasattr(unit, "out_rel") else unit.rel + ".kt"
    new, rows = rewrite_text(unit.kotlin, rel)
    if rows:
        unit.kotlin = new
        _ROWS.extend(rows)
        ctx["stats"]["fiat-reflective-init"] += len(rows)


@atexit.register
def _flush():
    if not _ROWS:
        return
    with open(MAP, "w") as fh:
        fh.write("# kn-poc/fiat-deferred.tsv -- rules/r998_fiat_reflective_init.py\n")
        fh.write("# A type-scope initialiser moved to first-READ evaluation. "
                 "NO value is installed and nothing is\n"
                 "# answered: the read still throws what the expression threw. "
                 "Only the blast radius changes,\n"
                 "# from the whole file's state group to this one member.  The "
                 "run log says which happened:\n"
                 "#   defer-expr <id>   delegate created at state-init time\n"
                 "#   eval <id> ok      the expression ran and produced a value\n"
                 "#   eval <id> threw:E the expression ran and threw, at read time\n")
        fh.write("\t".join(("id", "file", "line", "name", "type", "scope",
                            "expr")) + "\n")
        for r in _ROWS:
            fh.write("\t".join(str(r[k]) for k in
                               ("id", "file", "line", "name", "type", "scope",
                                "expr")) + "\n")
    sys.stderr.write("[fiat-defer] %d initialisers deferred -> %s\n"
                     % (len(_ROWS), MAP))
    for k, v in sorted(_STATS.items()):
        sys.stderr.write("[fiat-defer]   %-30s %d\n" % (k, v))
