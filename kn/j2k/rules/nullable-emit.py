"""Nullable-by-default: every declared reference type becomes `T?`, and every
dereference of a value gets `!!`.

The converter emits reference types non-null (README: nullability is out of
scope), but the android/androidx stubs are nullable throughout and the Java
source returns and assigns `null` constantly.  That mismatch is one error
bucket with three faces: `null cannot be a value of a non-null type`,
`'x' overrides nothing` where the only difference from the stub signature is
`Context` vs `Context?`, and nullable-vs-non-null mismatches at initialisers
and arguments.

Rather than infer real nullability (a type-inference problem, not a codemod),
this flips the policy: declarations are nullable, dereferences are asserted.
`!!` on a value that was already non-null is a warning, never an error - so
the asserting pass can be blunt.  The one thing it must not do is put `!!`
after a *type* name, so receivers are classified before they are asserted.

Passes, all pure insertions into the generated text (so offsets stay valid):

  A  declared types      val/var, fun params + return, constructor and enum
                         primary-constructor params  ->  `T?`
  B  dereferences        `x.y`, `x[i]`, `x::y` -> `x!!.y` &c, for every
                         receiver that is a value rather than a type, a
                         package segment, `this`/`super`, or a literal
  C  non-null demands    call arguments, the iterable of a `for (x in e)` and
                         the operand of a single-line `throw` / `return`

Overriding members are the exception to A: they follow the signature of the
stub they override, because the stubs are not uniformly nullable and an
override has to match exactly.

Strings and comments are masked out first; nothing inside them is rewritten.
"""
import os
import re

ORDER = 50
NAME = "nullable-emit"
DESCRIPTION = "declare every reference type nullable, assert (!!) at dereferences"

# Types that must stay non-null: Kotlin primitives have no null and `Unit?`
# / `Nothing?` are nonsense as declared types.
PRIMS = {
    "Int", "Long", "Short", "Byte", "Char", "Float", "Double", "Boolean",
    "Unit", "Nothing",
}

# Roots of fully-qualified names.  `org.mozilla.gecko.Foo.bar()` must not
# become `org!!.mozilla!!...`.
PKG_ROOTS = {
    "android", "androidx", "java", "javax", "org", "com", "kotlin", "kotlinx",
    "dalvik", "sun", "mozilla",
}

# Receivers that are not values, or are values that can never be null.
NO_ASSERT_IDENTS = {
    "this", "super", "null", "true", "false", "return", "break", "continue",
    "else", "is", "as", "in", "when", "if", "try", "catch", "finally",
    "throw", "class", "object", "val", "var", "fun", "Companion", "field",
}

# Signatures inherited from Kotlin's own hierarchy whose return type is
# non-null there; widening ours to `T?` would be an override error.
RETURN_KEEPS_NONNULL = {"toString", "hashCode", "equals", "compareTo"}

# Overrides must match the supertype exactly, and the stubs are NOT uniformly
# nullable (`onLocationChanged(location: Location)` is not).  Widening one of
# those to `Location?` trades an "overrides nothing" for a "does not implement
# abstract member", so overriding members follow the stub signature instead of
# the blanket policy.  Seeded with the Kotlin hierarchy, which has no stub.
BUILTIN_SIGS = {
    ("compareTo", 1): [(("*",), (False,), "Int", False)],
    ("compare", 2): [(("*", "*"), (False, False), "Int", False)],
    ("toString", 0): [((), (), "String", False)],
    ("hashCode", 0): [((), (), "Int", False)],
    ("iterator", 0): [((), (), "*", False)],
}

IDENT_CH = re.compile(r"[A-Za-z0-9_$]")
IDENT_RE = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
VALVAR_RE = re.compile(r"\b(?:val|var)\s+(?:`[^`]+`|[A-Za-z_$][\w$]*)\s*:")
FUN_RE = re.compile(r"\bfun\b")
CTOR_RE = re.compile(r"\bconstructor\b")
CLASS_RE = re.compile(r"\b(?:class|interface|object)\s+(?:`[^`]+`|[A-Za-z_$][\w$]*)")
FOR_RE = re.compile(r"^\s*for\s*\(")
RETURN_THROW_RE = re.compile(r"^\s*(?:throw|return(?:@\w+)?)\s+")
SKIP_LINE = re.compile(r"^\s*(?:import|package|@file:)")


