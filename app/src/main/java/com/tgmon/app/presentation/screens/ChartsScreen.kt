package com.tgmon.app.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import com.tgmon.app.presentation.components.LineChart
import com.tgmon.app.presentation.components.ProgressChart
import com.tgmon.app.presentation.viewmodel.ChartsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(
    onBackClick: () -> Unit = {},
    viewModel: ChartsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.error != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Error") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Failed to load metrics from database.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Metric History") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Period selector
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("1h", "6h", "24h").forEach { period ->
                        val isSelected = uiState.selectedPeriod == period
                        val contentDescriptionText = when (period) {
                            "1h" -> "1 hour"
                            "6h" -> "6 hours"
                            "24h" -> "24 hours"
                            else -> period
                        }
                        if (isSelected) {
                            Button(
                                onClick = { viewModel.selectPeriod(period) },
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = contentDescriptionText }
                            ) {
                                Text(period)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.selectPeriod(period) },
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = contentDescriptionText }
                            ) {
                                Text(period)
                            }
                        }
                    }
                }
            }

            // Period info
            item {
                Text(
                    text = if (uiState.filteredMetrics.isNotEmpty()) {
                        "Data points: ${uiState.filteredMetrics.size}"
                    } else {
                        "No data for this period - Data is collected every 15 minutes"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Loading indicator
            if (uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF6C63FF)
                        )
                    }
                }
            }

            // Empty state message
            if (!uiState.isLoading && uiState.filteredMetrics.isEmpty()) {
                item {
                    Text(
                        text = "No metric data available. Data is collected every 15 minutes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // CPU Chart
            if (uiState.filteredMetrics.isNotEmpty()) {
                item {
                    LineChart(
                        title = "CPU (%)",
                        metrics = uiState.filteredMetrics,
                        valueExtractor = { it.cpuPercent },
                        unit = "%",
                        color = Color(0xFF4CAF50),
                        maxValue = 100.0
                    )
                }
            }

            // Memory Chart
            if (uiState.filteredMetrics.isNotEmpty()) {
                item {
                    LineChart(
                        title = "Memory (%)",
                        metrics = uiState.filteredMetrics,
                        valueExtractor = { it.memoryPercent },
                        unit = "%",
                        color = Color(0xFF2196F3),
                        maxValue = 100.0
                    )
                }
            }

            // Disk Usage Chart
            if (uiState.filteredMetrics.isNotEmpty()) {
                item {
                    LineChart(
                        title = "Disk Used (GB)",
                        metrics = uiState.filteredMetrics,
                        valueExtractor = { it.diskUsedGb },
                        unit = " GB",
                        color = Color(0xFFFF9800)
                    )
                }
            }

            // Disk Percent Chart
            if (uiState.filteredMetrics.isNotEmpty()) {
                item {
                    LineChart(
                        title = "Disk (%)",
                        metrics = uiState.filteredMetrics,
                        valueExtractor = { it.diskPercent ?: 0.0 },
                        unit = "%",
                        color = Color(0xFFFF5722),
                        maxValue = 100.0
                    )
                }
            }

            // Current values
            if (uiState.filteredMetrics.isNotEmpty()) {
                val latest = uiState.filteredMetrics.lastOrNull()
                if (latest != null) {
                    item {
                        Text(
                            text = "Current Values",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    item {
                        ProgressChart(
                            title = "CPU",
                            value = latest.cpuPercent,
                            unit = "%",
                            color = Color(0xFF4CAF50)
                        )
                    }

                    item {
                        ProgressChart(
                            title = "Memory",
                            value = latest.memoryPercent,
                            unit = "%",
                            color = Color(0xFF2196F3)
                        )
                    }

                    item {
                        ProgressChart(
                            title = "Disk",
                            value = latest.diskPercent ?: 0.0,
                            unit = "%",
                            color = Color(0xFFFF9800)
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
