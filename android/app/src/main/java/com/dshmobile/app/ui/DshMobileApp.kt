package com.dshmobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DshMobileApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()

    when (val current = state) {
        is AppState.Loading -> LoadingScreen()

        is AppState.NeedsPairing -> PairingScreen(
            busy = current.busy,
            message = current.message,
            initialBaseUrl = current.suggestedBaseUrl,
            onPair = viewModel::pair,
        )

        is AppState.Paired -> HomeScreen(
            baseUrl = current.baseUrl,
            sessionCount = current.sessionCount,
            version = current.version,
            notificationsEnabled = notificationsEnabled,
            onToggleNotifications = viewModel::setNotificationsEnabled,
            onUnpair = viewModel::unpair,
            onRefresh = { viewModel.refreshSessionCount(current.baseUrl) },
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "正在连接 DSH…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
