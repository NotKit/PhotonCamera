"""What the nullable-emit policy did not reach: the positions it never asserts.

`nullable-emit` (ORDER 50) makes every declared reference type `T?` and puts
`!!` on dereference receivers and on call arguments.  Six positions are left
where a `T?` still meets a `T`:

  argument     a bare `null` handed to a parameter that stayed non-null - a
               Java boxed `Double`/`Boolean`/`Integer` (mapped onto a Kotlin
               primitive, which has no `?` form), a generic type argument
               (`AtomicReference<ScheduledFuture<*>>`), or an override whose
               signature is pinned by a non-null stub.
  return       `return null`, and `return f(...)` where the call spans lines
               because its last argument is a lambda - the single-line case is
               the only one nullable-emit asserts.
  assignment   `arr!![i] = key`, where the element type is non-null.
  initializer  `val startTime: Double = tryToGetProfilerTime()`, declared
               non-null because `Double` is a Kotlin primitive.
  condition    `if (mEtpStrict!!.get())`, where the stub returns `Boolean?`.
  field = null `active = null` onto a field the file declares `Boolean`.

plus one artefact of nullable-emit's own: `cond ? a : b` became
`if (cond) a else!! b`, because the assertion pass treated `else` as a
receiver.  Kotlin reads that as `!(!b)` and says "unresolved reference 'not'".

The fix in every position is one insertion: `!!` on the expression, or
`null!!` for the null literal.  `null!!` has type `Nothing`, a subtype of every
type including `Double` and `Unit`, so it satisfies whatever the callee asked
for; `x!!` on something already non-null is a warning, never an error.  That
asymmetry is what makes a blunt post-pass safe here - an unnecessary assertion
costs a warning, a missing one costs an error - and it is why this needs no
type information, which a post-pass does not have.  It needs to know which
*positions* the compiler checks, and there are only six.

Insertions are made only where the expression is a *postfix chain* - an
identifier or parenthesised group followed by `.f()`, `[i]`, `?.f`, `::f` - and
where that chain spans the whole position.  Anything with an operator in it, a
lambda, an `object :`, a `when`, or a `TODO()` is left alone: those are either
not this bucket, or `!!` would change what the text parses as.

Two things this rule deliberately does NOT do, both measured and both worse:

  `!expr`      `!TextUtils.isEmpty(s)` reports the same "unresolved reference
               'not'" as the `else!!` artefact, so it looks like the same bug.
               It is not: `TextUtils` and `BuildConfig` are unresolved, and the
               `!` error is a cascade off the error type.  Asserting the
               operand fixed 0 of those 8 and added 16 new ones.
  `Array<T?>`  the stubs spell arrays `Array<String?>?` and ours are
               `Array<String>`, so nullifying the type argument looks free.
               `Array` is invariant, so it is not: it cost +32 errors and took
               this bucket from 13 back to 30.
"""
import re

ORDER = 55
NAME = "nullability-mismatch-fallout"
DESCRIPTION = "assert (!!) in the argument/return/assignment/initializer/condition positions nullable-emit leaves"

IDENT_START = re.compile(r"[A-Za-z_$]")
IDENT_RE = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")

# Chain heads that must not be asserted: keywords that start a *statement* or a
# non-postfix expression, and values where `!!` would change inference rather
# than fix a mismatch.
NO_ASSERT_HEAD = {
    "null", "true", "false", "TODO", "if", "when", "try", "object", "return",
    "throw", "break", "continue", "val", "var", "fun", "class", "is", "as",
    "in", "do", "while", "for", "else", "super", "it",
}

# `= TODO()` is the converter's stand-in for a body it dropped; asserting it
# would only decorate a `Nothing`.
SKIP_RHS = re.compile(r"^\s*TODO\s*\(")


# ---------------------------------------------------------------- masking

def code_mask(text):
    """`text` with comment/string/char bytes blanked, offsets preserved."""
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


