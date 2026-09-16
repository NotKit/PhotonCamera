"""`override` decided by signature, not by method name.

j2k infers `override` from `Index.inherited_members()`, a set of method *names*
keyed by *simple* type name.  Both halves are lossy:

  * arity and parameter types are not in it, so `arguments(args)` counts as an
    override of anything called `arguments`;
  * the key is a simple name, so every `Builder` in the corpus - and there are
    a dozen - shares one member set.  That is why GeckoRuntimeSettings.java,
    which carries 5 `@Override` annotations, came out with 56 `override fun`.

It also cannot see the android/androidx/JDK/AIDL stubs, so a method that really
does override one of them loses its `override` and the class stops compiling
with "does not implement abstract member" - the same bug pointing the other
way.

The index this rule builds instead is keyed by FULLY QUALIFIED name and holds
(name, arity) signatures with their parameter and return type texts.  It comes
from two places, both of them Kotlin, so the signatures are the ones the
compiler will actually see:

  * every .kt already in the output tree - the stubs under `stubs/` plus the
    AIDL and third-party stubs earlier rules generate;
  * the corpus itself, re-converted in-process by j2k with every rule ordered
    before this one applied, so `nullable-emit` has already had its say.

That costs one extra conversion pass (~6s on 168 files) and is why the rule can
compare types at all: a post-pass has no type information otherwise.

Then every `fun` in the file is re-decided against the transitive supertype
closure of its enclosing class, with the class's type arguments substituted in:

  keep     (name, arity) is declared in the closure.
  drop     it is not, and every supertype in the closure resolved.  An
           unresolved supertype leaves the `override` alone: the closure is
           then only a lower bound, and a false drop costs two errors (the
           member and its class) where a false keep costs one.
  adopt    the match exists but our parameter or return types differ from it -
           the compiler's "Potential signatures for overriding".  The super's
           types are written into our declaration, fully qualified, with type
           arguments substituted, keeping our parameter names.  This is what
           fixes `override fun addCallback(callback: Callback?)` against
           SurfaceHolder, where Java inherited the nested `Callback` into scope
           and Kotlin does not, and the `T?`-vs-`T` returns nullable-emit
           leaves.  Return types are adopted only when the two differ by
           nullability or by how much of the package is spelled out.
  add      a plain `fun` matching an abstract - or an open concrete - member of
           the closure gets `override`.  If that member is a non-open one in
           this same file, it is opened.

Two more things the same index answers:

  * a `fun interface` whose closure has an abstract-method count other than 1
    loses the `fun `;
  * two members that collapsed onto one signature - Java's
    `putBooleanArray(String, boolean[])` and `putBooleanArray(String,
    Boolean[])` are both `(String?, BooleanArray?)` here - are Kotlin's
    "conflicting overloads", and the later one is deleted.

`finalize` is dropped unless a supertype really declares one: there is no
`java.lang.Object.finalize` on Kotlin/Native, but android.graphics.SurfaceTexture
has its own.

Overload sets are the one place matching gets refined past (name, arity): when
our class declares two same-arity overloads of a name, an entry is only theirs
if its parameter types match, and neither is ever rewritten.

Measured on the round-3 tree, whole corpus, klib: this bucket 194 -> 9 errors,
total 748 -> 549.  What is left is 7 members of GeckoSession.SessionState,
which implements kotlin.collections.MutableList/ListIterator - Kotlin builtins
this index does not carry and whose shape (`size` a property, `next()`
non-null) is the Java-collections bucket, not this one; one property override
(`WebResponse.Builder.mBody`; this rule only reads `fun`); and `asBinder()`
returning `Binder?` where android.os.IInterface wants `IBinder`, which is a
type change, not a nullability one.
"""
import os
import re
import sys

ORDER = 65               # after stub-surface-completion (60): stubs must exist
NAME = "override-inference-signature-aware"
DESCRIPTION = "decide `override` by (name, arity) over a fully-qualified supertype closure"

