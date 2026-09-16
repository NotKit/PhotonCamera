package com.particlesdevs.photoncamera.composeui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.particlesdevs.photoncamera.composeui.state.PreferenceItem
import com.particlesdevs.photoncamera.composeui.state.SettingsUiEvent
import com.particlesdevs.photoncamera.composeui.state.SettingsUiState

/** activity_settings.xml plus the preference list, as one composable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.title, fontSize = 18.sp) },
                navigationIcon = {
                    if (state.canGoBack) {
                        IconButton(onClick = { onEvent(SettingsUiEvent.Back) }) {
                            Text("←", fontSize = 22.sp)
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(state.items, key = { it.key }) { item ->
                when (item) {
                    is PreferenceItem.Category -> CategoryRow(item)
                    is PreferenceItem.Switch -> SwitchRow(item, onEvent)
                    is PreferenceItem.Choice -> ChoiceRow(item, onEvent)
                    is PreferenceItem.Slider -> SliderRow(item, onEvent)
                    is PreferenceItem.Action -> ClickRow(item.title, item.summary, item.enabled) {
                        onEvent(SettingsUiEvent.Click(item.key))
                    }
                    is PreferenceItem.Screen -> ClickRow(item.title, item.summary, item.enabled) {
                        onEvent(SettingsUiEvent.Click(item.key))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(item: PreferenceItem.Category) {
    Text(
        item.title,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(item: PreferenceItem.Switch, onEvent: (SettingsUiEvent) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitleAndSummary(item.title, item.summary, item.enabled, Modifier.weight(1f))
        Switch(
            checked = item.checked,
            enabled = item.enabled,
            onCheckedChange = { onEvent(SettingsUiEvent.Toggle(item.key, it)) },
        )
    }
}

@Composable
private fun ChoiceRow(item: PreferenceItem.Choice, onEvent: (SettingsUiEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ClickRow(item.title, item.selectedEntry, item.enabled) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            item.entries.forEachIndexed { index, entry ->
                DropdownMenuItem(
                    text = { Text(entry) },
                    onClick = {
                        expanded = false
                        onEvent(SettingsUiEvent.Choose(item.key, item.values[index]))
                    },
                )
            }
        }
    }
}

@Composable
private fun SliderRow(item: PreferenceItem.Slider, onEvent: (SettingsUiEvent) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TitleAndSummary(item.title, item.summary, item.enabled, Modifier.weight(1f))
            if (item.showValue) {
                // Tapping the value opens the preference's own precise-input dialog.
                TextButton(onClick = { onEvent(SettingsUiEvent.EditValue(item.key)) }) {
                    Text(item.valueLabel)
                }
            }
        }
        Slider(
            value = item.value,
            onValueChange = { onEvent(SettingsUiEvent.Slide(item.key, it)) },
            valueRange = item.min..item.max,
            steps = item.steps,
            enabled = item.enabled,
        )
    }
}

@Composable
private fun ClickRow(title: String, summary: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitleAndSummary(title, summary, enabled, Modifier.weight(1f))
    }
}

@Composable
private fun TitleAndSummary(title: String, summary: String, enabled: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(
            title,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
        )
        if (summary.isNotBlank()) {
            Text(
                summary,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.3f),
            )
        }
    }
}
