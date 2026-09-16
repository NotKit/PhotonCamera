"""Five Java declaration/statement forms the emitter copies through verbatim.

Each of these is legal Java that j2k reproduces token-for-token because the
shape is only illegal once you read it as Kotlin.  One rule, five textual
rewrites, all driven off a brace-depth scan of the emitted file (strings, chars
and comments masked first, so a `{` in a javadoc never moves a class body).

(a) declaration order in a class body.
    Java resolves fields for the whole class before it compiles a constructor,
    so `ClientAuthCertificate(...) { mAlias = alias; } ... String mAlias;`
    compiles.  Kotlin initialises a class body top to bottom and reports
    `variable cannot be initialized before declaration` for a write and
    `variable 'x' must be initialized` for a read.  Every property that a
    constructor or `init` block mentions is moved to just above the first such
    block, keeping the relative order of the moved declarations.

    The read form has a second cause that hoisting alone does not fix, and it
    is one of our own making: j2k gives an uninitialised field the initialiser
    `TODO()`, whose type is `Nothing`.  Kotlin's flow analysis reads a `Nothing`
    initialiser as "the initialiser never completes", so the property counts as
    never assigned, and a read of it from inside a loop in the same constructor
    is an error even when the declaration sits above.  Verified in isolation:
    the same class with `= TODO()` errors and with `= null` does not.  So a
    `var` that a constructor mentions and whose type has an obvious zero value
    (`T?` -> null, `Int` -> 0, `Boolean` -> false, ...) gets that value instead
    of `TODO()`.  `val` is left alone - assigning one is already an error for a
    different reason, and this rule should not look like it fixed that.

(b) assignment as an expression.  Java's `=` yields a value, Kotlin's does not.
    Two spellings appear:
      `while ((n = in.read(buf)) != -1)` -> `while (run { n = in.read(buf); n } != -1)`
      `a = b = 0`                        -> `b = 0` then `a = b`
    plus one degenerate emission, `var x: T = x = expr`, which loses the
    self-assignment.  `run { }` is used rather than restructuring the loop
    because it is a local, brace-balanced edit: the brace scan above still sees
    the same block structure afterwards, and it works in any expression
    position - one site reaches this rule as `mAttachedContext = (mTexName =
    0).toLong()`, an earlier widening rule having already wrapped the inner
    assignment.  A `(` that follows an identifier is skipped: `foo(x = 1)` is a
    named argument in Kotlin, not an assignment.

(c) Java 10 `var`.  The emitter treats the inferred-type keyword as a type
    name and writes it as the escaped identifier `` `var` ``.  The type
    annotation is simply dropped; Kotlin infers the same type from the
    initialiser, which is exactly what the Java meant.

(d) the `equals` parameter.  j2k renames the parameter to `other` in the
    signature so the override matches `Any.equals`, but leaves the Java name
    (`obj`, `that`) in the body.  The signature is renamed back rather than the
    body rewritten: Kotlin allows an override to rename a parameter (a
    warning), and one edit cannot collide with a local named `other`, which
    several of these bodies declare.

(e) `companion object` inside an `inner class`, which Kotlin prohibits.
    Dropping `inner` is not available: these classes use `this@Outer` and call
    outer instance methods.  The companion wrapper is removed instead and its
    members become ordinary members of the inner class, so unqualified uses
    inside the class still resolve.  No site in the corpus refers to one of
    these constants from outside its class - checked before writing this.
    A nested `annotation class` in an inner class is prohibited for the same
    reason and is deleted outright: j2k drops annotations at every use site, so
    the declaration has no readers (the one site, GeckoSession.ScrollPositionUpdate.
    SourceType, is referenced nowhere in the corpus).

Also here, because it is the same "Java text, Kotlin grammar" family: the three
`object : Foo {` sites where `Foo` is a stub *class* rather than an interface,
reported as `this type has a constructor, so it must be initialized here`.  The
kind is read off the generated stub tree in `out_root` (written by the ORDER
50-65 rules, so it is on disk by the time this runs) and `()` is added.
"""
import os
import re
from collections import Counter

ORDER = 70
NAME = "java-declaration-forms-emitted-verbatim"
DESCRIPTION = "hoist fields above constructors, unchain assignments, drop `var`, fix equals params, unwrap inner companions"


# --------------------------------------------------------------------------
# masking: a copy of the text with string/char/comment content blanked out, so
# structural scans never trip over a brace or an `=` inside a literal.
# --------------------------------------------------------------------------
def _mask(text):
    out = list(text)
    n = len(text)
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
            if text[i:i + 3] == '"""':
                j = text.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and text[j] != '"' and text[j] != "\n":
                    j += 2 if text[j] == "\\" else 1
                j = min(j + 1, n)
            blank(i, j)
            i = j
        elif c == "'":
            j = i + 1
            while j < n and text[j] != "'" and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            blank(i, j)
            i = j
        else:
            i += 1
    return "".join(out)


