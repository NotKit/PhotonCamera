#!/bin/bash
# Java -> Kotlin for the Kotlin/Native lane, through the j2k.py copied from
# firefox-atl/kn-app.  kn/gen/ is OUTPUT and is erased on every run: never edit
# it.  A construct that converts wrongly is fixed in the Java under
# app/src/main/java, so the Android build keeps working and the next run is
# right too.  Adding a rule under j2k/rules/ is the last resort.
#
#   kn/convert.sh            app/src/main/java minus drop.txt -> kn/gen/
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PY="${PYTHON:-$HOME/UT/kn-toolchain/venv/bin/python}"
SRC="$ROOT/app/src/main/java"
STAGE="$HERE/out/java-src"

# gen/ is replaced by one rename at the end, so a typecheck.sh running in
# another lane never sees a half-written tree.
GEN_NEW="$HERE/out/gen.new"
rm -rf "$STAGE" "$GEN_NEW"
mkdir -p "$STAGE"
# The app and the circularbarlib module (manual-mode models; its knob Views
# are in drop.txt) share one staging tree; their packages do not overlap.
LIB="$ROOT/circularbarlib/src/main/java"
for d in "$SRC" "$LIB"; do
    rsync -a --exclude-from="$HERE/drop.txt" \
          --include='*/' --include='*.java' --exclude='*' "$d/" "$STAGE/"
done
echo "java files staged: $(find "$STAGE" -name '*.java' | wc -l) (dropped: $(( $(find "$SRC" "$LIB" -name '*.java' | wc -l) - $(find "$STAGE" -name '*.java' | wc -l) )))"

