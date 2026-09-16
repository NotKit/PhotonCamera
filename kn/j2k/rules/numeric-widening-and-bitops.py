"""Java widens primitives implicitly; Kotlin never does.  Put the conversions back.

Java promotes `byte`/`short`/`char` to `int` and `int` to `long`/`float`/
`double` at every operator, every argument, every assignment and every
`return`.  Kotlin has no implicit numeric conversion at all, and it only
defines `and`/`or`/`xor`/`shl`/`inv` on `Int` and `Long`.  So a mechanical
transcription of GeckoView's Java produces three shapes of error:

  operator '!=' cannot be applied to 'Long' and 'Int'      `timestamp != 0`
  unresolved reference 'and'                               `transports and MASK`  (Byte)
  argument type mismatch: actual 'Int', but 'Float'        `draw(e, c, x, y, 0)`

plus one literal problem of its own: Java writes a negative 64-bit constant as
`0xedef8ba979d64aceL`, which Kotlin rejects as "value out of range" because a
hex literal above `Long.MAX_VALUE` does not wrap.

The fix is always the same insertion - `.toLong()`, `.toFloat()`, `.code` -
and the whole difficulty is knowing *which* conversion, which needs the type
of an expression that a text post-pass does not have.  So this rule builds one:

  * a corpus type index, from tree-sitter over every `.java` in the input
    tree: field name -> primitive, array-field name -> element primitive,
    `(method name, arity)` -> return primitive and parameter primitives.
    A name is kept only when every declaration of it in the corpus agrees;
    a name declared `short` in one class and `int` in another is dropped
    rather than guessed at.
  * a per-file environment, scanned off the emitted Kotlin: every
    `name: Int` binding in the file - locals, fields, parameters, all the
    same regex - again dropped on disagreement.  The file wins over the
    corpus, since a local shadows everything.  A second, members-only map
    is built from the `val`/`var` declarations alone and consulted first
    for anything after a dot, because a parameter of the same name is not
    a candidate for `recv.name` and would otherwise make the name look
    contradictory and get it dropped.
  * the emitted Kotlin stubs (`out/stubs/*.kt`), for the library side:
    `val position: Long`, `fun getPriority(): Int`.  Also unambiguous-only.

`infer()` then types a chain expression - literal, identifier, `a.b`, `a.b()`,
`a[i]`, `x as Int`, `x.toLong()`, `x.code`, and top-level arithmetic by Java's
widest-operand rule.  Everything else is `None`, and `None` means "do not
touch": every edit here is conditional on knowing *both* types, which is what
keeps a blunt pass from inventing `.toInt()` on a `String`.

Seven edits, applied per line over a token stream that skips strings and
comments (j2k emits one statement per line, so a line is a safe unit):

  1  comparison    both sides primitive and different -> widen the narrower
  2  bit op        `and`/`or`/`xor` with a Byte/Short/Char side -> both sides
                   to Int; an Int side against a Long -> to Long.  A shift is
                   not symmetric: `shl`/`shr`/`ushr` keep the left operand's
                   type and take an Int count, so only a narrow left side is
                   widened and the count is forced to Int.
  3  initializer   `val x: Long = <Int>` -> `(...).toLong()`
  4  assignment    `mEndTime = 0` where the target is Double
  5  return        against the enclosing `fun`'s declared primitive return type
  6  call argument  for a `(name, arity)` the corpus resolves unambiguously,
                   plus the built-in `byteArrayOf`/`doubleArrayOf` family
  7  hex literal   `0x...L` >= 2^63 -> the negative hex literal Kotlin accepts

`Char` sits outside the widening ladder: it converts *out* with `.code` and is
never a widening target, because `Char` is where Java's implicit conversion is
one-directional too.

Deliberately not attempted: `Math.min`/`max`/`abs` and the other overloaded
JDK numerics, where picking a conversion means picking an overload; and any
expression whose type needs the receiver's class, since the index is keyed by
member name alone.
"""
import os
import re
from collections import defaultdict

ORDER = 58                 # after nullable-emit (50) and its fallout (55): the
                           # `!!` are already in, and infer() strips them
NAME = "numeric-widening-and-bitops"
DESCRIPTION = "restore Java's implicit primitive widening, and Int-ify bit ops on Byte/Short"

