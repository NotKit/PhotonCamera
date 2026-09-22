"""`this.field = param!!` WHERE BOTH SIDES ARE NULLABLE.

The third position of the same argument scripts/bang_below.py makes, and the
one neither it nor j2k's own r996 reaches.  j2k's nullability pass asserts `!!`
on the right of an assignment; in a plain setter or a constructor that is

    fun setSelectedParam(selectedParam: ManualParam?) {
        this.selectedParam = selectedParam!!      <- throws on `setSelectedParam(null)`

where the PARAMETER is spelled `T?` and the FIELD is spelled `T?`, so the
assertion buys nothing at compile time and costs a NullPointerException on the
one call Java wrote the nullable parameter for.  ManualModeConsoleImpl.addKnobs
ends with `manualModeModel.setSelectedParam(null)` -- nothing is on the dial
yet -- and that line took the whole manual-mode console down.

THE TEST IS THE TWO DECLARATIONS, both read off this file's own text:

  - the right-hand side is a BARE IDENTIFIER that the enclosing `fun` or
    `constructor` declares as a parameter of nullable type.  Not an arbitrary
    expression: a chain in assignment position can feed a generic inference the
    `!!` is holding up, and that would be a silent change rather than a loud one.
  - the field named on the left is declared `var`/`val NAME: T? =` in this file.
    Without that the assertion is holding the TYPES together and removing it is
    a compile error, not a fix.

NOTHING IS INVENTED.  Every rewrite deletes two characters and leaves the value
Java itself assigned, and the direction is safe to reason about one way: an
`expr!!` reached on a null ALWAYS throws today, so removing it can only turn a
throw into the value Java passed.  Where the field really is non-null the build
names the line.
"""
import re

SIG = re.compile(
    r"^\s*(?:(?:open|override|private|protected|internal|final|abstract|inline)\s+)*"
    r"(?:fun\s+[\w<>, ?.]+|constructor)\s*\((.*)\)"
)
ASSIGN = re.compile(r"^(\s*this\.(\w+)\s*=\s*(\w+))!!(\s*)$")


def _nullable_param(signature, name):
    """`name: T?` in a parameter list, T? ending at the top-level comma."""
    return re.search(
        r"(?<![\w.$])" + re.escape(name) + r"\s*:\s*[^,]*\?\s*(?:,|$)", signature
    ) is not None


def _nullable_field(text, name):
    m = re.search(
        r"^\s*(?:(?:private|protected|internal|open|final|lateinit|@\w+)\s+)*"
        r"(?:val|var)\s+" + re.escape(name) + r"\s*:\s*([^=\n]+?)\s*(?:=|$)",
        text, re.M,
    )
    return m is not None and m.group(1).rstrip().endswith("?")


def strip_param_assignment_bang(text):
    lines = text.split("\n")
    signature = ""
    for i, line in enumerate(lines):
        m = SIG.match(line)
        if m:
            signature = m.group(1)
            continue
        a = ASSIGN.match(line)
        if not a:
            continue
        field, param = a.group(2), a.group(3)
        if _nullable_param(signature, param) and _nullable_field(text, field):
            lines[i] = a.group(1) + a.group(4)
    return "\n".join(lines)
