"""Java lets a subclass field shadow a superclass field; Kotlin does not.

Kotlin reports "'x' hides member of supertype 'S' and needs an 'override'
modifier", and `override` is not available when the two fields have unrelated
types (WebResponse.Builder.mBody is an InputStream over WebMessage.Builder's
ByteBuffer). The only mechanical fix is to rename the shadowing field and
rewrite the references inside the class that declares it.

Detection is done on the Java side, against a corpus-wide field index keyed by
the *nested* type name (Outer.Inner), because the superclass usually lives in
another file and `Builder`/`Factory` alone collide across a dozen of them.
A supertype that resolves to more than one candidate is skipped.
"""
import os
import re

ORDER = 73
NAME = "field-shadowing"
DESCRIPTION = "rename a subclass field that shadows an inherited one"

DECL = re.compile(r"^(?P<indent>[ \t]*)(?:(?:public|private|internal|protected|open|final|lateinit|const|@\w+) )*"
                  r"(?P<kw>va[lr]) (?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*:", re.M)
CLASS_HEAD = re.compile(
    r"^(?P<indent>[ \t]*)(?:(?:public|open|abstract|final|inner|private|internal|sealed|data) )*"
    r"class (?P<name>[A-Za-z_][A-Za-z0-9_]*)(?:<[^\n{]*?>)?\s*:\s*(?P<supers>[^\n{]+?)\s*\{", re.M)

TYPE_DECLS = ("class_declaration", "enum_declaration", "interface_declaration",
              "record_declaration")


def _scan(src, root):
    """[(qualified name, {fields}, extends-text or None)] for one Java file."""
    out = []

    def txt(n):
        return src[n.start_byte:n.end_byte].decode("utf8", "replace")

    def visit(node, stack):
        for ch in node.named_children:
            if ch.type in TYPE_DECLS:
                nm = ch.child_by_field_name("name")
                body = ch.child_by_field_name("body")
                if nm is None:
                    continue
                q = stack + [txt(nm)]
                fields = set()
                if body is not None:
                    for m in body.named_children:
                        if m.type != "field_declaration":
                            continue
                        for d in m.named_children:
                            if d.type == "variable_declarator":
                                dn = d.child_by_field_name("name")
                                if dn is not None:
                                    fields.add(txt(dn))
                sup = ch.child_by_field_name("superclass")
                sup = txt(sup).replace("extends", "").strip() if sup is not None else None
                out.append((".".join(q), fields, sup))
                if body is not None:
                    visit(body, q)
            elif ch.type in ("class_body", "interface_body", "enum_body", "block"):
                visit(ch, stack)
    visit(root, [])
    return out


def _corpus(ctx):
    cached = ctx.get("_shadow_index")
    if cached is not None:
        return cached
    from tree_sitter import Language, Parser
    import tree_sitter_java
    parser = Parser(Language(tree_sitter_java.language()))
    fields, supers = {}, {}
    for dp, _, fns in os.walk(ctx["src_root"]):
        for fn in fns:
            if not fn.endswith(".java"):
                continue
            src = open(os.path.join(dp, fn), "rb").read()
            for q, fs, sup in _scan(src, parser.parse(src).root_node):
                fields[q] = fs
                if sup:
                    supers[q] = sup.split("<")[0].strip()
    ctx["_shadow_index"] = (fields, supers)
    return fields, supers


def _resolve(fields, ref):
    """Qualified names that a supertype reference could mean; None if ambiguous."""
    ref = ref.split("<")[0].strip()
    if ref in fields:
        return ref
    cand = [q for q in fields if q == ref or q.endswith("." + ref)]
    return cand[0] if len(cand) == 1 else None


def _inherited(fields, supers, q, seen=None):
    seen = seen if seen is not None else set()
    if q in seen:
        return set()
    seen.add(q)
    sup = supers.get(q)
    if not sup:
        return set()
    rq = _resolve(fields, sup)
    if rq is None:
        return set()
    return set(fields.get(rq, ())) | _inherited(fields, supers, rq, seen)


def _body_span(text, head_end):
    start = text.index("{", head_end - 1)
    depth, i, n = 0, start, len(text)
    while i < n:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    return start + 1, i


def transform(unit, ctx):
    text = unit.kotlin
    if "class " not in text:
        return
    fields, supers = _corpus(ctx)
    # Which classes in THIS file shadow something, and which names.
    want = {}
    for q in fields:
        simple = q.split(".")[-1]
        hit = fields[q] & _inherited(fields, supers, q)
        if hit and unit.rel.split("/")[-1] == q.split(".")[0]:
            want.setdefault(simple, set()).update(hit)
    if not want:
        return
    renamed = 0
    done = set()
    while True:
        # One class per pass: a file-wide rename invalidates every offset.
        todo = [m for m in CLASS_HEAD.finditer(text) if m.group("name") in want
                and m.group("name") not in done]
        if not todo:
            break
        m = todo[0]
        cls = m.group("name")
        done.add(cls)
        bs, be = _body_span(text, m.end())
        body = text[bs:be]
        decls = [d.group("name") for d in DECL.finditer(body)
                 if body.count("{", 0, d.start()) == body.count("}", 0, d.start())]
        hits = sorted(set(decls) & want[cls])
        if not hits:
            continue
        for name in hits:
            # A reference from outside the class body (`builder.mBody`) has to
            # follow the rename too, and the file has no type information to
            # tell one receiver from another -- so rename file-wide, but only
            # when this file declares the name exactly once.
            whole = len([d for d in DECL.finditer(text) if d.group("name") == name]) == 1
            span = (0, len(text)) if whole else (bs, be)
            chunk = text[span[0]:span[1]]
            chunk = re.sub(r"(?<![\w])%s\b" % re.escape(name), name + "Shadow", chunk)
            text = text[:span[0]] + chunk + text[span[1]:]
            renamed += 1
            unit.notes.append("renamed shadowing field %s.%s -> %sShadow%s"
                              % (cls, name, name, "" if whole else " (class body only)"))
    if renamed:
        unit.kotlin = text
        ctx["stats"][NAME] += renamed
