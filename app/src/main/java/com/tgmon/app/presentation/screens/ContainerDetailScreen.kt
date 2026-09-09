package com.tgmon.app.presentation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgmon.app.data.models.ContainerDetails
import com.tgmon.app.presentation.components.MetricCard
import com.tgmon.app.presentation.components.MeshBackground
import com.tgmon.app.presentation.viewmodel.ContainerDetailViewModel

private val ContainerDetails.displayName: String
    get() = name.removePrefix("/")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerDetailScreen(
    viewModel: ContainerDetailViewModel,
    onBack: () -> Unit,
    onOpenHomepage: (Int, String, String) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startAutoRefresh()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopAutoRefresh() }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Text(
                        text = uiState.details?.displayName ?: "Container Details",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { viewModel.loadDetails() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }
            )
        }
    ) { paddingValues ->
        MeshBackground {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when {
                    uiState.isLoading && uiState.details == null -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color(0xFF6C63FF)
                        )
                    }
                    uiState.error != null && uiState.details == null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = uiState.error ?: "Error",
                                color = Color(0xFFFF4444),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.loadDetails() }) {
                                Text("Retry")
                            }
                        }
                    }
                    uiState.details != null -> {
                        uiState.details?.let { details ->
                            ContainerDetailContent(
                                details = details,
                                actionInProgress = uiState.actionInProgress,
                                onStart = { viewModel.startContainer() },
                                onStop = { viewModel.stopContainer() },
                                onRestart = { viewModel.restartContainer() },
                                onPause = { viewModel.pauseContainer() },
                                onUnpause = { viewModel.unpauseContainer() },
                                onOpenHomepage = onOpenHomepage
                            )
                        }
                    }
                }

                if (!uiState.isLoading && uiState.error == null && uiState.details == null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No container data available", color = Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadDetails() }) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerDetailContent(
    details: ContainerDetails,
    actionInProgress: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onPause: () -> Unit,
    onUnpause: () -> Unit,
    onOpenHomepage: (Int, String, String) -> Unit = { _, _, _ -> }
) {
    val isRunning = details.status == "running"
    val isPaused = details.status == "paused"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip(
                    status = details.status,
                    modifier = Modifier.weight(1f)
                )
                if (details.healthStatus != "none") {
                    HealthChip(
                        healthStatus = details.healthStatus,
                        modifier = Modifier.weight(1f)
                    )
                }
                val firstPort = details.ports?.firstOrNull()
                if (firstPort != null) {
                    IconButton(
                        // pass the mapping 'type' (http/https) as protocol to construct the URL scheme
                        onClick = { onOpenHomepage(firstPort.hostPort, firstPort.type, firstPort.protocol) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = "Open Homepage",
                            tint = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    title = "CPU",
                    value = details.cpuPercent?.let { String.format("%.1f", it) } ?: "N/A",
                    unit = "%",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "RAM",
                    value = details.memoryPercent?.let { String.format("%.1f", it) } ?: "N/A",
                    unit = "%",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Uptime",
                    value = details.uptimeHms.ifEmpty { "--:--" },
                    unit = "",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Network I/O",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Received: ${details.networkRxMb?.let { String.format("%.2f", it) } ?: "N/A"} MB",
                            color = Color(0xFF00FF88),
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Sent: ${details.networkTxMb?.let { String.format("%.2f", it) } ?: "N/A"} MB",
                            color = Color(0xFF6C63FF),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        if (!details.ports.isNullOrEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                    border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Port Mappings",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        details.ports?.forEach { port ->
                            Text(
                                text = "Port ${port.hostPort} maps to ${port.containerPort} ${port.protocol}",
                                color = Color(0xFFAAAAAA),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .padding(vertical = 2.dp)
                                    .semantics { contentDescription = "Port ${port.hostPort} maps to container port ${port.containerPort} over ${port.protocol}" }
                            )
                        }
                    }
                }
            }
        }

        if (details.networks.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                    border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Networks",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        details.networks.forEach { network ->
                            Text(
                                text = "• $network",
                                color = Color(0xFFAAAAAA),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        if (details.volumes.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                    border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Volumes",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        details.volumes.forEach { volume ->
                            Text(
                                text = "• $volume",
                                color = Color(0xFFAAAAAA),
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("Image", details.image)
                    InfoRow("Restart Count", "${details.restartCount} times")
                    if (details.created.isNotEmpty()) {
                        InfoRow("Created", details.created)
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Memory: ${details.memoryMb?.let { String.format("%.1f", it) } ?: "N/A"} MB",
                        color = Color(0xFFAAAAAA),
                        fontSize = 13.sp
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(
                    icon = Icons.Filled.PlayArrow,
                    label = "Start",
                    enabled = !actionInProgress && !isRunning,
                    onClick = onStart,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    icon = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    label = if (isPaused) "Resume" else "Pause",
                    enabled = !actionInProgress && (isRunning || isPaused),
                    onClick = if (isPaused) onUnpause else onPause,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(
                    icon = Icons.Filled.Refresh,
                    label = "Restart",
                    enabled = !actionInProgress && isRunning,
                    onClick = onRestart,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    icon = Icons.Filled.Stop,
                    label = "Stop",
                    enabled = !actionInProgress && isRunning,
                    onClick = onStop,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
private fun StatusChip(status: String, modifier: Modifier = Modifier) {
    val color = when (status) {
        "running" -> Color(0xFF4CAF50)
        "paused" -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    val text = when (status) {
        "running" -> "Running"
        "paused" -> "Paused"
        "restarting" -> "Restarting"
        else -> "Stopped"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, color)
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun HealthChip(healthStatus: String, modifier: Modifier = Modifier) {
    val color = when (healthStatus) {
        "healthy" -> Color(0xFF4CAF50)
        "unhealthy" -> Color(0xFFF44336)
        else -> Color(0xFFFF9800)
    }
    val text = when (healthStatus) {
        "healthy" -> "Healthy"
        "unhealthy" -> "Unhealthy"
        else -> "Starting"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, color)
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color(0xFFAAAAAA),
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Color.White,
            disabledContentColor = Color.Gray
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 11.sp)
    }
}