def match_bracket(s, i):
    """Index just past the group opened at `s[i]`, or -1."""
    pairs = {"(": ")", "[": "]", "{": "}"}
    if s[i] not in pairs:
        return -1
    want = pairs[s[i]]
    depth = 0
    while i < len(s):
        c = s[i]
        if c in pairs:
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return i + 1 if c == want else -1
        i += 1
    return -1


# ----------------------------------------------------------- chain scan

def scan_chain(s, i, limit):
    """End of the postfix chain starting at `s[i]`, or -1 if there is none.

    A chain is `ident` or `( ... )`, then any run of `.f`, `?.f`, `::f`,
    `(args)`, `[i]`, `!!`.  Operators end it, which is how a position with an
    operator in it gets skipped: the chain will not reach the position's end.
    """
    while i < limit and s[i] in " \t":
        i += 1
    if i >= limit:
        return -1
    if s[i] == "(":
        j = match_bracket(s, i)
        if j < 0 or j > limit:
            return -1
        i = j
    elif IDENT_START.match(s[i]):
        m = IDENT_RE.match(s, i)
        if m.group(0) in NO_ASSERT_HEAD:
            return -1
        i = m.end()
        if i < limit and s[i] == "@":                  # `this@GeckoSession`
            lm = IDENT_RE.match(s, i + 1)
            if not lm:
                return -1
            i = lm.end()
    else:
        return -1

    while i < limit:
        c = s[i]
        if c in "([":
            j = match_bracket(s, i)
            if j < 0 or j > limit:
                return i
            i = j
        elif c == "!" and s[i:i + 2] == "!!":
            i += 2
        elif c == "." or (c == "?" and s[i:i + 2] == "?.") or s[i:i + 2] == "::":
            k = i + (2 if c != "." else 1)
            while k < limit and s[k] in " \t\n":
                k += 1
            m = IDENT_RE.match(s, k) if k < limit else None
            if not m:
                return i
            i = m.end()
        else:
            return i
    return i


def ends_asserted(s, end):
    return s[max(0, end - 2):end] == "!!"


def apply(text, ins):
    """`ins` is [(offset, string)]; inserted right-to-left so offsets hold."""
    for off, frag in sorted(ins, key=lambda t: -t[0]):
        text = text[:off] + frag + text[off:]
    return text


def line_bounds(s, i):
    a = s.rfind("\n", 0, i) + 1
    b = s.find("\n", i)
    return a, (len(s) if b < 0 else b)


# ------------------------------------------------------------- the passes

def pass_else(text, stats):
    """`if (c) a else!! b` -> `if (c) a else b`.  nullable-emit's artefact."""
    n = text.count("else!!")
    if n:
        text = text.replace("else!!", "else")
        stats[NAME + "/else!!"] += n
    return text


def pass_null_args(text, stats):
    """A bare `null` in an argument list -> `null!!` (type `Nothing`)."""
    s = code_mask(text)
    ins = []
    for m in re.finditer(r"\bnull\b", s):
        a, b = m.start(), m.end()
        i = a - 1
        while i >= 0 and s[i] in " \t\n":
            i -= 1
        if i < 0 or s[i] not in "(,":
            continue
        j = b
        while j < len(s) and s[j] in " \t\n":
            j += 1
        if j >= len(s) or s[j] not in ",)":
            continue
        ins.append((b, "!!"))
    if ins:
        stats[NAME + "/null-argument"] += len(ins)
    return apply(text, ins)


def pass_return_null(text, stats):
    """`return null` -> `return null!!`, for a return type pinned non-null."""
    out, n = [], 0
    for line in text.split("\n"):
        m = re.match(r"^(\s*return(?:@\w+)?\s+null)\s*$", line)
        if m:
            line = m.group(1) + "!!"
            n += 1
        out.append(line)
    if n:
        stats[NAME + "/return-null"] += n
    return "\n".join(out)