# ---------------------------------------------------------------- type lattice

RANK = {"Byte": 1, "Short": 2, "Int": 3, "Long": 4, "Float": 5, "Double": 6}
PRIMS = set(RANK) | {"Char"}
JAVA_PRIM = {
    "byte": "Byte", "short": "Short", "int": "Int", "long": "Long",
    "float": "Float", "double": "Double", "char": "Char",
}
CONV = {"Byte": "toByte", "Short": "toShort", "Int": "toInt", "Long": "toLong",
        "Float": "toFloat", "Double": "toDouble", "Char": "toChar"}

# `xArrayOf(...)` is the one library signature worth hardcoding: it is how j2k
# emits a Java array initialiser, and `byteArrayOf('%', 'P')` is 5 errors.
BUILTIN_ARGS = {
    ("byteArrayOf", None): "Byte", ("shortArrayOf", None): "Short",
    ("intArrayOf", None): "Int", ("longArrayOf", None): "Long",
    ("floatArrayOf", None): "Float", ("doubleArrayOf", None): "Double",
    ("charArrayOf", None): "Char",
}


def widen(a, b):
    """Java's binary numeric promotion, for the pair we already know is mixed."""
    if a == b:
        return a
    if a == "Char" or b == "Char":
        # Char never widens *into*; the other side is the target.
        other = b if a == "Char" else a
        return other if other in RANK else None
    return a if RANK[a] > RANK[b] else b


# ---------------------------------------------------------------- tokenisation

_NUM = re.compile(r"0[xXbB][0-9a-fA-F_]+[uUlL]*|\d[\d_]*(?:\.[\d_]+)?(?:[eE][-+]?\d+)?[fFdDuUlL]*")
_ID = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
_OPS = ["...", "!!", "?:", "?.", "==", "!=", ">=", "<=", "&&", "||", "->", "::",
        "++", "--", "+=", "-=", "*=", "/=", "%=", "<<", ">>"]


def tokenize(s):
    """(kind, text, start, end, depth) over one line; strings/comments dropped.

    `depth` is the paren/bracket/brace nesting *before* the token."""
    toks = []
    i, n, depth = 0, len(s), 0
    while i < n:
        c = s[i]
        if c in " \t":
            i += 1
            continue
        if s.startswith("//", i):
            break
        if s.startswith("/*", i):
            j = s.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if s[j] == "\\":
                    j += 2
                    continue
                if s[j] == '"':
                    j += 1
                    break
                j += 1
            toks.append(("str", s[i:j], i, j, depth))
            i = j
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if s[j] == "\\":
                    j += 2
                    continue
                if s[j] == "'":
                    j += 1
                    break
                j += 1
            toks.append(("chr", s[i:j], i, j, depth))
            i = j
            continue
        m = _NUM.match(s, i)
        if m and c.isdigit():
            toks.append(("num", m.group(0), i, m.end(), depth))
            i = m.end()
            continue
        m = _ID.match(s, i)
        if m:
            toks.append(("id", m.group(0), i, m.end(), depth))
            i = m.end()
            continue
        if c in "([{":
            toks.append(("op", c, i, i + 1, depth))
            depth += 1
            i += 1
            continue
        if c in ")]}":
            depth -= 1
            toks.append(("op", c, i, i + 1, depth))
            i += 1
            continue
        for o in _OPS:
            if s.startswith(o, i):
                toks.append(("op", o, i, i + len(o), depth))
                i += len(o)
                break
        else:
            toks.append(("op", c, i, i + 1, depth))
            i += 1
    return toks


# tokens that end an operand when met at the operand's own depth
STOP_OPS = {",", ";", "=", "&&", "||", "?:", "?", ":", "->", "==", "!=",
            "<", ">", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "{", "}"}
STOP_IDS = {"return", "if", "else", "while", "when", "for", "is", "in", "to",
            "val", "var", "fun", "throw", "and", "or", "xor", "shl", "shr",
            "ushr", "until", "downTo", "step", "by", "as"}
ARITH = {"+", "-", "*", "/", "%"}
BITOPS = {"and", "or", "xor", "shl", "shr", "ushr"}
# a shift is not symmetric: `Long.shl` takes an *Int* count, and the result
# is the left operand's type.  Widening both sides is how Java reads, and
# it is wrong here.
SHIFTS = {"shl", "shr", "ushr"}
CMPOPS = {"==", "!=", "<", ">", "<=", ">="}


