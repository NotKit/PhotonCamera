"""A constructor reached from state-init time must not throw by construction.

WHY.  `val INSTANCE: GeckoThread? = GeckoThread()` is a type-scope initialiser,
so Kotlin/Native runs that constructor inside the file's `$init_global`.  If it
throws, the whole file's state group fails and every later JNI call into any
member of that file comes back `FileFailedToInitializeException` -- the same
blast radius r995_fiat_state_init was written for, reached through a
constructor rather than through a `= TODO()`.

GeckoThread.kt is the case that named this round, and it throws for TWO reasons
that r995 cannot see, both of them on the constructor:

    constructor() : super(null!!, null!!, "Gecko", 8 * 1024 * 1024) {
        TODO()
    }

ARM 1 -- the literal `null!!`.  Java's `super(null, null, ...)` passes null.  `!!` applied to the literal `null` is not a check, it is an
unconditional `ThrowNullPointerException`, emitted with no branch -- which is
why gdb shows ThrowNullPointerException called straight out of
`$init_global` once LLVM has inlined the constructor.  j2k's nullability pass
adds `!!` to every argument and every returned expression; on a literal null
that is always wrong and cannot mean anything else.  Rewriting it to `null`
RESTORES Java semantics; no value is invented.

SCOPE.  Every occurrence of the token in real code (string literals and line
comments excluded), not only the delegation -- 540 of them, 37 in a delegation.
The bucket forced it: GeckoThread's own `setState` is

    fun setState(newState: State?) { checkAndSetState(null!!, newState!!) }

so a delegation-only arm would have unpoisoned the file and left one of the
three calls throwing NullPointerException instead of
FileFailedToInitializeException.  The wide scope is safe to reason about in one
direction: a `null!!` that is reached ALWAYS throws today, so this rule can
only turn a throw into a value that Java itself passed.  It cannot break a path
that works now.  What it can do is fail to compile, where j2k used `null!!`
(type `Nothing`) to satisfy a non-null position; that is a type error, not a
silent change, and the -p library check is what rules it out.

ARM 2 -- a constructor whose Java body was only the delegation.  r99_typed_repairs
(ORDER 990) replaces the body of a member it could not type with `TODO()`;
`stub` rows are defeats and it says so.  For GeckoThread's constructor the Java
body is nothing but the `super(...)` call and two comment lines, so the stub
throws away exactly nothing and adds a throw at state-init time.  This arm
empties such a body again, but ONLY when the Java AST confirms the body held
nothing but the explicit constructor invocation.  Nothing is invented here
either: an empty Kotlin body is what the Java says.

ORDER 994: after r99_typed_repairs (990), whose stub this arm undoes, and
BEFORE r995 (995) -- arm 2 deletes a line, and r995 records the line number of
every initialiser it rewrites into fiat-map.tsv, so it has to see the file
after the deletion or that column goes stale.

Every rewrite is listed in kn-poc/ctor-init-safety.tsv.
"""
import atexit
import os
import re
import sys
from collections import Counter

ORDER = 994
NAME = "ctor-init-safety"
DESCRIPTION = ("constructor delegations: literal `null!!` -> `null`, and "
               "un-stub a body the Java left empty")

LANE = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
MAP = os.path.join(LANE, "ctor-init-safety.tsv")

# `constructor(...) : super(...) {`.  The delegation runs BEFORE the body, so
# it is what a file with no `= TODO()` left in it still dies on.
CTOR = re.compile(
    r'^(?P<ind>[ \t]*)'
    r'(?P<mods>(?:(?:public|private|protected|internal|open|final)[ \t]+)*)'
    r'constructor[ \t]*\((?P<params>.*?)\)[ \t]*'
    r'(?::[ \t]*(?P<kw>super|this)[ \t]*\((?P<args>.*)\)[ \t]*)?'
    r'\{[ \t]*$')

NULLBANG = re.compile(r'(?<![\w.$])null!!')
_STR = re.compile(r'"(?:\\.|[^"\\])*"')

_ROWS = []
_STATS = Counter()


def _blank_strings(s):
    """Same text, string literals blanked -- so a `null!!` inside one is not
    a token.  Offsets are preserved, so spans map back one to one."""
    return _STR.sub(lambda m: '"' + " " * (len(m.group(0)) - 2) + '"', s)


def _blank_code(s):
    """_blank_strings, plus the line comment.  Same length."""
    s = _blank_strings(s)
    k = s.find("//")
    return s if k < 0 else s[:k] + " " * (len(s) - k)


def _top_level_commas(s):
    """Count commas at paren/angle/bracket depth 0, over blanked text."""
    d, n = 0, 0
    for ch in _blank_strings(s):
        if ch in "(<[":
            d += 1
        elif ch in ")>]":
            d -= 1
        elif ch == "," and d == 0:
            n += 1
    return n


def _params_count(params):
    return 0 if not params.strip() else _top_level_commas(params) + 1


