rootProject.name = "photoncam-kn"

// Aurora's CMP fork is a git repo laid out as a maven repository: the only
// thing this lane takes from it is the Compose and skiko klibs for
// linuxX64/linuxArm64.  Its own window layer (ak-window) is not used -- see
// cinterop/mgwl.def.
//
// It is 922 MB (the arm64 sysroot beside it another 141 MB), so WHERE it is
// is a per-machine answer, and this is the search order.  build.gradle.kts
// repeats it for the sysroot and the two must stay in step:
//
//   1. PC_AURORA_MAVEN, or -PauroraMaven -- an explicit answer wins
//   2. kn/deps/aurora-maven -- what scripts/fetch-deps.sh clones, and what CI
//      and the clickable container use: the container mounts the project root
//      and NOTHING else, so a dependency outside it does not exist there
//   3. this box's aurora-probe checkout -- kept last so that a machine which
//      already has one needs no second 922 MB copy
//
// The in-project checkout has to be a REAL directory: a symlink pointing out of
// the project root resolves to nothing inside the container.
//
// A settings script CAN read a gradle property, contrary to the usual warning:
// Settings.getProviders() exists in Gradle 8.13 and gradleProperty() sees both
// -P and gradle.properties.  The env var is still tried first, because it is
// what a container passes in without a wrapper script.
fun resolveDep(envVar: String, property: String, inProject: String, fallback: String): String {
	System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let { return it }
	providers.gradleProperty(property).orNull?.takeIf { it.isNotBlank() }?.let { return it }
	val local = settingsDir.resolve(inProject)
	return if (local.isDirectory) local.absolutePath else fallback
}

val auroraMaven = resolveDep("PC_AURORA_MAVEN", "auroraMaven", "deps/aurora-maven",
	"/home/nekit/UT/firefox-atl/compose-ut/aurora-probe/aurora-maven")

pluginManagement {
	// THIS BLOCK IS COMPILED ON ITS OWN, before the rest of the script exists: the
	// Kotlin DSL evaluates pluginManagement in a first stage where a top-level
	// declaration below is not in scope, so it cannot call resolveDep() and the
	// chain is spelled out again.  That is why the path was written twice here
	// before this file had any chain at all.  Keep the two in step.
	val auroraMaven = System.getenv("PC_AURORA_MAVEN")?.takeIf { it.isNotBlank() }
		?: providers.gradleProperty("auroraMaven").orNull?.takeIf { it.isNotBlank() }
		?: settingsDir.resolve("deps/aurora-maven").takeIf { it.isDirectory }?.absolutePath
		?: "/home/nekit/UT/firefox-atl/compose-ut/aurora-probe/aurora-maven"
	repositories {
		maven { url = uri(auroraMaven) }
		mavenCentral()
		gradlePluginPortal()
		google()
	}
}

dependencyResolutionManagement {
	repositories {
		maven { url = uri(auroraMaven) }
		mavenCentral()
		google()
	}
}
