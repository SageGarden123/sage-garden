package com.example.sagegarden

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Yard
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sagegarden.ui.theme.AppearanceState
import com.example.sagegarden.ui.theme.TextSize

/**
 * First run only. Asks the two things that most change how the app feels: how much of it to show
 * (Basic or Advanced) and how big the text should be. Everything else is discoverable later, and
 * both answers can be changed any time in Settings.
 */
object Onboarding {
    private const val KEY = "onboarding_complete"
    private fun prefs(context: Context) = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)

    fun isComplete(context: Context) = prefs(context).getBoolean(KEY, false)
    fun markComplete(context: Context) = prefs(context).edit().putBoolean(KEY, true).apply()

    /** Existing users (anything already set up before onboarding existed) skip it. */
    suspend fun isNeeded(context: Context): Boolean {
        if (isComplete(context)) return false
        val alreadySetUp = AppDatabase.getInstance(context).plantDao().getAllOnce().isNotEmpty() ||
            prefs(context).contains("ui_mode_advanced") || prefs(context).contains("default_landing_tab")
        if (alreadySetUp) markComplete(context)
        return !alreadySetUp
    }
}

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var advanced by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        when (step) {
            0 -> {
                Icon(Icons.Outlined.Yard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(40.dp))
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_get_started)) }
            }
            1 -> {
                Text(stringResource(R.string.onboarding_mode_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(24.dp))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeCard(Icons.Outlined.Checklist, stringResource(R.string.onboarding_mode_basic), stringResource(R.string.onboarding_mode_basic_desc), selected = !advanced) { advanced = false }
                    ModeCard(Icons.Outlined.Tune, stringResource(R.string.onboarding_mode_advanced), stringResource(R.string.onboarding_mode_advanced_desc), selected = advanced) { advanced = true }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.onboarding_change_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Button(onClick = { FeatureVisibility.setAdvancedModeEnabled(context, advanced); step = 2 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_next))
                }
            }
            else -> {
                Text(stringResource(R.string.onboarding_text_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.onboarding_text_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Column(Modifier.selectableGroup().fillMaxWidth()) {
                    TextSize.entries.forEach { size ->
                        RadioRow(stringResource(size.labelRes), AppearanceState.textSize == size, { AppearanceState.setTextSize(context, size) })
                    }
                }
                Spacer(Modifier.height(32.dp))
                Button(onClick = { Onboarding.markComplete(context); onFinished() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_finish))
                }
            }
        }
    }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    OutlinedCard(
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
