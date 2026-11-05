package com.hansul.app.ui

import com.hansul.app.data.ExecutionMode
import com.hansul.app.model.Recipe
import com.hansul.app.model.VideoImportProgress
import com.hansul.app.model.VideoImportStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KitchenScreenTest {
    private val rejected = "인스타그램 링크는 바로 분석할 수 없어요. 앱에서 영상을 휴대폰에 저장한 뒤 한술로 공유해주세요."
    private val signedIn = RecipeImportUiState(isAuthInitializing = false, isAuthenticated = true)

    @Test
    fun `a message on a screen with no place for it goes to the snackbar`() {
        val signedOutCloud = RecipeImportUiState(executionMode = ExecutionMode.CLOUD, isAuthInitializing = false,
            errorMessage = rejected)
        assertEquals(KitchenScreen.WELCOME, signedOutCloud.kitchenScreen())
        assertEquals(rejected, signedOutCloud.snackbarError())

        val waitingForLogin = RecipeImportUiState(executionMode = ExecutionMode.LOCAL, isAuthInitializing = false,
            pendingUrl = PHONE_VIDEO, loginRequiredForVideo = true, errorMessage = rejected)
        assertEquals(KitchenScreen.VIDEO_LOGIN, waitingForLogin.kitchenScreen())
        assertEquals(rejected, waitingForLogin.snackbarError())

        val copying = signedIn.copy(isLoading = true, importProgress = VideoImportProgress(VideoImportStage.PREPARING_VIDEO),
            errorMessage = "30분이 넘는 영상은 아직 분석하지 않아요.")
        assertEquals(KitchenScreen.VIDEO_WORK, copying.kitchenScreen())
        assertEquals("30분이 넘는 영상은 아직 분석하지 않아요.", copying.snackbarError())

        val showingRecipe = signedIn.copy(recipe = RECIPE, pendingUrl = RECIPE.sourceUrl, errorMessage = rejected)
        assertEquals(KitchenScreen.RECIPE, showingRecipe.kitchenScreen())
        assertEquals(rejected, showingRecipe.snackbarError())
    }

    @Test
    fun `only the error screen shows its message itself`() {
        val failed = signedIn.copy(pendingUrl = YOUTUBE_WATCH, errorMessage = "요리 과정이 나오는 영상이 아닌 것 같아요.")

        assertEquals(KitchenScreen.ERROR, failed.kitchenScreen())
        assertNull(failed.snackbarError())
        assertNull(signedIn.snackbarError())
    }

    @Test
    fun `the video agreement comes after the sign-in and before any loading`() {
        val waiting = signedIn.copy(pendingUrl = PHONE_VIDEO, consentRequiredForVideo = true)
        assertEquals(KitchenScreen.VIDEO_CONSENT, waiting.kitchenScreen())

        val local = RecipeImportUiState(executionMode = ExecutionMode.LOCAL, isAuthInitializing = false)
        assertEquals(KitchenScreen.VIDEO_LOGIN, local.copy(pendingUrl = PHONE_VIDEO, loginRequiredForVideo = true).kitchenScreen())
        assertEquals(KitchenScreen.CHECKING_ACCOUNT, RecipeImportUiState(isAuthInitializing = true).kitchenScreen())
        assertEquals(KitchenScreen.VIDEO_WORK, waiting.copy(isLoading = true,
            importProgress = VideoImportProgress(VideoImportStage.PREPARING_VIDEO)).kitchenScreen())
        assertEquals(KitchenScreen.COOKING, signedIn.copy(recipe = RECIPE, isCooking = true).kitchenScreen())
        assertEquals(KitchenScreen.LOADING, signedIn.copy(pendingUrl = RECIPE.sourceUrl, isLoading = true).kitchenScreen())
    }

    private companion object {
        const val YOUTUBE_WATCH = "https://www.youtube.com/watch?v=AbCdEfGhIjK"
        const val PHONE_VIDEO = "hansul-video:0f8fad5b-d9cb-469f-a165-70867728950e"
        val RECIPE = Recipe("r", "https://www.10000recipe.com/recipe/1", "제목", "2", emptyList(), emptyList())
    }
}
