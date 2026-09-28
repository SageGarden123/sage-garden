package com.example.sagegarden

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The limits quoted in answers come from code, so they can't drift from the real values. */
@Composable
fun faqAnswerText(faq: Faq): String = when (faq) {
    Faq.AI_IDENTIFY -> stringResource(faq.answer, PLANTNET_TRIAL_DAILY_LIMIT)
    Faq.SAGE_LIMITS -> stringResource(faq.answer, EntitlementManager.FREE_SAGE_PROMPT_LIMIT, PLANTNET_TRIAL_DAILY_LIMIT)
    else -> stringResource(faq.answer)
}

/** Entries for features that are currently hidden (e.g. Advanced-only ones in Basic mode) are left out. */
fun visibleFaqs(context: android.content.Context): List<Faq> =
    Faq.entries.filter { it.feature == null || FeatureVisibility.shouldShow(context, it.feature) }

/**
 * Which single FAQ answer is showing in the pop-up sheet, if any. Any screen opens one via
 * [FaqInfoButton]; the sheet itself is hosted once, in GardenMapperApp, so it can navigate to the
 * full FAQ or a Settings page.
 */
object FaqSheetState {
    var entry by mutableStateOf<Faq?>(null)
}

/** The small ⓘ that replaces a paragraph of in-app guidance — opens that topic's FAQ answer in a pop-up. */
@Composable
fun FaqInfoButton(faq: Faq, modifier: Modifier = Modifier) {
    val question = stringResource(faq.question)
    IconButton(onClick = { FaqSheetState.entry = faq }, modifier = modifier) {
        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.faq_help_about, question), tint = MaterialTheme.colorScheme.primary)
    }
}

/** A section or setting title with its ⓘ alongside. */
@Composable
fun TitleWithHelp(title: String, faq: Faq, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
        FaqInfoButton(faq)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaqSheet(faq: Faq, onDismiss: () -> Unit, onOpenAll: (Faq) -> Unit, onOpenSettings: (SettingsPage) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(faq.question), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            Text(faqAnswerText(faq), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                faq.page?.let { page ->
                    Button(onClick = { onOpenSettings(page) }) { Text(stringResource(R.string.faq_open_setting)) }
                }
                OutlinedButton(onClick = { onOpenAll(faq) }) { Text(stringResource(R.string.faq_see_all)) }
            }
        }
    }
}

@Composable
fun FaqScreen(initialEntry: Faq?, onBack: () -> Unit, onOpenSettings: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOfNotNull(initialEntry)) }
    val all = remember(AdvancedModeState.enabled, SageEnabledState.enabled) { visibleFaqs(context) }

    // Resolve text once for searching (question and answer both count).
    val texts = all.associateWith { stringResource(it.question) + " " + faqAnswerText(it) }
    val matching = if (query.isBlank()) all else all.filter { texts.getValue(it).contains(query.trim(), ignoreCase = true) }

    // Flatten topics + entries into one list so a deep link can scroll straight to its entry.
    val rows: List<Any> = FaqTopic.entries.flatMap { topic ->
        val inTopic = matching.filter { it.topic == topic }
        if (inTopic.isEmpty()) emptyList() else listOf<Any>(topic) + inTopic
    }
    val listState = rememberLazyListState()
    LaunchedEffect(initialEntry) {
        val index = rows.indexOfFirst { it == initialEntry }
        if (index > 0) listState.scrollToItem(index - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(stringResource(R.string.faq_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.faq_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (rows.isEmpty()) {
            Text(stringResource(R.string.faq_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            items(rows, key = { if (it is Faq) "q_${it.name}" else "t_${(it as FaqTopic).name}" }) { row ->
                if (row is FaqTopic) {
                    Text(
                        stringResource(row.title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() }
                    )
                } else {
                    val faq = row as Faq
                    val isOpen = faq in expanded || query.isNotBlank()
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize()
                    ) {
                        Column(Modifier.clickable { expanded = if (faq in expanded) expanded - faq else expanded + faq }.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(faq.question), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Icon(
                                    if (isOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = stringResource(if (isOpen) R.string.action_collapse else R.string.action_expand)
                                )
                            }
                            if (isOpen) {
                                Spacer(Modifier.height(8.dp))
                                Text(faqAnswerText(faq), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                faq.page?.let { page ->
                                    TextButton(onClick = { onOpenSettings(page) }, contentPadding = PaddingValues(0.dp)) {
                                        Text(stringResource(R.string.faq_open_setting))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
