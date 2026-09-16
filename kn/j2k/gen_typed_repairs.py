#!/usr/bin/env python3
"""Turn a kotlinc-native error log into rules/typed_repairs.tsv.

Every error konanc reports comes with a caret span that brackets exactly the
expression it is unhappy about, and - for the type errors - both the type it
found and the type it wanted.  That is enough to write the conversion Java did
implicitly and the codemod could not, without any type inference of our own.

  operator '!=' cannot be applied to 'Long' and 'Int'   -> widen both sides
  argument type mismatch: actual 'Int', but 'Float'     -> `.toFloat()` it
  initializer / assignment / return type mismatch       -> the same

Anything else - an unresolved reference, a bad override, a candidate set with
no applicable member - has no mechanical repair, so the *enclosing member* is
stubbed with `TODO()` and counted as a defeat.

Usage:  gen_typed_repairs.py build.log [--out rules/typed_repairs.tsv]
        (re-run after every compile; the table is regenerated, not appended)
"""
import os
import re
import sys
from collections import Counter, defaultdict

OUT_ROOT = "/home/nekit/UT/firefox-src-kn/mobile/android/geckoview-kn/src"
HERE = os.path.dirname(os.path.abspath(__file__))

RANK = {"Byte": 1, "Short": 2, "Int": 3, "Long": 4, "Float": 5, "Double": 6}
CONV = {"Byte": "toByte", "Short": "toShort", "Int": "toInt", "Long": "toLong",
        "Float": "toFloat", "Double": "toDouble"}
NUM = set(RANK) | {"Char"}

ERR = re.compile(r"^(/\S+\.kt):(\d+):(\d+): error: (.*)$")
OPER = re.compile(r"^operator '(\S+)' cannot be applied to '(\w+)' and '(\w+)'")
ARG = re.compile(r"^(?:argument|assignment) type mismatch: actual type is "
                 r"'([\w<>?., ]+)', but '([\w<>?., ]+)' was expected")
INIT = re.compile(r"^initializer type mismatch: expected '([\w<>?., ]+)', "
                  r"actual '([\w<>?., ]+)'")
RET = re.compile(r"^return type mismatch: expected '([\w<>?., ]+)', "
                 r"actual '([\w<>?., ]+)'")

MODS = (r"(?:(?:public|private|internal|protected|open|final|abstract|"
        r"override|external|inline|infix|operator|suspend|const|lateinit|"
        r"tailrec|companion|data|sealed|enum|annotation|inner)\s+)*")
FUNDECL = re.compile(r"^(?:@\w[\w.()\"]*\s+)*" + MODS + r"(?:fun|init\b|constructor)\b")
PROPDECL = re.compile(r"^(?:@\w[\w.()\"]*\s+)*" + MODS + r"(?:val|var)\b")
TYPEDECL = re.compile(r"^(?:@\w[\w.()\"]*\s+)*" + MODS + r"(?:class|object|interface)\b")

DECL = re.compile(
    r"^(?:@\w[\w.()\"]*\s+)*"
    r"(?:(?:public|private|internal|protected|open|final|abstract|override|"
    r"external|inline|infix|operator|suspend|const|lateinit|tailrec|"
    r"companion)\s+)*"
    r"(?:fun|val|var|init\b|constructor)\b")


def parse(log_path):
    """(path, line, col, span_len, message) for every error with a caret."""
    lines = open(log_path, errors="replace").read().split("\n")
    out = []
    for i, ln in enumerate(lines):
        m = ERR.match(ln)
        if not m:
            continue
        # the caret line is the next line that is only spaces and '^'
        span = None
        for j in range(i + 1, min(i + 40, len(lines))):
            s = lines[j]
            if s.strip() and set(s.strip()) == {"^"}:
                span = len(s.strip())
                break
            if ERR.match(s):
                break
        out.append((m.group(1), int(m.group(2)), int(m.group(3)),
                    span, m.group(4)))
    return out


def split_top(text, op):
    """Index of `op` at paren/bracket/angle depth 0, outside strings."""
    depth = 0
    i = 0
    while i < len(text):
        c = text[i]
        if c == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
        elif depth == 0 and text.startswith(op, i):
            before = text[i - 1] if i else " "
            after = text[i + len(op)] if i + len(op) < len(text) else " "
            if not (before.isalnum() or after.isalnum() or after in "=<>"):
                return i
        i += 1
    return -1


def widen(expr, have, want):
    if have == want:
        return "(%s)" % expr
    if have == "Char":
        expr, have = "(%s).code" % expr, "Int"
        if want == "Int":
            return expr
    if want == "Char":
        return "(%s).toInt().toChar()" % expr
    return "(%s).%s()" % (expr, CONV[want])