def _is_stop(tok):
    k, t = tok[0], tok[1]
    return (k == "op" and t in STOP_OPS) or (k == "id" and t in STOP_IDS)


def _match(toks, i, back):
    """Index of the bracket token matching toks[i], or None."""
    step = -1 if back else 1
    grow = ")]}" if back else "([{"
    shrink = "([{" if back else ")]}"
    d = 0
    j = i
    while 0 <= j < len(toks):
        t = toks[j]
        if t[0] == "op" and t[1] in grow:
            d += 1
        elif t[0] == "op" and t[1] in shrink:
            d -= 1
            if d == 0:
                return j
        j += step
    return None


def operand_span(toks, k, depth, back):
    """Widest run of tokens beside toks[k] that is one operand at `depth`.

    A bracket token carries the depth *outside* its group, so a parenthesised
    operand - `(a or b) or c`, `a or (b or c)` - shows up here as a bracket at
    our own depth.  Stepping over the whole group is what makes a chain of bit
    ops fold: without it the left operand of every `or` after the first is
    empty and the line is left alone.
    """
    step = -1 if back else 1
    i = k + step
    first = last = None
    while 0 <= i < len(toks):
        t = toks[i]
        if t[4] < depth:
            break
        if t[4] == depth and _is_stop(t):
            break
        if t[4] == depth and t[0] == "op" and t[1] in (")]}" if back else "([{"):
            j = _match(toks, i, back)     # a group that belongs to this operand
            if j is None:
                break
            lo, hi = min(i, j), max(i, j)
            first = lo if first is None else min(first, lo)
            last = hi if last is None else max(last, hi)
            i = j + step
            continue
        if t[4] == depth and t[0] == "op" and t[1] in ")]}([{":
            break                         # the bracket that closes/opens us
        if first is None:
            first = last = i
        else:
            first, last = min(first, i), max(last, i)
        i += step
    if first is None:
        return None
    return first, last


# ------------------------------------------------------------------ inference

class Env(object):
    """Everything infer() is allowed to know."""

    def __init__(self):
        self.prop = {}          # member/local name -> prim
        self.member = {}        # `val`/`var` declarations only -> prim
        self.melem = {}         # `val`/`var` array declarations -> element prim
        self.elem = {}          # array name -> element prim
        self.ret = {}           # (name, argc) -> prim
        self.ret_any = {}       # name -> prim, when every arity agrees
        self.params = {}        # (name, argc) -> [prim|None, ...]

    def lookup(self, name):
        return self.prop.get(name)


def _strip(toks):
    """Drop trailing `!!` and one layer of wrapping parens, repeatedly."""
    changed = True
    while changed and toks:
        changed = False
        while toks and toks[-1][0] == "op" and toks[-1][1] == "!!":
            toks = toks[:-1]
            changed = True
        if (len(toks) >= 2 and toks[0][1] == "(" and toks[-1][1] == ")"
                and toks[0][4] == toks[-1][4]):
            inner = toks[1:-1]
            if inner and all(t[4] > toks[0][4] for t in inner):
                toks = inner
                changed = True
    return toks


