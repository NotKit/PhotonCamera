"""Drop `private`/`protected` on declarations: Java's nesting sees through them, Kotlin's does not.

In Java a nested class may read the enclosing class's private members and the
enclosing class may read the nested class's, because privacy is per *top-level*
class.  In Kotlin privacy is per *class*, so every builder, every
Selector/Loader/BasePrompt inner type, every `outer.mNested.mField` walk that
the Java compiler waved through becomes `cannot access 'var mAlias': it is
private in '...ClientAuthCertificate'`.  `protected` breaks the same way, plus
a knock-on shape - a public getter whose return or parameter type is a private
nested class - reported as "'public' function exposes its 'private-in-class'
type".

Nothing in this PoC depends on visibility - it is one module, nobody links
against it, nobody runs it - so the cheapest correct answer is to have no
visibility at all.  The modifier is deleted rather than rewritten to
`internal`, because `internal` would only move the problem: a public member
whose type is an internal class is itself an exposure error, so a partial
rewrite has to be kept consistent across every declaration it touches, and
"delete" is the consistent rewrite that needs no bookkeeping.

Scope of the edit, deliberately narrow:

  * only where the modifier is the first token of a line and is followed by a
    declaration keyword (`class`, `fun`, `val`, `var`, `constructor`,
    `object`, `interface`, `init`, or another modifier such as `open` /
    `abstract` / `inner` / `enum` / `const`).  That is every declaration j2k
    emits, and it cannot match inside a string, because a string never starts
    a line at column 0 of a declaration.
  * plus the one mid-line form, `class Foo private constructor(`, which Kotlin
    puts after the class name rather than in front.
  * `internal` is never touched.  The one "it is internal in file" error in
    this bucket is unrelated - it is a call landing on an internal *stdlib*
    extension - and no generated file declares `internal` anyway.
  * `private set` / `protected set` are left alone: a bare `set` with no body
    is not valid Kotlin, so deleting the modifier there would break the file
    rather than fix it.

Comments are skipped by tracking `/* */` nesting, because the converter copies
Java comments through verbatim and GeckoView has plenty that begin with the
word "private".
"""
import re

ORDER = 60
NAME = "java-outer-private-access"
DESCRIPTION = "drop private/protected on declarations - Kotlin scopes them per class, Java per file"

# Modifiers and declaration keywords that may legally follow `private`.
_FOLLOWS = (
    "class|object|interface|fun|val|var|constructor|init|typealias|enum|"
    "annotation|data|value|sealed|open|abstract|final|inner|companion|const|"
    "lateinit|external|operator|infix|inline|suspend|tailrec|expect|actual|"
    "override|vararg"
)

_LEAD = re.compile(r"^(\s*)((?:(?:private|protected)\s+)+)(?=(?:%s)\b)" % _FOLLOWS)
_INLINE_CTOR = re.compile(r"\b(?:private|protected)\s+(?=constructor\b)")


def transform(unit, ctx):
    out = []
    dropped = 0
    in_block_comment = False

    for line in unit.kotlin.split("\n"):
        stripped = line.lstrip()

        if in_block_comment:
            if "*/" in line:
                in_block_comment = False
            out.append(line)
            continue
        if stripped.startswith("//"):
            out.append(line)
            continue
        if stripped.startswith("/*"):
            if "*/" not in line[line.index("/*") + 2:]:
                in_block_comment = True
            out.append(line)
            continue

        new = line
        m = _LEAD.match(new)
        if m:
            dropped += len(m.group(2).split())
            new = m.group(1) + new[m.end():]
        elif " class " in new or new.lstrip().startswith("class "):
            # `class Foo private constructor(...)` - the modifier sits after
            # the name here, so the line-leading match above never sees it.
            new, n = _INLINE_CTOR.subn("", new)
            dropped += n

        out.append(new)

    if dropped:
        unit.kotlin = "\n".join(out)
        ctx["stats"][NAME] += dropped
