package com.hansul.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.hansul.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.connectivity.ConnectionBuilder
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OidcAuthRepository(
    context: Context,
    private val store: AuthStateStore,
) : AuthRepository {
    private val connectionBuilder = EnvironmentConnectionBuilder()
    private val appAuthConfiguration = AppAuthConfiguration.Builder()
        .setConnectionBuilder(connectionBuilder)
        .setSkipIssuerHttpsCheck(BuildConfig.DEBUG)
        .build()
    private val authorizationService = AuthorizationService(context, appAuthConfiguration)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private var authState = AuthState()

    private val _sessionState = MutableStateFlow(AuthSessionState())
    override val sessionState: StateFlow<AuthSessionState> = _sessionState.asStateFlow()

    init {
        scope.launch {
            try {
                authState = store.read() ?: AuthState()
                _sessionState.value = AuthSessionState(
                    isInitializing = false,
                    isAuthorized = authState.isAuthorized,
                )
            } catch (error: Throwable) {
                authState = AuthState()
                _sessionState.value = AuthSessionState(
                    isInitializing = false,
                    isAuthorized = false,
                    errorMessage = error.toAuthMessage(
                        "저장된 로그인 정보를 읽지 못했어요. 다시 로그인해주세요.",
                    ),
                )
            } finally {
                initialized.complete(Unit)
            }
        }
    }

    override suspend fun createAuthorizationIntent(): Intent {
        initialized.await()
        _sessionState.value = _sessionState.value.copy(isBusy = true, errorMessage = null)

        return try {
            val serviceConfiguration = authState.authorizationServiceConfiguration
                ?: fetchServiceConfiguration()
            stateMutex.withLock {
                if (authState.authorizationServiceConfiguration == null) {
                    authState = AuthState(serviceConfiguration)
                    store.write(authState)
                }
            }

            val request = AuthorizationRequest.Builder(
                serviceConfiguration,
                BuildConfig.OIDC_CLIENT_ID,
                ResponseTypeValues.CODE,
                BuildConfig.OIDC_REDIRECT_URI.toUri(),
            )
                .setScopes("openid", "profile", "email", "offline_access")
                .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
                .build()

            authorizationService.getAuthorizationRequestIntent(request)
        } catch (error: Throwable) {
            _sessionState.value = _sessionState.value.copy(
                isBusy = false,
                errorMessage = error.toAuthMessage("로그인 서버에 연결하지 못했어요."),
            )
            throw error
        } finally {
            _sessionState.value = _sessionState.value.copy(isBusy = false)
        }
    }

    override suspend fun completeAuthorization(intent: Intent?): Boolean {
        initialized.await()
        if (intent == null) {
            _sessionState.value = _sessionState.value.copy(
                isBusy = false,
                errorMessage = "로그인이 취소됐어요.",
            )
            return false
        }

        val response = AuthorizationResponse.fromIntent(intent)
        val authorizationError = AuthorizationException.fromIntent(intent)
        if (response == null) {
            _sessionState.value = _sessionState.value.copy(
                isBusy = false,
                errorMessage = authorizationError.toOidcMessage("로그인을 완료하지 못했어요."),
            )
            return false
        }

        _sessionState.value = _sessionState.value.copy(isBusy = true, errorMessage = null)
        return try {
            stateMutex.withLock {
                val nextState = AuthState(response, authorizationError)
                val (tokenResponse, tokenError) = exchangeToken(response)
                nextState.update(tokenResponse, tokenError)
                authState = nextState
                store.write(authState)

                val authorized = authState.isAuthorized
                _sessionState.value = AuthSessionState(
                    isInitializing = false,
                    isAuthorized = authorized,
                    isBusy = false,
                    errorMessage = if (authorized) {
                        null
                    } else {
                        tokenError.toOidcMessage("로그인 토큰을 발급받지 못했어요.")
                    },
                )
                authorized
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _sessionState.value = AuthSessionState(
                isInitializing = false,
                isAuthorized = authState.isAuthorized,
                isBusy = false,
                errorMessage = error.toAuthMessage("로그인 토큰을 발급받지 못했어요."),
            )
            false
        }
    }

    override suspend fun getFreshAccessToken(): String? {
        initialized.await()
        if (!authState.isAuthorized) return null

        return stateMutex.withLock {
            val result = suspendCancellableCoroutine { continuation ->
                authState.performActionWithFreshTokens(authorizationService) {
                        accessToken, _, exception ->
                    if (continuation.isActive) {
                        continuation.resume(accessToken to exception)
                    }
                }
            }
            store.write(authState)

            val (token, error) = result
            _sessionState.value = AuthSessionState(
                isInitializing = false,
                isAuthorized = authState.isAuthorized,
                errorMessage = error.toOidcMessage("로그인 세션을 갱신하지 못했어요."),
            )
            token
        }
    }

    override suspend fun logout() {
        initialized.await()
        stateMutex.withLock {
            authState = AuthState()
            store.clear()
            _sessionState.value = AuthSessionState(
                isInitializing = false,
                isAuthorized = false,
            )
        }
    }

    private suspend fun fetchServiceConfiguration(): AuthorizationServiceConfiguration =
        suspendCancellableCoroutine { continuation ->
            AuthorizationServiceConfiguration.fetchFromIssuer(
            BuildConfig.OIDC_ISSUER.toUri(),
                { configuration, exception ->
                    when {
                        !continuation.isActive -> Unit
                        configuration != null -> continuation.resume(configuration)
                        else -> continuation.resumeWithException(
                            exception ?: IOException("OIDC discovery failed"),
                        )
                    }
                },
                connectionBuilder,
            )
        }

    private suspend fun exchangeToken(
        authorizationResponse: AuthorizationResponse,
    ) = suspendCancellableCoroutine { continuation ->
        authorizationService.performTokenRequest(
            authorizationResponse.createTokenExchangeRequest(),
        ) { response, exception ->
            if (continuation.isActive) continuation.resume(response to exception)
        }
    }
}

private class EnvironmentConnectionBuilder : ConnectionBuilder {
    override fun openConnection(uri: Uri): HttpURLConnection {
        val isAllowed = uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")
        require(isAllowed) { "Only HTTPS OIDC endpoints are allowed in release builds" }

        return (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false
        }
    }
}

private fun AuthorizationException?.toOidcMessage(fallback: String): String =
    this?.errorDescription?.takeIf(String::isNotBlank) ?: fallback

private fun Throwable.toAuthMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback
