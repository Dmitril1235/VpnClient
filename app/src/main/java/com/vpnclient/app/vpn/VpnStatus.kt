package com.vpnclient.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED, STOPPING }

/**
 * Простое глобальное состояние подключения — CoreVpnService его обновляет,
 * MainActivity (Compose) на него подписывается через collectAsState().
 * На шаге "нормальная архитектура" это стоит заменить на bound-service + AIDL/Messenger,
 * но для одного процесса object-а достаточно.
 */
object VpnStatus {
    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun update(newState: VpnState) {
        _state.value = newState
    }

    fun reportError(message: String) {
        _lastError.value = message
        _state.value = VpnState.DISCONNECTED
    }

    fun clearError() {
        _lastError.value = null
    }
}
