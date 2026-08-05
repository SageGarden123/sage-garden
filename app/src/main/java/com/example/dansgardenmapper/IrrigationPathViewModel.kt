package com.example.dansgardenmapper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class IrrigationPathViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).irrigationPathDao()

    val paths: StateFlow<List<IrrigationPathEntity>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(path: IrrigationPathEntity) = viewModelScope.launch { dao.upsert(path) }
    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}