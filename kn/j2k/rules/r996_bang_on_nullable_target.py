"""`expr!!` in a position that Java lets be null.

j2k's nullability policy (rules/nullable-emit, ORDER 50) makes every declared
reference type `T?` and then asserts `!!` on dereference receivers, on call
arguments and on returned expressions.  On a *receiver* the assertion is
load-bearing.  In the other two positions, and at an initialiser, the target
type is already spelled `T?`, so the assertion buys nothing at compile time and
costs a NullPointerException at run time on exactly the paths Java wrote a null
check for:

    val display: Display? = sScreenCompat!!.getDisplay(sDisplayId!!)!!
    if (display != null) {                  <- dead; the !! already threw

r994_ctor_init_safety made this argument for the literal `null!!`.  This rule is
the same argument for a general expression, in the three positions where the
target's declared nullability is readable from the text:

  init    `val/var NAME: T? = <expr>!!` where the next statement null-tests
          NAME.  The null test is what proves Java meant it to be nullable;
          without it the rewrite would be a bare warning fix, and this lane
          only makes changes it can point at a behaviour for.
  return  `return <expr>!!`, and `fun f(): T? = <expr>!!`, where the enclosing
          function's declared return type ends in `?`.  A nullable return type
          accepts a nullable expression, so the assertion is pure loss.  This
          includes `return null!!`, which r994 deliberately left alone because
          it could not see the return type from an argument's point of view.
  arg     a BARE IDENTIFIER argument, `f(name!!)`, where the parameter it lands
          on is nullable.  Two ways to know that, both without a type checker:
            same-file   an unqualified call to a `fun` declared in this file,
                        matched on (name, arity) -- r994's rule, generalised
                        from the null literal to an identifier.
            qualified   `recv!!.m(...)` where `recv`'s declared type is in this
                        file's text and the corpus JAVA declares `m` at that
                        arity on that type with a reference parameter there.
                        j2k emits every reference parameter as `T?`, so a
                        reference parameter in the Java is a nullable one in
                        the Kotlin.
          Only a bare identifier: an arbitrary chain in argument position can
          feed a generic call whose inference the `!!` is holding up, and that
          would be a silent change rather than a loud one.

NOTHING IS INVENTED.  Every arm deletes two characters and leaves the value
Java itself computed.  The direction is safe to reason about one way: an `expr!!`
that is reached on a null ALWAYS throws today, so removing it can only turn a
throw into the value Java passed.  What it can do is fail to compile, where the
callee's parameter is really non-null (an override pinned by a stub signature);
that is a type error, not a silent change, and `build.sh` is what rules it out.

Every rewrite is listed in kn-poc/bang-on-nullable.tsv.

ORDER 996: after r994 (994), whose `null!!` -> `null` this rule must see already
done, and after r995 (995), which rewrites `= TODO()` initialisers this rule
skips anyway.
"""
import atexit
import os
import re
import sys
from collections import Counter, defaultdict

ORDER = 996
NAME = "bang-on-nullable-target"
DESCRIPTION = ("drop `!!` at an initialiser, a return or an argument whose "
               "declared target type is nullable")

LANE = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
MAP = os.path.join(LANE, "bang-on-nullable.tsv")

_ROWS = []
_STATS = Counter()

PRIMS = {"int", "long", "short", "byte", "char", "float", "double", "boolean",
         "void"}

KEYWORD_CALL = {"if", "while", "for", "when", "catch", "return", "throw",
                "switch", "synchronized", "super", "this", "do", "else",
                "assert", "TODO"}

_STR = re.compile(r'"(?:\\.|[^"\\])*"')
IDENT = r"[A-Za-z_$][A-Za-z0-9_$]*"

DECL = re.compile(r"^[ \t]*(?:val|var)[ \t]+(?P<name>" + IDENT +
                  r")[ \t]*:[ \t]*(?P<type>[^=]+?)[ \t]*=[ \t]*(?P<rhs>.+)$")
RET = re.compile(r"^[ \t]*return[ \t]+(?P<rhs>.+)$")
FUNSIG = re.compile(r"^[ \t]*(?:(?:public|private|protected|internal|open|final|"
                    r"override|abstract|external|operator|suspend|inline)[ \t]+)*"
                    r"fun[ \t]+(?:<[^>]*>[ \t]*)?(?P<name>`?" + IDENT + r"`?)"
                    r"[ \t]*\((?P<params>.*)\)[ \t]*:[ \t]*(?P<ret>[^={]+?)"
                    r"[ \t]*(?P<tail>[={])[ \t]*(?P<body>.*)$")
