package com.tgmon.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgmon.app.domain.repository.TgMonRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class SetupUIState(
    val serverName: String = "Local Server",
    val localUrl: String = "http://10.0.2.2:8000",
    val remoteUrl: String = "http://10.0.2.2:8000",
    val token: String = "student_secure_token_123",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val repository: TgMonRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SetupUIState())
    val uiState: StateFlow<SetupUIState> = _uiState

    fun updateServerName(name: String) { _uiState.value = _uiState.value.copy(serverName = name) }
    fun updateLocalUrl(url: String) { _uiState.value = _uiState.value.copy(localUrl = url) }
    fun updateRemoteUrl(url: String) { _uiState.value = _uiState.value.copy(remoteUrl = url) }
    fun updateToken(token: String) { _uiState.value = _uiState.value.copy(token = token) }
    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    fun validateAndSave() {
        if (_uiState.value.isLoading) return

        val currentState = _uiState.value
        
        if (currentState.localUrl.isBlank() && currentState.remoteUrl.isBlank()) {
            _uiState.value = currentState.copy(error = "Ingresa al menos una URL")
            return
        }
        
        if (currentState.token.isBlank()) {
            _uiState.value = currentState.copy(error = "Ingresa el token")
            return
        }

        _uiState.value = currentState.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = repository.validateAndSaveConfig(
                currentState.serverName,
                currentState.localUrl,
                currentState.remoteUrl,
                currentState.token
            )

            result.onSuccess { 
                _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true, error = null)
            }.onFailure { exception ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = exception.message ?: "Error desconocido")
            }
        }
    }
}