# ---------------------------------------------------------------- java side
def _java_eci_only_arities(tree, src):
    """(param count, super|this) keys ARM 2 may empty.

    A key qualifies only when EVERY Java constructor in the file with that
    parameter count and that delegation keyword has a body of nothing but the
    delegation.  The Kotlin side is matched by text, so a key shared with a
    constructor that does real work is ambiguous and is dropped: emptying that
    one would turn `throws NotImplementedError` into a silently half-built
    object, which is exactly the kind of quiet default this lane forbids.
    ContentBlocking.Settings is the case that made this necessary -- it has an
    ECI-only 2-arg constructor and a 2-arg one with an if/else after `super`.
    """
    seen = {}
    stack = [tree.root_node]
    while stack:
        n = stack.pop()
        if n.type == "constructor_declaration":
            body = n.child_by_field_name("body")
            ps = n.child_by_field_name("parameters")
            if body is not None:
                stmts = [c for c in body.named_children
                         if c.type not in ("line_comment", "block_comment")]
                eci = (stmts and
                       stmts[0].type == "explicit_constructor_invocation")
                if eci:
                    kw = "this" if src[stmts[0].start_byte:
                                       stmts[0].start_byte + 4] == b"this" \
                        else "super"
                    k = 0 if ps is None else len(
                        [c for c in ps.named_children
                         if c.type in ("formal_parameter", "spread_parameter",
                                       "receiver_parameter")])
                    seen.setdefault((k, kw), []).append(len(stmts) == 1)
        stack.extend(n.named_children)
    return {key for key, flags in seen.items() if all(flags)}


# ---------------------------------------------------------------- the rewrite
FUN = re.compile(r'^[ \t]*(?:(?:public|private|protected|internal|open|final|'
                 r'override|abstract|external|operator|suspend)[ \t]+)*'
                 r'fun[ \t]+(?:<[^>]*>[ \t]*)?(?P<name>`?[A-Za-z_$][\w$]*`?)'
                 r'[ \t]*\((?P<params>.*)\)[ \t]*[:{]')
IDENT_BEFORE = re.compile(r'(`?[A-Za-z_$][\w$]*`?)[ \t]*$')


def _split_top(s):
    """Split on depth-0 commas.  [] for an empty argument list."""
    if not s.strip():
        return []
    out, d, last = [], 0, 0
    b = _blank_strings(s)
    for k, ch in enumerate(b):
        if ch in "(<[{":
            d += 1
        elif ch in ")>]}":
            d -= 1
        elif ch == "," and d == 0:
            out.append(s[last:k])
            last = k + 1
    out.append(s[last:])
    return out


def _nullable_params(lines):
    """(function name, arity) -> per-parameter "is it declared nullable".

    Read out of THIS FILE's own Kotlin, so no type checker and no index is
    needed.  A key with two conflicting declarations is dropped: an overload
    the rule cannot tell apart is one it must not touch.
    """
    out, bad = {}, set()
    for line in lines:
        m = FUN.match(line)
        if not m:
            continue
        ps = _split_top(m.group("params"))
        flags = []
        for p in ps:
            t = p.split(":", 1)[1].strip() if ":" in p else ""
            t = t.split("=", 1)[0].strip()
            flags.append(t.endswith("?"))
        key = (m.group("name").strip("`"), len(ps))
        if key in out and out[key] != flags:
            bad.add(key)
        out[key] = flags
    for k in bad:
        out.pop(k, None)
    return out


def _enclosing_call(blanked, at):
    """The call `null!!` at offset `at` is an argument of.

    -> (name, args_start, args_end, arg index) or None.  `blanked` has string
    literals and the line comment blanked, so depth counting is over code.
    """
    d, i = 0, at - 1
    while i >= 0:
        c = blanked[i]
        if c in ")>]}":
            d += 1
        elif c in "<[{":
            d -= 1
        elif c == "(":
            if d == 0:
                break
            d -= 1
        i -= 1
    if i < 0:
        return None
    mm = IDENT_BEFORE.search(blanked[:i])
    if mm is None:
        return None
    # UNQUALIFIED calls only.  `mMap!!.put(key!!, null!!)` shares a name and an
    # arity with this file's own `fun put(key: String?, value: Any?)`, but the
    # callee is MutableMap.put, whose value parameter is NOT nullable.  A
    # receiver is the one thing that makes a same-file name lookup wrong.
    if blanked[:mm.start(1)].rstrip().endswith((".", "?")):
        return None
    d, j = 0, i + 1
    while j < len(blanked):
        c = blanked[j]
        if c in "(<[{":
            d += 1
        elif c == ")" and d == 0:
            break
        elif c in ")>]}":
            d -= 1
        j += 1
    if j >= len(blanked):
        return None
    idx, off = 0, i + 1
    for k, a in enumerate(_split_top(blanked[i + 1:j])):
        if off <= at < off + len(a) + 1:
            idx = k
            break
        off += len(a) + 1
    return mm.group(1).strip("`"), i + 1, j, idx