def _match_paren(s, start):
    """Index just past the `)` that closes the `(` at s[start]."""
    depth = 0
    for k in range(start, len(s)):
        if s[k] == "(":
            depth += 1
        elif s[k] == ")":
            depth -= 1
            if depth == 0:
                return k + 1
    return -1


# --------------------------------------------------------------------------
# (c) Java 10 `var`
# --------------------------------------------------------------------------
_VAR_TYPE = re.compile(r"^(\s*(?:va[lr])\s+[A-Za-z_$][\w$]*)\s*:\s*`var`\??\s*=")


def _drop_var_type(lines, masked, stats):
    hits = 0
    for i, m in enumerate(masked):
        mm = _VAR_TYPE.match(m)
        if mm:
            mm2 = _VAR_TYPE.match(lines[i])
            if mm2:
                lines[i] = mm2.group(1) + " =" + lines[i][mm2.end():]
                hits += 1
    if hits:
        stats[NAME + ":var-keyword"] += hits


# --------------------------------------------------------------------------
# (b) assignment as an expression
# --------------------------------------------------------------------------
_CALLISH = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_$>")
_EMBEDDED = re.compile(r"\(\s*([A-Za-z_$][\w$]*)\s*=(?!=)")
# `a = b = expr`, statement position, no compound/comparison operator involved.
_TARGET = r"[A-Za-z_$][\w$@.\[\]!]*"
_CHAIN = re.compile(r"^(\s*)(%s)\s*=(?!=)\s*(%s)\s*=(?!=)\s*(\S.*)$" % (_TARGET, _TARGET))
_SELF_DECL = re.compile(r"^(\s*va[lr]\s+([A-Za-z_$][\w$]*)(?:\s*:[^=]*?)?)\s*=(?!=)\s*\2\s*=(?!=)\s*(\S.*)$")


def _embedded_rewrite(line):
    """`(x = expr)` in an expression position -> `run { x = expr; x }`."""
    new, hits, guard = line, 0, 0
    while guard < 4:
        guard += 1
        nm = _mask(new)
        hit = None
        for mo in _EMBEDDED.finditer(nm):
            before = nm[:mo.start()]
            # a call's argument list: `foo(name = 1)` is a named argument
            if before and before[-1] in _CALLISH:
                continue
            # the `while (`/`if (` paren itself
            if re.search(r"\b(?:while|if|for)$", before.rstrip()):
                continue
            hit = mo
            break
        if hit is None:
            break
        close = _match_paren(nm, hit.start())
        if close < 0:
            break
        name = hit.group(1)
        inner = new[hit.end():close - 1].strip()
        new = "%srun { %s = %s; %s }%s" % (
            new[:hit.start()], name, inner, name, new[close:])
        hits += 1
    return new, hits


def _unchain(lines, masked, stats):
    out, out_masked = [], []
    embedded = chained = selfdecl = 0

    for line, m in zip(lines, masked):
        sd = _SELF_DECL.match(m) and _SELF_DECL.match(line)
        if sd:
            out.append("%s = %s" % (sd.group(1), sd.group(3)))
            out_masked.append(_mask(out[-1]))
            selfdecl += 1
            continue

        ch = _CHAIN.match(m)
        if re.search(r"[!<>=+\-*/%&|^]=", m):
            ch = None
        if ch and not m.lstrip().startswith(("val ", "var ", "return ")):
            ch = _CHAIN.match(line)
        else:
            ch = None
        if ch:
            indent, first, second, rest = ch.groups()
            out.append("%s%s = %s" % (indent, second, rest))
            out.append("%s%s = %s" % (indent, first, second))
            out_masked.append(_mask(out[-2]))
            out_masked.append(_mask(out[-1]))
            chained += 1
            continue

        new, hits = _embedded_rewrite(line)
        embedded += hits
        out.append(new)
        out_masked.append(_mask(new) if hits else m)

    if embedded:
        stats[NAME + ":assign-in-expression"] += embedded
    if chained:
        stats[NAME + ":assign-chain"] += chained
    if selfdecl:
        stats[NAME + ":assign-self-decl"] += selfdecl
    return out, out_masked


# --------------------------------------------------------------------------
# (d) the equals parameter
# --------------------------------------------------------------------------
_EQUALS = re.compile(r"^(\s*)((?:\w+\s+)*fun\s+equals\s*\(\s*)other(\s*:\s*Any\?)")


