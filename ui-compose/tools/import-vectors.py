#!/usr/bin/env python3
"""Copy the app's vector drawables into the shared module's compose resources.

Compose Multiplatform reads the same <vector> XML, but only plain ones: it cannot
follow a resource reference, and it takes dp or px where aapt also takes dip. So
colour references are resolved here against the app's own colour tables, theme
attributes are dropped (Compose tints at the call site), and the state lists,
shapes and insets are left behind - those became `when` branches in CameraIcons.

Run from the repository root after changing a drawable:

    python3 ui-compose/tools/import-vectors.py
"""
import glob
import os
import re
import xml.dom.minidom

SRC = 'app/src/main/res/drawable'
DST = 'ui-compose/src/commonMain/composeResources/drawable'
VALUES = 'app/src/main/res/values'

# Launcher icons: adaptive-icon layers with aapt:attr gradients the parser rejects,
# and the UI never draws them.
SKIP = {'camera_launch.xml', 'camera_launch_bg.xml', 'camera_launch_fg.xml',
        'camera_launch_mono.xml', 'gallery_launch_bg.xml', 'gallery_launch_fg.xml',
        'gallery_launch_mono.xml'}

ATTRS = {
    'colorControlNormal': '#FFFFFFFF',
    'colorControlActivated': '#FF7E57C2',
    'colorAccent': '#FF7E57C2',
    'textColorPrimary': '#FFFFFFFF',
    'colorPrimary': '#FF7E57C2',
    'colorOnSurface': '#FFFFFFFF',
}
FRAMEWORK = {'white': '#FFFFFFFF', 'black': '#FF000000',
             'darker_gray': '#FFAAAAAA', 'transparent': '#00000000'}


def load_colors():
    colors = {}
    for name in ('colors.xml', 'md_colors.xml', 'custom_themes.xml'):
        path = os.path.join(VALUES, name)
        if not os.path.exists(path):
            continue
        text = open(path, encoding='utf-8').read()
        for key, value in re.findall(r'<color name="([^"]+)"\s*>([^<]+)</color>', text):
            colors[key] = value.strip()
    for _ in range(3):  # @color/a -> @color/b chains
        for key, value in list(colors.items()):
            if value.startswith('@color/'):
                colors[key] = colors.get(value[len('@color/'):], value)
    return colors


def main():
    colors = load_colors()

    def resolve(match):
        # The match spans the quotes, so put them back around the literal.
        ref = match.group(1)
        if ref.startswith('@android:color/'):
            return '"%s"' % FRAMEWORK.get(ref.split('/')[1], '#FFFFFFFF')
        if ref.startswith('@color/'):
            value = colors.get(ref.split('/')[1])
            return '"%s"' % (value if value and value.startswith('#') else '#FFFFFFFF')
        attr = ref.lstrip('?').replace('android:', '').replace('attr/', '')
        return '"%s"' % ATTRS.get(attr, '#FFFFFFFF')

    copied, skipped = [], []
    for path in sorted(glob.glob(os.path.join(SRC, '*.xml'))):
        name = os.path.basename(path)
        if name in SKIP:
            continue
        text = open(path, encoding='utf-8').read()
        decl = re.match(r'\s*<\?xml[^>]*\?>', text)
        body = text[decl.end():] if decl else text
        body = re.sub(r'^\s*(?:<!--.*?-->\s*)*', '', body, flags=re.S)
        if not body.startswith('<vector'):
            skipped.append(name)
            continue
        body = re.sub(r'"([@?][A-Za-z0-9_:/.]+)"', resolve, body)
        body = re.sub(r'\s+android:tint="[^"]*"', '', body)
        body = re.sub(r'(android:(?:width|height)="[0-9.]+)dip"', r'\1dp"', body)
        left = sorted(set(re.findall(r'"([@?][^"]*)"', body)))
        if left:
            skipped.append('%s (unresolved: %s)' % (name, ','.join(left)))
            continue
        out_path = os.path.join(DST, name)
        with open(out_path, 'w', encoding='utf-8') as out:
            out.write('<?xml version="1.0" encoding="utf-8"?>\n' + body.lstrip())
        # A substitution that eats a quote only shows up as a crash on device.
        xml.dom.minidom.parse(out_path)
        copied.append(name)

    print('copied %d vectors, left %d composite drawables behind' % (len(copied), len(skipped)))
    for name in skipped:
        print('   ', name)


if __name__ == '__main__':
    main()
