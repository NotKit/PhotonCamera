/* THE SETTINGS SCREEN'S HOST.  -PwithApp only.
 *
 * ui-compose's `composeui/settings/SettingsScreen.kt` has drawn the whole
 * screen since before this port started: a title, a list of PreferenceItem and
 * SettingsUiEvent back out.  It was never reachable here because nothing built
 * it a list.  On Android that job is
 * `ui/settings/compose/SettingsScreenHost.kt`, 180 lines that walk an
 * androidx.preference tree; there is no androidx.preference on this port, so
 * that file is the SPECIFICATION and this is the code.
 *
 * WHERE THE ROWS COME FROM.  `res/xml/preferences.xml`, through `kn/gen-r.py`,
 * which already read that file for `R_PREFERENCE_DEFAULTS`.  It now emits the
 * ordered, typed tree as well (`R_PREFERENCE_TREE`), so the screen and the
 * defaults are read off the same 545 lines the Android build inflates.
 *
 * WHERE A CHANGE GOES.  SettingsManager, never at the file --
 * `CameraActions.applySetting` is the shape.  That matters twice over:
 * PreferenceKeys installs a listener on the store that re-runs
 * `Settings.loadCache()`, so a toggle reaches the running pipeline without
 * anything here knowing about it; and EVERY VALUE IS A STRING.
 * `ManagedSwitchPreference.persistBoolean` calls
 * `SettingsManager.set(scope, key, boolean)`, which converts to "1"/"0" and
 * puts a STRING.  A boolean put straight into the store is a
 * ClassCastException out of the next `PhotonCamera.onCreate` -- an app that no
 * longer starts, for a switch that was only meant to be off.
 */
package photoncam.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.particlesdevs.photoncamera.R
import com.particlesdevs.photoncamera.RPreferenceNode
import com.particlesdevs.photoncamera.R_PREFERENCE_DEFAULTS
import com.particlesdevs.photoncamera.R_PREFERENCE_TREE
import com.particlesdevs.photoncamera.R_PREFERENCE_VALUES
import com.particlesdevs.photoncamera.api.CameraMode
import com.particlesdevs.photoncamera.app.PhotonCamera
import com.particlesdevs.photoncamera.composeui.state.PreferenceItem
import com.particlesdevs.photoncamera.composeui.state.SettingsUiEvent
import com.particlesdevs.photoncamera.composeui.state.SettingsUiState
import com.particlesdevs.photoncamera.pro.SupportedDevice
import com.particlesdevs.photoncamera.settings.BackupRestoreUtil
import com.particlesdevs.photoncamera.settings.PreferenceKeys
import com.particlesdevs.photoncamera.settings.SettingsManager
import com.particlesdevs.photoncamera.util.FileManager
import java.io.File

private val SCOPE: String? = SettingsManager.SCOPE_GLOBAL

/**
 * The preference tree, drawn.
 *
 * [onLeave] is the back arrow at the root, which is SettingsActivity finishing:
 * the camera screen comes back and re-opens the camera, so a changed HDRX or
 * quad-bayer setting is applied by the same path a restart applies it.
 */
