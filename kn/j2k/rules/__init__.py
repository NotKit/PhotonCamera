"""kn-run/rules - post-pass rules for j2k.py.

A rule is a module in this package that exports:

    ORDER       int, default 500.  Rules run in (ORDER, module name) order.
    NAME        short slug, for reports.  Defaults to the module name.
    DESCRIPTION one line, what it fixes and why.
    transform(unit, ctx) -> None

`unit` is a j2k.Unit:

    unit.java_path  absolute path to the Java input
    unit.rel        package-relative path without extension
    unit.package    dotted package name
    unit.kotlin     THE KOTLIN TEXT.  A rule rewrites this in place.
    unit.imports    the emitted `import ...` lines, in order
    unit.source     original Java bytes
    unit.tree       tree-sitter Tree of the Java, if a rule needs real structure
    unit.notes      list of strings; append anything a human should see
    unit.meta       free-form dict, shared between rules

`ctx` is a dict:

    ctx["index"]     the corpus Index (kinds, members, enum_consts)
    ctx["src_root"]  Java root
    ctx["out_root"]  Kotlin root
    ctx["stats"]     collections.Counter, for counting what you changed

Rules are discovered automatically - dropping a file in this directory is the
whole installation step.  Name it rNN_slug.py so the filename shows the order.
Never edit j2k.py for something a rule can do, and never edit the generated
Kotlin: that directory is output.

Conventional bands:
    r0x   normalisation that later rules depend on
    r1x   import / header surgery
    r5x   type and expression fixes driven by compiler-error buckets
    r9x   whole-file wrappers (suppressions, banners)
"""