def infer(text, env, depth=0):
    if depth > 6:
        return None
    toks = _strip(tokenize(text))
    if not toks:
        return None
    base = min(t[4] for t in toks)

    # top-level arithmetic -> Java's binary numeric promotion
    for i in range(len(toks) - 1, 0, -1):
        t = toks[i]
        if t[4] == base and t[0] == "op" and t[1] in ARITH:
            prev = toks[i - 1]
            if prev[0] == "op" and prev[1] not in (")", "]", "!!"):
                continue                      # unary
            a = infer(text[toks[0][2]:prev[3]], env, depth + 1)
            b = infer(text[toks[i + 1][2]:toks[-1][3]], env, depth + 1)
            if a in PRIMS and b in PRIMS:
                w = widen(a, b)
                return "Int" if w in ("Byte", "Short", "Char") else w
            return None

    # top-level bit op -> Kotlin only has Int/Long forms
    for i in range(len(toks) - 1, 0, -1):
        t = toks[i]
        if t[4] == base and t[0] == "id" and t[1] in BITOPS and i > 0:
            a = infer(text[toks[0][2]:toks[i - 1][3]], env, depth + 1)
            b = infer(text[toks[i + 1][2]:toks[-1][3]], env, depth + 1)
            if t[1] in SHIFTS:                # a shift keeps the left type
                if a in ("Int", "Long"):
                    return a
                return "Int" if a in PRIMS else None
            if a in ("Int", "Long") and b in ("Int", "Long"):
                return widen(a, b)
            if a in PRIMS and b in PRIMS:
                return "Int"
            return None

    # `x as Int`
    for i in range(len(toks) - 1, 0, -1):
        t = toks[i]
        if t[4] == base and t[0] == "id" and t[1] == "as":
            tail = toks[i + 1:]
            if tail and tail[0][0] == "id" and tail[0][1] in PRIMS:
                return tail[0][1]
            return None

    last = toks[-1]

    # a call: `...name(args)`
    if last[0] == "op" and last[1] == ")":
        j = len(toks) - 1
        d = 0
        while j >= 0:
            if toks[j][1] == ")":
                d += 1
            elif toks[j][1] == "(":
                d -= 1
                if d == 0:
                    break
            j -= 1
        if j <= 0:
            return None
        callee = toks[j - 1]
        if callee[0] != "id":
            return None
        name = callee[1]
        if name in CONV.values():
            for p, c in CONV.items():
                if c == name:
                    return p
        argc = _count_args(toks, j, len(toks) - 1)
        return env.ret.get((name, argc), env.ret_any.get(name))

    # an index: `...name[i]`
    if last[0] == "op" and last[1] == "]":
        j = len(toks) - 1
        d = 0
        while j >= 0:
            if toks[j][1] == "]":
                d += 1
            elif toks[j][1] == "[":
                d -= 1
                if d == 0:
                    break
            j -= 1
        if j <= 0:
            return None
        base_tok = toks[j - 1]
        if base_tok[0] != "id":
            return None
        if j >= 2 and toks[j - 2][0] == "op" and toks[j - 2][1] in (".", "?."):
            t = env.melem.get(base_tok[1])
            if t is not None:
                return t
        return env.elem.get(base_tok[1])

    if last[0] == "num":
        return _literal_type(last[1])
    if last[0] == "chr":
        return "Char"
    if last[0] == "str":
        return None
    if last[0] == "id":
        if last[1] == "code":
            return "Int"
        if len(toks) == 1:
            return env.lookup(last[1])
        prev = toks[-2]
        if prev[0] == "op" and prev[1] in (".", "?."):
            t = env.member.get(last[1])
            return t if t is not None else env.prop.get(last[1])
    return None


def _count_args(toks, open_i, close_i):
    if close_i == open_i + 1:
        return 0
    d = toks[open_i][4]
    n = 1
    for t in toks[open_i + 1:close_i]:
        if t[4] == d + 1 and t[0] == "op" and t[1] == ",":
            n += 1
    return n


def _literal_type(t):
    t = t.replace("_", "")
    if t[-1] in "lL":
        return "Long"
    if t[-1] in "fF":
        return "Float"
    if t[-1] in "dD" and not t.lower().startswith("0x"):
        return "Double"
    if t.lower().startswith("0x") or t.lower().startswith("0b"):
        return "Int"
    if "." in t or "e" in t or "E" in t:
        return "Double"
    return "Int"


# ------------------------------------------------------------------- coercion

def coerce(expr, have, want):
    """Text that turns `expr` (type `have`) into `want`.  Never widens into Char
    from a variable, only from a literal, because `.toChar()` on a computed Int
    is exactly the Java cast j2k already emits elsewhere."""
    if have == want or want is None or have is None:
        return None
    e = expr.strip()
    if not e:
        return None
    if have == "Char":
        e = "(%s).code" % e
        have = "Int"
        if want == "Int":
            return e
    if want == "Char":
        return "(%s).%s()" % (e, CONV["Char"])
    if want not in CONV:
        return None
    if e.startswith("(") and e.endswith(")") and _balanced(e):
        return "%s.%s()" % (e, CONV[want])
    return "(%s).%s()" % (e, CONV[want])


