package com.hansul.app.data

import android.content.Context
import com.hansul.app.BuildConfig
import com.hansul.app.auth.AuthRepository
import com.hansul.app.auth.AuthStateStore
import com.hansul.app.auth.BearerTokenInterceptor
import com.hansul.app.auth.OidcAuthRepository
import com.hansul.app.data.local.PendingRecipeStore
import com.hansul.app.data.local.LocalCookingStore
import com.hansul.app.data.local.OfflineCookingSessionRepository
import com.hansul.app.data.local.OfflineRecipeRepository
import com.hansul.app.ai.LocalModelManager
import com.hansul.app.data.network.RecipeImportApi
import com.hansul.app.data.video.LocalVideoLibrary
import com.hansul.app.data.video.VideoIntake
import com.hansul.app.data.video.VideoRecipeImporter
import com.hansul.app.data.video.VideoUploadConsent
import com.hansul.app.data.video.VideoUploadConsentStore
import com.hansul.app.share.RecipeUrlParser
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppContainer(context: Context) {
    private val modePreferences = context.getSharedPreferences("execution-mode", Context.MODE_PRIVATE)
    private val _executionMode = MutableStateFlow(runCatching {
        ExecutionMode.valueOf(modePreferences.getString("mode", ExecutionMode.LOCAL.name)!!)
    }.getOrDefault(ExecutionMode.LOCAL))
    val executionMode: StateFlow<ExecutionMode> = _executionMode.asStateFlow()
    val localModelManager = LocalModelManager(context)

    suspend fun setExecutionMode(mode: ExecutionMode) = withContext(Dispatchers.IO) {
        check(modePreferences.edit().putString("mode", mode.name).commit()) { "조리 모드 설정을 저장하지 못했어요." }
        _executionMode.value = mode
    }
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    val authRepository: AuthRepository = OidcAuthRepository(
        context = context,
        store = AuthStateStore(context),
    )

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(BearerTokenInterceptor(authRepository))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val recipeImportApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(RecipeImportApi::class.java)

    private val pendingRecipeStore = PendingRecipeStore(context)

    /** Shared and picked phone videos; the UI hands it new video URIs through [VideoIntake]. */
    val videoLibrary = LocalVideoLibrary(context)

    /** Asked once before the first phone video is sent for analysis. */
    val videoUploadConsent: VideoUploadConsent = VideoUploadConsentStore(context)

    // One importer for both modes: video analysis always runs on the server with the signed-in user.
    private val videoRecipeImporter = VideoRecipeImporter(recipeImportApi, videoLibrary)

    private val cloudRecipeRepository: RecipeRepository = DefaultRecipeRepository(
        api = recipeImportApi,
        pendingRecipeStore = pendingRecipeStore,
        videoImporter = videoRecipeImporter,
    )

    private val cloudCookingSessionRepository: CookingSessionRepository =
        DefaultCookingSessionRepository(recipeImportApi)

    private val localStore = LocalCookingStore(context)
    private val sourceClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).build()
    private val offlineRecipeRepository =
        OfflineRecipeRepository(localStore, pendingRecipeStore, sourceClient, videoRecipeImporter)
    private val offlineCookingSessionRepository = OfflineCookingSessionRepository(localStore, localModelManager)

    val recipeRepository: RecipeRepository = ModeRecipeRepository(
        offlineRecipeRepository, cloudRecipeRepository, executionMode, videoRecipeImporter.progress,
    )
    val cookingSessionRepository: CookingSessionRepository = ModeCookingSessionRepository(
        offlineCookingSessionRepository, cloudCookingSessionRepository,
    )

    init {
        // A copied video nobody went on to analyse is reachable only from the pending link; old ones just use space.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val pending = pendingRecipeStore.pendingUrl.first()?.let(RecipeUrlParser::localVideoId)
                videoLibrary.deleteAbandoned(inUse = { id -> id == pending || hasLocalRecipe(id) })
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Housekeeping only; the next start tries again.
            }
        }
    }

    private suspend fun hasLocalRecipe(videoId: String): Boolean = try {
        localStore.findRecipe(LocalCookingStore.recipeId(RecipeUrlParser.localVideoUrl(videoId))) != null
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        true
    }
}
