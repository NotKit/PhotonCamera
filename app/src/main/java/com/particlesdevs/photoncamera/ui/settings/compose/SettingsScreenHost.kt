package com.particlesdevs.photoncamera.ui.settings.compose

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import com.particlesdevs.photoncamera.composeui.settings.SettingsScreen
import com.particlesdevs.photoncamera.composeui.state.PreferenceItem
import com.particlesdevs.photoncamera.composeui.state.SettingsUiEvent
import com.particlesdevs.photoncamera.composeui.state.SettingsUiState
import com.particlesdevs.photoncamera.composeui.theme.PhotonTheme
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference
import com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference

/**
 * Draws the app's preference tree with Compose.
 *
 * res/xml/preferences.xml and everything SettingsFragment generates into it at runtime
 * are untouched: the tree is walked into [PreferenceItem]s, and every change goes back
 * through the Preference that produced the row, so the existing change listeners,
 * dialogs and dependencies all still run.
 */
class SettingsScreenHost(
    private val context: Context,
    private val onBack: () -> Unit,
    private val onOpenScreen: (PreferenceScreen) -> Unit,
) {
    var state by mutableStateOf(SettingsUiState())
        private set

    private var root: PreferenceGroup? = null
    private val byKey = mutableMapOf<String, Preference>()

    fun createView(): View = ComposeView(context).apply {
        setContent {
            PhotonTheme {
                SettingsScreen(state, ::onEvent, Modifier.fillMaxSize())
            }
        }
    }

    fun bind(screen: PreferenceGroup, title: String, canGoBack: Boolean) {
        root = screen
        refresh(title, canGoBack)
    }

    fun refresh(
        title: String = state.title,
        canGoBack: Boolean = state.canGoBack,
    ) {
        val group = root ?: return
        byKey.clear()
        val items = mutableListOf<PreferenceItem>()
        collect(group, items)
        state = SettingsUiState(title = title, items = items, canGoBack = canGoBack)
    }

    /**
     * Flattens the tree the way a PreferenceFragment's list does: a category becomes a
     * header row and its children follow it.
     */
    private fun collect(group: PreferenceGroup, out: MutableList<PreferenceItem>) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            if (!preference.isVisible) continue
            val key = preference.key ?: "row_${out.size}_${preference.title}"
            byKey[key] = preference
            val title = preference.title?.toString().orEmpty()
            val summary = preference.summary?.toString().orEmpty()
            when {
                preference is PreferenceCategory -> {
                    out += PreferenceItem.Category(key, title)
                    collect(preference, out)
                }
                // A nested screen is a row that opens another screen, not a group to inline.
                preference is PreferenceScreen ->
                    out += PreferenceItem.Screen(key, title, summary, preference.isEnabled)

                preference is TwoStatePreference ->
                    out += PreferenceItem.Switch(key, title, summary, preference.isChecked, preference.isEnabled)

                preference is ListPreference ->
                    out += PreferenceItem.Choice(
                        key = key,
                        title = title,
                        summary = summary,
                        entries = preference.entries.orEmpty().map { it.toString() },
                        values = preference.entryValues.orEmpty().map { it.toString() },
                        selectedValue = preference.value.orEmpty(),
                        enabled = preference.isEnabled,
                    )

                preference is UniversalSeekBarPreference ->
                    out += PreferenceItem.Slider(
                        key = key,
                        title = title,
                        summary = summary,
                        value = preference.progress.toFloat(),
                        min = 0f,
                        max = preference.seekBarMax.toFloat(),
                        steps = (preference.seekBarMax - 1).coerceAtLeast(0),
                        valueLabel = preference.displayValue,
                        showValue = preference.isShowSeekBarValue,
                        enabled = preference.isEnabled,
                    )

                preference is TunableSeekBarPreference ->
                    out += PreferenceItem.Slider(
                        key = key,
                        title = title,
                        summary = summary,
                        value = preference.progress.toFloat(),
                        min = 0f,
                        max = preference.seekBarMax.toFloat(),
                        steps = (preference.seekBarMax - 1).coerceAtLeast(0),
                        valueLabel = preference.displayValue,
                        showValue = true,
                        enabled = preference.isEnabled,
                    )

                preference is PreferenceGroup -> collect(preference, out)

                // Everything else - the backup, restore, reset and tunable-key rows -
                // keeps its own dialog, so the row only has to deliver the click.
                else -> out += PreferenceItem.Action(key, title, summary, preference.isEnabled)
            }
        }
    }

    private fun onEvent(event: SettingsUiEvent) {
        when (event) {
            is SettingsUiEvent.Back -> onBack()

            is SettingsUiEvent.Toggle -> {
                val preference = byKey[event.key] as? TwoStatePreference ?: return
                if (preference.callChangeListener(event.checked)) preference.isChecked = event.checked
                refresh()
            }

            is SettingsUiEvent.Choose -> {
                val preference = byKey[event.key] as? ListPreference ?: return
                if (preference.callChangeListener(event.value)) preference.value = event.value
                refresh()
            }

            is SettingsUiEvent.Slide -> {
                when (val preference = byKey[event.key]) {
                    is UniversalSeekBarPreference -> preference.setProgressFromUi(event.value.toInt())
                    is TunableSeekBarPreference -> preference.setProgressFromUi(event.value.toInt())
                    else -> return
                }
                refresh()
            }

            is SettingsUiEvent.EditValue -> {
                when (val preference = byKey[event.key]) {
                    is UniversalSeekBarPreference -> preference.openPreciseValueDialog()
                    is TunableSeekBarPreference -> preference.openPreciseValueDialog()
                    else -> return
                }
            }

            is SettingsUiEvent.Click -> {
                val preference = byKey[event.key] ?: return
                if (preference is PreferenceScreen) onOpenScreen(preference) else preference.performClick()
                refresh()
            }
        }
    }
}
