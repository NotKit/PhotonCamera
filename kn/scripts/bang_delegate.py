"""`this(...)` delegation args j2k over-asserts.

j2k's nullability pass puts `!!` on every call argument. r996 undoes it for a
bare identifier landing on a nullable parameter of a same-file `fun`, but a
constructor's `this(...)` delegation is not a `fun` and falls through -- so a
parameter Java carries null through (GLTexture's `pixels`, which the main
constructor only uploads `if (pixels != null)`) throws on exactly the path
Java wrote the null for. Found by ESD4D's first GLTexture: the burst reached
the pipeline and died in the texture constructor, past the context the
previous round fixed.

The rule is r996's arg arm, pointed at delegations: a TOP-LEVEL bare
`IDENT!!` in a `this(...)` argument list loses its `!!` when the sibling
constructor it lands on declares that parameter nullable. `super(...)` is
skipped -- its target is another file and cannot be resolved here. Overloads
must agree: the assert is dropped only when EVERY same-arity sibling
constructor is nullable there, so an ambiguous call is left alone. Anything
left alone is today's behaviour, and anything rewritten either compiles (the
target takes null) or fails loudly at build time (it does not).

Shared by convert.sh (gen/) and atlas-fixups.sh (gen-atlas/): one copy, so
the two cannot drift.
"""

import re


def _split_top(s):
    """Split on top-level commas (blanked strings assumed not to contain any
    that matter; generated code keeps literals simple)."""
    parts, depth, cur = [], 0, []
    for ch in s:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return parts


_CTOR = re.compile(r"constructor\s*\(")
_BARE_BANG = re.compile(r"^([A-Za-z_]\w*)!!$")


def _parse_constructors(text):
    """[(params_raw, kind, args_raw_or_None)] for every constructor: kind is
    'this' with its delegation args, 'super', or 'plain'."""
    out = []
    for m in _CTOR.finditer(text):
        i = m.end()
        depth = 1
        while i < len(text) and depth > 0:
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
            i += 1
        params = text[m.end():i - 1]
        rest = text[i:i + 200]
        dm = re.match(r"\s*:\s*(this|super)\s*\(", rest)
        if dm:
            j = i + dm.end()
            depth = 1
            while j < len(text) and depth > 0:
                if text[j] == "(":
                    depth += 1
                elif text[j] == ")":
                    depth -= 1
                j += 1
            out.append((params, dm.group(1), text[i + dm.end():j - 1]))
        else:
            out.append((params, "plain", None))
    return out


def _param_nullable(param):
    """'name: Type?' -> True; anything unparseable -> False (leave alone)."""
    parts = _split_top(param)
    if len(parts) != 1:
        return False
    halves = parts[0].rsplit(":", 1)
    if len(halves) != 2:
        return False
    return halves[1].strip().endswith("?")


def strip_delegate_bang(text):
    ctors = _parse_constructors(text)
    targets = {}  # arity -> list of [nullable-per-position]
    for params, kind, _ in ctors:
        ps = _split_top(params)
        if any("vararg" in p for p in ps):
            continue
        targets.setdefault(len(ps), []).append([_param_nullable(p) for p in ps])
    if not targets:
        return text

    out = []
    last = 0
    for m in _CTOR.finditer(text):
        i = m.end()
        depth = 1
        while i < len(text) and depth > 0:
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
            i += 1
        rest = text[i:i + 200]
        dm = re.match(r"\s*:\s*this\s*\(", rest)
        if not dm:
            continue
        j = i + dm.end()
        depth = 1
        while j < len(text) and depth > 0:
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
            j += 1
        args = _split_top(text[i + dm.end():j - 1])
        cands = targets.get(len(args))
        if not cands:
            continue
        new_args = list(args)
        changed = False
        for pos, a in enumerate(args):
            bm = _BARE_BANG.match(a.strip())
            if not bm:
                continue
            if all(t[pos] for t in cands):
                new_args[pos] = args[pos].replace(bm.group(1) + "!!", bm.group(1), 1)
                changed = True
        if changed:
            out.append(text[last:i + dm.end()])
            out.append(",".join(new_args))
            last = j - 1
    if last == 0:
        return text
    out.append(text[last:])
    return "".join(out)
