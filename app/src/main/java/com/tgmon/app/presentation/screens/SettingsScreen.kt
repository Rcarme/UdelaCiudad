package com.tgmon.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgmon.app.presentation.viewmodel.SettingsViewModel
import com.tgmon.app.ui.theme.TgMonBackgroundBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val cpuThreshold by viewModel.cpuThreshold.collectAsState()
    val memoryThreshold by viewModel.memoryThreshold.collectAsState()
    val diskThreshold by viewModel.diskThreshold.collectAsState()
    val refreshResult by viewModel.refreshResult.collectAsState()
    var showSnackbar by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(showSnackbar) {
        if (showSnackbar) {
            snackbarHostState.showSnackbar("Changes saved")
            showSnackbar = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = TgMonBackgroundBrush)
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "Alert Thresholds",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ThresholdSlider(
                        label = "CPU",
                        value = cpuThreshold,
                        onValueChange = viewModel::updateCpuThreshold,
                        displayValue = "${cpuThreshold.toInt()}%"
                    )

                    ThresholdSlider(
                        label = "Memory",
                        value = memoryThreshold,
                        onValueChange = viewModel::updateMemoryThreshold,
                        displayValue = "${memoryThreshold.toInt()}%"
                    )

                    ThresholdSlider(
                        label = "Disk",
                        value = diskThreshold,
                        onValueChange = viewModel::updateDiskThreshold,
                        displayValue = "${diskThreshold.toInt()}%"
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        viewModel.refreshServiceUrls()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FF88)
                    )
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh Service URLs", color = Color.Black)
                }

                LaunchedEffect(refreshResult) {
                    refreshResult?.let { result ->
                        val message = if (result.isSuccess) {
                            "Found ${result.getOrNull()} services"
                        } else {
                            "No services found"
                        }
                        snackbarHostState.showSnackbar(message)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        viewModel.saveThresholds()
                        showSnackbar = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6C63FF)
                    )
                ) {
                    Text("Save", color = Color.White)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun ThresholdSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    displayValue: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = displayValue,
                    color = Color(0xFF6C63FF),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 50f..100f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF6C63FF),
                    activeTrackColor = Color(0xFF6C63FF),
                    inactiveTrackColor = Color(0x33FFFFFF)
                )
            )
        }
    }
}
