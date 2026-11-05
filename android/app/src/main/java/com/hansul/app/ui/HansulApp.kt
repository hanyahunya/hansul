package com.hansul.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hansul.app.ai.LocalModelManager
import com.hansul.app.data.ExecutionMode
import com.hansul.app.voice.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HansulApp(
    viewModel: RecipeImportViewModel,
    localModelManager: LocalModelManager? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val timerScheduler = remember(context) { CookingTimerScheduler(context) }
    val voiceController = remember(viewModel, context, state.executionMode) {
        VoiceCookingController(context, viewModel::submitVoiceTranscript, viewModel::reportVoiceError,
            requireOnDevice = state.executionMode == ExecutionMode.LOCAL)
    }
    val voiceState by voiceController.state.collectAsStateWithLifecycle()
    var permissionRevision by remember { mutableIntStateOf(0) }
    var enableVoiceAfterPermission by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val authorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.completeSignIn(it.data)
    }
    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && localModelManager != null) scope.launch {
            try { localModelManager.importModel(uri) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* manager exposes the failure */ }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
        if (enableVoiceAfterPermission) {
            enableVoiceAfterPermission = false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                viewModel.setVoiceEnabled(true)
            else viewModel.reportVoiceError("마이크 권한 없이도 터치나 글로 요리할 수 있어요.")
        }
    }
    val alarmPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionRevision++ }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.acceptVideoUri(uri.toString())
    }
    val screen = state.kitchenScreen()
    DisposableEffect(lifecycleOwner, voiceController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) voiceController.onForegroundChanged(true)
            if (event == Lifecycle.Event.ON_STOP) voiceController.onForegroundChanged(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); voiceController.destroy() }
    }
    val activity = context as? Activity
    val keepScreenOn = state.isCooking || screen == KitchenScreen.VIDEO_WORK
    DisposableEffect(activity, keepScreenOn) {
        if (keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (keepScreenOn) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(state.isVoiceEnabled, voiceController) {
        if (state.isVoiceEnabled) voiceController.enable() else voiceController.disable()
    }
    LaunchedEffect(viewModel) {
        viewModel.authorizationIntents.collect { authorizationLauncher.launch(it) }
    }
    LaunchedEffect(viewModel, voiceController) {
        viewModel.voiceEffects.collect {
            when (it) {
                is VoiceEffect.Speak -> voiceController.speak(it.text)
                VoiceEffect.ResumeListening -> voiceController.resumeListening()
            }
        }
    }
    LaunchedEffect(state.pendingDeviceTimers, permissionRevision) {
        val timer = state.pendingDeviceTimers.firstOrNull { it.stage == TimerScheduleStage.NEEDS_DEVICE_SCHEDULE }
            ?: return@LaunchedEffect
        val sessionId = state.cookingSession?.id ?: return@LaunchedEffect
        when (val result = timerScheduler.schedule(sessionId, timer.cueId, timer.durationSeconds, timer.clientAckId)) {
            TimerScheduleResult.Scheduled -> viewModel.onDeviceTimerScheduled(timer.cueId,
                timerScheduler.savedDeadline(sessionId, timer.cueId), timerScheduler.savedAcknowledgementId(sessionId, timer.cueId))
            is TimerScheduleResult.Failed -> viewModel.reportVoiceError(result.message)
            is TimerScheduleResult.PermissionRequired -> viewModel.reportVoiceError(
                if (result.readiness == TimerReadiness.NEEDS_NOTIFICATION_PERMISSION)
                    "타이머가 울리려면 알림 권한이 필요해요." else "타이머를 설정하려면 알람 권한이 필요해요.")
        }
    }
    LaunchedEffect(state.cookingSession?.id, state.timerDeadlines) {
        val sessionId = state.cookingSession?.id ?: return@LaunchedEffect
        viewModel.reconcileDeviceTimerDeadlines(state.timerDeadlines.mapNotNull { timerScheduler.savedDeadline(sessionId, it.timerCueId) })
    }
    LaunchedEffect(permissionRevision) {
        when (val restored = timerScheduler.restoreScheduledAlarms()) {
            is TimerScheduleResult.Failed -> viewModel.reportVoiceError(restored.message)
            is TimerScheduleResult.PermissionRequired -> viewModel.reportVoiceError("저장된 타이머를 복원하려면 알림·알람 권한을 확인해주세요.")
            TimerScheduleResult.Scheduled -> Unit
        }
    }
    fun enableVoice() {
        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isEmpty()) viewModel.setVoiceEnabled(true)
        else { enableVoiceAfterPermission = true; permissionLauncher.launch(permissions.toTypedArray()) }
    }
    fun pickVideo() = videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    fun requestTimerPermission() {
        when (timerScheduler.readiness()) {
            TimerReadiness.READY -> permissionRevision++
            TimerReadiness.NEEDS_NOTIFICATION_PERMISSION -> {
                enableVoiceAfterPermission = false
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            TimerReadiness.NEEDS_EXACT_ALARM_PERMISSION -> {
                val intent = timerScheduler.exactAlarmPermissionIntent()
                if (intent != null) alarmPermissionLauncher.launch(intent)
            }
        }
    }
    val snackbarError = state.snackbarError()
    LaunchedEffect(snackbarError) {
        val message = snackbarError ?: return@LaunchedEffect
        try {
            snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
        } finally {
            // Also when the screen changes under it: a share rejected before signing in must not reappear afterwards.
            viewModel.dismissError(message)
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    if (state.isCooking) Column {
                        Text("요리하기", style = MaterialTheme.typography.titleMedium)
                        Text(if (state.executionMode == ExecutionMode.LOCAL) "기기 내 AI" else "클라우드 AI",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KitchenLogo(Modifier.size(24.dp))
                        Text("한술", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                },
                navigationIcon = {
                    if (state.recipe != null) IconButton(
                        onClick = if (state.isCooking) viewModel::showRecipePreview else viewModel::clearRecipe,
                        modifier = Modifier.semantics { contentDescription = if (state.isCooking) "레시피 보기" else "다른 레시피 가져오기" },
                    ) { KitchenGlyphIcon(KitchenGlyph.BACK) }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }, modifier = Modifier.semantics { contentDescription = "설정" }) {
                        KitchenGlyphIcon(KitchenGlyph.SETTINGS)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                KitchenScreen.COOKING -> KitchenCookingScreen(
                    state, voiceState, viewModel::showStep, viewModel::updateCurrentStep,
                    viewModel::submitTextQuestion, ::enableVoice, { viewModel.setVoiceEnabled(false) },
                    voiceController::resumeListening, viewModel::retryFailedVoiceTurn,
                    ::requestTimerPermission, viewModel::retryTimerAcknowledgement, viewModel::dismissVoiceError,
                    viewModel::startSourceTimer, viewModel::showRecipePreview)
                KitchenScreen.VIDEO_WORK -> KitchenLoading(state.importProgress?.stageLabel() ?: "영상 준비 중",
                    "영상 분석은 1~3분 정도 걸려요. 화면을 켜 둔 채 기다려 주세요.",
                    state.importProgress?.fraction, onCancel = viewModel::clearRecipe)
                KitchenScreen.CHECKING_ACCOUNT -> KitchenLoading("계정을 확인하고 있어요")
                KitchenScreen.VIDEO_LOGIN -> KitchenVideoLogin(state, viewModel::signIn, viewModel::clearRecipe)
                KitchenScreen.VIDEO_CONSENT -> KitchenVideoConsent(viewModel::acceptVideoUpload, viewModel::clearRecipe)
                KitchenScreen.LOADING -> KitchenLoading("레시피를 준비하고 있어요", "원문 재료와 순서, 사진을 읽는 중이에요.")
                KitchenScreen.RECIPE -> KitchenRecipeScreen(state, viewModel::decreaseServings,
                    viewModel::increaseServings, viewModel::startCooking,
                    viewModel::askAboutRecipe)
                KitchenScreen.ERROR -> KitchenError(state.errorMessage.orEmpty(),
                    viewModel::retry.takeIf { state.pendingUrl != null }, viewModel::clearRecipe,
                    viewModel::signIn.takeIf { state.errorNeedsSignIn })
                KitchenScreen.WELCOME -> KitchenWelcome(state, viewModel::acceptSharedText, viewModel::signIn,
                    { viewModel.selectExecutionMode(ExecutionMode.LOCAL) }, ::pickVideo)
            }
        }
    }
    if (showSettings) KitchenSettingsSheet(
        state, localModelManager, onDismiss = { showSettings = false },
        onMode = viewModel::selectExecutionMode,
        onImportModel = { modelLauncher.launch(arrayOf("*/*")) },
        onDownloadModel = { if (localModelManager != null) scope.launch {
            try { localModelManager.downloadModel() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        } },
        onRemoveModel = { if (localModelManager != null) scope.launch {
            try { localModelManager.removeModel() } catch (e: CancellationException) { throw e } catch (e: Exception) { viewModel.reportVoiceError(e.message ?: "모델을 지우지 못했어요.") }
        } },
        onSignIn = viewModel::signIn, onSignOut = viewModel::logout,
    )
}
