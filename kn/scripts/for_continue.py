#!/usr/bin/env python3
"""j2k writes a Java `for` as a `while` with the update LAST IN THE BODY, and a
`continue` in that body therefore skips the update: the loop spins on the same
index forever.

    for (int i = 0; i < n; i++) {       run {
        if (skip) continue;                 var i = 0
        work(i);                            while (i < n) {
    }                                           if (skip) { continue }   // i stays
                                                work(i)
                                                i++                      // never reached
                                            }
                                        }

It is silent -- no exception, no output, just a thread at 100% -- and it is
everywhere the app filters inside a counted loop.  ESD4D's noise fit and its
merge loop both spun on it, and each looked like a GPU hang for a whole round.

This restores the update before every `continue` that belongs to such a loop.
A `continue` inside a NESTED loop belongs to that one and is left alone; so is
a labelled `continue@`.  A loop only qualifies when the variable its body ends
by updating is the one its condition tests, which is what makes it a converted
`for` rather than a `while` somebody wrote.

SHARED by convert.sh and atlas-fixups.sh -- one copy, so the two cannot drift.
"""
import re
import sys
from pathlib import Path

def strip_literals(line: str) -> str:
    """Braces in a string or a comment are not block braces."""
    line = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
    line = re.sub(r"'(?:\\.|[^'\\])*'", "''", line)
    return line.split('//')[0]


WHILE = re.compile(r'^(\s*)while \((?P<cond>.*)\)\s*\{\s*$')
UPDATE = re.compile(r'^\s*(?P<var>\w+)(?:\+\+|--|\s[+\-*/]= .+)\s*$')
OPENS_LOOP = re.compile(r'^\s*(?:while|for|do)\b.*\{\s*$')
CONTINUE = re.compile(r'^(\s*)continue\s*$')


def fix_file(path: Path) -> int:
    lines = path.read_text().split('\n')
    out = list(lines)
    fixed = 0
    for i, line in enumerate(lines):
        m = WHILE.match(line)
        if not m:
            continue
        indent = len(m.group(1))
        # the body: everything up to the closing brace at this indent
        end = None
        for j in range(i + 1, len(lines)):
            cur = lines[j]
            if cur.strip().startswith('}') and (len(cur) - len(cur.lstrip())) <= indent:
                end = j
                break
        if end is None:
            continue
        body = range(i + 1, end)
        tail = [j for j in body if lines[j].strip()]
        if not tail:
            continue
        upd = UPDATE.match(lines[tail[-1]])
        # a converted `for`: the body ends by updating what the condition tests
        if not upd or not re.search(r'\b%s\b' % re.escape(upd.group('var')), m.group('cond')):
            continue
        # AND j2k's own shape around it -- `run { var i = 0; while (...) {` --
        # which is what tells a converted `for` from a `while` somebody wrote.
        # UltraHdrGalleryUtil's JPEG segment walk is a real `while` whose last
        # statement happens to advance the variable its condition tests, and it
        # already steps before each `continue`: adding the tail update there
        # both double-steps and names a variable out of scope.
        if i < 2 or not re.match(r'^\s*var %s\b' % re.escape(upd.group('var')), lines[i - 1]) \
                or lines[i - 2].strip() != 'run {':
            continue
        update_stmt = lines[tail[-1]].strip()
        # A `continue` belongs to THIS loop only when no nested loop is open
        # around it.  Counting `while`/`for` lines is not enough -- an `if`
        # block's closing brace would close the count early and the outer
        # update would land inside an inner loop -- so the brace depth each
        # nested loop opened at is what is tracked and popped.
        depth = 0
        loops = []
        pending = []
        for j in body:
            cur = lines[j]
            opens_loop = OPENS_LOOP.match(cur) is not None
            if opens_loop:
                loops.append(depth)
            c = CONTINUE.match(cur)
            if c and not loops and lines[j - 1].strip() != update_stmt:  # idempotent
                pending.append((j, c.group(1)))
            bare = strip_literals(cur)
            depth += bare.count('{') - bare.count('}')
            while loops and depth <= loops[-1]:
                loops.pop()
        for j, ind in reversed(pending):
            out[j] = ind + update_stmt + '\n' + out[j]
            fixed += 1
    if fixed:
        path.write_text('\n'.join(out))
    return fixed


def main(roots):
    total = files = 0
    for root in roots:
        for f in sorted(Path(root).rglob('*.kt')):
            n = fix_file(f)
            if n:
                total += n
                files += 1
    print(f"`continue` that skipped its for-loop update, repaired: {total} in {files} files")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:] or ['gen']))
