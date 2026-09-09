package com.tgmon.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgmon.app.data.preferences.PreferencesManager
import com.tgmon.app.data.preferences.ServiceUrlManager
import com.tgmon.app.domain.repository.TgMonRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val repository: TgMonRepository,
    private val serviceUrlManager: ServiceUrlManager
) : ViewModel() {
    
    private val _cpuThreshold = MutableStateFlow(preferencesManager.getCpuThreshold())
    val cpuThreshold: StateFlow<Float> = _cpuThreshold
    
    private val _memoryThreshold = MutableStateFlow(preferencesManager.getMemoryThreshold())
    val memoryThreshold: StateFlow<Float> = _memoryThreshold
    
    private val _diskThreshold = MutableStateFlow(preferencesManager.getDiskThreshold())
    val diskThreshold: StateFlow<Float> = _diskThreshold
    
    private val _refreshResult = MutableStateFlow<Result<Int>?>(null)
    val refreshResult: StateFlow<Result<Int>?> = _refreshResult
    
    fun updateCpuThreshold(v: Float) { _cpuThreshold.value = v }
    fun updateMemoryThreshold(v: Float) { _memoryThreshold.value = v }
    fun updateDiskThreshold(v: Float) { _diskThreshold.value = v }
    
    fun saveThresholds() {
        preferencesManager.saveCpuThreshold(_cpuThreshold.value)
        preferencesManager.saveMemoryThreshold(_memoryThreshold.value)
        preferencesManager.saveDiskThreshold(_diskThreshold.value)
    }

    fun refreshServiceUrls() {
        viewModelScope.launch {
            val result = serviceUrlManager.fetchFromServer(repository)
            _refreshResult.value = result
        }
    }
}
