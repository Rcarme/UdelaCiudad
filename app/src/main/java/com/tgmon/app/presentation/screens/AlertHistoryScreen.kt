package com.tgmon.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgmon.app.data.database.AppDatabase
import com.tgmon.app.data.models.AlertHistory
import com.tgmon.app.ui.theme.TgMonBackgroundBrush
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertHistoryScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var alerts by remember { mutableStateOf<List<AlertHistory>>(emptyList()) }
    var selectedFilter by rememberSaveable { mutableStateOf("All") }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = remember { CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    DisposableEffect(Unit) {
        onDispose {
            scope.cancel()
        }
    }

    val filters = listOf("All", "CPU", "Memory", "Disk", "Containers")
    
    var isPullRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    
    LaunchedEffect(Unit) {
        val dao = AppDatabase.getInstance(context).alertHistoryDao()
        dao.getAll(limit = 100, offset = 0).collectLatest { list ->
            alerts = list
        }
    }
    
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Clear All") },
            text = { Text("Are you sure you want to delete all alert history?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val dao = AppDatabase.getInstance(context).alertHistoryDao()
                        dao.clearAll()
                    }
                    showDeleteAllDialog = false
                }) {
                    Text("Delete All", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
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
                title = { Text("Alert History", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                ),
                actions = {
                    if (alerts.isNotEmpty()) {
                        IconButton(onClick = { showDeleteAllDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear All", tint = Color.White)
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                scope.launch {
                    val dao = AppDatabase.getInstance(context).alertHistoryDao()
                    dao.getAll(limit = 100, offset = 0).collectLatest { list ->
                        alerts = list
                        isPullRefreshing = false
                    }
                }
            },
            state = pullRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .background(brush = backgroundBrush)
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter, fontSize = 12.sp) }
                        )
                    }
                }
                
                val filteredAlerts = alerts.filter { alert ->
                    when (selectedFilter) {
                        "All" -> true
                        "CPU" -> alert.type == "CPU_HIGH"
                        "Memory" -> alert.type == "MEMORY_HIGH"
                        "Disk" -> alert.type == "DISK_HIGH"
                        "Containers" -> alert.type.startsWith("CONTAINER")
                        else -> true
                    }
                }
                
                if (filteredAlerts.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "No alerts recorded",
                            color = Color(0xFFAAAAAA),
                            fontSize = 16.sp
                        )
                    }
                } else {
                    val groupedAlerts = filteredAlerts.groupBy { alert ->
                        val today = java.util.Calendar.getInstance().apply {
                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }
                        val yesterday = java.util.Calendar.getInstance().apply {
                            add(java.util.Calendar.DAY_OF_YEAR, -1)
                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }
                        when {
                            alert.timestamp >= today.timeInMillis -> "Today"
                            alert.timestamp >= yesterday.timeInMillis -> "Yesterday"
                            else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(alert.timestamp))
                        }
                    }
                    
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groupedAlerts.forEach { (date, dateAlerts) ->
                            item {
                                Text(
                                    text = date,
                                    color = Color.Gray,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            
                                items(dateAlerts, key = { it.id }) { alert ->
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart) {
                                            scope.launch {
                                                val dao = AppDatabase.getInstance(context).alertHistoryDao()
                                                dao.deleteById(alert.id)
                                            }
                                        }
                                        false
                                    }
                                )
                                
                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
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
                                        AlertItem(alert = alert)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlertItem(alert: AlertHistory) {
    val (icon, color) = when {
        alert.type == "CPU_HIGH" -> Icons.Filled.Memory to Color(0xFFFF9800)
        alert.type == "MEMORY_HIGH" -> Icons.Filled.Memory to Color(0xFF2196F3)
        alert.type == "DISK_HIGH" -> Icons.Filled.Storage to Color(0xFFFF5722)
        alert.type.contains("DOWN") -> Icons.Filled.Error to Color(0xFFFF4444)
        alert.type.contains("UP") -> Icons.Filled.CheckCircle to Color(0xFF00FF88)
        alert.type.contains("RESTART") -> Icons.Filled.Warning to Color(0xFFFFEB3B)
        else -> Icons.Filled.Info to Color.Gray
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = alert.type,
                tint = color,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alert.type.replace("_", " "),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = alert.message,
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Text(
                    text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(alert.timestamp)),
                    color = Color.DarkGray,
                    fontSize = 10.sp
                )
            }
        }
    }
}