def _arm1_null_bang(lines, rel, rows):
    """`null!!` -> `null`, where the position provably accepts null.

    Two positions, both decided without a type checker:
      deleg  an argument of `: super(...)` / `: this(...)`.  Java passed null
             to that constructor, and a constructor is the thing that runs at
             state-init time, which is the whole point of this module.
      arg    an argument of an UNQUALIFIED call to a `fun` DECLARED IN THIS
             SAME FILE whose matching parameter is spelled `T?`.  j2k emits every reference
             parameter of a converted method as nullable, so this is the case
             where `null` is exactly as well typed as `null!!` was.
    Everywhere else the token is left alone: `return null!!` and calls into the
    stub surface are positions where j2k used `null!!` (type `Nothing`) to
    satisfy a NON-null type, and `null` there is a compile error.  Those
    members stay broken; see the report.
    """
    funs = _nullable_params(lines)
    for i, line in enumerate(lines):
        blanked = _blank_code(line)
        spans = [mm.span() for mm in NULLBANG.finditer(blanked)]
        if not spans:
            continue
        deleg = CTOR.match(line)
        dspan = None
        if deleg is not None and deleg.group("args") is not None:
            a = deleg.start("args")
            dspan = (a, a + len(deleg.group("args")))
        keep, n_d, n_a = [], 0, 0
        for a, b in spans:
            if dspan and dspan[0] <= a < dspan[1]:
                keep.append((a, b))
                n_d += 1
                continue
            call = _enclosing_call(blanked, a)
            if call is None:
                _STATS["null-bang-left-no-call"] += 1
                continue
            name, s0, s1, idx = call
            flags = funs.get((name, len(_split_top(blanked[s0:s1]))))
            if flags and idx < len(flags) and flags[idx]:
                keep.append((a, b))
                n_a += 1
            else:
                _STATS["null-bang-left-not-provably-nullable"] += 1
        if not keep:
            continue
        new = line
        for a, b in reversed(keep):
            new = new[:a] + "null" + new[b:]
        lines[i] = new
        rows.append(dict(file=rel, line=i + 1, arm="null-bang",
                         detail="%d in a delegation, %d in a same-file "
                                "nullable parameter" % (n_d, n_a)))
        _STATS["null-bang-deleg"] += n_d
        _STATS["null-bang-samefile-arg"] += n_a


def rewrite_text(text, rel, eci_arities):
    lines = text.split("\n")
    rows = []
    _arm1_null_bang(lines, rel, rows)
    for i, line in enumerate(lines):
        m = CTOR.match(line)
        if not m:
            continue
        # ---- ARM 2: a body r99 stubbed that the Java left empty
        if m.group("kw") and i + 2 < len(lines) \
                and lines[i + 1].strip() == "TODO()" \
                and lines[i + 2].strip() == "}":
            k = _params_count(m.group("params"))
            if (k, m.group("kw")) in eci_arities:
                del lines[i + 1]
                rows.append(dict(file=rel, line=i + 1, arm="unstub-empty-ctor",
                                 detail="%d-param ctor; every Java ctor with "
                                        "that shape has a body of only the "
                                        "%s() call" % (k, m.group("kw"))))
                _STATS["unstub-empty-ctor"] += 1
            else:
                _STATS["kept-stub-java-body-not-only-the-delegation"] += 1
    return "\n".join(lines), rows


# ---------------------------------------------------------------- rule API
def transform(unit, ctx):
    try:
        arities = _java_eci_only_arities(unit.tree, unit.source)
    except Exception:                                    # noqa: BLE001
        arities = set()
    rel = unit.out_rel if hasattr(unit, "out_rel") else unit.rel + ".kt"
    new, rows = rewrite_text(unit.kotlin, rel, arities)
    if rows:
        unit.kotlin = new
        _ROWS.extend(rows)
        ctx["stats"]["ctor-init-safety"] += len(rows)


@atexit.register
def _flush():
    if not _ROWS:
        return
    with open(MAP, "w") as fh:
        fh.write("# kn-poc/ctor-init-safety.tsv -- rules/r996_ctor_init_safety.py\n")
        fh.write("# null-bang         literal `null!!` -> `null`.  Java passed null; "
                 "`!!` on a literal null is an unconditional\n"
                 "#                   throw, not a check.  Java semantics "
                 "restored, nothing invented.\n")
        fh.write("# unstub-empty-ctor r99_typed_repairs' TODO() body removed, "
                 "where the Java body held only the delegation.\n")
        fh.write("\t".join(("file", "line", "arm", "detail")) + "\n")
        for r in _ROWS:
            fh.write("\t".join(str(r[k]) for k in
                               ("file", "line", "arm", "detail")) + "\n")
    sys.stderr.write("[ctor-init] %d rewrites -> %s\n" % (len(_ROWS), MAP))
    for k, v in sorted(_STATS.items()):
        sys.stderr.write("[ctor-init]   %-34s %d\n" % (k, v))
