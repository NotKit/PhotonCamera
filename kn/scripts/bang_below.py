"""`= expr!!` where the NULL TEST IS A LINE OR TWO DOWN, unasserted.

Shared by convert.sh (kn/gen) and atlas-fixups.sh (kn/gen-atlas), which had a
copy each until round 5; there is one here so the two cannot drift.

j2k's nullability pass asserts `!!` on the right of `x = <expr>` and of
`var x: T? = <expr>`.  Its own r996 undoes the second only when the null test is
the VERY NEXT statement, and the first never -- and both the app's Java and
atlas's write the test a line or two later:

    CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
    CameraCharacteristics characteristics = CaptureController.mCameraCharacteristics;
    if (builder == null || characteristics == null) return;

    var current: CameraCaptureSession? = session!!
    if (ImageReader.DEBUG) Log.i(...)
    if (current != null) ...

There the assertion throws on exactly the path the null test was written for.
The proof that Java meant the value to be nullable is r996's: a null test on the
name.  The window is three statements, and it stops at any line that USES the
name first -- a dereference in between means Java had already decided the value
could not be null.
"""
import re

DECL = re.compile(r"^(\s*)(?:val|var)\s+(\w+)\s*:\s*[^=]*\?\s*=\s*(.+)!!$")
ASSIGN = re.compile(r"^(\s*)([\w.]+)\s*=\s*(.+)!!$")
# A declaration whose type is still NOT nullable after the arm above: there the
# assertion is holding the TYPES together, not only the value, so stripping it
# cannot help -- it turns a throw into a compile error.  Looked up by walking
# BACK from the assignment to the nearest declaration of that name, not over the
# whole file: `aperture` is a Float field of Parameters AND a Float? local of
# FillDynamicParameters, and a file-wide answer takes the field's.
def declared_nonnull(lines, upto, name):
    decl = re.compile(r"^\s*(?:val|var)\s+" + re.escape(name) + r"\s*:\s*([^=]+?)\s*=")
    for j in range(upto - 1, -1, -1):
        m = decl.match(lines[j])
        if m:
            return not m.group(1).rstrip().endswith("?")
    return False

# A Java local of BOXED type that the Java itself null-tests.  j2k maps
# `Integer` to `Int` -- nullable-emit leaves Kotlin primitives non-null, by
# design -- so
#
#     Integer sensivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
#     if (sensivity == null) { ... sensivity = ISO; }
#
# becomes `var sensivity: Int = result!!.get(...)!!` with a null test on a type
# that has no null: the assertion throws on exactly the absent key the Java
# falls back for, and the test below it is dead.  (Round 5: this is the NPE in
# Parameters.FillDynamicParameters that failed every HDRX burst.)  A null test
# on a primitive-typed local is proof of the mapping, so the declaration is made
# nullable and the assertion dropped; every later use already carries j2k's own
# `!!`, and one that does not is a compile error the build names.
PRIM_DECL = re.compile(
    r"^(\s*)(val|var)(\s+)(\w+)(\s*:\s*)"
    r"(Int|Long|Short|Byte|Float|Double|Boolean|Char)(\s*=\s*)(.+)!!$")


def box_nullable_primitive(lines):
    """`var x: Int = e!!` whose next statement null-tests x -> `var x: Int? = e`."""
    for i, line in enumerate(lines):
        m = PRIM_DECL.match(line)
        if not m:
            continue
        name = m.group(4)
        rhs = m.group(8)
        if rhs.count("(") != rhs.count(")") or rhs.count("[") != rhs.count("]"):
            continue
        test = re.compile(r"(?<![\w.$])(?:" + re.escape(name)
                          + r"\s*[!=]=\s*null|null\s*[!=]=\s*" + re.escape(name)
                          + r"(?![\w$]))")
        for j in range(i + 1, len(lines)):
            t = lines[j].strip()
            if not t or t.startswith("//"):
                continue
            if re.match(r"^(?:if|while)\s*\(", t) and test.search(lines[j]):
                lines[i] = (m.group(1) + m.group(2) + m.group(3) + name + m.group(5)
                            + m.group(6) + "?" + m.group(7) + rhs)
            break
    return lines


def strip_assignment_bang(text):
    lines = box_nullable_primitive(text.split("\n"))
    for i, line in enumerate(lines):
        m = DECL.match(line)
        if m:
            names = [m.group(2)]
        else:
            m = ASSIGN.match(line)
            if not m:
                continue
            names = [m.group(2).split(".")[-1]]
            bare = re.match(r"^(\w+)$", m.group(3))
            if bare:
                names.append(bare.group(1))
            if declared_nonnull(lines, i, names[0]):
                continue
        rhs = m.group(3)
        if rhs.count("(") != rhs.count(")") or rhs.count("[") != rhs.count("]"):
            continue
        alt = "|".join(re.escape(n) for n in names)
        test = re.compile(r"(?<![\w.$])(?:(?:" + alt + r")\s*[!=]=\s*null|null\s*[!=]=\s*(?:"
                          + alt + r")(?![\w$]))")
        mention = re.compile(r"(?<![\w.$])(?:" + alt + r")(?![\w$])")
        seen = 0
        for j in range(i + 1, len(lines)):
            t = lines[j].strip()
            if not t or t.startswith("//") or t in ("}", "},", "})"):
                continue
            if re.match(r"^(?:if|while)\s*\(", t) and test.search(lines[j]):
                lines[i] = line[:-2]
                break
            if mention.search(lines[j]):
                break
            seen += 1
            if seen >= 3:
                break
    return "\n".join(lines)
