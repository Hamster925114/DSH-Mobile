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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DshMobileApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    var showConnections by rememberSaveable { mutableStateOf(false) }

    if (showConnections) {
        ConnectionsScreen(
            profiles = profiles,
            activeProfileId = activeProfileId,
            onBack = { showConnections = false },
            onActivate = { id ->
                showConnections = false
                viewModel.activateProfile(id)
            },
            onSave = viewModel::saveProfile,
            onDelete = viewModel::deleteProfile,
            onTest = { profile -> viewModel.testProfile(profile) },
        )
    } else {
        when (val current = state) {
            is AppState.Loading -> LoadingScreen()

            is AppState.NeedsPairing -> PairingScreen(
                busy = current.busy,
                message = current.message,
                initialBaseUrl = current.suggestedBaseUrl,
                profileId = current.profileId,
                profiles = profiles,
                onSelectProfile = viewModel::selectProfileForPairing,
                onPair = viewModel::pair,
                onManageConnections = { showConnections = true },
            )

            is AppState.Paired -> HomeScreen(
                baseUrl = current.baseUrl,
                profileLabel = current.profileLabel,
                sessionCount = current.sessionCount,
                version = current.version,
                notificationsEnabled = notificationsEnabled,
                onToggleNotifications = viewModel::setNotificationsEnabled,
                onManageConnections = { showConnections = true },
                onUnpair = viewModel::unpair,
                onRefresh = { viewModel.refreshSessionCount(current.baseUrl) },
            )
        }
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
