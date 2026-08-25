package com.example.sagegarden

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SageChatSheet(onDismiss: () -> Unit, onOpenHelp: () -> Unit) {
    val context = LocalContext.current
    val viewModel: SageChatViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val messages by viewModel.messages.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val lastResult by viewModel.lastResult.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // Re-read on every recomposition (cheap SharedPreferences read) so it reflects the
    // write-back EntitlementManager.updateSagePromptsRemaining does after each send().
    val entitlement = EntitlementManager.getCached(context)
    val freeLimitReached = lastResult is SageChatResult.FreeLimitReached ||
        (!entitlement.isPro && entitlement.sagePromptsUsed >= entitlement.sagePromptLimit)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Sage 🌿", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821), modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.clearHistory() }, enabled = messages.isNotEmpty()) {
                    Text("Clear chat", fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) { Text("✕ Minimise", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    entitlement.isPro -> "Unlimited"
                    else -> "${(entitlement.sagePromptLimit - entitlement.sagePromptsUsed).coerceAtLeast(0)} of ${entitlement.sagePromptLimit} free questions left"
                },
                fontSize = 11.sp, color = Color.Gray
            )
            Spacer(Modifier.height(4.dp))
            Text("Ask about plant care or how to use this app.", fontSize = 11.sp, color = Color.Gray)
            Spacer(Modifier.height(10.dp))

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Ask me things like \"how often should I water a tomato plant?\" or \"how do I set up watering reminders?\"",
                            fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
                items(messages, key = { it.id }) { message -> SageMessageBubble(message) }
                if (sending) {
                    item { Text("Sage is thinking…", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp)) }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (freeLimitReached) {
                Card(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("You've used all your free Sage questions.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Enter a promo code in Help → Basic/Advanced mode for unlimited access.", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenHelp, modifier = Modifier.fillMaxWidth()) { Text("Open Help") }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Ask Sage…") },
                        modifier = Modifier.weight(1f),
                        enabled = !sending,
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.send(input); input = "" },
                        enabled = !sending && input.isNotBlank()
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun SageMessageBubble(message: SageChatMessageEntity) {
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (isUser) Color(0xFF3A5A40) else Color(0xFFEFEFEF)),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                message.text,
                modifier = Modifier.padding(10.dp),
                fontSize = 13.sp,
                color = if (isUser) Color.White else Color.Black
            )
        }
    }
}
