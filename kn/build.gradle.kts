// PhotonCamera as one Kotlin/Native binary: Aurora's JVM-free Compose klibs,
// our own Wayland/EGL window (host/mgwl.c), no JVM and no ART.
//
// Milestone 2 compiles the platform-neutral UI only -- ../ui-compose's Compose
// tree plus this lane's linuxMain host and a hand-made CameraUiState.  The
// converted app (gen/ and src/commonMain/kotlin/{android,java,...}) is mid-flight
// in four other lanes and is added behind -PwithApp, which defaults off.
//
// The shape is fenix-kn's (firefox-atl/fenix-kn/build.gradle.kts); the comments
// that explain a decision rather than a line are kept because every one of them
// cost that lane a pass.
plugins {
	kotlin("multiplatform") version "2.3.0"
	id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
	id("org.jetbrains.compose") version "0.0.4-aurora"
}

val auroraMaven = "/home/nekit/UT/firefox-atl/compose-ut/aurora-probe/aurora-maven"
val armSysroot = "/home/nekit/UT/firefox-atl/compose-ut/aurora-probe/sysroot-arm64"

// -PwithApp: also compile the converted app and the other lanes' shims.  Off by
// default so this lane's build never fails on somebody else's half-written file.
val withApp = providers.gradleProperty("withApp").isPresent

// THE DRAWABLES.  CMP on Kotlin/Native reads a drawable through Aurora's
// components-resources, whose toXmlElement() is SVGDOM -- an SVG parser -- so an
// Android <vector> does not render wrong, it THROWS ("Can't wrap nullptr").
// scripts/convert-drawables.sh turns ../ui-compose's 75 vectors into composeResources/
// and is run by scripts/build-kn.sh; ui-compose itself keeps its XML, so its
// Android and desktop-JVM builds are untouched.
compose.resources {
	publicResClass = true
	// The package ../ui-compose's sources import.  It must match exactly or the
	// generated Res resolves against nothing.
	packageOfResClass = "com.particlesdevs.photoncamera.composeui.resources"
	generateResClass = always
	customDirectory("commonMain", provider { layout.projectDirectory.dir("composeResources") })
}

// ru.auroraos.kmp:ak-path-info's cinterop klib -- which components-resources
// pulls in to find <package files> -- carries linkerOpts.linux=-lappdir, and no
// libappdir exists in aurora-maven, in aurora-probe's sysroots or on this host.
// The bundled libac_path_info.a needs exactly one symbol from it,
// appdir_get_path(int kind), and host/appdir_stub.c is the stand-in: it answers
// with the directory of the executable, which is where stageResources puts them.
fun registerAppdirStub(arch: String, cc: String) = tasks.register<Exec>("appdirStub${arch.replaceFirstChar { it.uppercase() }}") {
	val src = file("host/appdir_stub.c")
	val obj = layout.buildDirectory.file("appdir-$arch/appdir_stub.o").get().asFile
	// The NAME matters: ak-path-info's own cinterop klib puts `-lappdir` on the
	// link line, so the archive has to be libappdir.a in a directory of its own.
	val lib = layout.buildDirectory.file("appdir-$arch/libappdir.a").get().asFile
	inputs.file(src)
	outputs.file(lib)
	commandLine("sh", "-c",
		"mkdir -p '${obj.parentFile.absolutePath}' && " +
		"$cc -O2 -fPIC -c '${src.absolutePath}' -o '${obj.absolutePath}' && " +
		"rm -f '${lib.absolutePath}' && ar rcs '${lib.absolutePath}' '${obj.absolutePath}'")
}

val appdirStubX64 = registerAppdirStub("x64", "gcc")
val appdirStubArm64 = registerAppdirStub("arm64", "aarch64-linux-gnu-gcc")

// ---------------------------------------------------------------- the app's C
//
// THREE MORE CINTEROPS, and they exist only under -PwithApp.  Each is another
// lane's; this file is where they meet the Gradle build, and the authority for
// every flag below is that lane's own build script, not a guess:
//   gles       cinterop/gles.def          + cinterop/build-gles.sh
//   natives    cinterop/natives.def       + host/natives/build_klib.sh
//   atlcamera  cinterop/atlcamera.def     + host/atlcamera/build.sh
//
// A .def or a header that changes does NOT invalidate the cinterop klib --
// Gradle does not look inside either -- so scripts/build-kn.sh takes
// --clean-cinterop, and that is the only way the cache is dropped.
val archDirs = mapOf("x64" to "x86_64", "arm64" to "arm64")

