package com.particlesdevs.photoncamera.composeui.state

import androidx.compose.runtime.Immutable

/**
 * One row of the settings screen.
 *
 * The rows are read off the app's existing androidx Preference tree, so res/xml/preferences.xml
 * and everything generated into it at runtime stay the single source of truth; only the
 * drawing moved here.
 */
@Immutable
sealed interface PreferenceItem {
    val key: String
    val title: String
    val enabled: Boolean

    @Immutable
    data class Category(
        override val key: String,
        override val title: String,
        override val enabled: Boolean = true,
    ) : PreferenceItem

    @Immutable
    data class Switch(
        override val key: String,
        override val title: String,
        val summary: String,
        val checked: Boolean,
        override val enabled: Boolean = true,
    ) : PreferenceItem

    @Immutable
    data class Choice(
        override val key: String,
        override val title: String,
        val summary: String,
        val entries: List<String>,
        val values: List<String>,
        val selectedValue: String,
        override val enabled: Boolean = true,
    ) : PreferenceItem {
        val selectedEntry: String
            get() = values.indexOf(selectedValue).takeIf { it >= 0 }?.let { entries[it] } ?: summary
    }

    @Immutable
    data class Slider(
        override val key: String,
        override val title: String,
        val summary: String,
        val value: Float,
        val min: Float,
        val max: Float,
        val steps: Int,
        val valueLabel: String,
        val showValue: Boolean,
        override val enabled: Boolean = true,
    ) : PreferenceItem

    /** A row that just does something: dialogs, links, backup and restore. */
    @Immutable
    data class Action(
        override val key: String,
        override val title: String,
        val summary: String,
        override val enabled: Boolean = true,
    ) : PreferenceItem

    /** A nested PreferenceScreen. */
    @Immutable
    data class Screen(
        override val key: String,
        override val title: String,
        val summary: String,
        override val enabled: Boolean = true,
    ) : PreferenceItem
}

@Immutable
data class SettingsUiState(
    val title: String = "Settings",
    val items: List<PreferenceItem> = emptyList(),
    val canGoBack: Boolean = true,
)

sealed interface SettingsUiEvent {
    object Back : SettingsUiEvent
    data class Toggle(val key: String, val checked: Boolean) : SettingsUiEvent
    data class Choose(val key: String, val value: String) : SettingsUiEvent
    data class Slide(val key: String, val value: Float) : SettingsUiEvent
    /** A precise-value tap on a slider's label, which opens the app's own dialog. */
    data class EditValue(val key: String) : SettingsUiEvent
    data class Click(val key: String) : SettingsUiEvent
}