def kind_of(msg):
    return "operator" if OPER.match(msg) else "numeric-coercion"


def repair(expr, msg):
    """The conversion Java did implicitly, or None if there is no such thing."""
    m = OPER.match(msg)
    if m and m.group(2) in NUM and m.group(3) in NUM:
        op = m.group(1)
        k = split_top(expr, op)
        if k > 0:
            a, b = m.group(2), m.group(3)
            want = max((a, b), key=lambda t: RANK.get(t, 3))
            if want == "Char":
                want = "Int"
            return "%s %s %s" % (widen(expr[:k].strip(), a, want), op,
                                 widen(expr[k + len(op):].strip(), b, want))
        return None
    for pat, order in ((ARG, "ae"), (INIT, "ea"), (RET, "ea")):
        m = pat.match(msg)
        if not m:
            continue
        have, want = (m.group(1), m.group(2)) if order == "ae" \
            else (m.group(2), m.group(1))
        have, want = have.rstrip("?"), want.rstrip("?")
        if have in NUM and want in NUM and have != want:
            return widen(expr, have, want)
        return None
    return None


def main():
    log = sys.argv[1]
    out_path = os.path.join(HERE, "rules", "typed_repairs.tsv")
    if "--out" in sys.argv:
        out_path = sys.argv[sys.argv.index("--out") + 1]

    files = {}

    def text(path):
        if path not in files:
            files[path] = open(path, errors="replace").read().split("\n")
        return files[path]

    rows = []
    seen = set()
    stubbed = defaultdict(set)
    kinds = Counter()
    skipped = []

    # group by source line: one line gets one repaired-line row, and a line
    # with even one unrepairable error is stubbed instead of patched.
    per_line = defaultdict(list)
    order = []
    for e in parse(log):
        path, line = e[0], e[1]
        if not path.startswith(OUT_ROOT):
            continue
        if (path, line) not in per_line:
            order.append((path, line))
        per_line[(path, line)].append(e)

    for path, line in order:
        rel = os.path.relpath(path, OUT_ROOT)
        if rel.startswith("stubs/"):
            skipped.append((rel, line, per_line[(path, line)][0][4]))
            continue
        src = text(path)
        if line - 1 >= len(src):
            continue
        ln = src[line - 1]
        edits = []
        ok = True
        for _, _, col, span_len, msg in per_line[(path, line)]:
            expr = ln[col - 1:col - 1 + span_len] if span_len else ""
            repl = repair(expr, msg) if len(expr) >= 2 else None
            if repl is None or repl == expr:
                ok = False
                break
            edits.append((col - 1, span_len, repl))
            kinds[kind_of(msg)] += 1

        if ok and edits:
            new_line = ln
            for off, n, repl in sorted(edits, reverse=True):
                new_line = new_line[:off] + repl + new_line[off + n:]
            key = (rel, ln.strip())
            if key not in seen and new_line.strip() != ln.strip():
                seen.add(key)
                rows.append((rel, "expr", ln.strip(), new_line.strip()))
            continue

        for k in list(kinds):
            pass
        # no mechanical repair: stub the member that contains the error
        i = line - 1
        hit = None
        prop = None
        indent = len(ln) - len(ln.lstrip())
        while i >= 0:
            s2 = src[i]
            st = s2.strip()
            ind = len(s2) - len(s2.lstrip())
            if st and ind <= indent:
                if FUNDECL.match(st):
                    hit = i
                    break
                if prop is None and PROPDECL.match(st):
                    prop = i
                if TYPEDECL.match(st):
                    # a class header with no enclosing fun: the property
                    # initialiser is the outermost thing we can stub.
                    hit = prop
                    break
            i -= 1
        if hit is None:
            hit = prop
        if hit is None:
            skipped.append((rel, line, per_line[(path, line)][0][4]))
            continue
        header = src[hit].strip()
        occ = sum(1 for k in range(hit) if src[k].strip() == header)
        key = (rel, header, occ)
        if key in stubbed[rel]:
            continue
        stubbed[rel].add(key)
        rows.append((rel, "stub", header, str(occ)))
        kinds["stub"] += 1

    with open(out_path, "w") as fh:
        fh.write("# GENERATED by kn-run/gen_typed_repairs.py -- see "
                 "rules/r70_typed_repairs.py\n")
        fh.write("# relpath\taction\tneedle\treplacement\n")
        for r in rows:
            fh.write("\t".join(x.replace("\n", "\\n") for x in r) + "\n")

    print("rows: %d  (%s)" % (len(rows), dict(kinds)))
    if skipped:
        print("unanchored/stub-file errors: %d" % len(skipped))
        for s in skipped[:10]:
            print("   ", s)


if __name__ == "__main__":
    main()