# ---------------------------------------------------------------- masking

def code_mask(text):
    """Copy of `text` with comment/string/char bytes blanked (newlines kept).

    Offsets are preserved, so every match found in the mask is an offset into
    the real text.
    """
    n = len(text)
    out = list(text)
    i = 0

    def blank(a, b):
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            blank(i, j)
            i = j
        elif c == '"':
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and text[j] not in ('"', "\n"):
                    j += 2 if text[j] == "\\" else 1
                j = min(j + 1, n)
            blank(i, j)
            i = j
        elif c == "'":
            j = i + 1
            while j < n and text[j] not in ("'", "\n"):
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            blank(i, j)
            i = j
        else:
            i += 1
    return "".join(out)


def match_paren(s, open_idx):
    depth = 0
    for i in range(open_idx, len(s)):
        c = s[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        elif c == "\n" and depth == 0:
            return -1
    return -1


# ------------------------------------------------------- pass A: types

def scan_type(s, colon):
    """Span of the type that follows `s[colon] == ':'`, or None."""
    i = colon + 1
    while i < len(s) and s[i] in " \t":
        i += 1
    start = i
    depth = 0
    while i < len(s):
        c = s[i]
        if c == "<":
            depth += 1
        elif c == ">":
            if depth == 0:
                break
            depth -= 1
        elif c == "\n":
            break
        elif depth == 0 and c in " =,){};":
            break
        i += 1
    return (start, i) if i > start else None


def nullify(s, span, ins):
    if span is None:
        return False
    start, end = span
    t = s[start:end].strip()
    if not t or t in PRIMS or t.endswith("?") or "(" in t or t == "*":
        return False
    if t.startswith("out ") or t.startswith("in "):
        return False
    ins.append((end, 1, "?"))
    return True


def param_spans(s, open_idx, close_idx):
    """Spans of the declared types in a parameter list, in order."""
    out = []
    i = open_idx + 1
    depth = 0
    while i < close_idx:
        c = s[i]
        if c in "<([":
            depth += 1
        elif c in ">)]":
            depth -= 1
        elif c == ":" and depth == 0:
            span = scan_type(s, i)
            if span:
                out.append(span)
                i = span[1]
                continue
        i += 1
    return out


def arity(s, open_idx, close_idx):
    inner = s[open_idx + 1:close_idx]
    if not inner.strip():
        return 0
    n, depth = 1, 0
    for c in inner:
        if c in "<([":
            depth += 1
        elif c in ">)]":
            depth -= 1
        elif c == "," and depth == 0:
            n += 1
    return n


# ------------------------------------------------- supertype signatures

FUN_SIG = re.compile(r"\bfun\s+(?:<[^<>]*>\s*)?([A-Za-z_$][\w$]*)\s*\(")


def simple(t):
    """`SurfaceHolder.Callback?` -> `Callback`: the name an override has to
    agree on, ignoring the nullability we are deciding."""
    t = t.strip().rstrip("?").split("<")[0].strip()
    return t.split(".")[-1] or "*"


def pick_sig(sigs, name, argc, argtypes, rettype):
    """The stub overload this member is actually overriding, if any.  Matching
    on type names keeps an unrelated same-name stub method from deciding it:
    `onInputBufferAvailable(AsyncCodec, Int)` is ours, `(MediaCodec, Int)` is
    MediaCodec's."""
    cands = sigs.get((name, argc))
    if not cands:
        return None
    for names, ps, rname, ret in cands:
        if argc:
            if all(a == b or "*" in (a, b) for a, b in zip(names, argtypes)):
                return ps, ret
        elif rname == rettype or "*" in (rname, rettype):
            return ps, ret
    return None


def stub_sigs(ctx):
    """(name, arity) -> (param nullability, return nullability), read off the
    hand-written android/androidx stubs in the output tree.  Those are the
    supertypes an overriding member has to match exactly."""
    cached = ctx.get("_nullable_emit_sigs")
    if cached is not None:
        return cached
    sigs = dict(BUILTIN_SIGS)
    root = os.path.join(ctx.get("out_root", ""), "stubs")
    for d, _, fs in os.walk(root):
        for f in sorted(fs):
            if not f.endswith(".kt"):
                continue
            try:
                text = code_mask(open(os.path.join(d, f)).read())
            except OSError:
                continue
            for m in FUN_SIG.finditer(text):
                op = m.end() - 1
                close = match_paren(text, op)
                if close < 0:
                    continue
                spans = param_spans(text, op, close)
                ps = tuple(text[a:b].strip().endswith("?") for a, b in spans)
                names = tuple(simple(text[a:b]) for a, b in spans)
                n = arity(text, op, close)
                if len(ps) != n:
                    continue
                rm = re.match(r"\s*:", text[close + 1:close + 200])
                ret, rname = False, "Unit"
                if rm:
                    span = scan_type(text, close + rm.end())
                    if span:
                        ret = text[span[0]:span[1]].endswith("?")
                        rname = simple(text[span[0]:span[1]])
                sigs.setdefault((m.group(1), n), []).append(
                    (names, ps, rname, ret))
    ctx["_nullable_emit_sigs"] = sigs
    return sigs


def returns_null(s, after):
    """Does the function body starting after `after` contain a bare
    `return null`?"""
    b = s.find("{", after)
    if b < 0 or "\n" in s[after:b]:
        return False
    depth, i = 0, b
    while i < len(s):
        if s[i] == "{":
            depth += 1
        elif s[i] == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    return re.search(r"\breturn null\b", s[b:i]) is not None


def pass_types(s, ins, sigs, decls):
    n = 0
    for a, b in line_spans(s):
        text = s[a:b]
        if SKIP_LINE.match(text) or re.match(r"\s*(\}\s*)?catch\s*\(", text):
            continue
        # Annotation parameters cannot be nullable - but their parameter
        # list still has to be recorded, or the argument pass mistakes it for
        # a call and writes `val version: Int!!`.
        anno = "annotation class" in text

        for m in VALVAR_RE.finditer(text):
            if not anno:
                n += nullify(s, scan_type(s, a + m.end() - 1), ins)

        for m in FUN_RE.finditer(text):
            p = text.find("(", m.end())
            if p < 0:
                continue
            close = match_paren(text, p)
            if close < 0:
                continue
            nm = IDENT_RE.findall(text[m.end():p])
            name = nm[-1] if nm else ""
            decls.append((a + p, a + close))
            spans = param_spans(s, a + p, a + close)
            over = re.search(r"\boverride\b", text[:m.start()]) is not None
            rspan = None
            rm = re.match(r"\s*:", text[close + 1:])
            if rm:
                rspan = scan_type(s, a + close + rm.end())
            sig = pick_sig(sigs, name, arity(text, p, close),
                           [simple(s[x:y]) for x, y in spans],
                           simple(s[rspan[0]:rspan[1]]) if rspan else "Unit") \
                if over else None
            for i, span in enumerate(spans):
                if anno:
                    break
                if sig is None or (i < len(sig[0]) and sig[0][i]):
                    n += nullify(s, span, ins)
            if rspan and not anno and name not in RETURN_KEEPS_NONNULL \
                    and (sig is None or sig[1]):
                if nullify(s, rspan, ins):
                    n += 1
                elif s[rspan[0]:rspan[1]] in PRIMS and returns_null(s, a + close):
                    # java.lang.Double became Kotlin's Double; the body still
                    # says `return null`
                    ins.append((rspan[1], 1, "?"))
                    n += 1

        for rx in (CTOR_RE, CLASS_RE):
            for m in rx.finditer(text):
                q = re.match(r"\s*(<[^<>]*>)?\s*\(", text[m.end():])
                if not q:
                    continue
                p = m.end() + q.end() - 1
                close = match_paren(text, p)
                if close < 0:
                    continue
                decls.append((a + p, a + close))
                if anno:
                    continue
                for span in param_spans(s, a + p, a + close):
                    n += nullify(s, span, ins)
    return n


def line_spans(s):
    out, i = [], 0
    while i <= len(s):
        j = s.find("\n", i)
        if j < 0:
            out.append((i, len(s)))
            break
        out.append((i, j))
        i = j + 1
    return out


# -------------------------------------------------- pass B: dereferences

def ident_start(s, end):
    i = end
    while i > 0 and IDENT_CH.match(s[i - 1]):
        i -= 1
    return i


def chain_is_package(s, start):
    """True when this segment belongs to a dotted chain that starts at a
    package root and has been lower-case all the way down."""
    while start > 1 and s[start - 1] == "." and IDENT_CH.match(s[start - 2]):
        prev = ident_start(s, start - 1)
        seg = s[prev:start - 1]
        if seg == "R":
            return True       # android.R.string.copy: lower-case nested types
        if seg[:1].isupper():
            return False
        start = prev
    m = IDENT_RE.match(s, start)
    return bool(m) and m.group(0) in PKG_ROOTS


def is_type_name(name, types):
    if name in types:
        return True
    return name[:1].isupper() and any(c.islower() for c in name)


def pass_deref(s, ins, types):
    n = 0
    skip = set()
    for a, b in line_spans(s):
        if SKIP_LINE.match(s[a:b]):
            skip.update(range(a, b))
    for i, c in enumerate(s):
        if c not in ".[:" or i in skip or i == 0:
            continue
        if c == ":":
            # `x::foo` needs the assertion on the receiver, not the reference
            if s[i + 1:i + 2] != ":" or s[i - 1] == ":":
                continue
        prev = s[i - 1]
        if c == "." and prev in "?!.:":
            continue
        if c == "[" and prev in "?!":
            continue
        if prev in ")]":
            ins.append((i, 0, "!!"))
            n += 1
            continue
        if prev == "`":                      # `in`.readInt(): an escaped name
            if s.rfind("`", 0, i - 1) >= 0:
                ins.append((i, 0, "!!"))
                n += 1
            continue
        if not IDENT_CH.match(prev):
            continue
        start = ident_start(s, i)
        name = s[start:i]
        if not name or name[0].isdigit():
            continue          # numeric literal: 1.0f, 0x1f
        if name in NO_ASSERT_IDENTS or name in PRIMS:
            continue
        if is_type_name(name, types):
            continue
        if chain_is_package(s, start):
            continue
        ins.append((i, 0, "!!"))
        n += 1
    return n


# ------------------------------------------ pass C: other non-null demands

ENDS_KEYWORD = {"null", "true", "false", "this", "super", "class", "it"}


def assertable(expr):
    """Can `expr!!` be appended?  Not to a literal, a lambda, or the type of a
    cast - `x as Foo!!` would bind the assertion to the type, not the value."""
    expr = expr.rstrip()
    if not expr or expr.endswith("!!") or expr.endswith("?"):
        return False
    if not (expr[-1] in ")]`" or IDENT_CH.match(expr[-1])):
        return False
    if find_top(expr, " as ") >= 0 or find_top(expr, " is ") >= 0:
        return False
    if expr[-1] not in ")]`":
        tok = expr[ident_start(expr, len(expr)):]
        if not tok or tok[0].isdigit() or tok in ENDS_KEYWORD:
            return False      # a number literal, or `null` / `this` / `class`
    return True


def pass_args(s, ins, decls):
    """Arguments are a non-null demand too: the stubs take `String?` but the
    Kotlin stdlib and every generic type argument do not, so a nullified local
    passed straight through is an error until it is asserted."""
    n = 0
    skip = set()
    for a, b in line_spans(s):
        if SKIP_LINE.match(s[a:b]):
            skip.update(range(a, b))
    for i, c in enumerate(s):
        if c != "(" or i in skip or i == 0:
            continue
        if not (IDENT_CH.match(s[i - 1]) or s[i - 1] in ")]>"):
            continue          # `if (`, `while (`, `catch (` all keep a space
        if any(o <= i <= cl for o, cl in decls):
            continue
        close = match_paren(s, i)
        if close < 0 or "\n" in s[i:close]:
            continue          # only single-line calls
        start, depth = i + 1, 0
        for j in range(i + 1, close + 1):
            ch = s[j]
            if ch in "<([{":
                depth += 1
            elif ch in ">)]}":
                if j == close:
                    pass
                else:
                    depth -= 1
            if (j == close or (ch == "," and depth == 0)) and depth == 0:
                arg = s[start:j]
                if assertable(arg):
                    ins.append((start + len(arg.rstrip()), 3, "!!"))
                    n += 1
                start = j + 1
    return n


def pass_demands(s, ins):
    """`for (x in e)`, `throw e` and `return e` all demand a non-null value."""
    n = 0
    for a, b in line_spans(s):
        text = s[a:b]
        m = FOR_RE.match(text)
        if m:
            close = match_paren(text, m.end() - 1)
            if close > 0:
                inner = text[m.end():close]
                k = find_top(inner, " in ")
                if k >= 0:
                    expr = inner[k + 4:]
                    if assertable(expr):
                        ins.append((a + m.end() + k + 4 + len(expr.rstrip()),
                                    2, "!!"))
                        n += 1
            continue
        m = RETURN_THROW_RE.match(text)
        if m and "{" not in text and "->" not in text:
            expr = text[m.end():]
            if assertable(expr):
                ins.append((a + m.end() + len(expr.rstrip()), 2, "!!"))
                n += 1
    return n


def find_top(s, needle):
    depth = 0
    for i, c in enumerate(s):
        if c in "([<":
            depth += 1
        elif c in ")]>":
            depth -= 1
        elif depth == 0 and s.startswith(needle, i):
            return i
    return -1


# ---------------------------------------------------------------- driver

def known_types(unit, ctx):
    """Names that must never be treated as a value: putting `!!` after a type
    is the one way this rule can turn a working line into an error."""
    idx = ctx.get("index")
    types = set(getattr(idx, "kinds", {}) or {})
    for imp in unit.imports:
        simple = imp.split()[-1].rsplit(".", 1)[-1]
        if simple != "*":
            types.add(simple)
    types.update(PRIMS)
    types.update({"String", "Array", "Any", "List", "Map", "Set", "Math",
                  "System", "Integer", "Long", "Double", "Float", "Boolean",
                  "Character", "Byte", "Short", "Object", "Class", "Thread",
                  "Arrays", "Collections", "Objects", "Void", "Enum"})
    return types


def transform(unit, ctx):
    s = code_mask(unit.kotlin)
    ins = []
    decls = []
    a = pass_types(s, ins, stub_sigs(ctx), decls)
    b = pass_deref(s, ins, known_types(unit, ctx))
    c = pass_demands(s, ins) + pass_args(s, ins, decls)
    if not ins:
        return
    ins.sort(key=lambda x: (x[0], x[1]))
    seen = set()
    ins = [x for x in ins if (x[0], x[2]) not in seen and not seen.add((x[0], x[2]))]
    out, prev = [], 0
    for off, _, txt in ins:
        out.append(unit.kotlin[prev:off])
        out.append(txt)
        prev = off
    out.append(unit.kotlin[prev:])
    unit.kotlin = "".join(out)
    ctx["stats"]["nullable-emit/types"] += a
    ctx["stats"]["nullable-emit/deref"] += b
    ctx["stats"]["nullable-emit/demand"] += c
