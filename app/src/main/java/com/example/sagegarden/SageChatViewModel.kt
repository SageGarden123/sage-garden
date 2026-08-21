package com.example.sagegarden

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class SageChatViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).sageChatDao()

    val messages: StateFlow<List<SageChatMessageEntity>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _lastResult = MutableStateFlow<SageChatResult?>(null)
    val lastResult: StateFlow<SageChatResult?> = _lastResult.asStateFlow()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _sending.value) return
        viewModelScope.launch {
            _sending.value = true
            dao.insert(SageChatMessageEntity(id = UUID.randomUUID().toString(), role = "user", text = trimmed, timestamp = System.currentTimeMillis()))

            val result = SageClient.chat(getApplication(), trimmed)
            _lastResult.value = result
            when (result) {
                is SageChatResult.Success -> {
                    dao.insert(SageChatMessageEntity(id = UUID.randomUUID().toString(), role = "assistant", text = result.reply, timestamp = System.currentTimeMillis()))
                    EntitlementManager.updateSagePromptsRemaining(getApplication(), result.promptsRemaining)
                }
                is SageChatResult.FreeLimitReached -> {
                    EntitlementManager.updateSagePromptsRemaining(getApplication(), 0)
                    // No assistant message inserted — the sheet shows a dedicated upgrade prompt instead (see lastResult).
                }
                else -> {
                    dao.insert(
                        SageChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            role = "assistant",
                            text = "Sorry, I couldn't reach Sage just now — check your connection and try again.",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
            _sending.value = false
        }
    }

    fun clearHistory() {
        viewModelScope.launch { dao.clearAll() }
    }
}
