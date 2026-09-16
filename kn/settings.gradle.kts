rootProject.name = "photoncam-kn"

// Aurora's CMP fork is a git repo laid out as a maven repository: the only
// thing this lane takes from it is the Compose and skiko klibs for
// linuxX64/linuxArm64.  It is 922 MB and the disk is full, so it is REFERENCED
// where firefox-atl's lane already unpacked it, never copied.  Its own window
// layer (ak-window) is not used -- see cinterop/mgwl.def.
val auroraMaven = "/home/nekit/UT/firefox-atl/compose-ut/aurora-probe/aurora-maven"

pluginManagement {
	repositories {
		maven { url = uri("/home/nekit/UT/firefox-atl/compose-ut/aurora-probe/aurora-maven") }
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
