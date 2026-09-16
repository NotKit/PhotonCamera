"""Put a file-level @file:Suppress on every generated file.

Mechanical conversion produces a lot of noise the PoC does not care about:
unused parameters left behind by TODO() bodies, `open` on members nobody
overrides, redundant casts from Java's explicit style.  Warnings are not the
signal we are converging on, so they get muted at the file level rather than
line by line.
"""
ORDER = 90
NAME = "file-suppress"
DESCRIPTION = "prepend @file:Suppress(...) so warning noise does not drown errors"

SUPPRESS = (
    '@file:Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE", "unused",\n'
    '               "UNUSED_EXPRESSION", "NAME_SHADOWING", "USELESS_CAST",\n'
    '               "REDUNDANT_MODALITY_MODIFIER", "PARAMETER_NAME_CHANGED_ON_OVERRIDE",\n'
    '               "UNCHECKED_CAST", "DEPRECATION", "SENSELESS_COMPARISON",\n'
    '               "PLATFORM_CLASS_MAPPED_TO_KOTLIN", "OVERRIDE_DEPRECATION")'
)


def transform(unit, ctx):
    if unit.kotlin.startswith("@file:Suppress"):
        return
    lines = unit.kotlin.split("\n")
    # file annotations must precede the package declaration
    head, rest = [], lines
    for i, l in enumerate(lines):
        if l.startswith("package ") or l.startswith("import "):
            head, rest = lines[:i], lines[i:]
            break
    unit.kotlin = "\n".join(head + [SUPPRESS, ""] + rest)
    ctx["stats"]["file-suppress"] += 1