FUNANY = re.compile(r"^[ \t]*(?:(?:public|private|protected|internal|open|final|"
                    r"override|abstract|external|operator|suspend|inline)[ \t]+)*"
                    r"fun[ \t]")
# `val x: T` / `var x: T` / `x: T` in a parameter list -- the file's name -> type map
NAMED_TYPE = re.compile(r"(?:^|[(,][ \t]*|\b(?:val|var)[ \t]+)(" + IDENT +
                        r")[ \t]*:[ \t]*(" + IDENT + r"(?:\.[A-Za-z0-9_$]+)*)"
                        r"[ \t]*[?]?")


# ---------------------------------------------------------------- masking
def blank_code(s):
    """`s` with string literals and the line comment blanked, length kept."""
    s = _STR.sub(lambda m: '"' + " " * (len(m.group(0)) - 2) + '"', s)
    k = s.find("//")
    return s if k < 0 else s[:k] + " " * (len(s) - k)


def balanced(s):
    d = 0
    for ch in s:
        if ch in "([{":
            d += 1
        elif ch in ")]}":
            d -= 1
            if d < 0:
                return False
    return d == 0


def split_top(s):
    if not s.strip():
        return []
    out, d, last = [], 0, 0
    for k, ch in enumerate(s):
        if ch in "(<[{":
            d += 1
        elif ch in ")>]}":
            d -= 1
        elif ch == "," and d == 0:
            out.append((last, k))
            last = k + 1
    out.append((last, len(s)))
    return out


# ------------------------------------------------- the corpus's Java, once
_JAVA_INDEX = None


def java_index(src_root):
    """simple type name -> {(method, arity): [param is a reference type]}.

    Built once, from the Java the corpus is converted FROM.  A (name, arity)
    with two disagreeing declarations on the same type is dropped: an overload
    this rule cannot tell apart is one it must not touch.
    """
    global _JAVA_INDEX
    if _JAVA_INDEX is not None:
        return _JAVA_INDEX
    from tree_sitter import Language, Parser
    import tree_sitter_java
    parser = Parser(Language(tree_sitter_java.language()))
    idx = defaultdict(dict)
    bad = defaultdict(set)

    def txt(src, n):
        return src[n.start_byte:n.end_byte].decode("utf8", "replace")

    def _tp_names(src, n):
        if n is None:
            return frozenset()
        return frozenset(txt(src, c).split()[-1].split("<")[0]
                         for c in n.named_children if c.type == "type_parameter")

    def walk(src, node, owner, tparams):
        for ch in node.named_children:
            if ch.type in ("class_declaration", "interface_declaration",
                           "enum_declaration", "record_declaration"):
                nm = ch.child_by_field_name("name")
                tp = ch.child_by_field_name("type_parameters")
                walk(src, ch, txt(src, nm) if nm is not None else owner,
                     tparams | _tp_names(src, tp))
                continue
            if ch.type == "method_declaration" and owner:
                nm = txt(src, ch.child_by_field_name("name"))
                ps = ch.child_by_field_name("parameters")
                mods = next((c for c in ch.children
                             if c.type == "modifiers"), None)
                mine = tparams | _tp_names(
                    src, ch.child_by_field_name("type_parameters"))
                flags = []
                for p in (ps.named_children if ps is not None else []):
                    if p.type not in ("formal_parameter", "spread_parameter"):
                        continue
                    t = p.child_by_field_name("type")
                    tt = "" if t is None else txt(src, t)
                    flags.append(tt not in PRIMS and tt.split("<")[0].split("[")[0]
                                 not in mine)
                key = (nm, len(flags))
                # An @Override's Kotlin signature is pinned by the stub it
                # overrides, not by nullable-emit's policy (see nullable-emit,
                # pass A), so the Java type does not decide its nullability.
                # A type variable is not a reference type this rule can read
                # either.  Both are dropped rather than guessed.
                if (mods is not None and "@Override" in txt(src, mods)) \
                        or (key in idx[owner] and idx[owner][key] != flags):
                    bad[owner].add(key)
                idx[owner][key] = flags
            walk(src, ch, owner, tparams)

    for root, _d, files in os.walk(src_root):
        for f in files:
            if not f.endswith(".java"):
                continue
            p = os.path.join(root, f)
            try:
                src = open(p, "rb").read()
                walk(src, parser.parse(src).root_node, None, frozenset())
            except Exception:                              # noqa: BLE001
                continue
    for owner, keys in bad.items():
        for k in keys:
            idx[owner].pop(k, None)
    _JAVA_INDEX = dict(idx)
    _STATS["java-index-types"] = len(_JAVA_INDEX)
    return _JAVA_INDEX


