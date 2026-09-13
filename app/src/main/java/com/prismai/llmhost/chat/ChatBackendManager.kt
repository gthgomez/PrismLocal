package com.prismai.llmhost.chat

import com.prismai.llmhost.cloud.auth.SupabaseAuthClient
import com.prismai.llmhost.cloud.auth.SupabaseAuthSession
import com.prismai.llmhost.cloud.auth.TokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatBackendManager(
    private val tokenStorage: TokenStorage,
    private val authClient: SupabaseAuthClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val _activeBackend = MutableStateFlow(ChatBackend.LOCAL_LLAMA)
    val activeBackend: StateFlow<ChatBackend> = _activeBackend.asStateFlow()

    private val _authSession = MutableStateFlow<SupabaseAuthSession?>(null)
    val authSession: StateFlow<SupabaseAuthSession?> = _authSession.asStateFlow()

    private val authMutex = Mutex()

    init {
        val storedSession = tokenStorage.loadSession()
        _authSession.value = storedSession
    }

    fun selectBackend(backend: ChatBackend): Boolean {
        if (backend == ChatBackend.PRISMATIX_CLOUD && _authSession.value == null) {
            // Cannot select Cloud without an active session
            return false
        }
        _activeBackend.value = backend
        return true
    }

    fun onSessionAuthenticated(session: SupabaseAuthSession) {
        tokenStorage.saveSession(session)
        _authSession.value = session
    }

    fun signOut() {
        tokenStorage.clearSession()
        _authSession.value = null
        _activeBackend.value = ChatBackend.LOCAL_LLAMA
    }

    suspend fun getValidAccessToken(): String? = authMutex.withLock {
        val current = _authSession.value ?: return null
        if (!current.isExpired()) {
            return current.accessToken
        }

        val refreshToken = current.refreshToken
        if (refreshToken.isNullOrBlank()) {
            // Unrecoverable expiry: no refresh token to renew with. Purge the
            // session and drop back to local so Cloud is not left selected
            // without a usable token.
            signOut()
            return null
        }
        val refreshResult = authClient.refreshSession(refreshToken)
        if (refreshResult.isSuccess) {
            val newSession = refreshResult.getOrThrow()
            onSessionAuthenticated(newSession)
            return newSession.accessToken
        } else {
            // Refresh failed / revoked
            signOut()
            return null
        }
    }
}