# Every rule except the two "fiat" ones, which instrument lazy initialisers
# with a run-time logger (atl.fiat.*) that was GeckoView bring-up machinery.
RULES=$(ls "$HERE"/j2k/rules/*.py | xargs -n1 basename | sed 's/\.py$//' \
        | grep -v '^__init__$' | grep -vE '^r99[58]_' | paste -sd,)
"$PY" "$HERE/j2k/j2k.py" --src "$STAGE" --out "$GEN_NEW" --rules "$RULES" | tail -8

# j2k gives a field with no initialiser `= TODO()`, which K/N runs at the
# file's first touch and so fails the whole file. Give it Java's default.
"$PY" - "$GEN_NEW" <<'PYEOF'
import re, sys, pathlib
DEF = {"Int": "0", "Long": "0L", "Short": "0", "Byte": "0", "Float": "0f",
       "Double": "0.0", "Boolean": "false", "Char": "'\\u0000'"}
PROP = re.compile(r'^([ \t]*(?:[\w@.]+[ \t]+)*(?:val|var)[ \t]+`?[\w$]+`?[ \t]*:[ \t]*)([^=]+?)([ \t]*=[ \t]*)TODO\(\)[ \t]*$', re.M)
def sub(m):
    t = m.group(2).strip()
    v = "null" if t.endswith("?") else DEF.get(t)
    return m.group(0) if v is None else m.group(1) + m.group(2) + m.group(3) + v
n = 0
for f in pathlib.Path(sys.argv[1]).rglob("*.kt"):
    s = f.read_text(); r, k = PROP.subn(sub, s); n += k
    if k: f.write_text(r)
print(f"TODO() field initialisers defaulted: {n}")
PYEOF

# `null!!` IS A THROW, NOT A CHECK.  j2k's nullability pass asserts `!!` on
# every argument, and on the literal `null` that is an unconditional
# ThrowNullPointerException on a line where Java simply passed null.  r994 undoes
# it where it can prove the position takes null -- a super/this delegation, or an
# unqualified call in the same file -- and leaves the rest, which here is 70
# calls into the shims.  Every one of them throws the moment it is reached, so
# this can only turn a certain crash into the value Java passed; where the
# position really is non-null it is a COMPILE error, which the build names.
"$PY" - "$GEN_NEW" <<'PYEOF'
import re, sys, pathlib
# strings and line comments blanked, so a `null!!` inside either is left alone
TOKEN = re.compile(r'(?<![\w.$])null!!')
# AND Java's `(Buffer) null`, which j2k writes as `(null as Buffer)!!`: the same
# unconditional throw wearing a cast.  The cast is in the Java to pick an
# overload, so it stays -- made nullable, which is the overload Java chose.
# ESD4D's createKernelsMap died on `new GLTexture(size, format, (Buffer) null)`
# the moment the pipeline first reached the merge's combine pass.
CAST = re.compile(r'\(null as ([\w.<>, ?]+?)\)!!')
STR = re.compile(r'"(?:\\.|[^"\\])*"')
def blank(line):
    line = STR.sub(lambda m: '"' + " " * (len(m.group(0)) - 2) + '"', line)
    c = line.find("//")
    return line if c < 0 else line[:c] + " " * (len(line) - c)
def nullable(m):
    t = m.group(1).strip()
    return "(null as %s)" % (t if t.endswith("?") else t + "?")
n = c = 0
for f in pathlib.Path(sys.argv[1]).rglob("*.kt"):
    out, hit, chit = [], 0, 0
    for line in f.read_text().split("\n"):
        spans = [m.span() for m in CAST.finditer(blank(line))]
        for a, b in reversed(spans):
            line = line[:a] + nullable(CAST.match(line, a)) + line[b:]
        chit += len(spans)
        spans = [m.span() for m in TOKEN.finditer(blank(line))]
        for a, b in reversed(spans):
            line = line[:a] + "null" + line[b:]
        hit += len(spans)
        out.append(line)
    if hit or chit:
        f.write_text("\n".join(out))
        n += hit; c += chit
print(f"literal null!! restored to null: {n}")
print(f"`(null as T)!!` casts made nullable: {c}")
PYEOF

# `= expr!!` WHERE THE NULL TEST IS A LINE OR TWO DOWN is the same throw again,
# in the shape j2k's own r996 stops just short of.  scripts/bang_below.py says
# what the shape is and why; it is shared with atlas-fixups.sh, which had the
# only copy until round 5.  Found by TouchFocus.resetAutoFocus throwing in its
# constructor, before the camera was ever open.
"$PY" - "$HERE" "$GEN_NEW" <<'BANGEOF'
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(sys.argv[1]) / "scripts"))
from bang_below import strip_assignment_bang
from bang_delegate import strip_delegate_bang

n = 0
for f in pathlib.Path(sys.argv[2]).rglob("*.kt"):
    s = f.read_text()
    r = strip_assignment_bang(s)
    if r != s:
        f.write_text(r)
        n += sum(1 for a, b in zip(s.split("\n"), r.split("\n")) if a != b)
print(f"`= expr!!` with a null test below it, unasserted: {n}")
n = 0
for f in pathlib.Path(sys.argv[2]).rglob("*.kt"):
    s = f.read_text()
    r = strip_delegate_bang(s)
    if r != s:
        f.write_text(r)
        n += sum(1 for a, b in zip(s.split("\n"), r.split("\n")) if a != b)
print(f"`this(...)` delegation args on nullable targets, unasserted: {n}")
BANGEOF

# `this.field = param!!` WHERE BOTH SIDES ARE NULLABLE is the same throw once
# more, in the one position neither bang_below.py nor r996 reaches: a plain
# setter or a constructor whose parameter Java spelled nullable.
# scripts/bang_param.py says what the shape is and what proves it.  Found by
# ManualModeConsoleImpl.addKnobs' closing `setSelectedParam(null)`, which took
# the whole manual-mode console down before a single knob was drawn.
"$PY" - "$HERE" "$GEN_NEW" <<'PARAMEOF'
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(sys.argv[1]) / "scripts"))
from bang_param import strip_param_assignment_bang

n = 0
for f in pathlib.Path(sys.argv[2]).rglob("*.kt"):
    s = f.read_text()
    r = strip_param_assignment_bang(s)
    if r != s:
        f.write_text(r)
        n += sum(1 for a, b in zip(s.split("\n"), r.split("\n")) if a != b)
print(f"`this.field = param!!` on a nullable pair, unasserted: {n}")
PARAMEOF

# A `for` LOOP'S UPDATE IS THE LAST STATEMENT OF THE `while` j2k writes, so a
# `continue` in the body skips it and the loop spins on the same index for
# ever -- silently, at 100% of a core, with no exception and no output.
# scripts/for_continue.py says what the shape is; it is SHARED with
# atlas-fixups.sh so the two lanes cannot drift.  ESD4D's noise fit and its
# merge loop both hung on it and both read as a GPU stall for a whole round.
"$PY" "$HERE/scripts/for_continue.py" "$GEN_NEW"

# j2k emits GeckoView's generated surface (aidl stand-ins, android.R, the JDK
# TODO() stubs) for any --src.  The shims under src/commonMain are real
# implementations, so the stub surface is not wanted; only the converted
# app and the java-idiom shim stay.
rm -rf "$GEN_NEW/org" "$GEN_NEW/stubs" "$GEN_NEW/android"
rm -rf "$HERE/gen.old"; [ -d "$HERE/gen" ] && mv "$HERE/gen" "$HERE/gen.old"
mv "$GEN_NEW" "$HERE/gen"; rm -rf "$HERE/gen.old"
echo "gen/: $(find "$HERE/gen" -name '*.kt' | wc -l) files, $(cat $(find "$HERE/gen" -name '*.kt') | wc -l) lines, $(grep -rho 'TODO()' "$HERE/gen" | wc -l) TODO()"