/** glib's include paths, asked of pkg-config exactly as host/atlcamera/build.sh does. */
val glibIncludes: List<String> by lazy {
	runCatching {
		providers.exec {
			commandLine("pkg-config", "--cflags-only-I", "glib-2.0", "libzstd")
		}.standardOutput.asText.get().trim().split(Regex("\\s+")).filter { it.startsWith("-I") }
	}.getOrDefault(emptyList())
}

/**
 * What a link of the natives needs, read from the file the natives lane WRITES
 * for exactly this purpose (`host/natives/build.sh` ends by emitting it).
 * Reading it rather than repeating it is the whole point: when that lane adds
 * libjpeg, libpng or stb this round, the flag appears here with no edit.
 */
fun nativesLinkFlags(arch: String): List<String> {
	val f = file("out/natives/${archDirs[arch]}/link-flags.txt")
	if (!f.exists()) return emptyList()
	return f.readLines().flatMap { it.trim().split(Regex("\\s+")) }.filter { it.isNotEmpty() }
}

fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.appCinterops(arch: String) {
	if (!withApp) return
	val main = compilations.getByName("main")
	val nativesDir = file("out/natives/${archDirs[arch]}")
	val atlDir = file("host/atlcamera/build-${archDirs[arch]}")

	// GLES 3.1 + EGL for the android.opengl.* shims.  The headers are the host's
	// and are architecture-independent; -idirafter (in the .def) is what keeps
	// them BEHIND Kotlin/Native's own sysroot, so <stdint.h> is still konan's.
	main.cinterops.create("gles") {
		definitionFile.set(project.file("cinterop/gles.def"))
	}

	// The app's own C++ behind a plain C ABI.  build_klib.sh names the static
	// libraries on the command line and not in the .def, because the paths are
	// this machine's; so does this.
	main.cinterops.create("natives") {
		definitionFile.set(project.file("cinterop/natives.def"))
		extraOpts("-compiler-option", "-I${projectDir}/host/natives")
		if (nativesDir.resolve("libphotoncam_native.a").exists()) {
			extraOpts("-libraryPath", nativesDir.absolutePath)
			extraOpts("-staticLibrary", "libphotoncam_native.a")
		}
		// EVERY OTHER ARCHIVE THE LANE NAMES, taken from its own link-flags.txt
		// rather than listed here.  Today that is ncnn -- optional by
		// construction, and at a different path per architecture; tomorrow it is
		// whatever the image codec turns out to need.  Nothing in this file has
		// to change for either.
		nativesLinkFlags(arch).filter { it.startsWith("/") && it.endsWith(".a") }.forEach { p ->
			val a = file(p)
			if (a.exists()) {
				extraOpts("-libraryPath", a.parentFile.absolutePath)
				extraOpts("-staticLibrary", a.name)
			}
		}
	}

	// atlas's camera2 backend.
	main.cinterops.create("atlcamera") {
		definitionFile.set(project.file("cinterop/atlcamera.def"))
		extraOpts("-compiler-option", "-I${projectDir}/host/atlcamera/src")
		glibIncludes.forEach { extraOpts("-compiler-option", it) }
		if (atlDir.resolve("libatlcamera.a").exists()) extraOpts("-libraryPath", atlDir.absolutePath)
	}
}

/** The -L/-l the three interops add to the final link, per architecture. */
fun appLinkerOpts(arch: String): List<String> {
	if (!withApp) return emptyList()
	val atlDir = file("host/atlcamera/build-${archDirs[arch]}")
	return nativesLinkFlags(arch) +
		listOf("-L${atlDir.absolutePath}", "-latlcamera", "-lglib-2.0", "-lzstd", "-lpthread")
}