def _fix_equals_param(lines, masked, depth_start, stats):
    fixed = 0
    for i, m in enumerate(masked):
        mo = _EQUALS.match(m)
        if not mo or not m.rstrip().endswith("{"):
            continue
        d = depth_start[i]
        j = i + 1
        while j < len(masked) and depth_start[j] > d:
            j += 1
        body = "\n".join(masked[i + 1:j])
        alias = None
        for cand in ("obj", "that"):
            if re.search(r"(?<![.\w$])%s\b" % cand, body):
                alias = cand
                break
        if alias is None:
            continue
        uses_other = re.search(r"\bother\b", body)
        declares_other = re.search(r"\b(?:val|var)\s+other\b", body)
        if uses_other and not declares_other:
            continue
        mo2 = _EQUALS.match(lines[i])
        if not mo2:
            continue
        lines[i] = mo2.group(1) + mo2.group(2) + alias + lines[i][mo2.end(2) + len("other"):]
        fixed += 1
    if fixed:
        stats[NAME + ":equals-param"] += fixed


# --------------------------------------------------------------------------
# supertype kind lookup, for `object : SomeStubClass {`
# --------------------------------------------------------------------------
_STUB_CLASS = re.compile(r"^\s*(?:(?:open|abstract|sealed|final|inner|data|value)\s+)*class\s+([A-Za-z_$][\w$]*)")
_STUB_IFACE = re.compile(r"^\s*(?:(?:fun|sealed)\s+)*interface\s+([A-Za-z_$][\w$]*)")
_OBJECT_SUPER = re.compile(r"\bobject\s*:\s*([A-Za-z_$][\w$.]*)\s*\{")


def _stub_kinds(ctx):
    cached = ctx.get("_decl_forms_stub_kinds")
    if cached is not None:
        return cached
    classes, ifaces = set(), set()
    root = ctx.get("out_root")
    for sub in ("stubs", "android"):
        d = os.path.join(root or "", sub)
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".kt"):
                continue
            try:
                with open(os.path.join(d, fn), "r", encoding="utf-8") as fh:
                    text = fh.read()
            except OSError:
                continue
            for line in text.split("\n"):
                mc = _STUB_CLASS.match(line)
                if mc:
                    classes.add(mc.group(1))
                mi = _STUB_IFACE.match(line)
                if mi:
                    ifaces.add(mi.group(1))
    kinds = (classes - ifaces, ifaces)
    ctx["_decl_forms_stub_kinds"] = kinds
    return kinds


def _parenthesise_object_supertypes(lines, masked, ctx, stats):
    classes, ifaces = _stub_kinds(ctx)
    if not classes:
        return
    index = ctx.get("index")
    hits = 0
    for i, m in enumerate(masked):
        mo = _OBJECT_SUPER.search(m)
        if not mo:
            continue
        name = mo.group(1)
        simple = name.split(".")[-1]
        if simple in ifaces or simple not in classes:
            continue
        if index is not None and index.kind_of(name) is not None:
            continue
        mo2 = _OBJECT_SUPER.search(lines[i])
        if not mo2 or mo2.group(1) != name:
            continue
        lines[i] = lines[i][:mo2.end(1)] + "()" + lines[i][mo2.end(1):]
        masked[i] = _mask(lines[i])
        hits += 1
    if hits:
        stats[NAME + ":object-supertype-ctor"] += hits


# --------------------------------------------------------------------------
# (a) + (e): the class-body pass
# --------------------------------------------------------------------------
_CLASSLIKE = re.compile(r"\b(?:class|interface)\b|\bobject\b")
_CTOR_ITEM = re.compile(r"^\s*(?:init\b|constructor\s*\()")
_PROP = re.compile(
    r"^\s*(?:(?:open|override|abstract|final|lateinit|const|inner|external|"
    r"vararg|companion)\s+)*(va[lr])\s+([A-Za-z_$][\w$]*)\s*:\s*([^=]+?)\s*=\s*(.+?)\s*$")
_COMPANION = re.compile(r"^\s*companion\s+object\s*\{\s*$")
# a nested annotation class is prohibited inside an inner class, same as a
# companion; annotations mean nothing on Kotlin/Native, so it is just dropped.
_INNER_ANNOTATION = re.compile(
    r"^\s*(?:(?:public|internal|private|protected|open|abstract)\s+)*"
    r"annotation\s+class\b")

_ZERO = {
    "Int": "0", "Long": "0L", "Short": "0", "Byte": "0", "Float": "0f",
    "Double": "0.0", "Boolean": "false", "Char": "' '",
}


