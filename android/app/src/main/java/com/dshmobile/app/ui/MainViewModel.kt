package com.dshmobile.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dshmobile.app.data.DshApi
import com.dshmobile.app.data.PairResult
import com.dshmobile.app.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface AppState {
    data object Loading : AppState

    data class NeedsPairing(
        val message: String? = null,
        val busy: Boolean = false,
        val suggestedBaseUrl: String = "",
    ) : AppState

    data class Paired(
        val baseUrl: String,
        val sessionCount: Int,
        val version: String,
    ) : AppState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val api = DshApi(settingsRepository)

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(false)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _notificationsEnabled.value = settings.notificationsEnabled
            }
        }
        restore()
    }

    fun restore() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val base = settings.baseUrl
            if (base.isBlank()) {
                _state.value = AppState.NeedsPairing()
                return@launch
            }

            _state.value = AppState.Loading
            if (!api.hasCookie(base)) {
                _state.value = AppState.NeedsPairing(
                    message = "登录 Cookie 已失效，请重新配对",
                    suggestedBaseUrl = base,
                )
                return@launch
            }

            api.testConnection(base)
                .onSuccess { hello ->
                    val count = runCatching { api.sessionCount(base) }.getOrDefault(0)
                    _state.value = AppState.Paired(base, count, hello.version)
                }
                .onFailure { error ->
                    _state.value = AppState.NeedsPairing(
                        message = error.message ?: "连接失败，请重新配对",
                        suggestedBaseUrl = base,
                    )
                }
        }
    }

    fun pair(baseUrl: String, code: String) {
        viewModelScope.launch {
            _state.value = AppState.NeedsPairing(
                busy = true,
                suggestedBaseUrl = baseUrl,
            )
            when (val result = api.pair(baseUrl, code)) {
                is PairResult.Success -> {
                    _state.value = AppState.Paired(
                        baseUrl = result.outcome.baseUrl,
                        sessionCount = result.outcome.sessionCount,
                        version = result.outcome.hello.version,
                    )
                }

                is PairResult.Failure -> {
                    _state.value = AppState.NeedsPairing(
                        message = result.message,
                        suggestedBaseUrl = baseUrl,
                    )
                }
            }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            api.clearCookies()
            settingsRepository.clear()
            _state.value = AppState.NeedsPairing()
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotificationsEnabled(enabled)
        }
    }

    fun refreshSessionCount(baseUrl: String) {
        viewModelScope.launch {
            runCatching { api.sessionCount(baseUrl) }
                .onSuccess { count ->
                    val current = _state.value
                    if (current is AppState.Paired) {
                        _state.value = current.copy(sessionCount = count)
                    }
                }
        }
    }

    override fun onCleared() {
        api.close()
        super.onCleared()
    }
}
