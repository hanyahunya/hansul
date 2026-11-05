package com.hansul.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFFB9462E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE9DB),
    onPrimaryContainer = Color(0xFF78301F),
    secondary = Color(0xFF626D52),
    secondaryContainer = Color(0xFFF1F2E8),
    onSecondaryContainer = Color(0xFF3E4932),
    tertiary = Color(0xFF84632E),
    tertiaryContainer = Color(0xFFFFF0D5),
    onTertiaryContainer = Color(0xFF60451C),
    background = Color(0xFFFFFBF6),
    surface = Color(0xFFFFFBF6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF8F0E8),
    surfaceContainerHigh = Color(0xFFF1E5DA),
    onSurface = Color(0xFF30231E),
    onSurfaceVariant = Color(0xFF7C6B61),
    outline = Color(0xFFAD978A),
    outlineVariant = Color(0xFFEBDDD3),
    error = Color(0xFFAA3827),
    errorContainer = Color(0xFFFFEBE4),
    onErrorContainer = Color(0xFF702B1E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9D0B9),
    onPrimary = Color(0xFF15392A),
    primaryContainer = Color(0xFF2D4C3D),
    onPrimaryContainer = Color(0xFFD4EDDC),
    secondary = Color(0xFFD9C7A7),
    secondaryContainer = Color(0xFF3F3C30),
    onSecondaryContainer = Color(0xFFEDE4D1),
    background = Color(0xFF181D18),
    surface = Color(0xFF181D18),
    surfaceContainerLow = Color(0xFF222922),
    surfaceContainer = Color(0xFF282F28),
    surfaceContainerHigh = Color(0xFF30382F),
    surfaceVariant = Color(0xFF343D32),
    onSurface = Color(0xFFE7EBE1),
    onSurfaceVariant = Color(0xFFBBC5B6),
    outline = Color(0xFF859180),
    outlineVariant = Color(0xFF404C3C),
    tertiaryContainer = Color(0xFF513624),
    onTertiaryContainer = Color(0xFFFFDCC2),
)

private val KitchenTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 39.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun HansulTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KitchenTypography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