def _balanced(e):
    d = 0
    for i, c in enumerate(e):
        if c == "(":
            d += 1
        elif c == ")":
            d -= 1
            if d == 0 and i != len(e) - 1:
                return False
    return d == 0


# --------------------------------------------------------------- corpus index

_CORPUS_CACHE = {}


def _merge(dst, key, val):
    if val is None:
        return
    if key in dst and dst[key] != val:
        dst[key] = "!"          # conflicting declarations: refuse to guess
    else:
        dst.setdefault(key, val)


def _clean(d):
    return {k: v for k, v in d.items() if v != "!"}


def _java_type(txt):
    txt = txt.strip()
    if txt.endswith("[]"):
        return None
    return JAVA_PRIM.get(txt)


def _java_elem(txt):
    txt = txt.strip()
    if txt.endswith("[]"):
        return JAVA_PRIM.get(txt[:-2].strip())
    return None


def _scan_java(src_root):
    """Field/method primitive types over the whole Java corpus, via tree-sitter."""
    prop, elem = {}, {}
    ret, params = {}, {}
    try:
        import tree_sitter_java
        from tree_sitter import Language, Parser
        parser = Parser(Language(tree_sitter_java.language()))
    except Exception:
        return prop, elem, ret, params

    def txt(src, node):
        return src[node.start_byte:node.end_byte].decode("utf8", "replace")

    def walk(src, node):
        for ch in node.named_children:
            t = ch.type
            if t in ("field_declaration", "local_variable_declaration"):
                ty = ch.child_by_field_name("type")
                if ty is not None:
                    ts = txt(src, ty)
                    for dec in ch.named_children:
                        if dec.type != "variable_declarator":
                            continue
                        nm = dec.child_by_field_name("name")
                        if nm is None:
                            continue
                        name = txt(src, nm)
                        dims = txt(src, dec).split("=")[0]
                        if "[" in dims or ts.endswith("[]"):
                            _merge(elem, name, _java_elem(ts) or JAVA_PRIM.get(ts.strip()))
                        else:
                            _merge(prop, name, _java_type(ts))
            elif t == "method_declaration":
                nm = ch.child_by_field_name("name")
                ty = ch.child_by_field_name("type")
                pl = ch.child_by_field_name("parameters")
                if nm is not None and pl is not None:
                    name = txt(src, nm)
                    ps = [p for p in pl.named_children
                          if p.type in ("formal_parameter", "spread_parameter")]
                    key = (name, len(ps))
                    if ty is not None:
                        _merge(ret, key, _java_type(txt(src, ty)))
                    sig = []
                    for p in ps:
                        pt = p.child_by_field_name("type")
                        sig.append(_java_type(txt(src, pt)) if pt is not None else None)
                    if key in params and params[key] != sig:
                        params[key] = "!"
                    else:
                        params.setdefault(key, sig)
            walk(src, ch)

    for dirpath, _dirs, files in os.walk(src_root):
        for f in files:
            if not f.endswith(".java"):
                continue
            p = os.path.join(dirpath, f)
            try:
                src = open(p, "rb").read()
            except OSError:
                continue
            try:
                walk(src, parser.parse(src).root_node)
            except Exception:
                continue
    return _clean(prop), _clean(elem), _clean(ret), _clean(params)


_KT_PROP = re.compile(r"\b(?:val|var)\s+([A-Za-z_$][\w$]*)\s*:\s*([A-Za-z]+)")
_KT_FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_$][\w$]*)\s*\(([^\n]*?)\)\s*:\s*([A-Za-z]+)")
_KT_ARR = {"ByteArray": "Byte", "ShortArray": "Short", "IntArray": "Int",
           "LongArray": "Long", "FloatArray": "Float", "DoubleArray": "Double",
           "CharArray": "Char"}


