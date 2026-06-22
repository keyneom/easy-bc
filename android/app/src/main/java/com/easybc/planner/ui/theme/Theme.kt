package com.easybc.planner.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Action colors — consistent in light and dark
val ActionUnprotected = Color(0xFF4CAF50)
val ActionUnprotectedBg = Color(0xFFE8F5E9)
val ActionWithdrawal = Color(0xFFCDDC39)
val ActionWithdrawalBg = Color(0xFFF9FBE7)
val ActionCondom = Color(0xFFFF9800)
val ActionCondomBg = Color(0xFFFFF3E0)
val ActionAbstain = Color(0xFFEF5350)
val ActionAbstainBg = Color(0xFFFFEBEE)
val PeriodColor = Color(0xFFC2185B)
val PeriodBg = Color(0xFFFCE4EC)
val FertileColor = Color(0xFF7B1FA2)
val FertileBg = Color(0xFFF3E5F5)

// Dark-mode action backgrounds
val ActionUnprotectedBgDark = Color(0xFF1B5E20).copy(alpha = 0.3f)
val ActionCondomBgDark = Color(0xFFE65100).copy(alpha = 0.3f)
val ActionAbstainBgDark = Color(0xFFB71C1C).copy(alpha = 0.3f)
val ActionWithdrawalBgDark = Color(0xFF827717).copy(alpha = 0.3f)
val PeriodBgDark = Color(0xFF880E4F).copy(alpha = 0.3f)
val FertileBgDark = Color(0xFF4A148C).copy(alpha = 0.3f)

val RiskLow = Color(0xFF66BB6A)
val RiskMedium = Color(0xFFFFA726)
val RiskHigh = Color(0xFFEF5350)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00897B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00251E),
    secondary = Color(0xFF5C6BC0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC5CAE9),
    tertiary = Color(0xFFFFB300),
    onTertiary = Color.Black,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFBDBDBD),
    error = Color(0xFFD32F2F),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFF9FA8DA),
    onSecondary = Color(0xFF1A237E),
    secondaryContainer = Color(0xFF303F9F),
    tertiary = Color(0xFFFFD54F),
    onTertiary = Color(0xFF3E2723),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF616161),
    error = Color(0xFFEF5350),
)

val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp),
)

@Composable
fun EasyBCTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}

/** Return the appropriate background color for a recommended action. */
@Composable
fun actionBackgroundColor(action: com.easybc.planner.data.RecommendedAction, dark: Boolean = isSystemInDarkTheme()): Color =
    if (dark) {
        when (action) {
            com.easybc.planner.data.RecommendedAction.U -> ActionUnprotectedBgDark
            com.easybc.planner.data.RecommendedAction.W -> ActionWithdrawalBgDark
            com.easybc.planner.data.RecommendedAction.C -> ActionCondomBgDark
            com.easybc.planner.data.RecommendedAction.A -> ActionAbstainBgDark
        }
    } else {
        when (action) {
            com.easybc.planner.data.RecommendedAction.U -> ActionUnprotectedBg
            com.easybc.planner.data.RecommendedAction.W -> ActionWithdrawalBg
            com.easybc.planner.data.RecommendedAction.C -> ActionCondomBg
            com.easybc.planner.data.RecommendedAction.A -> ActionAbstainBg
        }
    }

fun actionForegroundColor(action: com.easybc.planner.data.RecommendedAction): Color =
    when (action) {
        com.easybc.planner.data.RecommendedAction.U -> ActionUnprotected
        com.easybc.planner.data.RecommendedAction.W -> ActionWithdrawal
        com.easybc.planner.data.RecommendedAction.C -> ActionCondom
        com.easybc.planner.data.RecommendedAction.A -> ActionAbstain
    }

fun riskColor(score: Int): Color = when {
    score <= 30 -> RiskLow
    score <= 65 -> RiskMedium
    else -> RiskHigh
}
