package com.hansul.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import net.openid.appauth.AuthState
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.authDataStore by preferencesDataStore(name = "oidc_auth")

class AuthStateStore(private val context: Context) {
    private val cipher = KeystoreCipher()

    suspend fun read(): AuthState? {
        val encrypted = context.authDataStore.data.first()[AUTH_STATE] ?: return null
        return runCatching {
            AuthState.jsonDeserialize(cipher.decrypt(encrypted))
        }.getOrElse {
            clear()
            null
        }
    }

    suspend fun write(authState: AuthState) {
        val encrypted = cipher.encrypt(authState.jsonSerializeString())
        context.authDataStore.edit { preferences ->
            preferences[AUTH_STATE] = encrypted
        }
    }

    suspend fun clear() {
        context.authDataStore.edit { preferences ->
            preferences.remove(AUTH_STATE)
        }
    }

    private companion object {
        val AUTH_STATE = stringPreferencesKey("encrypted_auth_state")
    }
}

private class KeystoreCipher {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val secretKey: SecretKey
        get() {
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey

            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(
            cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return "$iv:$encrypted"
    }

    fun decrypt(value: String): String {
        val separator = value.indexOf(':')
        require(separator > 0) { "Invalid encrypted auth state" }
        val iv = Base64.decode(value.substring(0, separator), Base64.NO_WRAP)
        val encrypted = Base64.decode(value.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hansul_oidc_auth_state"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