kotlin {
	linuxX64 {
		compilations.getByName("main").cinterops.create("mgwl") {
			definitionFile.set(project.file("cinterop/mgwl.def"))
			// Absolute, so cinterop does not care where it is run from.
			extraOpts("-compiler-option", "-I${projectDir}/host")
			extraOpts("-compiler-option", "-I${projectDir}/host/vendor")
		}
		appCinterops("x64")
		binaries.executable { entryPoint = "photoncam.main" }
		binaries.all {
			freeCompilerArgs += listOf(
				// Kotlin/Native's bundled sysroot is glibc 2.19; the Aurora .a
				// files want __isoc23_* and memfd_create.  aurora-probe's flags.
				"-Xoverride-konan-properties=" + listOf(
					"targetSysRoot.linux_x64=/",
					"crtFilesLocation.linux_x64=usr/lib/x86_64-linux-gnu",
					"libGcc.linux_x64=usr/lib/gcc/x86_64-linux-gnu/14",
				).joinToString(";")
			)
			linkerOpts += listOf(
				"-Wl,--allow-multiple-definition",
				"-L/usr/lib/x86_64-linux-gnu",
				"-L${projectDir}/hostlibs",
				"-L${projectDir}/build",
				"-L$auroraMaven/3rd_party/maliit-glib/x86_64",
				"-lmgwl-x64",
				"-L${projectDir}/build/appdir-x64",
			) + commonLibs + appLinkerOpts("x64")
		}
	}
	// THE PHONE.  Same shape, with aurora-probe's sysroot flags (its checkout is
	// read, never written).  Two additions of our own: libmgwl-arm64.a from
	// scripts/build-mgwl.sh, and armlibs-extra/ -- that sysroot carries no
	// libxkbcommon, because Aurora's link line has none and mgwl's does.
	linuxArm64 {
		compilations.getByName("main").cinterops.create("mgwl") {
			definitionFile.set(project.file("cinterop/mgwl.def"))
			extraOpts("-compiler-option", "-I${projectDir}/host")
			extraOpts("-compiler-option", "-I${projectDir}/host/vendor")
		}
		appCinterops("arm64")
		binaries.executable { entryPoint = "photoncam.main" }
		binaries.all {
			freeCompilerArgs += listOf(
				"-Xoverride-konan-properties=" + listOf(
					"targetSysRoot.linux_arm64=$armSysroot",
					"crtFilesLocation.linux_arm64=usr/lib/aarch64-linux-gnu",
					"libGcc.linux_arm64=usr/lib/gcc/aarch64-linux-gnu/13",
				).joinToString(";")
			)
			linkerOpts += listOf(
				"-Wl,--allow-multiple-definition",
				"-L$armSysroot/usr/lib/aarch64-linux-gnu",
				"-L${projectDir}/armlibs",
				"-L${projectDir}/armlibs-extra",
				"-L${projectDir}/build",
				"-L$auroraMaven/3rd_party/maliit-glib/aarch64",
				"-lmgwl-arm64",
				"-L${projectDir}/build/appdir-arm64",
			) + commonLibs + appLinkerOpts("arm64")
		}
	}
	sourceSets {
		// MATCHING and not `by getting`: the default hierarchy's intermediate
		// source sets do not exist yet while this block runs, and asking for
		// `commonMain` by name here is fine but `linuxMain` is not -- keep both
		// in one idiom.
		matching { it.name == "commonMain" }.configureEach {
			// ../ui-compose is the app's already platform-neutral Compose UI,
			// compiled from where the Android build keeps it -- not copied, so
			// there is one tree and one truth.
			kotlin.srcDir("../ui-compose/src/commonMain/kotlin")
			if (withApp) {
				// ROUND 2.  src/commonMain/kotlin is already on the list (it is
				// the default), so only the two generated trees are added: gen/
				// is the converted app and gen-atlas/ is atlas's converted
				// camera2, which the android.hardware.camera2 shims sit on.
				kotlin.srcDir("gen")
				kotlin.srcDir("gen-atlas")
			} else {
				// DROP the default src/commonMain/kotlin, which is where five
				// other lanes are half-way through the converted app: compiling
				// their work in progress fails this lane on somebody else's
				// file.  Filtering the list rather than replacing it keeps the
				// Compose plugin's generated `Res` accessors, which it adds to
				// this same source set and which a setSrcDirs would erase
				// depending on which of the two ran first.
				val theirs = file("src/commonMain/kotlin")
				kotlin.setSrcDirs(kotlin.srcDirs.filterNot { it == theirs })
				// A srcDir may point at a subdirectory -- Kotlin does not
				// require the package to match the path -- so this lane's own
				// fake state comes back on its own.  AppCameraHost.kt is the one
				// file in there that needs gen/, so it stays out.
				kotlin.srcDir("src/commonMain/kotlin/photoncam/host")
				kotlin.exclude("AppCameraHost.kt")
			}
		}
		// WHICH SCREEN main() SHOWS, as a source root rather than a flag: the
		// no-app build must not so much as NAME the converted app, or it would
		// need gen/ to resolve it.  Both roots define photoncam.screen.
		matching { it.name == "linuxMain" }.configureEach {
			kotlin.srcDir(if (withApp) "src/linuxApp/kotlin" else "src/linuxNoApp/kotlin")
		}
		commonMain.dependencies {
			// NOT a test dependency by accident.  Compose's postDelayed (used by
			// RectManager) launches on Dispatchers.Main, and Kotlin/Native on
			// Linux has no main dispatcher at all -- the first layout pass dies
			// with "Dispatchers.Main is missing on the current platform".  The
			// only sanctioned way to install one is coroutines-test's setMain,
			// and Aurora's own `application {}` does exactly this.  Ours does it
			// in src/linuxMain/kotlin/photoncam/Main.kt.
			implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
			implementation(compose.runtime)
			implementation(compose.foundation)
			implementation(compose.material3)
			implementation(compose.ui)
			implementation(compose.components.resources)
		}
	}
}

