/* j2k emits `import org.mozilla.gecko.knstub.*` into every converted file (it
 * was GeckoView's per-lane stub package) and convert.sh deletes gen/org, so the
 * import has nothing to resolve to.  This empty package answers it.  If the
 * rule stops emitting that import, delete this file. */
package org.mozilla.gecko.knstub