internal class SettingsHost(
	private val app: PhotonCamera,
	private val onLeave: () -> Unit,
) {
	var state by mutableStateOf(SettingsUiState())
		private set

	private val settings: SettingsManager? get() = app.getSettingsManager()

	/** One entry per screen the user has opened; the last one is drawn. */
	private class Page(val title: String, val nodes: Array<RPreferenceNode>)

	private val stack = ArrayList<Page>()

	/** key -> the node that drew the row, so an event never has to search. */
	private val byKey = HashMap<String, RPreferenceNode>()

	/**
	 * Summaries this port computes rather than reads: what a backup wrote, why
	 * a row does nothing here.  SettingsActivity sets these on the Preference
	 * itself; there is no Preference to set.
	 */
	private val notes = HashMap<String, String>()

	init {
		stack.add(Page(string(R.string.settings, "Settings"), rootNodes()))
		refresh()
	}

	// -- the list ------------------------------------------------------------

	fun refresh() {
		val page = stack.lastOrNull() ?: return
		byKey.clear()
		val items = ArrayList<PreferenceItem>()
		collect(page.nodes, items)
		state = SettingsUiState(title = page.title, items = items, canGoBack = true)
	}

	/**
	 * SettingsFragment.filterPreferencesByMode and showHideHdrxSettings, which
	 * drop whole categories before the list is ever built.  Both run once, when
	 * the screen opens, exactly as the fragment's onCreate does.
	 */
	private fun rootNodes(): Array<RPreferenceNode> {
		val photo = string(R.string.pref_category_photo_key, "")
		val jpg = string(R.string.pref_category_jpg_key, "")
		val hdrx = string(R.string.pref_category_hdrx_key, "")
		val video = string(R.string.pref_category_video_key, "")
		val rawvideo = string(R.string.pref_category_rawvideo_key, "")
		val drop = HashSet<String>()
		when (runCatching { CameraMode.valueOf(PreferenceKeys.getCameraModeOrdinal()) }.getOrNull()) {
			CameraMode.RAWVIDEO -> drop += listOf(photo, jpg, hdrx, video)
			CameraMode.VIDEO -> drop += listOf(photo, jpg, hdrx, rawvideo)
			else -> drop += listOf(video, rawvideo)
		}
		drop += if (runCatching { PreferenceKeys.isHdrXOn() }.getOrDefault(true)) jpg else hdrx
		return R_PREFERENCE_TREE.filter { it.key !in drop }.toTypedArray()
	}

	/**
	 * Flattens the tree the way a PreferenceFragment's list does: a category is
	 * a header row and its children follow it; a nested screen is a row that
	 * opens another screen, not a group to inline.
	 */
	private fun collect(nodes: Array<RPreferenceNode>, out: MutableList<PreferenceItem>) {
		for (node in nodes) {
			byKey[node.key] = node
			val title = titleOf(node)
			val summary = summaryOf(node)
			when (node.type) {
				"category" -> {
					out += PreferenceItem.Category(node.key, title)
					collect(node.children, out)
				}
				// The two tunable submenus are generated at runtime on Android
				// by reflecting over TunableRegistry; TunableInjectorKn.kt says
				// why that cannot happen here, so they have no children and the
				// row says so rather than opening an empty screen.
				"screen" -> out += PreferenceItem.Screen(
					node.key, title, summary, node.children.isNotEmpty(),
				)
				"switch" -> out += PreferenceItem.Switch(
					node.key, title, summary, readBoolean(node), enabledOf(node),
				)
				"list" -> out += PreferenceItem.Choice(
					key = node.key,
					title = title,
					summary = summary,
					entries = node.entries.toList(),
					values = node.values.toList(),
					selectedValue = readString(node),
					enabled = enabledOf(node),
				)
				"seek" -> {
					val bar = seekBar(node)
					out += PreferenceItem.Slider(
						key = node.key,
						title = title,
						summary = summary,
						value = bar.progress.toFloat(),
						min = 0f,
						max = bar.max.toFloat(),
						steps = (bar.max - 1).coerceAtLeast(0),
						valueLabel = bar.label,
						showValue = true,
						enabled = enabledOf(node),
					)
				}
				else -> out += PreferenceItem.Action(node.key, title, summary, enabledOf(node))
			}
		}
	}

	// -- what a row says -----------------------------------------------------

	/** SettingsFragment.setHdrxTitle, which is the only title it rewrites. */
	private fun titleOf(node: RPreferenceNode): String {
		if (node.key == string(R.string.pref_category_hdrx_key, "") &&
			runCatching { PreferenceKeys.isPerLensSettingsOn() }.getOrDefault(false)
		) {
			return node.title + "\t(Lens: " + PreferenceKeys.getCameraID() + ')'
		}
		return node.title
	}

	/**
	 * setFramesSummary, setVersionDetails, setThisDevice and setSupportedDevices,
	 * which are the four summaries SettingsActivity fills in code.
	 */
	private fun summaryOf(node: RPreferenceNode): String {
		notes[node.key]?.let { return it }
		return when (node.key) {
			string(R.string.pref_frame_count_key, "") ->
				if (readString(node).trim() == "1") string(R.string.unprocessed_raw, "")
				else string(R.string.frame_count_summary, "")
			string(R.string.pref_version_key, "") -> PhotonCamera.getVersion().orEmpty()
			string(R.string.pref_this_device_key, "") ->
				string(R.string.this_device, "%1\$s").replace("%1\$s", SupportedDevice.THIS_DEVICE.orEmpty())
			string(R.string.all_devices_names, "") -> supportedDevices()
			// A summary with a format specifier in it is one the Android build
			// fills from PackageManager; verbatim it is noise.
			else -> if (node.summary.contains('%')) "" else node.summary
		}
	}

	private fun supportedDevices(): String {
		val manager = settings ?: return ""
		val file = PreferenceKeys.Key.DEVICES_PREFERENCE_FILE_NAME.mValue ?: return ""
		val key = PreferenceKeys.Key.ALL_DEVICES_NAMES_KEY.mValue ?: return ""
		val names = runCatching { manager.getArrayList(file, key, null) }.getOrNull()
		if (names.isNullOrEmpty()) return string(R.string.list_not_loaded, "")
		return names.sorted().joinToString(", ")
	}

	/** The XML's own flag: the fragment has no runtime enable rule any more. */
	private fun enabledOf(node: RPreferenceNode): Boolean = node.enabled

	// -- what a row holds ----------------------------------------------------

	/** ManagedSwitchPreference.getPersistedBoolean. */
	private fun readBoolean(node: RPreferenceNode): Boolean {
		val d = node.default?.trim()
		val fallback = d != null && d != "" && d != "0" && d != "false"
		val manager = settings ?: return fallback
		return runCatching { manager.getBoolean(SCOPE, node.key, fallback) }.getOrDefault(fallback)
	}

	private fun readString(node: RPreferenceNode): String {
		val fallback = node.default ?: R_PREFERENCE_DEFAULTS[node.key] ?: ""
		val manager = settings ?: return fallback
		return runCatching { manager.getString(SCOPE, node.key, fallback) }.getOrNull() ?: fallback
	}

	private class Bar(val max: Int, val progress: Int, val label: String)

	/**
	 * UniversalSeekBarPreference's constructor and showStoredValue, ported.
	 *
	 * The arithmetic is in DOUBLE precision and closed with a round, never a
	 * truncation, for the reason that class's own header gives: in float32
	 * `(-0.15f - -0.2f) * 20` is 0.99999994, which truncates to 0 and loses a
	 * whole step.  And binding never rewrites the store, so a precise value
	 * that sits between two steps survives being looked at.
	 */
	private fun seekBar(node: RPreferenceNode): Bar {
		val step = if (!node.isFloat && node.step > 1f) 1.0 else node.step.toDouble()
		val max = maxOf(1, java.lang.Math.round((node.max.toDouble() - node.min) * step).toInt())
		val fallback = node.default?.trim()?.toFloatOrNull() ?: node.min
		val stored = settings?.let {
			runCatching { it.getString(SCOPE, node.key, node.default) }.getOrNull()
		}
		val raw = stored?.trim()?.toFloatOrNull() ?: fallback
		val clamped = raw.coerceIn(node.min, node.max)
		val progress = java.lang.Math.round((clamped.toDouble() - node.min) * step).toInt()
			.coerceIn(0, max)
		val label = if (clamped != raw || stored == null) exact(clamped, node.isFloat) else stored
		return Bar(max, progress, label)
	}

	/** UniversalSeekBarPreference.convertToValue: what a drag persists. */
	private fun valueOf(node: RPreferenceNode, progress: Int): String {
		val step = if (!node.isFloat && node.step > 1f) 1.0 else node.step.toDouble()
		val value = progress.toDouble() / step + node.min
		return if (node.isFloat) fixed(value, 2) else java.lang.Math.round(value).toString()
	}

	// -- events --------------------------------------------------------------

	fun onEvent(event: SettingsUiEvent) {
		when (event) {
			is SettingsUiEvent.Back -> back()

			is SettingsUiEvent.Toggle -> {
				val node = byKey[event.key] ?: return
				// "1"/"0" as a String -- see this file's header.
				settings?.set(SCOPE, node.key, event.checked)
				after(node)
			}

			is SettingsUiEvent.Choose -> {
				val node = byKey[event.key] ?: return
				settings?.set(SCOPE, node.key, event.value)
				after(node)
			}

			is SettingsUiEvent.Slide -> {
				val node = byKey[event.key] ?: return
				settings?.set(SCOPE, node.key, valueOf(node, event.value.toInt()))
				after(node)
			}

			// The precise-input dialog is UniversalSeekBarPreference's own
			// AlertDialog with an EditText in it; neither exists here.
			is SettingsUiEvent.EditValue ->
				log("${event.key}: the precise-value dialog is not wired on this port")

			is SettingsUiEvent.Click -> {
				val node = byKey[event.key] ?: return
				if (node.type == "screen") open(node) else click(node)
			}
		}
	}

	private fun after(node: RPreferenceNode) {
		log("${node.key} = ${readString(node)}")
		refresh()
	}

	fun back() {
		if (stack.size > 1) {
			stack.removeAt(stack.size - 1)
			refresh()
		} else {
			onLeave()
		}
	}

	private fun open(node: RPreferenceNode) {
		if (node.children.isEmpty()) return
		stack.add(Page(node.title, node.children))
		refresh()
	}

	/**
	 * The rows that are a button.  Three of them do their whole job through
	 * BackupRestoreUtil, which is converted and needs nothing from Android; the
	 * rest need a browser, a file picker or a package manager, and say so in
	 * their own summary rather than doing nothing.
	 */
	private fun click(node: RPreferenceNode) {
		when (node.key) {
			string(R.string.pref_backup_preferences_key, "") -> {
				val name = "photoncamera-" + java.lang.System.currentTimeMillis() + ".json"
				note(node, BackupRestoreUtil.backupSettings(app, name) ?: "Failed")
			}
			string(R.string.pref_restore_preferences_key, "") -> restoreNewest(node)
			string(R.string.pref_reset_preferences_key, "") -> reset(node)
			string(R.string.pref_fetch_configurations_key, "") -> {
				note(node, "Fetching\u2026")
				fetchConfigurations(node)
			}
			string(R.string.pref_contributors_key, "") ->
				note(node, "https://github.com/eszdman/PhotonCamera")
			string(R.string.pref_telegram_channel_key, "") ->
				note(node, "https://t.me/photon_camera_channel")
			else -> log("${node.key}: nothing to do")
		}
	}

	/**
	 * RestorePreference opens a file picker; there is none here, so the newest
	 * backup in DCIM/PhotonCamera is the file.  The JSON is applied through
	 * BackupRestoreUtil's own applyRestoredJson -- NOT through restoreFromJson,
	 * which ends in `PhotonCamera.restartWithDelay`, and restartApp here is a
	 * startActivity that does nothing followed by `System.exit(0)`.
	 */
	private fun restoreNewest(node: RPreferenceNode) {
		val dir = FileManager.sPHOTON_DIR
		val newest = dir?.listFiles()
			?.filter { it.getName().endsWith(".json") }
			?.maxByOrNull { it.lastModified() }
		if (newest == null) {
			note(node, "No .json backup in ${dir?.getAbsolutePath()}")
			return
		}
		val result = runCatching {
			val text = java.io.FileInputStream(newest).use { it.readBytes().decodeToString() }
			val root = com.google.gson.JsonParser.parseString(text).getAsJsonObject()
			BackupRestoreUtil.applyRestoredJson(app, root)
		}
		if (result.isFailure) {
			note(node, "Failed: ${result.exceptionOrNull()}")
			return
		}
		reload()
		note(node, "Restored ${newest.getName()}")
	}

	/**
	 * ResetPreferences' dialog, minus the dialog.  BackupRestoreUtil.reset-
	 * Preferences deletes the shared_prefs DIRECTORY, which the store this
	 * process is holding open would write straight back; clearing it through
	 * the store is the same outcome and survives not restarting.
	 */
	private fun reset(node: RPreferenceNode) {
		val manager = settings ?: return
		runCatching { manager.getDefaultPreferences()?.edit()?.clear()?.apply() }
		reload()
		note(node, "Reset to defaults")
	}

	/** AppScreen.registerPreferenceDefaults, for a store that just lost them. */
	private fun reload() {
		val manager = settings ?: return
		for ((key, default) in R_PREFERENCE_DEFAULTS)
			manager.setDefaults(key, default, R_PREFERENCE_VALUES[key] ?: arrayOf(default))
		runCatching { PreferenceKeys.setDefaults(app) }
		runCatching { PhotonCamera.getSettings()?.loadCache() }
	}

	/**
	 * SettingsFragment.setFetchConfigurationsPref: the network read is on a
	 * thread of its own, and the summary it lands in is Compose state -- so the
	 * result goes back through the app's main-thread queue, the same one the
	 * capture path posts to, and not straight off the worker.
	 */
	private fun fetchConfigurations(node: RPreferenceNode) {
		val device = app.getSupportedDevice()
		val handler = PhotonCamera.getMainHandler()
		if (device == null || handler == null) {
			note(node, "No device list to fetch into")
			return
		}
		java.lang.Thread(java.lang.Runnable {
			val result = runCatching { device.fetchFromNetwork() }
			val text = if (result.isSuccess) "Updated -- restart to apply"
			else "Failed: " + result.exceptionOrNull()
			handler.post(java.lang.Runnable { note(node, text) })
		}).start()
	}

	private fun note(node: RPreferenceNode, text: String) {
		notes[node.key] = text
		log("${node.key}: $text")
		refresh()
	}

	// -- odds and ends -------------------------------------------------------

	private fun string(id: Int, fallback: String): String =
		runCatching { app.getString(id) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: fallback

	/** String.format(Locale.ROOT, "%.<digits>f", v), which is what is persisted. */
	private fun fixed(value: Double, digits: Int): String {
		var scale = 1L
		repeat(digits) { scale *= 10 }
		val n = java.lang.Math.round(kotlin.math.abs(value) * scale)
		val text = if (digits == 0) n.toString()
		else (n / scale).toString() + "." + (n % scale).toString().padStart(digits, '0')
		return if (value < 0 && n != 0L) "-$text" else text
	}

	/** UniversalSeekBarPreference.formatExactValue: "%.6f", trimmed. */
	private fun exact(value: Float, isFloat: Boolean): String {
		if (!isFloat) return java.lang.Math.round(value).toString()
		val s = fixed(value.toDouble(), 6).trimEnd('0').trimEnd('.')
		return s.ifEmpty { "0" }
	}

	private fun log(message: String) = println("[pc] settings: $message")
}
