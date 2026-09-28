package com.example.sagegarden

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Settings is split by what a setting applies to: pages about THIS garden (the one on screen — each
 * garden keeps its own values) come first, then pages that apply to the whole app on this phone.
 * Making that split visible is the point: per-garden settings used to sit mixed in with device-wide
 * ones in one long Help screen, so it was never clear which changed when you switched gardens.
 */
enum class SettingsPage(
    val key: String,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val icon: ImageVector,
    val perGarden: Boolean,
) {
    GARDEN("garden", R.string.settings_garden, R.string.settings_garden_summary, Icons.Outlined.Yard, perGarden = true),
    REMINDERS("reminders", R.string.settings_reminders, R.string.settings_reminders_summary, Icons.Outlined.Notifications, perGarden = true),
    IRRIGATION("irrigation", R.string.settings_irrigation, R.string.settings_irrigation_summary, Icons.Outlined.WaterDrop, perGarden = true),
    APPEARANCE("appearance", R.string.settings_appearance, R.string.settings_appearance_summary, Icons.Outlined.Palette, perGarden = false),
    PHOTOS("photos", R.string.settings_photos, R.string.settings_photos_summary, Icons.Outlined.PhotoLibrary, perGarden = false),
    DATA("data", R.string.settings_data, R.string.settings_data_summary, Icons.Outlined.Backup, perGarden = false),
    APP("app", R.string.settings_app, R.string.settings_app_summary, Icons.Outlined.Tune, perGarden = false),
    ABOUT("about", R.string.settings_about, R.string.settings_about_summary, Icons.AutoMirrored.Outlined.HelpOutline, perGarden = false);

    companion object {
        fun fromKey(key: String?): SettingsPage? = entries.firstOrNull { it.key == key }
    }
}

/** Which pages exist right now — irrigation only for a garden you own with the feature shown, backup only with edit access. */
fun visibleSettingsPages(context: android.content.Context): List<SettingsPage> = SettingsPage.entries.filter { page ->
    when (page) {
        SettingsPage.IRRIGATION -> isOwnerOfActiveGarden(context) && FeatureVisibility.shouldShow(context, Feature.TUYA_INTEGRATION)
        SettingsPage.DATA -> hasWriteAccessToActiveGarden(context)
        else -> true
    }
}

@Composable
fun SettingsHome(onOpenPage: (SettingsPage) -> Unit, onOpenFaq: () -> Unit) {
    val context = LocalContext.current
    val pages = remember(ActiveGardenState.activeGardenId, AdvancedModeState.enabled) { visibleSettingsPages(context) }
    val gardenName = remember(ActiveGardenState.activeGardenId) { activeGardenName(context) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_section_garden, gardenName))
        pages.filter { it.perGarden }.forEach { SettingsPageRow(it) { onOpenPage(it) } }
        Spacer(Modifier.height(8.dp))
        SettingsSectionLabel(stringResource(R.string.settings_section_app))
        pages.filterNot { it.perGarden }.forEach { SettingsPageRow(it) { onOpenPage(it) } }
        ListItem(
            headlineContent = { Text(stringResource(R.string.faq_title)) },
            leadingContent = { Icon(Icons.Outlined.QuestionAnswer, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onOpenFaq)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }
    )
}

@Composable
private fun SettingsPageRow(page: SettingsPage, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(page.title)) },
        supportingContent = { Text(stringResource(page.summary)) },
        leadingContent = { Icon(page.icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** Title bar for a settings sub-page or any other pushed screen. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        actions()
    }
}

/**
 * One titled group of related settings on a settings page. Replaces the old collapsible sections:
 * each page now holds only a handful of related groups, so hiding them behind taps just added work.
 * [faq] adds an ⓘ that opens the matching FAQ answer — instead of a paragraph of guidance text.
 * [initiallyExpanded] is accepted (and ignored) so former ExpandableSection call sites keep compiling.
 */
@Composable
fun SettingsGroup(
    title: String,
    faq: Faq? = null,
    @Suppress("UNUSED_PARAMETER") initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                if (faq != null) FaqInfoButton(faq)
            }
            Column(Modifier.padding(end = 8.dp)) { content() }
        }
    }
}

/** The name shown for the garden on screen (your own default garden is "My Garden" until renamed). */
fun activeGardenName(context: android.content.Context): String {
    val id = effectiveGardenId(context)
    return knownGardensIncludingOwn(context).firstOrNull { it.gardenId == id }?.name ?: "My Garden"
}
