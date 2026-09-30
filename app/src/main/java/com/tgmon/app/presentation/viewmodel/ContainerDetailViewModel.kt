package com.tgmon.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgmon.app.data.models.ContainerDetails
import com.tgmon.app.domain.repository.TgMonRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class ContainerDetailUIState(
    val details: ContainerDetails? = null,
    val isLoading: Boolean = true,
    val actionInProgress: Boolean = false,
    val error: String? = null,
    val isAdmin: Boolean = true
)

@HiltViewModel
class ContainerDetailViewModel @Inject constructor(
    private val repository: TgMonRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val containerId: String = savedStateHandle.get<String>("containerId") ?: ""
    private val _uiState = MutableStateFlow(ContainerDetailUIState())
    val uiState: StateFlow<ContainerDetailUIState> = _uiState

    private var refreshJob: Job? = null
    private var isAutoRefreshStarted = false

    init {
        _uiState.update { it.copy(isAdmin = repository.isCurrentServerAdmin()) }
        loadDetails()
    }

    fun loadDetails() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            repository.getContainerDetails(containerId).onSuccess { details ->
                _uiState.update { it.copy(details = details, isLoading = false) }
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun startAutoRefresh() {
        if (isAutoRefreshStarted) return
        isAutoRefreshStarted = true
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(30_000)
                loadDetails()
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
        isAutoRefreshStarted = false
    }

    fun restartContainer() { doAction { repository.restartContainer(containerId) } }
    fun startContainer() { doAction { repository.startContainer(containerId) } }
    fun stopContainer() { doAction { repository.stopContainer(containerId) } }
    fun pauseContainer() { doAction { repository.pauseContainer(containerId) } }
    fun unpauseContainer() { doAction { repository.unpauseContainer(containerId) } }

    private fun doAction(action: suspend () -> Result<*>) {
        _uiState.update { it.copy(actionInProgress = true) }
        viewModelScope.launch {
            action().onSuccess {
                delay(1000)
                loadDetails()
                _uiState.update { it.copy(actionInProgress = false) }
            }.onFailure { e ->
                _uiState.update { it.copy(actionInProgress = false, error = e.message) }
            }
        }
    }

    override fun onCleared() { super.onCleared(); stopAutoRefresh() }
}