// THE BAGGAGE, and why it is here.  compose-ui's klib manifest names ak-window,
// ak-uri-launcher and ak-keyboard-maliit as dependencies, so their archives and
// these -l flags stay on the link line whether or not a single symbol of theirs
// is called.  Unreferenced archive members are not pulled in, so no winit and no
// glutin end up in the binary.
val commonLibs: List<String>
	get() = listOf(
		"-lQt5Core", "-lQt5DBus",
		"-lEGL", "-lGLESv2",
		"-lwayland-client", "-lwayland-egl", "-lwayland-cursor",
		"-lxkbcommon",
		"-lfontconfig", "-ldbus-1",
		"-lmaliit-glib", "-lgobject-2.0", "-lglib-2.0", "-lstdc++",
		"-lruntime-manager-qt5",
	)

// THE RUNTIME SIDE OF THE RESOURCES, and it is not optional.  Aurora's
// LinuxResourceReader.normalizePath keeps only the LAST TWO path segments and
// joins them with '_', and reads them under PathInfo.getPathResources(), which is
// appdir_get_path(0x10) + "/resources".  host/appdir_stub.c answers that with the
// directory of the executable, so `resources/` has to sit beside the .kexe.
// Staged by the BUILD rather than by a run script, because a missing resource is
// a MissingResourceError on the first frame that draws an icon.
fun registerStageResources(arch: String, binDir: String) =
	tasks.register<Sync>("stageResources${arch.replaceFirstChar { it.uppercase() }}") {
		dependsOn(tasks.matching {
			it.name.startsWith("convertXmlValueResourcesFor") ||
				it.name.startsWith("copyNonXmlValueResourcesFor")
		})
		from(layout.buildDirectory.dir(
			"generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
		into(layout.buildDirectory.dir("$binDir/resources"))
		includeEmptyDirs = false
		eachFile { path = relativePath.segments.takeLast(2).joinToString("_") }
	}
val stageResourcesX64 = registerStageResources("x64", "bin/linuxX64/debugExecutable")
val stageResourcesArm64 = registerStageResources("arm64", "bin/linuxArm64/releaseExecutable")

// The link needs libappdir.a on its -L path before it starts, and the staged
// resources beside the binary after it finishes; nothing else produces either.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
	val arm64 = name.contains("Arm64")
	dependsOn(if (arm64) appdirStubArm64 else appdirStubX64)
	finalizedBy(if (arm64) stageResourcesArm64 else stageResourcesX64)
}
