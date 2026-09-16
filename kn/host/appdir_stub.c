#include <stdio.h>
/* libappdir stand-in for Stage 0b's CMP resources.
 *
 * ru.auroraos.kmp:ak-path-info's cinterop klib carries
 * linkerOpts.linux=-lappdir, and the bundled libac_path_info.a has exactly one
 * undefined symbol from it: appdir_get_path.  No libappdir ships in
 * aurora-maven, in aurora-probe's sysroots, or on this host -- it is part of the
 * Aurora application container, which UT does not have.
 *
 * Signature read off the .a: const char *appdir_get_path(int kind).  Rust
 * copies the string out through utils_c::read_string, so a malloc'd copy is
 * safe whether or not it frees.  kind 0x10 is "package files", the only one
 * CMP's LinuxResourceReader uses (getPathResources = package_files/resources).
 *
 * ATL_APPDIR names the package root; it defaults to the directory of the
 * executable, so a desktop run stages `resources/` beside the .kexe.
 *
 * ON A PHONE THIS IS A DECISION, NOT A STUB.  appdir_get_path is Aurora's
 * application-container API and UT has no equivalent: nothing on a UT device
 * answers "where are this package's files".  The click has to pick that path
 * itself.  See scripts/resources-session.sh's header and the pass-3 report.
 */
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <unistd.h>

static char *dup_or_null(const char *s) {
	if (!s) return NULL;
	size_t n = strlen(s) + 1;
	char *p = (char *)malloc(n);
	if (p) memcpy(p, s, n);
	return p;
}

static const char *appdir_root(void) {
	static char root[PATH_MAX];
	if (root[0]) return root;
	const char *env = getenv("ATL_APPDIR");
	if (env && env[0]) {
		snprintf(root, sizeof root, "%s", env);
		return root;
	}
	ssize_t n = readlink("/proc/self/exe", root, sizeof root - 1);
	if (n <= 0) { snprintf(root, sizeof root, "."); return root; }
	root[n] = 0;
	char *slash = strrchr(root, '/');
	if (slash) *slash = 0;
	return root;
}

/* Aurora's AppDirKind, as far as the .a's call sites reveal it. */
#define APPDIR_APP_DATA        0x01
#define APPDIR_APP_CACHE       0x05
#define APPDIR_HOME            0x08
#define APPDIR_PACKAGE_FILES   0x10
#define APPDIR_TEMP            0x14
#define APPDIR_TRANSLATIONS    0x15

const char *appdir_get_path(int kind) {
	char buf[PATH_MAX];
	switch (kind) {
	case APPDIR_PACKAGE_FILES:
		return dup_or_null(appdir_root());
	case APPDIR_TRANSLATIONS:
		snprintf(buf, sizeof buf, "%s/translations", appdir_root());
		return dup_or_null(buf);
	case APPDIR_HOME:
		return dup_or_null(getenv("HOME") ? getenv("HOME") : "/tmp");
	case APPDIR_TEMP:
		return dup_or_null(getenv("TMPDIR") ? getenv("TMPDIR") : "/tmp");
	case APPDIR_APP_DATA:
		snprintf(buf, sizeof buf, "%s/data", appdir_root());
		return dup_or_null(buf);
	case APPDIR_APP_CACHE:
		snprintf(buf, sizeof buf, "%s/cache", appdir_root());
		return dup_or_null(buf);
	default:
		return dup_or_null(appdir_root());
	}
}
