package com.tgmon.app.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tgmon.app.presentation.viewmodel.SetupViewModel

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onConfigComplete: () -> Unit
) {
    val uiState = viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var tokenVisible by remember { mutableStateOf(false) }
    var navigationHandled by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.value.isSuccess) {
        if (uiState.value.isSuccess && !navigationHandled) {
            navigationHandled = true
            onConfigComplete()
        }
    }

    LaunchedEffect(uiState.value.error) {
        uiState.value.error?.let { errorMessage ->
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "TGMON Setup",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Add a server to monitor",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = uiState.value.serverName,
                onValueChange = { viewModel.updateServerName(it) },
                label = { Text("Server name") },
                placeholder = { Text("Local Server") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.value.isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.value.localUrl,
                onValueChange = { viewModel.updateLocalUrl(it) },
                label = { Text("Local IP (LAN)") },
                placeholder = { Text("http://10.0.2.2:8000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !uiState.value.isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = uiState.value.remoteUrl,
                onValueChange = { viewModel.updateRemoteUrl(it) },
                label = { Text("Remote Domain / DDNS (Fallback)") },
                placeholder = { Text("http://your-server-ip:8000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !uiState.value.isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.value.token,
                onValueChange = { viewModel.updateToken(it) },
                label = { Text("Token") },
                placeholder = { Text("your_secret_token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.value.isLoading,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (tokenVisible) "Hide token" else "Show token"
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { viewModel.validateAndSave() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !uiState.value.isLoading
            ) {
                if (uiState.value.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Validate and Auto-Connect")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Both URLs will be tried",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
