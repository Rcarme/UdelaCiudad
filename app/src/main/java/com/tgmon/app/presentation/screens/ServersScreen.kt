package com.tgmon.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.tgmon.app.presentation.viewmodel.ServerWithStatus
import com.tgmon.app.presentation.viewmodel.ServersViewModel
import com.tgmon.app.ui.theme.TgMonBackgroundBrush
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    viewModel: ServersViewModel,
    onBack: () -> Unit = {},
    onAddServer: () -> Unit = {}
) {
    val servers by viewModel.servers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf<ServerWithStatus?>(null) }
    
    var isPullRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    
    // Fallback: reset spinner when servers list emits (original behavior)
    LaunchedEffect(servers) {
        isPullRefreshing = false
    }
    // Explicit fix: also reset when ViewModel finishes loading (handles timeout/partial failure)
    // This ensures pull-to-refresh is never blocked if loadServers hangs.
    LaunchedEffect(isLoading) {
        if (!isLoading && isPullRefreshing) {
            isPullRefreshing = false
        }
    }
    
    showDeleteDialog?.let { server ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Server") },
            text = { Text("Are you sure you want to delete \"${server.config.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteServer(server.config.id)
                    showDeleteDialog = null
                }) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    val backgroundBrush = TgMonBackgroundBrush
    
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = { Text("Servers", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddServer,
                containerColor = Color(0xFF6C63FF)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Server", tint = Color.White)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                // Explicit fix for blocked pull-to-refresh:
                // Previously only LaunchedEffect(servers) reset isPullRefreshing, so a hung
                // network call left the spinner spinning forever. Now we launch with a
                // 7s timeout and always reset the spinner even on failure.
                isPullRefreshing = true
                scope.launch {
                    try {
                        withTimeout(7000L) {
                            // Use suspend version if available, else trigger load and await isLoading
                            viewModel.refreshSuspend()
                        }
                    } catch (e: TimeoutCancellationException) {
                        // Timeout still requires spinner reset - handled in finally
                    } catch (e: Exception) {
                        // Any other error - ensure spinner is cleared
                    } finally {
                        isPullRefreshing = false
                    }
                }
            },
            state = pullRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .background(brush = backgroundBrush)
                .padding(paddingValues)
        ) {
            if (servers.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "No servers configured.",
                        color = Color(0xFFAAAAAA),
                        fontSize = 16.sp
                    )
                    Text(
                        "Tap + to add one.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                // Verified P0 Hotfix: show ALL servers, no isActive filter (previously filtered out offline servers)
                // Keeps list correct even when some servers offline; low-resource: direct LazyColumn no extra filter allocations
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(servers, key = { it.config.id }) { server ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    showDeleteDialog = server
                                    false
                                } else {
                                    false
                                }
                            }
                        )
                        
                        SwipeToDismissBox(
                            state = dismissState,
                            // Fix red-screen bug: fillMaxSize() en LazyColumn con maxHeight=Infinity se expande a viewport.
                            // Usamos altura finita fija que calza con ServerCard (72.dp min) para que el fondo rojo
                            // no cubra toda la pantalla. El Card ya tiene heightIn(72.dp), usamos el mismo.
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 72.dp)
                                        .background(Color.Red.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = Color.White
                                    )
                                }
                            },
                            content = {
                                ServerCard(
                                    server = server,
                                    isDefault = server.config.isDefault,
                                    onSetDefault = {
                                        viewModel.setDefault(server.config.id)
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerCard(
    server: ServerWithStatus,
    isDefault: Boolean,
    onSetDefault: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var hasShownDefaultSnackbar by remember { mutableStateOf(false) }

    Card(
        // Fix: use heightIn instead of allowing infinite height; LazyColumn + SwipeToDismissBox
        // could measure Card as unbounded, leading to red background expansion. heightIn constrains
        // layout to a finite min while allowing wrapContent growth for multiline chips.
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        border = if (isDefault) CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(Color(0xFF6C63FF), Color(0xFF00FFFF)))) else null,
        shape = MaterialTheme.shapes.medium,
        onClick = {
            onSetDefault()
        }
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
                // Weight ensures this row fills available width so truncation can occur
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusColor = when (server.isOnline) {
                        true -> Color(0xFF00FF88)
                        false -> Color(0xFFFF4444)
                        null -> Color.Gray
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                            .semantics {
                                contentDescription = when (server.isOnline) {
                                    true -> "Online"
                                    false -> "Offline"
                                    null -> "Unknown"
                                }
                            }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    // Weight allows name row to truncate correctly inside LazyColumn item
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = server.config.name,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isDefault) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AssistChip(
                                    onClick = {},
                                    label = { Text("Default", fontSize = 10.sp) },
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }
                        Text(
                            text = server.config.localUrl.takeIf { it.isNotBlank() } ?: server.config.remoteUrl,
                            color = Color.Gray,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
    
    LaunchedEffect(isDefault) {
        if (isDefault && !hasShownDefaultSnackbar) {
            snackbarHostState.showSnackbar("Default server updated")
            hasShownDefaultSnackbar = true
        }
    }
    
    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(16.dp))
}
