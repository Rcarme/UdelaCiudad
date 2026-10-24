package com.tgmon.app.presentation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Refresh
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.tgmon.app.data.models.DockerContainer
import com.tgmon.app.data.preferences.PreferencesManager
import com.tgmon.app.data.preferences.ServerConfig
import com.tgmon.app.presentation.components.DiskUsageCard
import com.tgmon.app.presentation.components.LineChart
import com.tgmon.app.presentation.components.MeshBackground
import com.tgmon.app.presentation.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    preferencesManager: PreferencesManager,
    onLogout: () -> Unit = {},
    onChartsClick: () -> Unit = {},
    onContainersClick: () -> Unit = {},
    onServerSwitch: (String) -> Unit = {},
    onAddServer: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onServersClick: () -> Unit = {},
    onAlertsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // N97 low-resource: collectAsState kept vs collectAsStateWithLifecycle. The latter
    // (androidx.lifecycle:lifecycle-runtime-compose) would be more lifecycle-aware and
    // stops collection when off-screen, saving CPU/battery. Not added here to avoid
    // extra dependency with Kotlin 1.9.22 / composeBom 2024.09.00; current usage is safe
    // because DashboardScreen is always in composition when visible. To migrate later:
    // add "androidx.lifecycle:lifecycle-runtime-compose:2.7.0" and replace with
    // collectAsStateWithLifecycle().
    val uiState by viewModel.uiState.collectAsState()
    val historicalMetrics by viewModel.historicalMetrics.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val aiPredictionState by viewModel.aiPredictionState.collectAsState()
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }
    var showServerMenu by rememberSaveable { mutableStateOf(false) }
    var showOptionsMenu by rememberSaveable { mutableStateOf(false) }

    val currentServer by produceState<ServerConfig?>(
        initialValue = preferencesManager.getDefaultServer(),
        keys = arrayOf(preferencesManager, showServerMenu, preferencesManager.getDefaultServerId() ?: "")
    ) {
        value = preferencesManager.getDefaultServer()
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = "Warning") },
            title = { Text("Logout") },
            text = { Text("Are you sure? You'll need to reconfigure the server connection.") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) {
                    Text("Logout")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showServerMenu) {
        ServerManagementDialog(
            servers = servers,
            preferencesManager = preferencesManager,
            currentServerId = currentServer?.id,
            onServerSelected = { serverId ->
                showServerMenu = false
                viewModel.loadServers()
                onServerSwitch(serverId)
            },
            onDismiss = { showServerMenu = false },
            onAddServer = {
                showServerMenu = false
                onAddServer()
            }
        )
    }

    val pullRefreshState = rememberPullToRefreshState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "TGMON",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        letterSpacing = 1.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = onServersClick) {
                        Icon(Icons.Filled.Dns, contentDescription = "Servers", tint = Color.White)
                    }
                    IconButton(onClick = onContainersClick) {
                        Icon(Icons.Filled.Info, contentDescription = "Containers", tint = Color.White)
                    }
                    IconButton(onClick = onAlertsClick) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Alerts", tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            containerColor = Color(0xFF1E2235)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Settings", color = Color.White) },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = Color.White) },
                                onClick = {
                                    showOptionsMenu = false
                                    onSettingsClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Full Charts", color = Color.White) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null, tint = Color.White) },
                                onClick = {
                                    showOptionsMenu = false
                                    onChartsClick()
                                }
                            )
                            HorizontalDivider(color = Color(0x33FFFFFF))
                            DropdownMenuItem(
                                text = { Text("Logout", color = Color(0xFFFF5252)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = Color(0xFFFF5252)) },
                                onClick = {
                                    showOptionsMenu = false
                                    showLogoutDialog = true
                                }
                            )
                        }
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
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(
                        selectedTabIndex = uiState.selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        indicator = { tabPositions ->
                            if (uiState.selectedTab < tabPositions.size) {
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[uiState.selectedTab]),
                                    color = Color(0xFF6C63FF)
                                )
                            }
                        },
                        divider = {}
                    ) {
                        Tab(
                            selected = uiState.selectedTab == 0,
                            onClick = { viewModel.onTabSelected(0) },
                            text = { Text("Overview", color = if(uiState.selectedTab == 0) Color.White else Color.Gray) }
                        )
                        Tab(
                            selected = uiState.selectedTab == 1,
                            onClick = { viewModel.onTabSelected(1) },
                            text = { Text("Charts", color = if(uiState.selectedTab == 1) Color.White else Color.Gray) }
                        )
                    }

                    PullToRefreshBox(
                        isRefreshing = uiState.isLoading,
                        onRefresh = { viewModel.loadHealth() },
                        state = pullRefreshState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        when (uiState.selectedTab) {
                            0 -> OverviewTab(uiState = uiState,
                                preferencesManager = preferencesManager,
                                serverName = currentServer?.name,
                                onContainersClick = onContainersClick,
                                aiPredictionState = aiPredictionState,
                                onRefreshAI = { viewModel.loadAiPrediction() })
                            1 -> ChartsTab(historicalMetrics, onSyncHistory = { viewModel.syncHistory() })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerManagementDialog(
    servers: List<ServerConfig>,
    preferencesManager: PreferencesManager,
    currentServerId: String?,
    onServerSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onAddServer: () -> Unit
) {
    var serverToDelete by remember { mutableStateOf<ServerConfig?>(null) }

    if (serverToDelete != null) {
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text("Delete Server") },
            text = { Text("Are you sure you want to delete \"${serverToDelete?.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        serverToDelete?.let { preferencesManager.removeServer(it.id) }
                        serverToDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servers") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                servers.forEach { server ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (server.id != currentServerId) {
                                    preferencesManager.setDefaultServerId(server.id)
                                    onServerSelected(server.id)
                                } else {
                                    onDismiss()
                                }
                            }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = server.name,
                                fontWeight = if (server.id == currentServerId) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = server.localUrl.takeIf { it.isNotBlank() } ?: server.remoteUrl,
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        if (server.id == currentServerId) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Active",
                                tint = Color(0xFF00FF88)
                            )
                        }
                        if (servers.size > 1) {
                            IconButton(onClick = { serverToDelete = server }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.Red)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onAddServer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Server")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun OverviewTab(
    uiState: com.tgmon.app.presentation.viewmodel.DashboardUIState,
    preferencesManager: com.tgmon.app.data.preferences.PreferencesManager,
    serverName: String? = null,
    onContainersClick: () -> Unit,
    aiPredictionState: com.tgmon.app.presentation.viewmodel.AIPredictionState,
    onRefreshAI: () -> Unit
) {
    val context = LocalContext.current
    // N97: Top 4 most used containers (running first, highest CPU+RAM usage)
    val topContainers = remember(uiState.containers) {
        uiState.containers
            .sortedWith(
                compareByDescending<DockerContainer> { it.isRunning }
                    .thenByDescending { it.cpuPercent + it.memoryPercent }
            )
            .take(4)
    }
    val topContainerChunks = remember(topContainers) { topContainers.chunked(2) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (uiState.error != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x33FF4444)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = "Error", tint = Color(0xFFFF4444), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = uiState.error, color = Color(0xFFFF4444), fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            val isHexContainerId = remember(uiState.health?.hostname) {
                val h = uiState.health?.hostname?.trim() ?: ""
                h.length in 12..64 && h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            }

            val displayName = when {
                !serverName.isNullOrBlank() -> serverName
                !uiState.health?.hostname.isNullOrBlank() && !isHexContainerId -> uiState.health!!.hostname
                else -> uiState.health?.hostname ?: "SERVER"
            }

            val hostSuffix = if (isHexContainerId && !uiState.health?.hostname.isNullOrBlank()) {
                " • ${uiState.health!!.hostname}"
            } else ""

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "Status: ${if (uiState.isOffline) "Offline" else "Online"} • ${uiState.lastUpdateTime}$hostSuffix",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (uiState.isOffline) Color(0xFFFF4444) else Color(0xFF00FF88)
                    )
                }
                IconButton(
                    onClick = {
                        val serverUrl = preferencesManager.getServerUrl() ?: return@IconButton
                        val host = serverUrl
                            .substringAfter("://")
                            .substringBefore(":")
                            .substringBefore("/")
                        if (host.isBlank()) return@IconButton
                        val scheme = if (serverUrl.startsWith("https", ignoreCase = true)) "https" else "http"
                        val homepageUrl = "${scheme}://$host:3000"
                        try {
                            android.util.Log.d("TgMon", "Opening homepage: $homepageUrl (from serverUrl=$serverUrl)")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(homepageUrl))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.w("TgMon", "Failed to open homepage: ${e.message}")
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Language, contentDescription = "Open Server Homepage", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }

        item {
            uiState.health?.let { h ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatGlassCard(title = "CPU", value = "${String.format(java.util.Locale.US, "%.1f", h.cpuPercent)}%", modifier = Modifier.weight(1f))
                    StatGlassCard(title = "RAM", value = "${String.format(java.util.Locale.US, "%.1f", h.memoryPercent)}%", modifier = Modifier.weight(1f))
                }
            }
        }

        item {
            AIPredictionCard(
                state = aiPredictionState,
                onRefresh = onRefreshAI
            )
        }

        item {
            uiState.health?.let { h ->
                DiskUsageCard(
                    usedGb = h.diskUsedGb,
                    freeGb = h.diskFreeGb,
                    totalGb = h.diskTotalGb,
                    disks = h.disks
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.uptime?.let { uptime ->
                    AssistChip(
                        onClick = {},
                        label = { Text(uptime, fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Filled.Timer, null, Modifier.size(14.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                uiState.loadAvg?.let { loadAvg ->
                    AssistChip(
                        onClick = {},
                        label = { Text(loadAvg, fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Filled.Speed, null, Modifier.size(14.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
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
                    text = "Top Active Services",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                if (uiState.containers.isNotEmpty()) {
                    TextButton(
                        onClick = onContainersClick,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "View all (${uiState.containers.size}) →",
                            color = Color(0xFF6C63FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (topContainers.isNotEmpty()) {
            items(
                items = topContainerChunks,
                key = { it.first().id }
            ) { rowContainers ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowContainers.forEach { container ->
                        Box(modifier = Modifier.weight(1f)) {
                            ServiceGlassCard(container)
                        }
                    }
                    if (rowContainers.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else if (!uiState.isLoading) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No services detected.", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                    Text("Make sure Docker is running and containers are active.", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun ChartsTab(
    metrics: List<com.tgmon.app.data.models.MetricHistory>,
    onSyncHistory: () -> Unit = {}
) {
    // N97: hoisted lambdas avoid reallocating Function on each recomposition;
    // LineChart is skippable when valueExtractor is stable — lowers CPU on low-resource device.
    val cpuExtractor = remember { { m: com.tgmon.app.data.models.MetricHistory -> m.cpuPercent } }
    val memoryExtractor = remember { { m: com.tgmon.app.data.models.MetricHistory -> m.memoryPercent } }
    val diskExtractor = remember { { m: com.tgmon.app.data.models.MetricHistory -> m.diskPercent } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Performance History",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (metrics.isNotEmpty()) "${metrics.size} data points recorded" else "Waiting for data points...",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = onSyncHistory) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Sync History", tint = Color(0xFF6C63FF))
                }
            }
        }
        
        if (metrics.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "No Historical Metrics Yet",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Text(
                            "Data points are saved periodically. Tap below to sync recent history from the server.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = onSyncHistory,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF))
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sync with Server")
                        }
                    }
                }
            }
        } else {
            item {
                LineChart(
                    title = "CPU Usage (%)",
                    metrics = metrics,
                    valueExtractor = cpuExtractor,
                    color = Color(0xFF6C63FF)
                )
            }

            item {
                LineChart(
                    title = "Memory Usage (%)",
                    metrics = metrics,
                    valueExtractor = memoryExtractor,
                    color = Color(0xFF00FFFF)
                )
            }

            item {
                LineChart(
                    title = "Disk Usage (%)",
                    metrics = metrics,
                    valueExtractor = diskExtractor,
                    unit = "%",
                    color = Color(0xFFFF5722),
                    maxValue = 100.0
                )
            }

            item {
                Text(
                    "Charts based on the last ${metrics.size} recorded data points.",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
fun StatGlassCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .height(64.dp)
            .semantics { contentDescription = "$title $value" },
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x11FFFFFF)))),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = title, color = Color(0xFFAAAAAA), fontSize = 12.sp)
            Text(text = value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ServiceGlassCard(container: DockerContainer) {
    Card(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x2A1C1635)),
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(Color(0x55FFFFFF), Color(0x1AFFFFFF)))),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                val statusColor = if (container.isRunning) Color(0xFF00FF88) else Color(0xFFFF4444)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                        .semantics { contentDescription = if (container.isRunning) "Running" else "Stopped" }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = container.displayName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x33000000)),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "${container.memoryMb.toInt()} MB",
                    color = Color(0xFF00FFFF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}
    @Composable
    fun AIPredictionCard(
        state: com.tgmon.app.presentation.viewmodel.AIPredictionState,
        onRefresh: () -> Unit
    ) {
        val prediction = state.prediction

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0x2A1C1635)
            ),
            border = BorderStroke(
                1.dp,
                Color(0x556C63FF)
            ),
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFF6C63FF)
                        )

                        Spacer(Modifier.width(8.dp))

                        Text(
                            text = "AI Prediction",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh AI prediction",
                            tint = Color.White
                        )
                    }
                }

                when {
                    state.isLoading -> {
                        CircularProgressIndicator(
                            color = Color(0xFF6C63FF),
                            modifier = Modifier.size(28.dp)
                        )

                        Text(
                            text = "Analyzing server metrics...",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }

                    state.error != null -> {
                        Text(
                            text = "AI prediction unavailable",
                            color = Color(0xFFFFB74D),
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = state.error,
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }

                    prediction != null -> {
                        val json = if (prediction.isJsonObject) {
                            prediction.asJsonObject
                        } else {
                            null
                        }

                        val riskLevel = json?.get("risk_level")
                            ?.takeUnless { it.isJsonNull }
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString ?: "Unknown"

                        val message = json?.get("message")
                            ?.takeUnless { it.isJsonNull }
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString ?: "Prediction received"

                        val riskColor = when (riskLevel.lowercase()) {
                            "high", "critical" -> Color(0xFFFF5252)
                            "medium", "moderate" -> Color(0xFFFFB74D)
                            "low" -> Color(0xFF00FF88)
                            else -> Color.LightGray
                        }

                        Text(
                            text = "Risk level: $riskLevel",
                            color = riskColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        Text(
                            text = message,
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }

                    else -> {
                        Text(
                            text = "No AI prediction available yet.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