# Phases, so each can be measured on its own.
DO_DROP = True           # override that overrides nothing
DO_ADOPT = True          # rewrite parameter types to the overridden signature
DO_ADOPT_RET = True      # ... and its return type, where only nullability differs
DO_ADD = True            # missing override on an abstract member of a stub
DO_ADD_CONCRETE = True   # ... and on an open concrete one (Kotlin's "hides member")
DO_FUN_IFACE = True      # `fun interface` with != 1 abstract method
DO_DEDUPE = True         # two Java overloads that collapsed onto one signature

MODS = ("public", "private", "protected", "internal", "open", "abstract",
        "final", "sealed", "data", "inner", "value", "expect", "actual",
        "external", "const", "lateinit", "override", "operator", "infix",
        "inline", "suspend", "tailrec", "companion", "enum", "annotation",
        "fun")
MOD_RE = r"(?:(?:%s)\s+)*" % "|".join(MODS)
IDENT = r"[A-Za-z_][A-Za-z0-9_$]*"

TYPE_DECL_RE = re.compile(
    r"(?P<mods>%s)(?P<kw>class|interface|object)\s+(?P<name>`[^`]+`|%s)" % (MOD_RE, IDENT))
ANON_RE = re.compile(r"\bobject\s*:")
COMPANION_RE = re.compile(r"\bcompanion\s+object\b")
FUN_RE = re.compile(r"^(?P<indent>\s*)(?P<mods>%s)fun\s+(?:<[^>]*>\s*)?"
                    r"(?P<name>`[^`]+`|%s)\s*\(" % (MOD_RE, IDENT))

# Names that are always Object/Any members: never dropped, never adopted.
ANY_MEMBERS = {("toString", 0), ("hashCode", 0), ("equals", 1)}

KOTLIN_BUILTIN = {
    "List": "kotlin.collections.List", "MutableList": "kotlin.collections.MutableList",
    "Map": "kotlin.collections.Map", "MutableMap": "kotlin.collections.MutableMap",
    "Set": "kotlin.collections.Set", "MutableSet": "kotlin.collections.MutableSet",
    "Collection": "kotlin.collections.Collection",
    "Iterable": "kotlin.collections.Iterable",
    "ArrayList": "kotlin.collections.ArrayList",
    "HashMap": "kotlin.collections.HashMap",
}


# --------------------------------------------------------------------------
# text scaffolding

def blank(text):
    """Same length as `text`, with comments and string literals spaced out."""
    out = []
    i, n = 0, len(text)
    in_block = in_raw = False
    while i < n:
        c = text[i]
        if in_block:
            if text.startswith("*/", i):
                in_block = False
                out.append("  ")
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        if in_raw:
            if text.startswith('"""', i):
                in_raw = False
                out.append("   ")
                i += 3
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if text.startswith("/*", i):
            in_block = True
            out.append("  ")
            i += 2
            continue
        if text.startswith('"""', i):
            in_raw = True
            out.append("   ")
            i += 3
            continue
        if c in '"\'':
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(" " * (j - i))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def split_top(s, sep=","):
    """Split on `sep` at bracket depth 0."""
    out, depth, cur = [], 0, []
    for ch in s:
        if ch in "<([{":
            depth += 1
        elif ch in ">)]}":
            depth -= 1
        if ch == sep and depth <= 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    out.append("".join(cur))
    return [x.strip() for x in out if x.strip()]


def find_top(s, chars, start=0):
    depth = 0
    for i in range(start, len(s)):
        ch = s[i]
        if depth == 0 and ch in chars:
            return i
        if ch in "<([{":
            depth += 1
        elif ch in ">)]}":
            depth -= 1
    return -1