# ------------------------------------------------- this file's own tables
def kotlin_fun_params(lines):
    """(fun name, arity) -> [param is declared nullable], for THIS file.

    Ambiguous keys are dropped, same reason as above.
    """
    out, bad = {}, set()
    for line in lines:
        m = re.match(r"^[ \t]*(?:(?:public|private|protected|internal|open|final|"
                     r"override|abstract|external|operator|suspend|inline)[ \t]+)*"
                     r"fun[ \t]+(?:<[^>]*>[ \t]*)?(?P<name>`?" + IDENT +
                     r"`?)[ \t]*\((?P<params>.*)\)[ \t]*[:{=]", line)
        if not m:
            continue
        ps = m.group("params")
        if not balanced(ps):
            continue
        flags = []
        for a, b in split_top(ps):
            p = ps[a:b]
            t = p.split(":", 1)[1].strip() if ":" in p else ""
            t = t.split("=", 1)[0].strip()
            flags.append(t.endswith("?"))
        key = (m.group("name").strip("`"), len(flags))
        if key in out and out[key] != flags:
            bad.add(key)
        out[key] = flags
    for k in bad:
        out.pop(k, None)
    return out


def kotlin_decl_types(lines):
    """identifier -> declared simple type name, over the whole file.

    Fields, locals and parameters together; a name declared twice with two
    types is dropped.  Coarse on purpose -- it is only ever used to pick a
    row out of the Java index, and a wrong row cannot silently survive the
    (method, arity) match plus the compiler.
    """
    out, bad = {}, set()
    for line in lines:
        b = blank_code(line)
        for m in NAMED_TYPE.finditer(b):
            nm, ty = m.group(1), m.group(2)
            # `Consumer<Delegate>` -- a generic receiver's parameter may be a
            # type variable, and `Thread.UncaughtExceptionHandler` is not a
            # simple name the corpus index is keyed by.  Neither resolves.
            if "." in ty or b[m.end():m.end() + 1] == "<":
                bad.add(nm)
                continue
            if nm in out and out[nm] != ty:
                bad.add(nm)
            out[nm] = ty
    for k in bad:
        out.pop(k, None)
    return out


# ------------------------------------------------------------- the arms
def trailing_bang(code_line):
    """Offset of the `!!` that ends the line's code, or None."""
    s = code_line.rstrip()
    if not s.endswith("!!"):
        return None
    return len(s) - 2


def return_types(lines):
    """line index -> declared return type of the enclosing `fun`, or None.

    A stack keyed on brace depth, so a nested `object : X { fun ... }` does not
    leak its return type onto the outer function's returns.  A signature that
    spans lines is simply not matched, and every return inside it is skipped.
    """
    out = [None] * len(lines)
    stack = []          # (depth at which the fun opened, return type)
    depth = 0
    for i, line in enumerate(lines):
        b = blank_code(line)
        while stack and depth < stack[-1][0]:
            stack.pop()
        out[i] = stack[-1][1] if stack else None
        m = FUNSIG.match(b)
        if m and m.group("tail") == "{" and balanced(m.group("params")):
            out[i] = m.group("ret").strip()
            stack.append((depth + b.count("{") - b.count("}"), out[i]))
        elif FUNANY.match(b):
            # a `fun` this rule cannot read: shadow the enclosing one
            stack.append((depth + b.count("{") - b.count("}"), None))
            out[i] = None
        depth += b.count("{") - b.count("}")
    return out


def arg_bangs(b, funs, decls, jidx):
    """Offsets of `!!` on bare-identifier arguments that land on a nullable
    parameter.  `b` is the code-masked line."""
    spans = []
    for m in re.finditer(r"(?<![\w.$])(?P<head>" + IDENT + r")(?P<bang>!!)?"
                         r"(?P<dot>\.)?(?P<meth>" + IDENT + r")?[ \t]*\(", b):
        if m.group("dot"):
            recv, meth = m.group("head"), m.group("meth")
            if meth is None:
                continue
            ty = decls.get(recv)
            flags_by_arity = jidx.get(ty) if ty else None
            if flags_by_arity is None:
                continue
            lookup = lambda n: flags_by_arity.get((meth, n))   # noqa: E731
        else:
            if m.group("bang") or m.group("meth"):
                continue
            name = m.group("head")
            if name in KEYWORD_CALL:
                continue
            # a receiver before it means this is not an unqualified call
            if b[:m.start("head")].rstrip().endswith((".", "?", "!")):
                continue
            lookup = lambda n: funs.get((name, n))             # noqa: E731
        o = m.end() - 1                       # the '('
        d, j = 0, o + 1
        while j < len(b):
            c = b[j]
            if c in "([{":
                d += 1
            elif c == ")" and d == 0:
                break
            elif c in ")]}":
                d -= 1
            j += 1
        if j >= len(b):
            continue
        args = b[o + 1:j]
        parts = split_top(args)
        flags = lookup(len(parts))
        if not flags or len(flags) != len(parts):
            continue
        for k, (a0, a1) in enumerate(parts):
            if not flags[k]:
                continue
            arg = args[a0:a1]
            am = re.match(r"^[ \t]*(" + IDENT + r")!![ \t]*$", arg)
            if not am:
                continue
            spans.append(o + 1 + a0 + am.end(1))
    return spans


