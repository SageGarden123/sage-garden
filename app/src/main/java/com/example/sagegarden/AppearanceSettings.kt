package com.example.sagegarden

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.sagegarden.ui.theme.AppPalette
import com.example.sagegarden.ui.theme.AppearanceState
import com.example.sagegarden.ui.theme.TextSize
import com.example.sagegarden.ui.theme.ThemeMode
import com.example.sagegarden.ui.theme.appColors
import com.example.sagegarden.ui.theme.paletteScheme

@Composable
fun AppearanceSettings() {
    val context = LocalContext.current

    SettingsGroup(title = stringResource(R.string.appearance_theme)) {
        Column(Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { mode ->
                RadioRow(
                    label = stringResource(mode.labelRes),
                    selected = AppearanceState.themeMode == mode,
                    onSelect = { AppearanceState.setThemeMode(context, mode) }
                )
            }
        }
    }

    SettingsGroup(title = stringResource(R.string.appearance_colours)) {
        val palettes = AppPalette.entries.filter { it != AppPalette.DYNAMIC || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
        Column(Modifier.selectableGroup()) {
            palettes.forEach { palette ->
                RadioRow(
                    label = stringResource(palette.labelRes),
                    selected = AppearanceState.palette == palette,
                    onSelect = { AppearanceState.setPalette(context, palette) },
                    trailing = { if (palette != AppPalette.DYNAMIC) PaletteSwatch(palette) }
                )
            }
        }
    }

    SettingsGroup(title = stringResource(R.string.appearance_accessibility)) {
        Text(stringResource(R.string.appearance_text_size), style = MaterialTheme.typography.labelLarge)
        Column(Modifier.selectableGroup()) {
            TextSize.entries.forEach { size ->
                RadioRow(
                    label = stringResource(size.labelRes),
                    selected = AppearanceState.textSize == size,
                    onSelect = { AppearanceState.setTextSize(context, size) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = stringResource(R.string.appearance_high_contrast),
            description = stringResource(R.string.appearance_high_contrast_desc),
            checked = AppearanceState.highContrast,
            onCheckedChange = { AppearanceState.setHighContrast(context, it) }
        )
        SwitchRow(
            label = stringResource(R.string.appearance_colour_blind),
            description = stringResource(R.string.appearance_colour_blind_desc),
            checked = AppearanceState.colourBlindSafe,
            onCheckedChange = { AppearanceState.setColourBlindSafe(context, it) }
        )
        StatusColourPreview()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.appearance_system_filters), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }) { Text(stringResource(R.string.appearance_open_android_accessibility)) }
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, description: String? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = checked, enabled = enabled, role = Role.Switch, onClick = { onCheckedChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun PaletteSwatch(palette: AppPalette) {
    val dark = MaterialTheme.colorScheme.background.run { red + green + blue } < 1.5f
    val scheme = paletteScheme(palette, dark)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(scheme.primary, scheme.primaryContainer, scheme.tertiary).forEach { c ->
            Surface(color = c, shape = MaterialTheme.shapes.small, modifier = Modifier.size(20.dp)) {}
        }
    }
}

/** Shows the three due-status colours as they'll appear, always paired with words. */
@Composable
private fun StatusColourPreview() {
    val colors = MaterialTheme.appColors
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(
            stringResource(R.string.status_overdue) to colors.statusUrgent,
            stringResource(R.string.status_due_soon) to colors.statusSoon,
            stringResource(R.string.status_on_track) to colors.statusGood,
        ).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = color, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.size(12.dp)) {}
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}
