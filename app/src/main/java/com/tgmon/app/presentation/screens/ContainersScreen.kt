package com.tgmon.app.presentation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.tgmon.app.data.models.DockerContainer
import com.tgmon.app.presentation.components.MetricCard
import com.tgmon.app.presentation.components.MeshBackground
import com.tgmon.app.presentation.viewmodel.ContainerViewModel
import com.tgmon.app.data.preferences.PreferencesManager
import android.content.Intent
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainersScreen(
    viewModel: ContainerViewModel,
    preferencesManager: PreferencesManager,
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit = {},
    onContainerClick: (String) -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsState()
    val aiRecommendationsState by viewModel.aiRecommendationsState.collectAsState()
    // N97: collectAsState hoisted out of conditional — collecting inside let/if causes
    // recomposition instability when selectedLogContainerId toggles. Keeping at top ensures
    // stable lifecycle and avoids extra recompositions on low-CPU device.
    // Note: stable LazyColumn keys (key = { it.id }) also reduce re-measure cost.
    val isAutoRefreshing by viewModel.isAutoRefreshingLogs.collectAsState()
    val isAdmin = preferencesManager.isCurrentServerAdmin()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var restartConfirmation by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    var lastErrorShown by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.value.error, uiState.value.lastUpdateTime) {
        val currentError = uiState.value.error
        if (currentError == null) {
            lastErrorShown = null
            return@LaunchedEffect
        }
        if (currentError != lastErrorShown) {
            lastErrorShown = currentError
            snackbarHostState.showSnackbar(currentError)
            viewModel.clearError()
        }
    }

    restartConfirmation?.let { containerId ->
        val container = uiState.value.containers.find { it.id == containerId }
        AlertDialog(
            onDismissRequest = { restartConfirmation = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = "Warning") },
            title = { Text("Restart Container") },
            text = { Text("Are you sure you want to restart '${container?.name?.removePrefix("/") ?: containerId}'? This will cause a brief service interruption.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restartContainer(containerId)
                    restartConfirmation = null
                    scope.launch {
                        snackbarHostState.showSnackbar("Container restarted successfully")
                    }
                }) {
                    Text("Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = { restartConfirmation = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val pullRefreshState = rememberPullToRefreshState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = { Text("Containers", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { viewModel.loadContainers() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        MeshBackground {
            PullToRefreshBox(
                isRefreshing = uiState.value.isLoading,
                onRefresh = {
                    viewModel.loadContainers()
                    viewModel.loadAiRecommendations()
                },
                state = pullRefreshState,
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
            if (uiState.value.isLoading && uiState.value.containers.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Loading containers...",
                        color = Color(0xFFAAAAAA)
                    )
                }
            } else if (uiState.value.containers.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Recomendaciones de inteligencia artificial
                    item {
                        AIRecommendationsCard(
                            state = aiRecommendationsState,
                            isAdmin = isAdmin,
                            containers = uiState.value.containers,
                            onRefresh = { viewModel.loadAiRecommendations() },
                            onPause = { id -> viewModel.pauseContainer(id) }
                        )
                    }

                    if (!isAdmin) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0x1A2196F3)),
                                border = BorderStroke(1.dp, Color(0x332196F3)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Visibility,
                                        contentDescription = "Viewer Mode",
                                        tint = Color(0xFF64B5F6),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Modo Observador (Solo Lectura)",
                                        color = Color(0xFF90CAF9),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                // Correct counts: total = size, running = count{isRunning} (running||restarting); no hardcoded 84
                                // N97 friendly: simple O(n) count without extra allocations
                                text = "Active: ${uiState.value.containers.count { it.isRunning }}/${uiState.value.containers.size}",
                                color = if (uiState.value.containers.all { it.isRunning }) Color(0xFF00FF88) else Color(0xFFFF4444),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = uiState.value.lastUpdateTime,
                                color = Color(0xFFAAAAAA),
                                fontSize = 12.sp
                            )
                        }
                    }

                    items(
                        items = uiState.value.containers,
                        key = { it.id }
                    ) { container ->
                        ContainerCard(
                            container = container,
                            isRestarting = uiState.value.isRestarting == container.id,
                            isLoading = uiState.value.isLoading,
                            isAdmin = isAdmin,
                            onRestart = { restartConfirmation = container.id },
                            onViewLogs = {
                                viewModel.openLogs(container.id, container.name)
                            },
                            onPauseToggle = {
                                if (container.isPaused) {
                                    viewModel.unpauseContainer(container.id)
                                } else {
                                    viewModel.pauseContainer(container.id)
                                }
                            },
                            onOpenHomepage = {
                                var baseUrl: String? = preferencesManager.getLocalUrl()
                                if (baseUrl.isNullOrBlank()) {
                                    baseUrl = preferencesManager.getServerUrl()
                                }
                                
                                if (baseUrl != null && !container.ports.isNullOrEmpty()) {
                                    val host = baseUrl.substringAfter("://").substringBefore(":").substringBefore("/")
                                    val port = container.firstHttpPort
                                    if (port > 0) {
                                        val url = "http://${host}:${port}"
                                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                                        try { context.startActivity(intent) } catch (e: Exception) { }
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar("No web port found") }
                                    }
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("No ports available") }
                                }
                            },
                            onCardClick = { onContainerClick(container.id) }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "No containers detected.",
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp
                    )
                    Text(
                        "Make sure Docker is running and containers are active.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    uiState.value.selectedLogContainerId?.let {
        LogsBottomSheetInline(
            containerName = uiState.value.selectedLogContainerName ?: "",
            logContent = uiState.value.currentLogContent,
            isLoading = uiState.value.logsLoading != null,
            isAutoRefreshing = isAutoRefreshing,
            onRefresh = { viewModel.refreshLogs() },
            onToggleAutoRefresh = { viewModel.toggleLogAutoRefresh() },
            onDismiss = { viewModel.closeLogs() }
        )
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsBottomSheetInline(
    containerName: String,
    logContent: String?,
    isLoading: Boolean,
    isAutoRefreshing: Boolean = false,
    onRefresh: () -> Unit,
    onToggleAutoRefresh: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0D0D14)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = containerName,
                    color = Color(0xFF00FF88),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isAutoRefreshing) "Auto (5s)" else "Manual",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    IconButton(onClick = onToggleAutoRefresh) {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = "Toggle auto-refresh",
                            tint = if (isAutoRefreshing) Color(0xFF6C63FF) else Color.Gray
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh once",
                            tint = Color(0xFF00FF88)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color(0xFFFF4444)
                        )
                    }
                }
            }
            
            if (isAutoRefreshing) {
                Text(
                    "Auto-refresh: 5s",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
            }

            val displayText = when {
                isLoading && logContent == null -> "Loading logs..."
                isLoading && logContent != null -> "$logContent\n\n[Updating...]"
                logContent != null -> logContent
                else -> "No logs available"
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = displayText,
                    color = Color(0xFF00FF88),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun ContainerCard(
    container: DockerContainer,
    isRestarting: Boolean = false,
    isLoading: Boolean = false,
    isAdmin: Boolean = true,
    onRestart: () -> Unit = {},
    onViewLogs: () -> Unit = {},
    onPauseToggle: () -> Unit = {},
    onOpenHomepage: () -> Unit = {},
    onCardClick: () -> Unit = {}
) {
Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { if (!isLoading) onCardClick() },
        enabled = !isLoading,
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x11FFFFFF)))),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = container.displayName,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (container.healthStatus != "none") {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = when (container.healthStatus) {
                                    "healthy" -> Icons.Filled.Favorite
                                    "unhealthy" -> Icons.Filled.HeartBroken
                                    else -> Icons.Filled.Sync
                                },
                                contentDescription = "Health: ${container.healthStatus}",
                                tint = when (container.healthStatus) {
                                    "healthy" -> Color(0xFF4CAF50)
                                    "unhealthy" -> Color(0xFFF44336)
                                    else -> Color(0xFFFF9800)
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = container.image,
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp
                    )
                }

                val statusColor = if (container.isRunning) Color(0xFF00FF88) else if (container.isPaused) Color(0xFFFF9800) else Color(0xFFFF4444)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                            .semantics { contentDescription = if (container.isRunning) "Running" else if (container.isPaused) "Paused" else "Stopped" }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            container.isRunning -> "Running"
                            container.isPaused -> "Paused"
                            else -> "Stopped"
                        },
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (container.isRunning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .semantics { contentDescription = "Metrics for ${container.displayName}" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricCard(
                        title = "CPU",
                        value = String.format(java.util.Locale.US, "%.1f", container.cpuPercent),
                        unit = "%",
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "RAM",
                        value = String.format(java.util.Locale.US, "%.1f", container.memoryPercent),
                        unit = "%",
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "Memory",
                        value = String.format(java.util.Locale.US, "%.0f", container.memoryMb),
                        unit = " MB",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isAdmin) {
                    OutlinedButton(
                        onClick = onRestart,
                        enabled = !isRestarting && !isLoading && container.isRunning,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isRestarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp).size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        }
                        Text(
                            text = if (isRestarting) "Restarting..." else "Restart",
                            color = Color.White
                        )
                    }
                }

                OutlinedButton(
                    onClick = onViewLogs,
                    enabled = !isLoading,
                    modifier = if (isAdmin) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("View Logs", color = Color.White)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (isAdmin && (container.isRunning || container.isPaused)) {
                    IconButton(onClick = onPauseToggle, enabled = !isLoading) {
                        Icon(
                            imageVector = if (container.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (container.isPaused) "Resume" else "Pause",
                            tint = if (isLoading) Color.Gray else Color.White
                        )
                    }
                }
                IconButton(onClick = onOpenHomepage, enabled = !isLoading) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = "Open Homepage",
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun AIRecommendationsCard(
    state: com.tgmon.app.presentation.viewmodel.AIRecommendationsState,
    isAdmin: Boolean,
    containers: List<DockerContainer>,
    onRefresh: () -> Unit,
    onPause: (String) -> Unit
) {
    var pendingPause by remember { mutableStateOf<String?>(null) }

    val json = state.recommendations
    val recommendations = when {
        json == null -> emptyList()
        json.isJsonArray -> json.asJsonArray.toList()
        json.isJsonObject -> {
            val list = json.asJsonObject.get("recommendations")
            if (list != null && list.isJsonArray) list.asJsonArray.toList()
            else emptyList()
        }
        else -> emptyList()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0x2A1C1635)
        ),
        border = BorderStroke(1.dp, Color(0x556C63FF)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AI Recommendations",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh AI recommendations",
                        tint = Color.White
                    )
                }
            }

            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFF6C63FF)
                    )
                    Text("Analyzing containers...", color = Color.LightGray)
                }

                state.error != null -> {
                    Text(
                        "AI recommendations unavailable",
                        color = Color(0xFFFFB74D)
                    )
                    Text(
                        state.error,
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }

                json == null -> {
                    Text(
                        "No recommendations loaded.",
                        color = Color.LightGray
                    )
                }

                recommendations.isEmpty() -> {
                    Text(
                        "No actionable recommendations available.",
                        color = Color.LightGray
                    )
                }

                else -> {
                    recommendations.forEach { element ->
                        if (element.isJsonObject) {
                            val obj = element.asJsonObject

                            fun field(name: String): String? {
                                val value = obj.get(name)
                                return if (
                                    value != null &&
                                    value.isJsonPrimitive &&
                                    !value.isJsonNull
                                ) value.asString else null
                            }

                            val id = field("container_id")
                            val name = field("container_name") ?: id ?: "Unknown"
                            val action = field("recommended_action") ?: "Unknown"
                            val reason = field("reason") ?: "No reason provided"

                            val container = containers.find { it.id == id }
                            val canPause = isAdmin &&
                                    action.equals("pause", ignoreCase = true) &&
                                    container?.isRunning == true &&
                                    !container.isPaused

                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Action: $action",
                                    color = Color(0xFFB6ADFF),
                                    fontSize = 12.sp
                                )
                                Text(
                                    reason,
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )

                                if (canPause && id != null) {
                                    OutlinedButton(
                                        onClick = { pendingPause = id }
                                    ) {
                                        Text("Pause container")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingPause?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingPause = null },
            title = { Text("Pause container?") },
            text = {
                Text(
                    "Pausing this container may interrupt its service. Do you want to continue?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingPause = null
                    onPause(id)
                }) {
                    Text("Pause")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPause = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