def pass_assign(text, stats):
    """`lhs = chain` -> `lhs = chain!!`, for assignments and initialisers."""
    s = code_mask(text)
    ins = []
    for m in re.finditer(r"(?<![=!<>+\-*/%&|^])=(?![=])", s):
        eq = m.start()
        a, b = line_bounds(s, eq)
        if eq + 1 >= b:
            continue                                   # `= <newline>`
        lhs = s[a:eq]
        if SKIP_RHS.match(s[eq + 1:b]):
            continue
        if "@" in lhs or "->" in lhs or lhs.lstrip().startswith("for "):
            continue
        end = scan_chain(s, eq + 1, b)
        if end < 0 or ends_asserted(s, end):
            continue
        if s[end:b].strip():                           # operator tail: not ours
            continue
        ins.append((end, "!!"))
    if ins:
        stats[NAME + "/assign-rhs"] += len(ins)
    return apply(text, ins)


def pass_condition(text, stats):
    """`if (chain)` / `while (chain)` -> `... (chain!!)`: `Boolean?` is not a
    condition."""
    s = code_mask(text)
    ins = []
    for m in re.finditer(r"\b(if|while)\s*\(", s):
        op = m.end() - 1
        close = match_bracket(s, op)
        if close < 0:
            continue
        end = scan_chain(s, op + 1, close - 1)
        if end < 0 or ends_asserted(s, end):
            continue
        if s[end:close - 1].strip():
            continue
        ins.append((end, "!!"))
    if ins:
        stats[NAME + "/condition"] += len(ins)
    return apply(text, ins)


STMT_RETURN = re.compile(r"^[ \t]*(return(?:@\w+)?)[ \t]", re.M)
DECL_NONNULL = re.compile(
    r"\b(?:val|var)\s+(?:`[^`]+`|([A-Za-z_$][\w$]*))\s*:\s*([^=\n]+?)\s*(?:=|$)",
    re.M)


def pass_return_chain(text, stats):
    """`return chain` -> `return chain!!`, where the chain may span lines.

    nullable-emit asserts a single-line `return`; a call whose argument is a
    multi-line lambda (`return awaitPlayerThread(j2k1@{ ... })`) is the case it
    misses, and it is exactly where the lambda's `T?` becomes the return type.
    """
    s = code_mask(text)
    ins = []
    for m in STMT_RETURN.finditer(s):
        start = m.end(1) + 1
        end = scan_chain(s, start, len(s))
        if end < 0 or ends_asserted(s, end):
            continue
        _, eol = line_bounds(s, end - 1)
        if s[end:eol].strip():
            continue
        ins.append((end, "!!"))
    if ins:
        stats[NAME + "/return-chain"] += len(ins)
    return apply(text, ins)


def pass_null_assign(text, stats):
    """`x = null` where *this file* declares `x` with a non-null type.

    Java's boxed `Boolean`/`Integer` fields map onto Kotlin primitives, which
    have no `?` form, so nullable-emit leaves them non-null and the Java
    `field = null` that follows does not type-check.  `null!!` is `Nothing`,
    which does.  Scoped to names the file itself declares non-null, so a
    `Foo? = null` field keeps its honest `null`.
    """
    s = code_mask(text)
    nonnull = set()
    for m in DECL_NONNULL.finditer(s):
        name, ty = m.group(1), m.group(2).strip()
        if name and ty and not ty.endswith("?") and "->" not in ty:
            nonnull.add(name)
    if not nonnull:
        return text
    ins = []
    for m in re.finditer(r"^\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*=\s*null\s*$",
                         s, re.M):
        if m.group(1) in nonnull:
            ins.append((m.end(), "!!"))
    if ins:
        stats[NAME + "/null-assignment"] += len(ins)
    return apply(text, ins)


def transform(unit, ctx):
    stats = ctx["stats"]
    t = unit.kotlin
    t = pass_else(t, stats)
    t = pass_return_null(t, stats)
    t = pass_null_args(t, stats)
    t = pass_assign(t, stats)
    t = pass_condition(t, stats)
    t = pass_return_chain(t, stats)
    t = pass_null_assign(t, stats)
    unit.kotlin = t
