package com.dshmobile.app.ui

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dshmobile.app.data.ConnectionDiagnostics
import com.dshmobile.app.data.ConnectionKind
import com.dshmobile.app.data.ConnectionProfile
import com.dshmobile.app.data.DiagnosticStep
import com.dshmobile.app.data.DshApi
import com.dshmobile.app.data.DshUrl
import com.dshmobile.app.data.PairResult
import com.dshmobile.app.data.SettingsRepository
import com.dshmobile.app.data.defaultProfiles
import com.dshmobile.app.data.newProfileId
import com.dshmobile.app.data.withDefaultScheme
import kotlinx.coroutines.Job
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
        val profileId: String? = null,
        val profileLabel: String? = null,
    ) : AppState

    data class Paired(
        val baseUrl: String,
        val sessionCount: Int,
        val version: String,
        val profileId: String? = null,
        val profileLabel: String? = null,
    ) : AppState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val api = DshApi()
    private val diagnostics = ConnectionDiagnostics(
        cookieLookup = { base ->
            runCatching {
                CookieManager.getInstance().getCookie("$base/")
            }.getOrNull()
        },
    )

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(false)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _profiles = MutableStateFlow(defaultProfiles())
    val profiles: StateFlow<List<ConnectionProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private var connectJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _profiles.value = settings.profiles
                _activeProfileId.value = settings.activeProfileId
                _notificationsEnabled.value = settings.notificationsEnabled
            }
        }
        restore()
    }

    fun restore() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            _profiles.value = settings.profiles
            _activeProfileId.value = settings.activeProfileId
            connectTo(settings.activeProfile)
        }
    }

    /** Switch the active saved endpoint and reload the WebView against it. */
    fun activateProfile(id: String) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val profile = settings.profiles.firstOrNull { it.id == id } ?: return@launch
            settingsRepository.setActiveProfile(id)
            _activeProfileId.value = id
            connectTo(profile)
        }
    }

    /** Called by the connection editor. Blank base URLs are allowed as slots. */
    fun saveProfile(profile: ConnectionProfile) {
        viewModelScope.launch {
            val trimmed = profile.baseUrl.trim()
            val normalized = if (trimmed.isBlank()) {
                ""
            } else {
                runCatching {
                    DshUrl.normalize(withDefaultScheme(profile.kind, trimmed))
                }.getOrNull() ?: return@launch
            }
            val saved = profile.copy(
                id = profile.id.ifBlank { newProfileId() },
                baseUrl = normalized,
            )
            settingsRepository.saveProfile(saved)
            val settings = settingsRepository.settings.first()
            if (settings.activeProfileId == null && saved.baseUrl.isNotBlank()) {
                settingsRepository.setActiveProfile(saved.id)
                connectTo(saved)
            } else if (settings.activeProfileId == saved.id) {
                connectTo(saved)
            }
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            val wasActive = settingsRepository.settings.first().activeProfileId == id
            settingsRepository.deleteProfile(id)
            val settings = settingsRepository.settings.first()
            if (wasActive) connectTo(settings.activeProfile)
        }
    }

    /**
     * Choose which saved slot a manual pairing should fill. The pairing screen
     * calls this when the user taps a connection chip.
     */
    fun selectProfileForPairing(id: String, baseUrlOverride: String? = null) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val profile = settings.profiles.firstOrNull { it.id == id } ?: return@launch
            settingsRepository.setActiveProfile(id)
            _state.value = AppState.NeedsPairing(
                suggestedBaseUrl = baseUrlOverride ?: profile.baseUrl,
                profileId = profile.id,
                profileLabel = profile.label,
            )
        }
    }

    fun pair(baseUrl: String, code: String) {
        viewModelScope.launch {
            connectJob?.cancel()
            val current = _state.value as? AppState.NeedsPairing
            val targetId = current?.profileId ?: _activeProfileId.value
            val settings = settingsRepository.settings.first()
            val target = settings.profiles.firstOrNull { it.id == targetId }
            val targetLabel = target?.label
            val normalizedInput = withDefaultScheme(
                target?.kind ?: ConnectionKind.CUSTOM,
                baseUrl,
            )
            _state.value = AppState.NeedsPairing(
                busy = true,
                suggestedBaseUrl = normalizedInput,
                profileId = targetId,
                profileLabel = targetLabel,
            )
            when (val result = api.pair(normalizedInput, code)) {
                is PairResult.Success -> {
                    val existing = settings.profiles.firstOrNull { it.id == targetId }
                    val profile = existing?.copy(baseUrl = result.outcome.baseUrl)
                        ?: ConnectionProfile(
                            id = newProfileId(),
                            label = "自定义",
                            kind = ConnectionKind.CUSTOM,
                            baseUrl = result.outcome.baseUrl,
                        )
                    settingsRepository.saveProfile(profile)
                    settingsRepository.setActiveProfile(profile.id)
                    _activeProfileId.value = profile.id
                    _state.value = AppState.Paired(
                        baseUrl = result.outcome.baseUrl,
                        sessionCount = result.outcome.sessionCount,
                        version = result.outcome.hello.version,
                        profileId = profile.id,
                        profileLabel = profile.label,
                    )
                }

                is PairResult.Failure -> {
                    _state.value = AppState.NeedsPairing(
                        message = result.message,
                        suggestedBaseUrl = normalizedInput,
                        profileId = targetId,
                        profileLabel = targetLabel,
                    )
                }
            }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            api.clearCookies()
            settingsRepository.clearActivePairing()
            _activeProfileId.value = null
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

    /** Run the DNS/TCP/TLS/HTTP/Cookie/API/WebSocket chain without switching. */
    suspend fun diagnose(profile: ConnectionProfile): List<DiagnosticStep> =
        diagnostics.run(profile.baseUrl)

    /** Probe a saved profile without switching to it. */
    suspend fun testProfile(profile: ConnectionProfile): Result<String> = runCatching {
        val base = DshUrl.normalize(profile.baseUrl)
        val hello = api.testConnection(base).getOrThrow()
        val count = api.sessionCount(base)
        "连接成功 · $count 个会话 · bridge ${hello.version}"
    }

    private fun connectTo(profile: ConnectionProfile?) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            if (profile == null) {
                _state.value = AppState.NeedsPairing()
                return@launch
            }

            val target = profile
            if (target.baseUrl.isBlank()) {
                _state.value = AppState.NeedsPairing(
                    message = "请先填写「${target.label}」的地址",
                    suggestedBaseUrl = target.baseUrl,
                    profileId = target.id,
                    profileLabel = target.label,
                )
                return@launch
            }
            _state.value = AppState.Loading
            if (!api.hasCookie(target.baseUrl)) {
                _state.value = AppState.NeedsPairing(
                    message = "登录 Cookie 已失效，请重新配对",
                    suggestedBaseUrl = target.baseUrl,
                    profileId = target.id,
                    profileLabel = target.label,
                )
                return@launch
            }

            api.testConnection(target.baseUrl)
                .onSuccess { hello ->
                    val count = runCatching { api.sessionCount(target.baseUrl) }.getOrNull()
                    if (count == null) {
                        _state.value = AppState.NeedsPairing(
                            message = "登录状态无效或会话列表读取失败，请重新配对",
                            suggestedBaseUrl = target.baseUrl,
                            profileId = target.id,
                            profileLabel = target.label,
                        )
                    } else {
                        _state.value = AppState.Paired(
                            baseUrl = target.baseUrl,
                            sessionCount = count,
                            version = hello.version,
                            profileId = target.id,
                            profileLabel = target.label,
                        )
                    }
                }
                .onFailure { error ->
                    _state.value = AppState.NeedsPairing(
                        message = error.message ?: "连接失败，请重新配对",
                        suggestedBaseUrl = target.baseUrl,
                        profileId = target.id,
                        profileLabel = target.label,
                    )
                }
        }
    }

    override fun onCleared() {
        api.close()
        super.onCleared()
    }
}
