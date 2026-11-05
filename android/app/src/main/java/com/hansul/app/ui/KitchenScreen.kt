package com.hansul.app.ui

import com.hansul.app.data.ExecutionMode
import com.hansul.app.share.RecipeUrlParser

/** What [HansulApp] shows for a state; the first case that applies wins. */
internal enum class KitchenScreen { COOKING, VIDEO_WORK, CHECKING_ACCOUNT, WELCOME, VIDEO_LOGIN, VIDEO_CONSENT, LOADING, RECIPE, ERROR }

internal fun RecipeImportUiState.kitchenScreen(): KitchenScreen = when {
    isCooking && recipe != null -> KitchenScreen.COOKING
    // A video copy can start before the account is known, so it goes ahead of the login screens.
    isVideoWork() -> KitchenScreen.VIDEO_WORK
    executionMode == ExecutionMode.CLOUD && isAuthInitializing -> KitchenScreen.CHECKING_ACCOUNT
    executionMode == ExecutionMode.CLOUD && !isAuthenticated -> KitchenScreen.WELCOME
    loginRequiredForVideo && isAuthInitializing -> KitchenScreen.CHECKING_ACCOUNT
    loginRequiredForVideo -> KitchenScreen.VIDEO_LOGIN
    consentRequiredForVideo -> KitchenScreen.VIDEO_CONSENT
    isLoading -> KitchenScreen.LOADING
    recipe != null -> KitchenScreen.RECIPE
    errorMessage != null -> KitchenScreen.ERROR
    else -> KitchenScreen.WELCOME
}

/** Copying, uploading and analysing a video; the stage can be briefly unknown between the copy and the import. */
internal fun RecipeImportUiState.isVideoWork(): Boolean =
    isLoading && (importProgress != null || pendingUrl?.let(RecipeUrlParser::requiresServerAnalysis) == true)

/**
 * Only the error screen shows [RecipeImportUiState.errorMessage] itself. Everywhere else (sign-in, video login,
 * loading, recipe) it goes to the snackbar, instead of waiting unseen and popping up on some later screen.
 */
internal fun RecipeImportUiState.snackbarError(): String? = errorMessage?.takeIf { kitchenScreen() != KitchenScreen.ERROR }
