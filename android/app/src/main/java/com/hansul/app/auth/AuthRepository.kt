package com.hansul.app.auth

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

data class AuthSessionState(
    val isInitializing: Boolean = true,
    val isAuthorized: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

interface AuthRepository {
    val sessionState: StateFlow<AuthSessionState>

    suspend fun createAuthorizationIntent(): Intent

    suspend fun completeAuthorization(intent: Intent?): Boolean

    suspend fun getFreshAccessToken(): String?

    suspend fun logout()
}