def match_paren(s, start):
    """Index just past the `)` matching the `(` at `start`; -1 if unbalanced."""
    depth = 0
    for i in range(start, len(s)):
        if s[i] == "(":
            depth += 1
        elif s[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def super_ref(ref):
    """`Foo.Bar<Baz>()` -> `Foo.Bar<Baz>`; drops `()` and `by ...` delegation.

    The type arguments have to survive: they are what binds the supertype's
    type parameters when its member signatures are read.
    """
    ref = ref.strip()
    ref = re.sub(r"\bby\b.*$", "", ref).strip()
    i = find_top(ref, "(")
    if i >= 0:
        ref = ref[:i]
    return ref.strip()


def base_of(ref):
    """`Foo.Bar<Baz>` -> `Foo.Bar`."""
    return super_ref(ref).split("<")[0].strip().strip("?").strip()


def type_args_of(ref):
    ref = ref.strip()
    i = ref.find("<")
    if i < 0:
        return []
    j = ref.rfind(">")
    if j < i:
        return []
    return split_top(ref[i + 1:j])


# --------------------------------------------------------------------------
# a Kotlin file, read structurally enough for signatures

class Sig(object):
    __slots__ = ("name", "arity", "params", "ret", "abstract", "owner",
                 "open", "line")

    def __init__(self, name, arity, params, ret, abstract, owner, open_=True,
                 line=None):
        self.name = name
        self.arity = arity
        self.params = params        # [(name, type_text)]
        self.ret = ret              # type text or None
        self.abstract = abstract
        self.owner = owner          # fqn
        self.open = open_           # can be overridden at all
        self.line = line            # only usable for the file being rewritten


class TypeInfo(object):
    __slots__ = ("fqn", "kind", "tparams", "supers", "methods", "pkg",
                 "imports", "abstract_own", "is_fun_iface", "open")

    def __init__(self, fqn, kind, tparams, supers, pkg, imports):
        self.fqn = fqn
        self.kind = kind
        self.tparams = tparams
        self.supers = supers
        self.methods = {}           # (name, arity) -> [Sig]
        self.pkg = pkg
        self.imports = imports
        self.abstract_own = 0
        self.is_fun_iface = False


def parse_tparams(header, name_end):
    """Type parameter names of a declaration, from just after its name."""
    rest = header[name_end:].lstrip()
    if not rest.startswith("<"):
        return []
    depth = 0
    for i, ch in enumerate(rest):
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
            if depth == 0:
                inner = rest[1:i]
                out = []
                for p in split_top(inner):
                    p = re.sub(r"^(?:in|out|reified)\s+", "", p.strip())
                    p = p.split(":")[0].strip()
                    if re.match(r"^%s$" % IDENT, p):
                        out.append(p)
                return out
    return []


def supers_of_header(header, kw_pos):
    """Supertype references in a declaration header, after the top-level `:`."""
    tail = header[kw_pos:]
    i = find_top(tail, ":")
    if i < 0:
        return []
    tail = tail[i + 1:]
    j = tail.find(" where ")
    if j >= 0:
        tail = tail[:j]
    return [s for s in (super_ref(x) for x in split_top(tail)) if s]


def parse_params(text):
    """`a: Int, vararg b: String` -> [("a", "Int"), ("b", "String")], flags."""
    out = []
    has_vararg = has_default = False
    for p in split_top(text):
        if re.match(r"^\s*vararg\b", p):
            has_vararg = True
            p = re.sub(r"^\s*vararg\s+", "", p)
        eq = find_top(p, "=")
        if eq >= 0:
            has_default = True
            p = p[:eq]
        c = find_top(p, ":")
        if c < 0:
            out.append((p.strip(), ""))
        else:
            out.append((p[:c].strip(), p[c + 1:].strip()))
    return out, has_vararg, has_default


class KtFile(object):
    """One Kotlin source, scanned for types, supertypes and method shapes."""

    def __init__(self, text):
        self.text = text
        self.lines = text.split("\n")
        self.blines = blank(text).split("\n")
        self.pkg = ""
        self.imports = []
        self.types = {}             # fqn -> TypeInfo
        self.funs = []              # dicts, see _fun()
        self._scan()

    # -- scanning ---------------------------------------------------------
    def _scan(self):
        for bl in self.blines:
            m = re.match(r"\s*package\s+([\w.]+)", bl)
            if m:
                self.pkg = m.group(1)
            m = re.match(r"\s*import\s+([\w.*]+)", bl)
            if m:
                self.imports.append(m.group(1))

        stack = []          # [{"ti":TypeInfo|None, "depth":int, "kind":str}]
        depth = 0
        for li, bl in enumerate(self.blines):
            # a function declaration, before the braces on this line move us
            fm = FUN_RE.match(bl)
            if fm:
                self._fun(li, fm, stack)
            # a type declaration with no body at all (`class A : B`)
            opened_here = False
            pos = 0
            while pos < len(bl):
                ch = bl[pos]
                if ch == "{":
                    ti = self._decl(bl[:pos].rsplit("{", 1)[-1], li, stack)
                    stack.append({"ti": ti, "depth": depth})
                    depth += 1
                    opened_here = True
                elif ch == "}":
                    depth -= 1
                    while stack and stack[-1]["depth"] >= depth:
                        stack.pop()
                pos += 1
            if not opened_here:
                m = TYPE_DECL_RE.search(bl)
                if m and "(" not in bl[:m.start()]:
                    self._decl(bl, li, stack, bodyless=True)

    def _enclosing(self, stack):
        for fr in reversed(stack):
            if fr["ti"] is not None:
                return fr["ti"]
        return None

    def _decl(self, header, li, stack, bodyless=False):
        """Register the type a `{`-opening header declares.  None if not one."""
        m = None
        for m2 in TYPE_DECL_RE.finditer(header):
            m = m2
        anon = ANON_RE.search(header)
        outer = self._enclosing(stack)
        prefix = outer.fqn if outer is not None else self.pkg

        if m is not None and not COMPANION_RE.search(header[:m.start()] or ""):
            name = m.group("name").strip("`")
            kw = m.group("kw")
            kind = kw
            if "enum" in m.group("mods"):
                kind = "enum"
            elif "annotation" in m.group("mods"):
                kind = "annotation"
            fqn = (prefix + "." + name) if prefix else name
            ti = self.types.get(fqn)
            if ti is None:
                ti = TypeInfo(fqn, kind, parse_tparams(header, m.end("name")),
                              supers_of_header(header, m.end("name")),
                              self.pkg, self.imports)
                ti.is_fun_iface = bool(re.search(r"\bfun\s+interface\b", header))
                ti.open = ("open" in m.group("mods")
                           or "abstract" in m.group("mods")
                           or "sealed" in m.group("mods"))
                self.types[fqn] = ti
            return None if bodyless else ti

        if anon is not None:
            # `object : Foo, Bar {` - an anonymous type, addressable only here
            tail = header[anon.end():]
            supers = [s for s in (super_ref(x) for x in split_top(tail)) if s]
            fqn = "%s$anon%d" % (prefix, li)
            ti = TypeInfo(fqn, "class", [], supers, self.pkg, self.imports)
            ti.open = False
            self.types[fqn] = ti
            return ti

        if COMPANION_RE.search(header):
            fqn = "%s.Companion" % (prefix or "")
            ti = TypeInfo(fqn, "object", [], supers_of_header(header, 0),
                          self.pkg, self.imports)
            ti.open = False
            self.types[fqn] = ti
            return ti
        return None

    def _fun(self, li, fm, stack):
        """Record one `fun` declaration and hang it on its enclosing type."""
        bl = self.blines[li]
        raw = self.lines[li]
        popen = bl.index("(", fm.end() - 1)
        pclose = match_paren(bl, popen)
        single = pclose >= 0
        if not single:
            # join continuation lines (stubs wrap long parameter lists)
            joined_b, joined_r = bl, raw
            lj = li
            while pclose < 0 and lj + 1 < len(self.blines):
                lj += 1
                joined_b += "\n" + self.blines[lj]
                joined_r += "\n" + self.lines[lj]
                pclose = match_paren(joined_b, popen)
            if pclose < 0:
                return
            bl, raw = joined_b, joined_r
        params_txt = raw[popen + 1:pclose]
        after = bl[pclose + 1:]
        ret = None
        ret_start = ret_end = -1
        c = find_top(after, ":")
        if c >= 0:
            stop = len(after)
            for k in ("{", "="):
                p = find_top(after, k, c + 1)
                if p >= 0:
                    stop = min(stop, p)
            w = after.find(" where ", c + 1)
            if w >= 0:
                stop = min(stop, w)
            seg = raw[pclose + 1:][c + 1:stop]
            ret = seg.strip()
            ret_start = pclose + 1 + c + 1 + (len(seg) - len(seg.lstrip()))
            ret_end = ret_start + len(ret)
        params, has_vararg, has_default = parse_params(params_txt)
        mods = fm.group("mods").split()
        owner = self._enclosing(stack)
        body_here = ("{" in after) or (find_top(after, "=") >= 0)
        abstract = ("abstract" in mods) or (
            not body_here and owner is not None and owner.kind == "interface")
        open_ = (abstract or "open" in mods or "override" in mods
                 or (owner is not None and owner.kind in ("interface", "annotation")))
        sig = Sig(fm.group("name").strip("`"), len(params), params, ret,
                  abstract, owner.fqn if owner is not None else None, open_, li)
        rec = {"line": li, "sig": sig, "mods": mods, "owner": owner,
               "single": single, "indent": fm.group("indent"),
               "params_start": popen + 1, "params_end": pclose,
               "ret_start": ret_start, "ret_end": ret_end,
               "has_vararg": has_vararg, "has_default": has_default}
        self.funs.append(rec)
        if owner is not None:
            owner.methods.setdefault((sig.name, sig.arity), []).append(sig)
            if abstract:
                owner.abstract_own += 1


# --------------------------------------------------------------------------
# corpus-wide index

class Corpus(object):
    def __init__(self):
        self.types = {}
        self.by_tail = {}
        self.stub_types = set()

    def add_file(self, kt, stub=False):
        for fqn, ti in kt.types.items():
            if fqn in self.types:
                continue
            self.types[fqn] = ti
            if stub:
                self.stub_types.add(fqn)
            parts = fqn.split(".")
            for k in range(len(parts)):
                self.by_tail.setdefault(".".join(parts[k:]), set()).add(fqn)

    def resolve(self, ref, scope_fqn, pkg, imports):
        ref = base_of(ref)
        if not ref or not re.match(r"^%s(\.%s)*$" % (IDENT, IDENT), ref):
            return None
        if ref in self.types:
            return ref
        if scope_fqn:
            parts = scope_fqn.split(".")
            for k in range(len(parts), 0, -1):
                cand = ".".join(parts[:k]) + "." + ref
                if cand in self.types:
                    return cand
        if pkg and (pkg + "." + ref) in self.types:
            return pkg + "." + ref
        head = ref.split(".")[0]
        rest = ref[len(head):]
        for imp in imports:
            if imp.endswith(".*"):
                cand = imp[:-1] + ref
                if cand in self.types:
                    return cand
            elif imp.split(".")[-1] == head:
                cand = imp + rest
                if cand in self.types:
                    return cand
        for p in ("java.lang.", "kotlin."):
            if (p + ref) in self.types:
                return p + ref
        hits = self.by_tail.get(ref)
        if hits and len(hits) == 1:
            return next(iter(hits))
        return None

    # -- closure ----------------------------------------------------------
    def closure(self, ti, limit=40):
        """(name, arity) -> [(Sig, owner, subst)] over the supertypes of `ti`.

        `resolved` is False as soon as one supertype reference cannot be
        resolved: the closure is then a lower bound only.
        """
        out = {}
        resolved = True
        seen = set()
        work = [(ref, {}, ti) for ref in ti.supers]
        while work and len(seen) < limit:
            ref, subst, ctx_ti = work.pop()
            fqn = self.resolve(ref, ctx_ti.fqn, ctx_ti.pkg, ctx_ti.imports)
            if fqn is None:
                resolved = False
                continue
            sup = self.types[fqn]
            args = [subst_text(a, subst) for a in type_args_of(ref)]
            sub2 = {}
            for i, tp in enumerate(sup.tparams):
                sub2[tp] = args[i] if i < len(args) else None
            key = (fqn, tuple(sorted((k, v) for k, v in sub2.items() if v)))
            if key in seen:
                continue
            seen.add(key)
            for k, sigs in sup.methods.items():
                for s in sigs:
                    out.setdefault(k, []).append((s, sup, sub2))
            for sref in sup.supers:
                work.append((sref, sub2, sup))
        return out, resolved

    def abstract_count(self, ti):
        """Abstract methods visible on an interface, own plus inherited."""
        names = set()
        for k, sigs in ti.methods.items():
            for s in sigs:
                if s.abstract:
                    names.add(k)
        cl, resolved = self.closure(ti)
        for k, entries in cl.items():
            if any(s.abstract for s, _, _ in entries):
                names.add(k)
        implemented = set()
        for k, sigs in ti.methods.items():
            if any(not s.abstract for s in sigs):
                implemented.add(k)
        for k in cl:
            if any(not s.abstract for s, _, _ in cl[k]):
                implemented.add(k)
        return len(names - implemented), resolved

    # -- type text --------------------------------------------------------
    def qualify(self, text, owner, subst):
        """Rewrite a super's type text so it means the same in another file."""
        if not text:
            return text

        def repl(m):
            word = m.group(0)
            if word in subst and subst[word]:
                return subst[word]
            if word in subst:
                return word
            fqn = self.resolve(word, owner.fqn, owner.pkg, owner.imports)
            if fqn:
                return fqn
            head = word.split(".")[0]
            if head == word and word in KOTLIN_BUILTIN:
                return KOTLIN_BUILTIN[word]
            return word

        return re.sub(r"(?<![\w.$])%s(?:\.%s)*" % (IDENT, IDENT), repl, text)


def subst_text(text, subst):
    if not subst or not text:
        return text
    return re.sub(r"(?<![\w.$])%s\b" % IDENT,
                  lambda m: subst.get(m.group(0)) or m.group(0), text)


# --------------------------------------------------------------------------
# building the index once per run

def _build(ctx):
    corpus = Corpus()
    notes = []

    out_root = ctx["out_root"]
    n_stub = 0
    if os.path.isdir(out_root):
        for d, _, fs in os.walk(out_root):
            for f in sorted(fs):
                if not f.endswith(".kt"):
                    continue
                try:
                    text = open(os.path.join(d, f)).read()
                    if "Generated by kn-run/j2k.py" in text[:200]:
                        continue        # a corpus file from an earlier run
                    corpus.add_file(KtFile(text), True)
                    n_stub += 1
                except Exception as ex:              # noqa: BLE001
                    notes.append("stub scan failed %s: %r" % (f, ex))

    # The corpus half: convert every Java file again, with every rule ordered
    # before this one, so the signatures match what the compiler will see.
    n_corpus = 0
    try:
        sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        import j2k
        prior = [(o, nm, m) for (o, nm, m) in j2k.load_rules()
                 if (o, nm) < (ORDER, NAME)]
        parser = j2k.Parser(j2k.JAVA)
        sub_ctx = dict(ctx)
        sub_ctx["stats"] = type(ctx["stats"])()
        src = ctx["src_root"]
        for d, _, fs in os.walk(src):
            for f in sorted(fs):
                if not f.endswith(".java"):
                    continue
                p = os.path.join(d, f)
                try:
                    data = open(p, "rb").read()
                    tree = parser.parse(data)
                    if tree.root_node.has_error:
                        continue
                    rel = os.path.relpath(p, src)[:-len(".java")]
                    u = j2k.convert_file(p, data, tree, ctx["index"], rel)
                    for _, _, m in prior:
                        try:
                            m.transform(u, sub_ctx)
                        except Exception:            # noqa: BLE001
                            pass
                    corpus.add_file(KtFile(u.kotlin))
                    n_corpus += 1
                except Exception as ex:              # noqa: BLE001
                    notes.append("corpus scan failed %s: %r" % (f, ex))
    except Exception as ex:                          # noqa: BLE001
        notes.append("corpus index unavailable: %r" % (ex,))

    ctx["stats"]["override-index-stubs"] = n_stub
    ctx["stats"]["override-index-corpus"] = n_corpus
    return corpus, notes


def _index(ctx):
    got = ctx.get("_override_sig_index")
    if got is None:
        got = _build(ctx)
        ctx["_override_sig_index"] = got
    return got


# --------------------------------------------------------------------------
# the rule

def transform(unit, ctx):
    corpus, build_notes = _index(ctx)
    for n in build_notes[:5]:
        if n not in unit.notes:
            unit.notes.append(n)
    if not corpus.types:
        return

    kt = KtFile(unit.kotlin)
    # this file's own types shadow whatever the index picked up for them
    local = Corpus()
    local.types = dict(corpus.types)
    local.by_tail = corpus.by_tail
    local.stub_types = corpus.stub_types
    for fqn, ti in kt.types.items():
        local.types[fqn] = ti

    lines = kt.lines
    edits = {}          # line index -> new text
    stats = ctx["stats"]
    closures = {}

    def closure_of(ti):
        if ti.fqn not in closures:
            closures[ti.fqn] = local.closure(ti)
        return closures[ti.fqn]

    for rec in kt.funs:
        owner = rec["owner"]
        sig = rec["sig"]
        li = rec["line"]
        if owner is None or li in edits or not rec["single"]:
            continue
        line = lines[li]
        is_override = "override" in rec["mods"]
        key = (sig.name, sig.arity)

        cl, resolved = closure_of(owner)
        entries = cl.get(key)

        # There is no java.lang.Object.finalize on Kotlin/Native.  Only a
        # supertype that really declares one (SurfaceTexture does) keeps it.
        if sig.name == "finalize" and not entries:
            if is_override:
                lines[li] = _strip_override(line, owner)
                edits[li] = True
                stats["override-finalize-dropped"] += 1
            continue
        if key in ANY_MEMBERS:
            continue

        sibs = owner.methods.get(key, ())
        if entries and len(sibs) > 1:
            # two same-arity overloads of ours, one signature up there: only
            # the one whose parameters match it is the override.
            entries = [e for e in entries if _entry_matches(local, e, rec)]

        if is_override:
            if entries:
                if (DO_ADOPT and len(sibs) < 2
                        and _adopt(local, lines, li, rec, entries, stats)):
                    edits[li] = True
                continue
            if DO_DROP and resolved:
                lines[li] = _strip_override(line, owner)
                edits[li] = True
                stats["override-dropped"] += 1
            elif not resolved:
                stats["override-kept-unresolved-super"] += 1
        elif DO_ADD and entries and "private" not in rec["mods"]:
            addable = any(s.abstract for s, _, _ in entries) or (
                DO_ADD_CONCRETE and all(s.open for s, _, _ in entries))
            if not addable and DO_ADD_CONCRETE and len(entries) == 1:
                sup_sig, sup_owner, _ = entries[0]
                if (sup_owner.fqn in kt.types and sup_sig.line is not None
                        and sup_sig.line not in edits
                        and _entry_matches(local, entries[0], rec)):
                    sup_line = lines[sup_sig.line]
                    pos = sup_line.find("fun ")
                    if pos >= 0 and "private" not in sup_line[:pos]:
                        lines[sup_sig.line] = (sup_line[:pos] + "open "
                                               + sup_line[pos:])
                        edits[sup_sig.line] = True
                        stats["supertype-member-opened"] += 1
                        addable = True
            if addable and "abstract" not in rec["mods"] and owner.kind != "object":
                pos = line.find("fun ")
                if pos >= 0:
                    head = re.sub(r"\b(open|final)\s+", "", line[:pos])
                    lines[li] = head + "override fun " + line[pos + 4:]
                    edits[li] = True
                    stats["override-added"] += 1
                    if DO_ADOPT and len(sibs) < 2:
                        _adopt(local, lines, li, rec, entries, stats,
                               shifted=len(lines[li]) - len(line))

    if DO_DEDUPE:
        _dedupe(kt, lines, edits, unit, stats)

    if DO_FUN_IFACE:
        for fqn, ti in kt.types.items():
            if not getattr(ti, "is_fun_iface", False):
                continue
            n, resolved = local.abstract_count(ti)
            if resolved and n != 1:
                for li, line in enumerate(lines):
                    if li in edits:
                        continue
                    m = re.search(r"\bfun\s+interface\s+%s\b"
                                  % re.escape(fqn.split(".")[-1]), line)
                    if m:
                        lines[li] = line[:m.start()] + line[m.start():].replace(
                            "fun interface", "interface", 1)
                        edits[li] = True
                        stats["fun-interface-demoted"] += 1
                        break

    if edits:
        unit.kotlin = "\n".join(lines)


def _dedupe(kt, lines, edits, unit, stats):
    """Delete a member that collapsed onto a signature already declared.

    Java's `putBooleanArray(String, boolean[])` and
    `putBooleanArray(String, Boolean[])` are two methods; both come out as
    `(String?, BooleanArray?)`, which Kotlin reads as one declared twice.
    Nothing here is ever executed, so the later one is deleted outright -
    a call that meant it now binds to the survivor.
    """
    seen = {}
    for rec in kt.funs:
        owner = rec["owner"]
        if owner is None or not rec["single"]:
            continue
        key = (owner.fqn, rec["sig"].name, rec["sig"].arity,
               tuple(t.replace(" ", "") for _, t in rec["sig"].params))
        first = seen.get(key)
        if first is None:
            seen[key] = rec
            continue
        li = rec["line"]
        end = _member_end(kt, li)
        if end < 0 or any(k in edits for k in range(li, end + 1)):
            continue
        for k in range(li, end + 1):
            lines[k] = ""
            edits[k] = True
        stats["duplicate-signature-dropped"] += 1
        unit.notes.append("dropped a duplicate signature: %s(%s) in %s"
                          % (rec["sig"].name,
                             ", ".join(t for _, t in rec["sig"].params),
                             owner.fqn))


def _member_end(kt, li):
    """Last line of the member declared at `li`, by brace matching."""
    bl = kt.blines[li]
    if "{" not in bl:
        return li               # expression body or no body at all
    depth = 0
    for k in range(li, len(kt.blines)):
        for ch in kt.blines[k]:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return k
    return -1


def _strip_override(line, owner):
    """Remove `override`, restoring a modifier the member may now need."""
    out = re.sub(r"\boverride\s+", "", line, count=1)
    if owner.kind == "class" and getattr(owner, "open", False):
        if not re.search(r"\b(open|abstract|final|private)\b", out.split("fun")[0]):
            out = re.sub(r"\bfun\b", "open fun", out, count=1)
    return out


def _entry_matches(corpus, entry, rec):
    """Does this supertype signature have our parameter types?"""
    sig, owner, subst = entry
    if len(sig.params) != rec["sig"].arity:
        return False
    return all(_same_shape(corpus.qualify(t, owner, subst), ours)
               for (_, t), (_, ours) in zip(sig.params, rec["sig"].params))


def _same_shape(a, b):
    """Same type modulo `?` and how much of the package is spelled out."""
    def norm(t):
        t = (t or "").replace("?", "").replace(" ", "")
        return re.sub(r"(?<![\w.$])(?:%s\.)+(%s)" % (IDENT, IDENT), r"\1", t)
    return norm(a) == norm(b)


def _adopt(corpus, lines, li, rec, entries, stats, shifted=0):
    """Rewrite our parameter types to the ones the overridden member declares."""
    if rec["has_vararg"] or rec["has_default"]:
        return False
    cands = []
    rets = []
    for s, owner, subst in entries:
        if len(s.params) != rec["sig"].arity:
            continue
        raw = " ".join(t for _, t in s.params)
        # an unbound type parameter of the supertype would not mean anything
        # in this file: leave the declaration alone rather than import a `T`.
        if any(subst.get(tp) is None and re.search(r"(?<![\w.$])%s\b" % tp, raw)
               for tp in owner.tparams):
            return False
        txt = [corpus.qualify(t, owner, subst) for _, t in s.params]
        if any(t == "" for t in txt):
            return False
        rets.append(corpus.qualify(s.ret, owner, subst) if s.ret else None)
        cands.append(tuple(txt))
    if not cands or len(set(cands)) != 1:
        return False
    line = lines[li]
    done = False
    want = cands[0]
    ours = [t for _, t in rec["sig"].params]
    names = [n for n, _ in rec["sig"].params]
    if list(want) != ours and all(names):
        new_params = ", ".join("%s: %s" % (n, t) for n, t in zip(names, want))
        a, b = rec["params_start"] + shifted, rec["params_end"] + shifted
        if a <= b <= len(line):
            line = line[:a] + new_params + line[b:]
            shifted += len(new_params) - (b - a)
            stats["override-params-adopted"] += 1
            done = True
    if DO_ADOPT_RET and len(set(rets)) == 1 and rets[0] and rec["ret_start"] >= 0:
        want_ret = rets[0]
        # only widen: our T? against the super's T is the mismatch worth fixing
        if (want_ret != rec["sig"].ret
                and _same_shape(want_ret, rec["sig"].ret)):
            a, b = rec["ret_start"] + shifted, rec["ret_end"] + shifted
            if a <= b <= len(line):
                line = line[:a] + want_ret + line[b:]
                stats["override-return-adopted"] += 1
                done = True
    if done:
        lines[li] = line
    return done