def _scan_stubs(out_root):
    """The library side, read back off the stubs another rule already wrote."""
    prop, elem, ret = {}, {}, {}
    stubs = os.path.join(out_root, "stubs")
    if not os.path.isdir(stubs):
        return prop, elem, ret
    for f in sorted(os.listdir(stubs)):
        if not f.endswith(".kt"):
            continue
        try:
            text = open(os.path.join(stubs, f), encoding="utf8", errors="replace").read()
        except OSError:
            continue
        for m in _KT_PROP.finditer(text):
            name, ty = m.group(1), m.group(2)
            if ty in PRIMS:
                _merge(prop, name, ty)
            elif ty in _KT_ARR:
                _merge(elem, name, _KT_ARR[ty])
        for m in _KT_FUN.finditer(text):
            name, args, ty = m.group(1), m.group(2), m.group(3)
            if ty not in PRIMS:
                continue
            argc = 0 if not args.strip() else args.count(",") + 1
            _merge(ret, (name, argc), ty)
    return _clean(prop), _clean(elem), _clean(ret)


def _corpus(ctx):
    key = (ctx.get("src_root"), ctx.get("out_root"))
    if key in _CORPUS_CACHE:
        return _CORPUS_CACHE[key]
    env = Env()
    prop, elem, ret, params = _scan_java(key[0])
    sprop, selem, sret = _scan_stubs(key[1])
    merged_prop = dict(sprop)
    for k, v in prop.items():                 # corpus wins over stubs
        merged_prop[k] = v
    env.prop = merged_prop
    env.elem = dict(selem)
    env.elem.update(elem)
    env.ret = dict(sret)
    env.ret.update(ret)
    env.params = params
    by_name = defaultdict(set)
    for (n, _a), v in env.ret.items():
        by_name[n].add(v)
    env.ret_any = {n: next(iter(v)) for n, v in by_name.items() if len(v) == 1}
    _CORPUS_CACHE[key] = env
    return env


# ------------------------------------------------------------ per-file scanning

_DECL = re.compile(r"\b([A-Za-z_$][\w$]*)\s*:\s*([A-Za-z]+)\b")
_FUNSIG = re.compile(r"^(\s*).*\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_$][\w$]*)\s*\(")


def _file_env(kotlin, corpus):
    env = Env()
    env.ret = corpus.ret
    env.ret_any = corpus.ret_any
    env.params = corpus.params
    prop, elem = {}, {}
    memb, melem = {}, {}
    for line in kotlin.split("\n"):
        s = line.strip()
        if s.startswith("//") or s.startswith("*"):
            continue
        for m in _DECL.finditer(line):
            name, ty = m.group(1), m.group(2)
            if ty in PRIMS:
                _merge(prop, name, ty)
            elif ty in _KT_ARR:
                _merge(elem, name, _KT_ARR[ty])
        # `val x: Short` is a member; `fun f(x: Int)` and `for (x: Int)` are
        # not.  Keeping them apart is what lets `recv.value` be typed in a
        # class that also has a parameter called `value` - the flat map drops
        # the name as contradictory, and then nothing is widened at all.
        for m in _KT_PROP.finditer(line):
            name, ty = m.group(1), m.group(2)
            if ty in PRIMS:
                _merge(memb, name, ty)
            elif ty in _KT_ARR:
                _merge(melem, name, _KT_ARR[ty])
    prop, elem = _clean(prop), _clean(elem)
    env.prop = dict(corpus.prop)
    env.prop.update(prop)                     # the file shadows the corpus
    env.elem = dict(corpus.elem)
    env.elem.update(elem)
    env.member = dict(corpus.prop)            # corpus fields are members too
    env.member.update(_clean(memb))
    env.melem = dict(corpus.elem)
    env.melem.update(_clean(melem))
    return env


def _return_types(lines):
    """line index -> declared primitive return type of the enclosing `fun`."""
    out = [None] * len(lines)
    stack = []                                # (brace depth at entry, prim)
    depth = 0
    for i, line in enumerate(lines):
        toks = tokenize(line)
        m = re.search(r"\bfun\s+(?:<[^>]*>\s*)?[A-Za-z_$][\w$]*\s*\(", line)
        prim = None
        if m:
            tail = line[m.end():]
            mm = re.search(r"\)\s*:\s*([A-Za-z]+)", tail)
            if mm and mm.group(1) in PRIMS:
                prim = mm.group(1)
        out[i] = stack[-1][1] if stack else None
        if prim is not None:
            out[i] = prim
        opens = sum(1 for t in toks if t[0] == "op" and t[1] == "{")
        closes = sum(1 for t in toks if t[0] == "op" and t[1] == "}")
        if prim is not None and opens > closes:
            stack.append((depth, prim))
        depth += opens - closes
        while stack and depth <= stack[-1][0]:
            stack.pop()
    return out


