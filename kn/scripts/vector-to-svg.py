#!/usr/bin/env python3
"""Convert Android <vector> drawables to SVG.

    scripts/vector-to-svg.py SRC [SRC...] -o OUTDIR [--report FILE]
    scripts/vector-to-svg.py --one FILE.xml            # one file to stdout

WHY THIS EXISTS.  Compose Multiplatform's `painterResource` reads a drawable
through Aurora's `components-resources`, whose `toXmlElement()` is implemented
with `SVGDOM` -- an SVG parser.  So an Android vector XML does not merely render
wrong, it throws (`Can't wrap nullptr`), while an .svg of the same glyph parses
and draws.  Measured by spike/cmp-resources/cmpres-session.sh, claims
`probe:skia-svgdom-xml` (expected FAIL) against `probe:skia-svgdom-svg` (PASS).
Fenix and android-components ship 467 vector drawables and ZERO .svg, so this
script is the missing half.

THE AUTHORITY IS AOSP, NOT MEMORY.  Every rule below is read out of
~/UT/atlas/src/api-impl/android/graphics/drawable/VectorDrawable.java, which is
the real platform source, and the line is cited where it is not obvious.

WHAT IT REFUSES RATHER THAN GUESSES.  A converter that silently emits a wrong
glyph is worse than one that stops: the glyph is small, plausible and nobody
looks.  So anything this cannot represent exactly is REFUSED by name, the file
is not written, and the reason is in the report.  Ten of the eleven refusable
features do not occur in the corpus at all; they are here so that the day one
appears, it says so.
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, OrderedDict

A = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"
SVGNS = "http://www.w3.org/2000/svg"


class Refuse(Exception):
    """Raised with a reason.  The file is not written."""


class Skip(Exception):
    """Not a vector drawable at all.  Not a failure -- a <shape>, <selector>,
    <ripple>, <layer-list> or <adaptive-icon> is a different kind of drawable
    and was never in scope.  Counted separately so the refusal list stays
    meaningful."""


# `@android:color/*` -- the framework palette.  These are not in any values/*.xml
# in this tree because they belong to the platform, so a resolver that only walks
# the app's own resources reports them missing and refuses a glyph that is fine.
# Values are AOSP's frameworks/base/core/res/res/values/colors.xml.
FRAMEWORK_COLORS = {
    "white": "#ffffffff",
    "black": "#ff000000",
    "transparent": "#00000000",
    "background_dark": "#ff000000",
    "background_light": "#ffffffff",
    "darker_gray": "#ffaaaaaa",
    "primary_text_dark": "#ffffffff",
    "primary_text_light": "#ff000000",
    "secondary_text_dark": "#ffbebebe",
    "secondary_text_light": "#ff323232",
    "holo_blue_light": "#ff33b5e5",
    "holo_red_light": "#ffff4444",
    "holo_green_light": "#ff99cc00",
}


# ---------------------------------------------------------------- colours

_HEX = re.compile(r"^#([0-9a-fA-F]{3,8})$")


def parse_color(raw, resolver, what):
    """An Android colour literal -> (#rrggbb, alpha 0..1).

    Android accepts #RGB, #ARGB, #RRGGBB and #AARRGGBB (Color.parseColor), and
    the alpha is the LEADING component, which is the trap: SVG has no alpha in
    the hex, so it comes out as a separate opacity.
    """
    raw = (raw or "").strip()
    if not raw:
        return None, 1.0
    if raw.startswith("@") or raw.startswith("?"):
        raw = resolver(raw, what)
        if raw is None:
            return None, 1.0
        # A theme attribute resolves to a POLICY, not to a colour -- the default
        # is the SVG keyword `currentColor`, which has no hex form and must not
        # be run through the parser below.  Alpha is the caller's.
        if not raw.startswith("#"):
            return raw, 1.0
    m = _HEX.match(raw)
    if not m:
        raise Refuse(f"{what}: colour {raw!r} is neither a hex literal nor resolvable")
    h = m.group(1)
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
        a = 255
    elif len(h) == 4:
        a = int(h[0] * 2, 16)
        h = "".join(c * 2 for c in h[1:])
    elif len(h) == 6:
        a = 255
    elif len(h) == 8:
        a = int(h[0:2], 16)
        h = h[2:]
    else:
        raise Refuse(f"{what}: colour {raw!r} has {len(h)} hex digits")
    return "#" + h.lower(), a / 255.0


class ColorResolver:
    """`@color/foo` out of the res tree; `?attr/foo` is a decision, not a lookup.

    A theme attribute has no value until a theme is chosen, and this lane has no
    aapt and no theme.  Emitting `currentColor` is the honest answer -- the
    caller tints the glyph, which is what Fenix does with these anyway -- but it
    is a CHANGE of meaning, so every one is counted and reported.
    """

    def __init__(self, roots, attr_policy="currentColor"):
        self.map = {}
        self.attr_policy = attr_policy
        self.attr_hits = Counter()
        self.missing = Counter()
        for root in roots:
            for dirpath, _dirs, files in os.walk(root):
                if os.path.basename(dirpath).startswith("values"):
                    for fn in files:
                        if fn.endswith(".xml"):
                            self._load(os.path.join(dirpath, fn))

    def _load(self, path):
        try:
            tree = ET.parse(path).getroot()
        except Exception:
            return
        for el in tree:
            if el.tag == "color" and el.get("name") and (el.text or "").strip():
                self.map.setdefault(el.get("name"), el.text.strip())

    def __call__(self, ref, what):
        if ref.startswith("?"):
            self.attr_hits[ref] += 1
            return None if self.attr_policy is None else self.attr_policy
        if ref.startswith("@android:color/") or ref.startswith("@*android:color/"):
            val = FRAMEWORK_COLORS.get(ref.split("/")[-1])
            if val is None:
                raise Refuse(f"{what}: {ref} is a framework colour this table does not carry")
            return val
        name = ref.split("/")[-1]
        seen, chain = set(), [ref]
        while True:
            val = self.map.get(name)
            if val is None:
                self.missing[ref] += 1
                # Name the link that is actually missing, not the reference the
                # path started from -- a @color that resolves two hops and dies
                # on the third is otherwise reported against the wrong name.
                via = "" if len(chain) == 1 else f" (via {' -> '.join(chain[:-1])})"
                raise Refuse(
                    f"{what}: @color/{name}{via} is not defined in any values/*.xml "
                    "on the search path"
                )
            if not val.startswith("@"):
                return val
            if val.startswith("@android:color/") or val.startswith("@*android:color/"):
                fw = FRAMEWORK_COLORS.get(val.split("/")[-1])
                if fw is None:
                    raise Refuse(f"{what}: {val} is a framework colour this table does not carry")
                return fw
            name = val.split("/")[-1]
            chain.append(val)
            if name in seen:
                raise Refuse(f"{what}: {ref} resolves in a cycle")
            seen.add(name)


# ---------------------------------------------------------------- numbers


def num(el, attr, default=None):
    v = el.get(A + attr)
    if v is None:
        return default
    v = v.strip()
    if v.startswith("@") or v.startswith("?"):
        raise Refuse(f"{attr}={v!r} is a resource reference, not a number")
    v = re.sub(r"(dp|dip|px|sp)$", "", v)
    return float(v)


def fmt(x):
    """Short, stable, and never scientific notation -- SVG readers vary on it."""
    if x is None:
        return None
    if abs(x - round(x)) < 1e-9:
        return str(int(round(x)))
    return f"{x:.6f}".rstrip("0").rstrip(".")


# ---------------------------------------------------------------- converter


class Converter:
    def __init__(self, resolver):
        self.res = resolver
        self.notes = []
        self._gid = 0
        self._defs = []

    def uid(self, kind):
        self._gid += 1
        return f"{kind}{self._gid}"

    # -- gradients ------------------------------------------------------

    def gradient(self, gel, what):
        gtype = (gel.get(A + "type") or "linear").strip()
        tile = (gel.get(A + "tileMode") or "clamp").strip()
        spread = {"clamp": "pad", "repeat": "repeat", "mirror": "reflect"}.get(tile)
        if spread is None:
            raise Refuse(f"{what}: tileMode={tile!r} has no SVG spreadMethod")

        stops = []
        items = [e for e in gel if e.tag.split("}")[-1] == "item"]
        if items:
            for it in items:
                off = num(it, "offset", 0.0)
                col, alpha = parse_color(it.get(A + "color"), self.res, what)
                stops.append((off, col, alpha))
        else:
            # The six-file form: startColor / centerColor / endColor.
            for attr, off in (("startColor", 0.0), ("centerColor", 0.5), ("endColor", 1.0)):
                raw = gel.get(A + attr)
                if raw:
                    col, alpha = parse_color(raw, self.res, what)
                    stops.append((off, col, alpha))
        if not stops:
            raise Refuse(f"{what}: <gradient> has no stops")

        gid = self.uid("g")
        body = []
        for off, col, alpha in stops:
            s = f'<stop offset="{fmt(off)}" stop-color="{col}"'
            if alpha < 1.0:
                s += f' stop-opacity="{fmt(alpha)}"'
            body.append(s + "/>")
        joined = "".join(body)

        if gtype == "linear":
            x1, y1 = num(gel, "startX", 0.0), num(gel, "startY", 0.0)
            x2, y2 = num(gel, "endX", 0.0), num(gel, "endY", 0.0)
            self._defs.append(
                f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
                f'x1="{fmt(x1)}" y1="{fmt(y1)}" x2="{fmt(x2)}" y2="{fmt(y2)}" '
                f'spreadMethod="{spread}">{joined}</linearGradient>'
            )
        elif gtype == "radial":
            cx, cy = num(gel, "centerX", 0.0), num(gel, "centerY", 0.0)
            r = num(gel, "gradientRadius", None)
            if r is None:
                raise Refuse(f"{what}: radial <gradient> with no gradientRadius")
            self._defs.append(
                f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" '
                f'cx="{fmt(cx)}" cy="{fmt(cy)}" r="{fmt(r)}" '
                f'spreadMethod="{spread}">{joined}</radialGradient>'
            )
        elif gtype == "sweep":
            # SVG 1.1 has no sweep gradient and Skia's SVGDOM is 1.1.  Faking it
            # with a many-stop conic approximation would be a different picture.
            raise Refuse(f"{what}: sweep <gradient> has no SVG 1.1 equivalent")
        else:
            raise Refuse(f"{what}: unknown gradient type {gtype!r}")
        return f"url(#{gid})"

    def aapt_attr(self, el, name, what):
        """<aapt:attr name="android:fillColor"><gradient/></aapt:attr>."""
        for child in el:
            if child.tag == AAPT + "attr" and child.get("name") == name:
                grads = [g for g in child if g.tag.split("}")[-1] == "gradient"]
                if len(grads) != 1:
                    raise Refuse(f"{what}: aapt:attr {name} holds {len(grads)} <gradient>")
                return self.gradient(grads[0], what)
        return None

    # -- paths ----------------------------------------------------------

    def path(self, el, what):
        d = el.get(A + "pathData")
        if d is None:
            raise Refuse(f"{what}: <path> with no pathData")
        if d.strip().startswith("@"):
            raise Refuse(f"{what}: pathData is a resource reference")
        for bad in ("trimPathStart", "trimPathEnd", "trimPathOffset"):
            v = el.get(A + bad)
            if v is not None and float(v) not in (0.0, 1.0 if bad == "trimPathEnd" else 0.0):
                raise Refuse(f"{what}: {bad}={v} -- a trimmed path is a different path")

        out = [f'<path d="{escape_attr(d)}"']

        # AOSP VectorDrawable.java:1527-1535 -- both colours default to
        # TRANSPARENT and both alphas to 1.  SVG's default fill is BLACK, so a
        # path with no fillColor must be given fill="none" explicitly or it
        # appears out of nowhere.
        grad = self.aapt_attr(el, "android:fillColor", what)
        if grad:
            fill, falpha = grad, 1.0
        else:
            fill, falpha = parse_color(el.get(A + "fillColor"), self.res, what + " fillColor")
        fa = num(el, "fillAlpha", 1.0) * falpha
        if fill is None:
            out.append('fill="none"')
        else:
            out.append(f'fill="{fill}"')
            if fa < 1.0:
                out.append(f'fill-opacity="{fmt(fa)}"')

        ftype = (el.get(A + "fillType") or "").strip()
        if ftype:
            rule = {"evenOdd": "evenodd", "nonZero": "nonzero"}.get(ftype)
            if rule is None:
                raise Refuse(f"{what}: fillType={ftype!r}")
            out.append(f'fill-rule="{rule}"')

        sgrad = self.aapt_attr(el, "android:strokeColor", what)
        if sgrad:
            stroke, salpha = sgrad, 1.0
        else:
            stroke, salpha = parse_color(el.get(A + "strokeColor"), self.res, what + " strokeColor")
        sw = num(el, "strokeWidth", 0.0)
        if stroke is not None and sw > 0:
            out.append(f'stroke="{stroke}"')
            out.append(f'stroke-width="{fmt(sw)}"')
            sa = num(el, "strokeAlpha", 1.0) * salpha
            if sa < 1.0:
                out.append(f'stroke-opacity="{fmt(sa)}"')
            cap = (el.get(A + "strokeLineCap") or "").strip()
            if cap:
                if cap not in ("butt", "round", "square"):
                    raise Refuse(f"{what}: strokeLineCap={cap!r}")
                out.append(f'stroke-linecap="{cap}"')
            join = (el.get(A + "strokeLineJoin") or "").strip()
            if join:
                if join not in ("miter", "round", "bevel"):
                    raise Refuse(f"{what}: strokeLineJoin={join!r}")
                out.append(f'stroke-linejoin="{join}"')
            ml = num(el, "strokeMiterLimit", None)
            if ml is not None and ml != 4:  # AOSP:1542, SVG's default is 4 too
                out.append(f'stroke-miterlimit="{fmt(ml)}"')
        return " ".join(out) + "/>"

    # -- groups ---------------------------------------------------------

    def group_transform(self, el, what):
        """AOSP VectorDrawable.java:1312-1320, VGroup.updateLocalMatrix():

            postTranslate(-pivotX, -pivotY)
            postScale(scaleX, scaleY)
            postRotate(rotation, 0, 0)
            postTranslate(translateX + pivotX, translateY + pivotY)

        `post` means applied AFTER, so the matrix is
        T(tx+px, ty+py) . R(rot) . S(sx, sy) . T(-px, -py) -- and an SVG
        transform list composes left-to-right in exactly that order, so the
        four operations transcribe one for one.  Getting this backwards is the
        classic vector-drawable bug: it looks right for a centred glyph with
        pivot 0,0 and wrong for every other one.
        """
        px, py = num(el, "pivotX", 0.0), num(el, "pivotY", 0.0)
        sx, sy = num(el, "scaleX", 1.0), num(el, "scaleY", 1.0)
        rot = num(el, "rotation", 0.0)
        tx, ty = num(el, "translateX", 0.0), num(el, "translateY", 0.0)
        ops = []
        if (tx + px, ty + py) != (0.0, 0.0):
            ops.append(f"translate({fmt(tx + px)},{fmt(ty + py)})")
        if rot:
            ops.append(f"rotate({fmt(rot)})")
        if (sx, sy) != (1.0, 1.0):
            ops.append(f"scale({fmt(sx)},{fmt(sy)})")
        if (px, py) != (0.0, 0.0):
            ops.append(f"translate({fmt(-px)},{fmt(-py)})")
        return " ".join(ops)

    def children(self, el, what):
        """A group's subtree.

        A <clip-path> is `canvas.clipPath()` (AOSP:1082-1084), so it applies to
        everything drawn after it in the same group.  Every clip-path in this
        corpus precedes its group's drawing children, which makes it equivalent
        to a clip on the whole group -- and that equivalence is CHECKED, not
        assumed: a clip-path with a drawn sibling before it is refused.
        """
        kids, clips, seen_drawn = [], [], False
        for child in el:
            tag = child.tag.split("}")[-1]
            if tag == "clip-path":
                if seen_drawn:
                    raise Refuse(
                        f"{what}: a <clip-path> follows a drawn element, so it clips only "
                        "its later siblings and is not a clip on the group"
                    )
                d = child.get(A + "pathData")
                if not d:
                    raise Refuse(f"{what}: <clip-path> with no pathData")
                clips.append(d)
            elif tag == "path":
                seen_drawn = True
                kids.append(self.path(child, what))
            elif tag == "group":
                seen_drawn = True
                kids.append(self.group(child, what))
            elif child.tag == AAPT + "attr":
                pass  # handled by the owning path
            else:
                raise Refuse(f"{what}: unhandled element <{tag}>")

        body = "".join(kids)
        # Multiple clip-paths intersect; SVG gives one clip-path per element, so
        # they nest.  Innermost first keeps the order readable.
        for d in reversed(clips):
            cid = self.uid("c")
            self._defs.append(
                f'<clipPath id="{cid}" clipPathUnits="userSpaceOnUse">'
                f'<path d="{escape_attr(d)}"/></clipPath>'
            )
            body = f'<g clip-path="url(#{cid})">{body}</g>'
        return body

    def group(self, el, what):
        tr = self.group_transform(el, what)
        body = self.children(el, what)
        return f'<g transform="{tr}">{body}</g>' if tr else f"<g>{body}</g>"

    # -- root -----------------------------------------------------------

    def convert(self, path):
        root = ET.parse(path).getroot()
        if root.tag.split("}")[-1] != "vector":
            raise Skip(f"a <{root.tag.split('}')[-1]}> drawable, not a <vector>")
        name = os.path.basename(path)

        if root.get(A + "tint"):
            raise Refuse("android:tint on the root recolours the whole drawable")

        vw = num(root, "viewportWidth", None)
        vh = num(root, "viewportHeight", None)
        if not vw or not vh:
            raise Refuse("no viewportWidth/viewportHeight")
        w = num(root, "width", vw)
        h = num(root, "height", vh)

        if (root.get(A + "autoMirrored") or "").strip() == "true":
            # Real, but it is a property of the DRAW, not of the picture: SVG
            # has no RTL mirroring.  The glyph is still correct in LTR, so this
            # is a note rather than a refusal -- the caller mirrors it.
            self.notes.append(f"{name}: autoMirrored=true has no SVG equivalent (LTR form emitted)")

        self._defs = []
        body = self.children(root, name)

        alpha = num(root, "alpha", 1.0)
        if alpha != 1.0:
            body = f'<g opacity="{fmt(alpha)}">{body}</g>'

        defs = f"<defs>{''.join(self._defs)}</defs>" if self._defs else ""
        return (
            f'<svg xmlns="{SVGNS}" width="{fmt(w)}" height="{fmt(h)}" '
            f'viewBox="0 0 {fmt(vw)} {fmt(vh)}">{defs}{body}</svg>\n'
        )


def escape_attr(s):
    return (
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")
    )


# ---------------------------------------------------------------- driver


def out_name(path):
    """The output stem, and why it is not just the basename.

    The same drawable name legitimately appears more than once:
      * qualifiers   -- drawable/ vs drawable-night/, drawable-v24/ …
      * build flavours -- src/main/res vs src/beta/res, src/nightly/res
    Flattening either into one directory makes the last one silently win.  On
    the first run that cost 8 glyphs with no message at all, which is the exact
    failure this tool exists to avoid, so both go into the name and a remaining
    collision is reported rather than written.
    """
    stem = os.path.splitext(os.path.basename(path))[0]
    parts = path.split(os.sep)
    suffix = []
    qual = os.path.basename(os.path.dirname(path))
    if qual != "drawable" and qual.startswith("drawable-"):
        suffix.append(qual[len("drawable-"):])
    if "src" in parts:
        i = len(parts) - 1 - parts[::-1].index("src")
        if i + 1 < len(parts) and parts[i + 1] != "main":
            suffix.insert(0, parts[i + 1])
    return stem + ("." + ".".join(suffix) if suffix else "")


def collect(srcs):
    out = []
    for s in srcs:
        if os.path.isfile(s):
            out.append(s)
        else:
            for dirpath, _d, files in os.walk(s):
                if os.path.basename(dirpath).startswith("drawable"):
                    for fn in sorted(files):
                        if fn.endswith(".xml"):
                            out.append(os.path.join(dirpath, fn))
    return sorted(set(out))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", nargs="+", help="XML file(s), or a res tree to walk")
    ap.add_argument("-o", "--outdir", help="write <name>.svg here")
    ap.add_argument("--one", action="store_true", help="one file to stdout")
    ap.add_argument("--colors", action="append", default=[],
                    help="res root(s) to read values/*.xml colours from (repeatable)")
    ap.add_argument("--attr-policy", default="currentColor",
                    help="what ?attr/foo becomes; 'refuse' to reject instead")
    ap.add_argument("--report", help="write a per-file report here")
    args = ap.parse_args()

    roots = args.colors or [s for s in args.src if os.path.isdir(s)]
    resolver = ColorResolver(roots, None if args.attr_policy == "refuse" else args.attr_policy)
    conv = Converter(resolver)

    files = collect(args.src)
    if args.one:
        sys.stdout.write(conv.convert(files[0]))
        return 0

    if not args.outdir:
        ap.error("-o/--outdir is required unless --one")
    os.makedirs(args.outdir, exist_ok=True)

    done, refused, skipped, rows = 0, [], [], []
    written = {}
    for f in files:
        name = out_name(f)
        try:
            svg = conv.convert(f)
        except Skip as e:
            skipped.append((f, str(e)))
            rows.append(f"SKIP\t{name}\t{e}")
            continue
        except Refuse as e:
            refused.append((f, str(e)))
            rows.append(f"REFUSED\t{name}\t{e}")
            continue
        except ET.ParseError as e:
            refused.append((f, f"XML parse error: {e}"))
            rows.append(f"REFUSED\t{name}\tXML parse error: {e}")
            continue
        out = os.path.join(args.outdir, name + ".svg")
        if out in written:
            refused.append((f, f"output name collides with {written[out]}"))
            rows.append(f"REFUSED\t{name}\tcollides with {written[out]}")
            continue
        written[out] = f
        with open(out, "w") as fh:
            fh.write(svg)
        done += 1
        rows.append(f"OK\t{name}\t{len(svg)} bytes")

    print(f"converted : {done}")
    print(f"skipped   : {len(skipped)}  (not <vector> drawables)")
    print(f"refused   : {len(refused)}")
    print(f"?attr/ hits (emitted as {args.attr_policy}): "
          f"{sum(resolver.attr_hits.values())} across {len(resolver.attr_hits)} names")
    for n in conv.notes:
        print("note      :", n)
    for f, why in refused[:20]:
        print("  REFUSED", os.path.basename(f), "--", why)
    if len(refused) > 20:
        print(f"  ... and {len(refused) - 20} more")

    if args.report:
        with open(args.report, "w") as fh:
            fh.write(f"# converted={done} skipped={len(skipped)} refused={len(refused)}\n")
            fh.write(f"# attr_policy={args.attr_policy} "
                     f"attr_hits={sum(resolver.attr_hits.values())}\n")
            for n in conv.notes:
                fh.write(f"# note {n}\n")
            for r in rows:
                fh.write(r + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
