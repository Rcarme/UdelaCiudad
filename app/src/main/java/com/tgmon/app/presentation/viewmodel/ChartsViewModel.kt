package com.tgmon.app.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgmon.app.data.database.MetricHistoryDao
import com.tgmon.app.data.models.MetricHistory
import com.tgmon.app.domain.repository.TgMonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChartsUIState(
    val metrics: List<MetricHistory> = emptyList(),
    val filteredMetrics: List<MetricHistory> = emptyList(),
    val selectedPeriod: String = "1h",
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class ChartsViewModel @Inject constructor(
    private val metricHistoryDao: MetricHistoryDao,
    private val repository: TgMonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChartsUIState())
    val uiState: StateFlow<ChartsUIState> = _uiState

    init {
        loadMetrics()
    }

    fun loadMetrics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            launch {
                try {
                    repository.syncHistoryFromServer(24)
                } catch (e: Exception) {
                    Log.w("ChartsViewModel", "History sync failed: ${e.message}")
                }
            }
            try {
                metricHistoryDao.getLastNMetrics(1000).collect { metrics ->
                    val reversed = metrics.reversed()
                    _uiState.update { state ->
                        state.copy(
                            metrics = reversed,
                            filteredMetrics = filterByPeriod(reversed, state.selectedPeriod),
                            isLoading = false,
                            error = null
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun selectPeriod(period: String) {
        _uiState.update { state ->
            state.copy(
                selectedPeriod = period,
                filteredMetrics = filterByPeriod(state.metrics, period)
            )
        }
    }

    private fun filterByPeriod(metrics: List<MetricHistory>, period: String): List<MetricHistory> {
        val cutoff = System.currentTimeMillis() - when (period) {
            "1h" -> 3600_000L
            "6h" -> 21600_000L
            else -> 86400_000L
        }
        return metrics.filter { it.timestamp >= cutoff }
    }
}