# ------------------------------------------------------------------- the edits

_HEX_LONG = re.compile(r"\b0[xX]([0-9a-fA-F_]+)[lL]\b")


def _fix_hex_long(line, stats):
    def sub(m):
        digits = m.group(1).replace("_", "")
        try:
            v = int(digits, 16)
        except ValueError:
            return m.group(0)
        if v < (1 << 63) or v >= (1 << 64):
            return m.group(0)
        stats["hex-long-range"] += 1
        return "-0x%xL" % ((1 << 64) - v)
    return _HEX_LONG.sub(sub, line)


def _apply(line, edits):
    for start, end, text in sorted(edits, key=lambda e: -e[0]):
        line = line[:start] + text + line[end:]
    return line


def _fix_binary(line, env, stats, which):
    """One pass over comparisons (`which='cmp'`) or bit ops (`which='bit'`)."""
    toks = tokenize(line)
    edits = []
    used = set()
    for k, t in enumerate(toks):
        if which == "cmp":
            hit = t[0] == "op" and t[1] in CMPOPS
        else:
            hit = t[0] == "id" and t[1] in BITOPS
        if not hit:
            continue
        d = t[4]
        ls = operand_span(toks, k, d, True)
        rs = operand_span(toks, k, d, False)
        if not ls or not rs:
            continue
        lo, ltext = (toks[ls[0]][2], toks[ls[1]][3]), None
        ro = (toks[rs[0]][2], toks[rs[1]][3])
        ltext = line[lo[0]:lo[1]]
        rtext = line[ro[0]:ro[1]]
        if lo in used or ro in used:
            continue
        a, b = infer(ltext, env), infer(rtext, env)
        if a not in PRIMS or b not in PRIMS:
            continue
        if which == "bit":
            if t[1] in SHIFTS:
                lt = a if a in ("Int", "Long") else "Int"
                if a in ("Float", "Double"):
                    continue
                for (span, text, have, want) in ((lo, ltext, a, lt),
                                                 (ro, rtext, b, "Int")):
                    if have == want:
                        continue
                    new = coerce(text, have, want)
                    if new is None:
                        continue
                    edits.append((span[0], span[1], new))
                    used.add(span)
                    stats["bit-%s->%s" % (have, want)] += 1
                continue
            if a in ("Int", "Long") and b in ("Int", "Long") and a == b:
                continue
            target = "Int"
            if "Long" in (a, b):
                target = "Long"
        else:
            if a == b:
                continue
            target = widen(a, b)
            if target is None:
                continue
        for (span, text, have) in ((lo, ltext, a), (ro, rtext, b)):
            if have == target:
                continue
            new = coerce(text, have, target)
            if new is None:
                continue
            edits.append((span[0], span[1], new))
            used.add(span)
            stats["%s-%s->%s" % (which, have, target)] += 1
    return _apply(line, edits)


_INIT = re.compile(r"^(\s*(?:[\w@]+\s+)*(?:val|var)\s+[A-Za-z_$][\w$]*\s*:\s*"
                   r"([A-Za-z]+)\s*=\s*)(\S.*?)\s*$")
_ASSIGN = re.compile(r"^(\s*)([A-Za-z_$][\w$]*(?:(?:\!\!)?(?:\.[A-Za-z_$][\w$]*|\[[^\]]*\]))*)"
                     r"\s*(=|\+=|-=|\*=|/=)\s*(\S.*?)\s*$")
_RETURN = re.compile(r"^(\s*return\s+)(\S.*?)\s*$")


def _rhs_end(text):
    """Trim a trailing line comment from a right-hand side."""
    toks = tokenize(text)
    return toks[-1][3] if toks else 0