def rewrite(text, rel, src_root):
    lines = text.split("\n")
    rets = return_types(lines)
    funs = kotlin_fun_params(lines)
    decls = kotlin_decl_types(lines)
    jidx = java_index(src_root) if src_root else {}
    rows = []
    for i, line in enumerate(lines):
        b = blank_code(line)
        cuts = []
        arm = []
        t = trailing_bang(b)
        if t is not None:
            # ---- init: `val NAME: T? = <expr>!!`, next statement null-tests NAME
            m = DECL.match(b)
            if m and m.group("type").rstrip().endswith("?") \
                    and balanced(m.group("rhs")) \
                    and not m.group("rhs").lstrip().startswith("TODO("):
                nm = re.escape(m.group("name"))
                j = i + 1
                while j < len(lines) and (not lines[j].strip()
                                          or lines[j].lstrip().startswith("//")):
                    j += 1
                if j < len(lines) and re.match(r"^[ \t]*(?:if|while)[ \t]*\(", lines[j]) \
                        and re.search(r"(?<![\w.$])(?:" + nm + r"[ \t]*[!=]=[ \t]*null"
                                      r"|null[ \t]*[!=]=[ \t]*" + nm + r"(?![\w$]))",
                                      blank_code(lines[j])):
                    cuts.append(t)
                    arm.append("init")
            # ---- return: `return <expr>!!` under a nullable return type
            if not cuts:
                m = RET.match(b)
                if m and rets[i] and rets[i].endswith("?") \
                        and balanced(m.group("rhs")) \
                        and not m.group("rhs").lstrip().startswith("TODO("):
                    cuts.append(t)
                    arm.append("return")
            # ---- expression body: `fun f(): T? = <expr>!!`
            if not cuts:
                m = FUNSIG.match(b)
                if m and m.group("tail") == "=" \
                        and m.group("ret").strip().endswith("?") \
                        and balanced(m.group("params")) \
                        and m.group("body").strip() \
                        and not m.group("body").lstrip().startswith("TODO("):
                    cuts.append(t)
                    arm.append("return")
        # ---- arg: `f(name!!)` onto a nullable parameter
        for off in arg_bangs(b, funs, decls, jidx):
            if off not in cuts:
                cuts.append(off)
                arm.append("arg")
        if not cuts:
            continue
        new = line
        for off in sorted(cuts, reverse=True):
            new = new[:off] + new[off + 2:]
        lines[i] = new
        for a in sorted(set(arm)):
            _STATS[a] += arm.count(a)
        rows.append(dict(file=rel, line=i + 1, arm=",".join(sorted(set(arm))),
                         detail=line.strip()))
    return "\n".join(lines), rows


# ---------------------------------------------------------------- rule API
def transform(unit, ctx):
    new, rows = rewrite(unit.kotlin, unit.out_rel, ctx.get("src_root"))
    if rows:
        unit.kotlin = new
        _ROWS.extend(rows)
        ctx["stats"]["bang-on-nullable-target"] += len(rows)


@atexit.register
def _flush():
    if not _ROWS:
        return
    with open(MAP, "w") as fh:
        fh.write("# kn-poc/bang-on-nullable.tsv -- rules/r996_bang_on_nullable_target.py\n")
        fh.write("# init    `val NAME: T? = expr!!` with the next statement null-testing NAME\n")
        fh.write("# return  `return expr!!` / `fun f(): T? = expr!!` under a nullable return type\n")
        fh.write("# arg     `f(name!!)` where the parameter it lands on is nullable\n")
        fh.write("# detail is the line BEFORE the rewrite.\n")
        fh.write("\t".join(("file", "line", "arm", "detail")) + "\n")
        for r in _ROWS:
            fh.write("\t".join(str(r[k]) for k in
                               ("file", "line", "arm", "detail")) + "\n")
    sys.stderr.write("[bang-on-nullable] %d lines -> %s\n" % (len(_ROWS), MAP))
    for k, v in sorted(_STATS.items()):
        sys.stderr.write("[bang-on-nullable]   %-24s %d\n" % (k, v))
