"""Java collection interfaces are Kotlin's Mutable* ones, and size() is a property.

Java's java.util.List/Iterator/ListIterator all carry the mutators. Kotlin
splits them: the unprefixed names are read-only and `Mutable*` has add/set/
remove. A Java class implementing ListIterator therefore lands on the wrong
Kotlin supertype, and every mutator it declares "overrides nothing".

Kotlin also models `size` as a property, so Java's `int size()` overrides
nothing. And an override of a stdlib collection member has to match the
member's nullability exactly, which the converter's blanket-nullable return
types do not.

Scope is deliberately narrow: only classes whose supertype list names one of
these types, and only members that the collection interface actually declares.
"""
import re

ORDER = 72
NAME = "kotlin-collection-conformance"
DESCRIPTION = "Java collection supertypes -> Kotlin Mutable*, size() -> val size"

# Read-only Kotlin spelling -> mutable spelling.
MUTABLE = {
    "Iterator": "MutableIterator",
    "ListIterator": "MutableListIterator",
    "Collection": "MutableCollection",
    "List": "MutableList",
    "Set": "MutableSet",
    "Map": "MutableMap",
    "Iterable": "MutableIterable",
}
# Declaring any of these means the Java type was the mutable one.
MUTATORS = ("add", "set", "remove", "put", "clear", "addAll", "removeAll",
            "retainAll", "putAll")

# Supertypes that put a Kotlin collection interface in scope, either directly
# or through a stub that delegates to one.
COLLECTION_SUPERS = set(MUTABLE) | set(MUTABLE.values()) | {
    "AbstractSequentialList", "AbstractList", "AbstractCollection", "AbstractMap",
    "AbstractSet", "ArrayList", "HashMap", "HashSet", "LinkedList",
}
# Members whose signature the stdlib pins down.
PINNED = {
    "add", "addAll", "clear", "contains", "containsAll", "containsKey",
    "containsValue", "get", "hasNext", "hasPrevious", "indexOf", "isEmpty",
    "iterator", "lastIndexOf", "listIterator", "next", "nextIndex", "previous",
    "previousIndex", "put", "putAll", "remove", "removeAll", "retainAll",
    "set", "subList",
}

CLASS_HEAD = re.compile(
    r"^(?P<indent>[ \t]*)(?:(?:public|open|abstract|final|inner|private|internal|sealed|data) )*"
    r"class (?P<name>[A-Za-z_][A-Za-z0-9_]*)(?P<targs><[^\n{]*?>)?\s*:\s*(?P<supers>[^\n{]+?)\s*\{",
    re.M)


def _bodies(text):
    """Yield (header_match, body_start, body_end) for every class with a supertype list."""
    for m in CLASS_HEAD.finditer(text):
        start = text.index("{", m.start())
        depth, i, n = 0, start, len(text)
        while i < n:
            c = text[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        yield m, start + 1, i


def _base(t):
    return t.split("<")[0].split(".")[-1].strip()


def transform(unit, ctx):
    text = unit.kotlin
    changed = 0
    for m, bs, be in list(_bodies(text)):
        supers = [s.strip() for s in re.split(r",(?![^<>]*>)", m.group("supers"))]
        bases = {_base(s) for s in supers}
        if not (bases & COLLECTION_SUPERS):
            continue
        body = text[bs:be]
        head = m.group(0)
        new_head = head
        # (1) read-only supertype + declared mutator -> Mutable spelling
        declares_mutator = any(re.search(r"\bfun %s\s*\(" % mu, body) for mu in MUTATORS)
        if declares_mutator:
            for ro, mut in MUTABLE.items():
                if ro in bases:
                    new_head = re.sub(r"(:\s*|,\s*)%s(?=\s*<)" % ro, r"\g<1>" + mut, new_head)
        # (2) pinned members: exact signature, no blanket nullability
        new_body = body
        for name in PINNED:
            pat = re.compile(
                r"(?P<pre>\n[ \t]*(?:override |open |final )*fun )%s(?P<sig>\s*\((?P<params>[^()]*)\))"
                r"(?P<ret>\s*:\s*(?P<rt>[^\n=]+?))?(?P<tail>\s*[={])" % name)

            def fix(mm):
                params = re.sub(r"\?(\s*(?:,|$))", r"\1", mm.group("params"))
                out = mm.group("pre") + name + "(" + params + ")"
                if mm.group("ret"):
                    rt = mm.group("rt").rstrip()
                    while rt.endswith("?"):
                        rt = rt[:-1].rstrip()
                    rb = _base(rt)
                    if rb in MUTABLE and rb not in ("Map",):
                        rt = MUTABLE[rb] + rt[len(rb):]
                    out += ": " + rt
                return out + mm.group("tail")

            new_body, k = pat.subn(fix, new_body)
            changed += k
        # (3) Kotlin's size is a property
        new_body = re.sub(
            r"(\n[ \t]*)(?:override |open |final )*fun size\(\)\s*:\s*Int\s*\{",
            r"\1override val size: Int get() {", new_body)
        if new_head != head or new_body != body:
            text = text[:m.start()] + new_head + text[m.start() + len(head):bs] + new_body + text[be:]
            changed += 1
            unit.notes.append("collection conformance applied to class %s" % m.group("name"))
    if changed:
        unit.kotlin = text
        ctx["stats"][NAME] += changed
