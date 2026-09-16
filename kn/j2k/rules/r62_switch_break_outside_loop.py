"""Java's `break` leaves a `switch`; Kotlin's `when` has no such break.

j2k drops the *trailing* `break` of a case, which is most of them.  What is
left is the `break` buried inside an `if` in the middle of a case:

    when (x) { A -> { if (p) break; f() } }

Java means "leave the switch".  Kotlin means "leave the enclosing loop", and
when there is no enclosing loop that is `'break' and 'continue' are only
allowed inside loops`.  There is no Kotlin statement that means "leave this
`when`", so the statement is replaced with `Unit` - it keeps whatever `if`
held it well-formed, and this PoC does not run.

Only a `break`/`continue` with no enclosing loop *at all* is touched; inside a
loop the compiler already accepts it and rewriting would change behaviour for
no gain.  The enclosing-construct stack is read off the emitted text, which is
safe because j2k emits one statement per line and never wraps a brace.
"""
import re

ORDER = 62
NAME = "switch-break-outside-loop"
DESCRIPTION = "`break` inside a `when` that no loop encloses becomes `Unit`"

_LOOP = re.compile(r"(?<![\w.])(?:for|while|do)\s*[({]")
_JUMP = re.compile(r"(?<![\w.@])(break|continue)(?![\w@])")
_STR = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def _blank_strings(line):
    return _STR.sub(lambda m: '"' + " " * (len(m.group(0)) - 2) + '"', line)


def transform(unit, ctx):
    out = []
    loops = 0          # brace depth entries that are loops
    stack = []         # True for each open brace that opened a loop body
    changed = 0
    for raw in unit.kotlin.split("\n"):
        line = _blank_strings(raw)
        code = line.split("//", 1)[0]

        if loops == 0 and _JUMP.search(code) and "@" not in code:
            # a labelled break (`break@outer`) is a real loop jump j2k emitted
            # deliberately; only the bare one is the switch fallout.
            new = _JUMP.sub("Unit", raw)
            if new != raw:
                changed += 1
                raw = new
                line = _blank_strings(raw)
                code = line.split("//", 1)[0]

        opens_loop = bool(_LOOP.search(code))
        for ch in code:
            if ch == "{":
                stack.append(opens_loop)
                if opens_loop:
                    loops += 1
                opens_loop = False
            elif ch == "}":
                if stack and stack.pop():
                    loops -= 1
        out.append(raw)
    if changed:
        unit.kotlin = "\n".join(out)
        ctx["stats"][NAME] += changed
