"""Drop imports whose simple name never appears in the converted body.

Worked example of the rule API.  It exists because the converter deletes whole
categories of Java (annotations, `throws` clauses, JNI plumbing), which leaves
imports behind that resolve to nothing on Kotlin/Native - each one is a
compiler error for a type the file does not even use any more.
"""
import re

ORDER = 10
NAME = "unused-imports"
DESCRIPTION = "remove import lines whose simple name is unused after conversion"

_IMPORT = re.compile(r"^import\s+([\w.]+)(\.\*)?$")


def transform(unit, ctx):
    lines = unit.kotlin.split("\n")
    body = "\n".join(l for l in lines if not l.startswith("import "))
    kept, dropped = [], 0
    for line in lines:
        m = _IMPORT.match(line.strip())
        if m and not m.group(2):
            simple = m.group(1).rsplit(".", 1)[-1]
            if not re.search(r"\b%s\b" % re.escape(simple), body):
                dropped += 1
                continue
        kept.append(line)
    if dropped:
        unit.kotlin = "\n".join(kept)
        unit.imports = [l for l in kept if l.startswith("import ")]
        ctx["stats"]["unused-imports"] += dropped
