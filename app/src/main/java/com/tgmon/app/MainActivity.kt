package com.tgmon.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tgmon.app.presentation.screens.DashboardScreen
import com.tgmon.app.presentation.screens.SetupScreen
import com.tgmon.app.presentation.screens.ChartsScreen
import com.tgmon.app.presentation.screens.ContainersScreen
import com.tgmon.app.presentation.screens.SettingsScreen
import com.tgmon.app.presentation.screens.ContainerDetailScreen
import com.tgmon.app.presentation.screens.ServersScreen
import com.tgmon.app.presentation.screens.AlertHistoryScreen
import com.tgmon.app.presentation.viewmodel.DashboardViewModel
import com.tgmon.app.presentation.viewmodel.SetupViewModel
import com.tgmon.app.presentation.viewmodel.ContainerViewModel
import com.tgmon.app.presentation.viewmodel.SettingsViewModel
import com.tgmon.app.presentation.viewmodel.ContainerDetailViewModel
import com.tgmon.app.presentation.viewmodel.ServersViewModel
import com.tgmon.app.ui.theme.TgMonTheme
import com.tgmon.app.widget.SnapshotWorker
import com.tgmon.app.data.preferences.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var preferencesManager: PreferencesManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.util.Log.d("TgMon", "Notification permission granted")
        } else {
            android.util.Log.w("TgMon", "Notification permission denied - notifications will not work")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        // Seed default local server if none configured (idempotent, no duplicate)
        try {
            val servers = preferencesManager.getAllServers()
            val hasDefault = servers.any {
                it.localUrl == "http://10.0.2.2:8000" &&
                it.remoteUrl == "http://10.0.2.2:8000" &&
                it.token == "student_secure_token_123"
            }
            if ((servers.isEmpty() || !preferencesManager.isConfigured()) && !hasDefault) {
                val defaultServer = com.tgmon.app.data.preferences.ServerConfig(
                    name = "Local Server",
                    localUrl = "http://10.0.2.2:8000",
                    remoteUrl = "http://10.0.2.2:8000",
                    token = "student_secure_token_123",
                    isDefault = true
                )
                preferencesManager.saveServerConfig(defaultServer)
                preferencesManager.setDefaultServerId(defaultServer.id)
                android.util.Log.d("TgMon", "MainActivity seeded default local server: ${defaultServer.id}")
            }
        } catch (e: Exception) {
            android.util.Log.e("TgMon", "Failed to seed default in MainActivity: ${e.message}")
        }

        val intentForceSetup = intent?.getBooleanExtra("force_setup", false) == true
        val isConfigured = preferencesManager.isConfigured() && !intentForceSetup

        if (isConfigured) {
            SnapshotWorker.schedule(this)
        }

        setContent {
            TgMonTheme {
                val defaultId = preferencesManager.getDefaultServerId()
                TgMonApp(
                    preferencesManager = preferencesManager,
                    startDestination = if (isConfigured && !defaultId.isNullOrEmpty()) "dashboard/$defaultId" else "setup",
                    initialServerId = defaultId
                )
            }
        }
    }
}

@Composable
fun TgMonApp(
    preferencesManager: PreferencesManager,
    startDestination: String,
    initialServerId: String? = null
) {
    val navController = rememberNavController()
    var currentServerId by rememberSaveable { mutableStateOf(initialServerId ?: "") }

    LaunchedEffect(navController) {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val route = destination.route
            if (route?.startsWith("dashboard/") == true) {
                val serverId = route.removePrefix("dashboard/")
                if (serverId.isNotEmpty()) {
                    currentServerId = serverId
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("setup") {
            val viewModel: SetupViewModel = hiltViewModel()

            SetupScreen(
                viewModel = viewModel,
                onConfigComplete = {
                    SnapshotWorker.schedule(navController.context)
                    val defaultId = preferencesManager.getDefaultServerId()
                    if (defaultId.isNullOrEmpty()) return@SetupScreen
                    navController.navigate("dashboard/$defaultId") {
                        popUpTo("setup") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = "dashboard/{serverId}",
            arguments = listOf(
                androidx.navigation.navArgument("serverId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                    nullable = true
                }
            )
        ) {
            val viewModel: DashboardViewModel = hiltViewModel()

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(viewModel) {
                viewModel.setLifecycleOwner(lifecycleOwner)
                viewModel.initialize()
                onDispose {
                    viewModel.clearError()
                }
            }

            DashboardScreen(
                viewModel = viewModel,
                preferencesManager = preferencesManager,
                onLogout = {
                    viewModel.logout()
                    SnapshotWorker.cancel(navController.context)
                    navController.navigate("setup") {
                        popUpTo("setup") { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onChartsClick = {
                    navController.navigate("charts") {
                        launchSingleTop = true
                    }
                },
                onContainersClick = {
                    navController.navigate("containers") {
                        launchSingleTop = true
                    }
                },
                onServerSwitch = { newServerId ->
                    viewModel.switchServer(newServerId)
                    currentServerId = newServerId
                    navController.navigate("dashboard/$newServerId") {
                        popUpTo("dashboard") { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onAddServer = {
                    navController.navigate("setup") {
                        launchSingleTop = true
                    }
                },
                onSettingsClick = {
                    navController.navigate("settings") {
                        launchSingleTop = true
                    }
                },
                onServersClick = {
                    navController.navigate("servers") {
                        launchSingleTop = true
                    }
                },
                onAlertsClick = {
                    navController.navigate("alerts") {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("settings") {
            val viewModel: SettingsViewModel = hiltViewModel()
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("charts") {
            ChartsScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        composable("containers") {
            val viewModel: ContainerViewModel = hiltViewModel()

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(viewModel) {
                viewModel.setLifecycleOwner(lifecycleOwner)
                viewModel.initialize()
                onDispose { }
            }

            ContainersScreen(
                viewModel = viewModel,
                preferencesManager = preferencesManager,
                onBackClick = {
                    navController.popBackStack()
                },
                onContainerClick = { containerId ->
                    navController.navigate("containerDetail/$containerId")
                }
            )
        }

        composable("containerDetail/{containerId}") {
            val viewModel: ContainerDetailViewModel = hiltViewModel()
            val context = androidx.compose.ui.platform.LocalContext.current
            var localUrl: String? = null
            var serverUrl: String? = null
            
            // Get URLs from preferences
            val prefsManager = remember { com.tgmon.app.data.preferences.PreferencesManager(context) }
            localUrl = prefsManager.getLocalUrl()
            serverUrl = prefsManager.getServerUrl()
            
            ContainerDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenHomepage = { port, protocol, type ->
                    try {
                        var baseUrl: String? = localUrl?.takeIf { it.isNotBlank() }
                        // If no local URL or on remote, use server URL
                        if (baseUrl.isNullOrBlank()) {
                            baseUrl = serverUrl?.takeIf { it.isNotBlank() }
                        }
                        if (baseUrl != null) {
                            val host = baseUrl.substringAfter("://").substringBefore(":").substringBefore("/")
                            val url = "${protocol.lowercase()}://${host}:${port}"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        // Handle error
                    }
                }
            )
        }

        composable("servers") {
            val viewModel: ServersViewModel = hiltViewModel()
            ServersScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onAddServer = { navController.navigate("setup") }
            )
        }

        composable("alerts") {
            AlertHistoryScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
