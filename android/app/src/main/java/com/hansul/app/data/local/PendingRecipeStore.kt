package com.hansul.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pendingRecipeDataStore by preferencesDataStore(name = "pending_recipe")

/** The recipe link waiting to be imported; survives process death. */
interface PendingRecipeUrls {
    val pendingUrl: Flow<String?>

    /** A new link also forgets how the previous one ended. */
    suspend fun save(url: String)

    suspend fun clear()

    /** Code of the failure the last import of [url] ended with, when it is not worth repeating by itself. */
    suspend fun failure(url: String): String?

    /** Null forgets the failure, for example when a new attempt starts. */
    suspend fun rememberFailure(url: String, code: String?)
}

class PendingRecipeStore(private val context: Context) : PendingRecipeUrls {
    override val pendingUrl: Flow<String?> = context.pendingRecipeDataStore.data
        .map { preferences -> preferences[PENDING_URL] }

    override suspend fun save(url: String) {
        context.pendingRecipeDataStore.edit { preferences ->
            preferences[PENDING_URL] = url
            preferences.remove(FAILED_URL)
            preferences.remove(FAILURE_CODE)
        }
    }

    override suspend fun clear() {
        context.pendingRecipeDataStore.edit { preferences ->
            preferences.remove(PENDING_URL)
            preferences.remove(FAILED_URL)
            preferences.remove(FAILURE_CODE)
        }
    }

    override suspend fun failure(url: String): String? = context.pendingRecipeDataStore.data.first().let { preferences ->
        preferences[FAILURE_CODE]?.takeIf { preferences[FAILED_URL] == url }
    }

    override suspend fun rememberFailure(url: String, code: String?) {
        context.pendingRecipeDataStore.edit { preferences ->
            if (code == null) {
                if (preferences[FAILED_URL] == url) {
                    preferences.remove(FAILED_URL)
                    preferences.remove(FAILURE_CODE)
                }
            } else {
                preferences[FAILED_URL] = url
                preferences[FAILURE_CODE] = code
            }
        }
    }

    private companion object {
        val PENDING_URL = stringPreferencesKey("pending_recipe_url")
        val FAILED_URL = stringPreferencesKey("pending_recipe_failed_url")
        val FAILURE_CODE = stringPreferencesKey("pending_recipe_failure_code")
    }
}
