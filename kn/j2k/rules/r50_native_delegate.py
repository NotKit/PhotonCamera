"""A converted `native` method delegates to `photoncam.natives.<Class>`.

The single new conversion rule this port adds; the natives lane owns it.

Java's `native` keyword has no Kotlin/Native equivalent - there is no JNI at
all - so j2k emits every native method with a `TODO("native")` body.  That is
58 methods across five classes, and a `TODO()` body hides a crash rather than
naming one, so each is rewritten into a call to the hand-written binding of the
same name:

    private native void setWhiteLevel(long nativePtr, double whiteLevel);

    fun setWhiteLevel(nativePtr: Long, whiteLevel: Double) {
        photoncam.natives.DngCreator.setWhiteLevel(nativePtr, whiteLevel)
    }

The binding objects are in kn/src/commonMain/kotlin/photoncam/natives/, one per
Java class, with the Java's method names and Kotlin-mapped signatures; under
them is a plain C ABI (kn/host/natives/photoncam_native.h) over the app's own
JNI code, which is never edited.

Notes on the shape:

  * the call is fully qualified.  `photoncam.natives.DngCreator` written short
    inside `class DngCreator` would resolve to the converted class itself, and
    the method would call itself.
  * static and instance natives are treated alike, because the Java already
    passes the handle explicitly (`destroy(nativePtr)`, `nativeClose(ctx)`);
    nothing here needs a receiver.  A native that took its handle from `this`
    would need the binding to take it, and this rule would still pass only the
    declared parameters - the note below would then be the warning.
  * `TODO("native")` is an exact marker: j2k emits it for native methods and
    for nothing else, so the count of rewrites equals the count of natives.
"""

ORDER = 50
NAME = "native-delegate"
DESCRIPTION = "`native` methods delegate to photoncam.natives.<Class>.<method>"

_MARKER = 'TODO("native")'
_TARGET_PACKAGE = "photoncam.natives"


def _split_params(text):
    """Parameter names of a Kotlin parameter list, generics-aware."""
    names, depth, start = [], 0, 0
    for i, ch in enumerate(text):
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth -= 1
        elif ch == "," and depth == 0:
            names.append(text[start:i])
            start = i + 1
    names.append(text[start:])

    out = []
    for part in names:
        part = part.strip()
        if not part:
            continue
        # "vararg x: T" / "x: T" / "noinline f: () -> Unit"
        head = part.split(":", 1)[0].strip()
        out.append(head.split()[-1])
    return out


def _parse_fun(line):
    """(name, [param names], has_return) for a one-line `fun ... {`, else None."""
    stripped = line.strip()
    if not stripped.endswith("{"):
        return None
    at = stripped.find("fun ")
    if at == -1:
        return None
    rest = stripped[at + 4:]

    open_paren = rest.find("(")
    if open_paren == -1:
        return None
    name = rest[:open_paren].strip()
    if not name.isidentifier():
        return None

    depth, close = 0, -1
    for i in range(open_paren, len(rest)):
        if rest[i] == "(":
            depth += 1
        elif rest[i] == ")":
            depth -= 1
            if depth == 0:
                close = i
                break
    if close == -1:
        return None

    params = _split_params(rest[open_paren + 1:close])
    tail = rest[close + 1:-1].strip()          # "" or ": Long"
    return name, params, tail.startswith(":") and tail[1:].strip() not in ("", "Unit")


def _enclosing_class(stack):
    """Innermost named class/object; a companion belongs to the one outside it."""
    for name in reversed(stack):
        if name:
            return name
    return None


def transform(unit, ctx):
    lines = unit.kotlin.split("\n")
    if _MARKER not in unit.kotlin:
        return

    out = []
    stack = []      # one entry per open brace: a class name, or None
    pending = None  # the `fun` line most recently opened
    rewritten = 0

    for raw in lines:
        code = raw.split("//", 1)[0]
        stripped = code.strip()

        if stripped == _MARKER and pending is not None:
            name, params, has_return = pending
            cls = _enclosing_class(stack)
            if cls:
                indent = raw[:len(raw) - len(raw.lstrip())]
                call = "%s.%s.%s(%s)" % (_TARGET_PACKAGE, cls, name, ", ".join(params))
                out.append(indent + ("return " + call if has_return else call))
                rewritten += 1
                # the brace bookkeeping below still has to see this line
                _track(code, stack)
                pending = None
                continue
            unit.notes.append("r50: native %s() has no enclosing class" % name)

        parsed = _parse_fun(code) if "fun " in code else None
        if parsed:
            pending = parsed

        out.append(raw)
        _track(code, stack)

    if rewritten:
        unit.kotlin = "\n".join(out)
        ctx["stats"][NAME] += rewritten

    left = unit.kotlin.count(_MARKER)
    if left:
        unit.notes.append("r50: %d native method(s) not delegated" % left)


def _track(code, stack):
    """Push a class name for every `{` opened, pop on `}`."""
    declared = _declared_name(code)
    for ch in code:
        if ch == "{":
            stack.append(declared)
            declared = None
        elif ch == "}":
            if stack:
                stack.pop()


def _declared_name(code):
    """The class/object this line declares, or None.

    A companion is anonymous, and so is an object *expression* (`object :
    Runnable {`, `= object {`) - there the token after `object` is not a name,
    which is what distinguishes the two.
    """
    tokens = code.replace("(", " ").split()
    for i, tok in enumerate(tokens):
        if tok in ("class", "interface", "object") and i + 1 < len(tokens):
            if tok == "object" and i > 0 and tokens[i - 1] == "companion":
                return None
            candidate = tokens[i + 1]
            return candidate if candidate.isidentifier() else None
    return None