def _fix_positions(line, env, ret_prim, stats):
    m = _INIT.match(line)
    if m and m.group(2) in PRIMS:
        want = m.group(2)
        rhs = m.group(3)
        cut = _rhs_end(rhs)
        body, tail = rhs[:cut], rhs[cut:]
        have = infer(body, env)
        if have in PRIMS and have != want:
            new = coerce(body, have, want)
            if new:
                stats["init-%s->%s" % (have, want)] += 1
                return m.group(1) + new + tail
        return line

    m = _RETURN.match(line)
    if m and ret_prim in PRIMS:
        rhs = m.group(2)
        cut = _rhs_end(rhs)
        body, tail = rhs[:cut], rhs[cut:]
        have = infer(body, env)
        if have in PRIMS and have != ret_prim:
            new = coerce(body, have, ret_prim)
            if new:
                stats["return-%s->%s" % (have, ret_prim)] += 1
                return m.group(1) + new + tail
        return line

    m = _ASSIGN.match(line)
    if m:
        target, rhs = m.group(2), m.group(4)
        if target.split(".")[-1].split("[")[0] in ("val", "var"):
            return line
        want = infer(target, env)
        cut = _rhs_end(rhs)
        body, tail = rhs[:cut], rhs[cut:]
        have = infer(body, env)
        if want in PRIMS and have in PRIMS and have != want:
            new = coerce(body, have, want)
            if new:
                stats["assign-%s->%s" % (have, want)] += 1
                return m.group(1) + target + " " + m.group(3) + " " + new + tail
    return line


def _fix_call_args(line, env, stats):
    toks = tokenize(line)
    edits = []
    for k, t in enumerate(toks):
        if not (t[0] == "op" and t[1] == "("):
            continue
        if k == 0 or toks[k - 1][0] != "id":
            continue
        name = toks[k - 1][1]
        if k >= 2 and toks[k - 2][0] == "id" and toks[k - 2][1] in ("fun", "class", "object"):
            continue
        # matching close
        d = t[4]
        close = None
        for j in range(k + 1, len(toks)):
            if toks[j][4] == d and toks[j][0] == "op" and toks[j][1] == ")":
                close = j
                break
        if close is None:
            continue
        argc = _count_args(toks, k, close)
        if argc == 0:
            continue
        sig = env.params.get((name, argc))
        uniform = None
        for (bn, ba), bt in BUILTIN_ARGS.items():
            if bn == name and (ba is None or ba == argc):
                uniform = bt
        if sig is None and uniform is None:
            continue
        if sig is not None and (sig == "!" or len(sig) != argc):
            sig = None
        if sig is None and uniform is None:
            continue
        # split arguments at depth d+1
        bounds = [k]
        for j in range(k + 1, close):
            if toks[j][4] == d + 1 and toks[j][0] == "op" and toks[j][1] == ",":
                bounds.append(j)
        bounds.append(close)
        for ai in range(argc):
            lo_t, hi_t = bounds[ai] + 1, bounds[ai + 1] - 1
            if lo_t > hi_t:
                continue
            want = uniform if sig is None else sig[ai]
            if want not in PRIMS:
                continue
            s, e = toks[lo_t][2], toks[hi_t][3]
            body = line[s:e]
            if "->" in body or "{" in body:
                continue
            have = infer(body, env)
            if have not in PRIMS or have == want:
                continue
            new = coerce(body, have, want)
            if new:
                edits.append((s, e, new))
                stats["arg-%s->%s" % (have, want)] += 1
    return _apply(line, edits)


# ------------------------------------------------------------------------- run

def transform(unit, ctx):
    # A/B switch, so the bucket can be measured with and without this rule.
    if os.environ.get("KN_NUMERIC_OFF"):
        return
    corpus = _corpus(ctx)
    env = _file_env(unit.kotlin, corpus)
    lines = unit.kotlin.split("\n")
    rets = _return_types(lines)
    stats = ctx["stats"]
    out = []
    in_block_comment = False
    for i, line in enumerate(lines):
        s = line.strip()
        if in_block_comment:
            if "*/" in line:
                in_block_comment = False
            out.append(line)
            continue
        if s.startswith("/*"):
            if "*/" not in s[2:]:
                in_block_comment = True
            out.append(line)
            continue
        if not s or s.startswith("//") or s.startswith("*") or s.startswith("import "):
            out.append(line)
            continue
        line = _fix_hex_long(line, stats)
        line = _fix_binary(line, env, stats, "bit")
        line = _fix_binary(line, env, stats, "cmp")
        line = _fix_call_args(line, env, stats)
        line = _fix_positions(line, env, rets[i], stats)
        out.append(line)
    new = "\n".join(out)
    if new != unit.kotlin:
        unit.kotlin = new
        stats[NAME] += 1
