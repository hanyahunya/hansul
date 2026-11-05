package com.hansul.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.hansul.app.ui.HansulApp
import com.hansul.app.ui.RecipeImportViewModel
import com.hansul.app.ui.theme.HansulTheme

class MainActivity : ComponentActivity() {
    private val viewModel: RecipeImportViewModel by viewModels {
        RecipeImportViewModel.factory(
            repository = (application as HansulApplication).container.recipeRepository,
            authRepository = (application as HansulApplication).container.authRepository,
            cookingSessionRepository =
                (application as HansulApplication).container.cookingSessionRepository,
            executionMode = (application as HansulApplication).container.executionMode,
            changeExecutionMode = (application as HansulApplication).container::setExecutionMode,
            videoIntake = (application as HansulApplication).container.videoLibrary,
            videoUploadConsent = (application as HansulApplication).container.videoUploadConsent,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity receives the same share again; the ViewModel already has it (a video would be copied twice).
        if (savedInstanceState == null) handleShareIntent(intent)

        setContent {
            HansulTheme {
                HansulApp(viewModel = viewModel,
                    localModelManager = (application as HansulApplication).container.localModelManager)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        when {
            type == "text/plain" -> viewModel.acceptSharedText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            type.startsWith("video/") -> (IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)
                ?.let { viewModel.acceptVideoUri(it.toString()) }
        }
    }
}