def _zero_for(type_text):
    t = type_text.strip()
    if t.endswith("?"):
        return "null"
    return _ZERO.get(t)


def _depths(masked):
    """(depth at start of line, depth at end of line) for every line."""
    starts, d = [], 0
    for m in masked:
        starts.append(d)
        d += m.count("{") - m.count("}")
    return starts


def _split_items(masked, lo, hi, depth):
    """Top-level items of a body: (start, end) line ranges at `depth`."""
    starts = []
    d = depth
    for i in range(lo, hi):
        if d == depth:
            starts.append(i)
        d += masked[i].count("{") - masked[i].count("}")
    items = []
    for k, s in enumerate(starts):
        e = starts[k + 1] if k + 1 < len(starts) else hi
        items.append((s, e))
    return items


def _body_pass(lines, masked, lo, hi, depth, header, stats):
    """Rewrite the body [lo, hi) of the block opened by `header`; returns lines."""
    items = _split_items(masked, lo, hi, depth)
    classlike = bool(header is not None and _CLASSLIKE.search(header))
    inner = bool(header is not None and re.search(r"\binner\s+class\b", header))

    rendered = []       # parallel to items: list of (lines, masked)
    for (s, e) in items:
        blk_lines = lines[s:e]
        blk_masked = masked[s:e]
        opens = e - s > 1 and blk_masked[0].rstrip().endswith("{")
        if opens:
            sub = _body_pass(lines, masked, s + 1, e - 1, depth + 1,
                             blk_masked[0], stats)
            blk_lines = [lines[s]] + sub + lines[e - 1:e]
            blk_masked = [_mask(x) for x in blk_lines]
        rendered.append([blk_lines, blk_masked])

    if not classlike:
        out = []
        for r in rendered:
            out.extend(r[0])
        return out

    # (e) unwrap a companion object that sits inside an inner class
    if inner:
        for r in rendered:
            if len(r[0]) > 2 and _COMPANION.match(r[1][0]):
                body = r[0][1:-1]
                r[0] = [(x[4:] if x.startswith("    ") else x) for x in body]
                r[1] = [_mask(x) for x in r[0]]
                stats[NAME + ":inner-companion"] += 1
        kept = []
        for r in rendered:
            if _INNER_ANNOTATION.match(r[1][0]):
                stats[NAME + ":inner-annotation-class"] += 1
                continue
            kept.append(r)
        rendered = kept

    # (a) constructors and init blocks: what do they mention?
    ctor_idx = [k for k, r in enumerate(rendered) if _CTOR_ITEM.match(r[1][0])]
    if ctor_idx:
        first = ctor_idx[0]
        ctor_text = "\n".join("\n".join(rendered[k][1]) for k in ctor_idx)
        mentioned = set(re.findall(r"[A-Za-z_$][\w$]*", ctor_text))

        moved = []
        for k, r in enumerate(rendered):
            if len(r[0]) != 1:
                continue
            pm = _PROP.match(r[1][0])
            if not pm or pm.group(2) not in mentioned:
                continue
            # Nothing-typed initialiser: the property never counts as assigned
            if pm.group(1) == "var" and pm.group(4) == "TODO()":
                zero = _zero_for(pm.group(3))
                if zero:
                    real = _PROP.match(r[0][0])
                    if real:
                        r[0][0] = real.group(0)[:real.start(4) - real.start(0)] + zero
                        r[1] = [_mask(r[0][0])]
                        stats[NAME + ":todo-initialiser"] += 1
            if k > first:
                moved.append(k)

        if moved:
            movedset = set(moved)
            head = [rendered[k] for k in range(first)]
            body = [rendered[k] for k in moved]
            tail = [rendered[k] for k in range(first, len(rendered))
                    if k not in movedset]
            rendered = head + body + tail
            stats[NAME + ":hoisted-field"] += len(moved)

    out = []
    for r in rendered:
        out.extend(r[0])
    return out


def transform(unit, ctx):
    text = unit.kotlin
    if "\n" not in text:
        return
    stats = ctx["stats"]

    lines = text.split("\n")
    masked = _mask(text).split("\n")
    if len(masked) != len(lines):
        return

    _drop_var_type(lines, masked, stats)
    lines, masked = _unchain(lines, masked, stats)
    depth_start = _depths(masked)
    _fix_equals_param(lines, masked, depth_start, stats)
    _parenthesise_object_supertypes(lines, masked, ctx, stats)

    lines = _body_pass(lines, masked, 0, len(lines), 0, None, stats)
    unit.kotlin = "\n".join(lines)
