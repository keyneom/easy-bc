package com.easybc.planner.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/*
 * Brand palette, sampled from the launcher logo:
 *   pink #FFB6CC · blush #FFE0EC · raspberry #7B2D5F · plum #3E1E3C
 * Tokens are documented in docs/ui-kit.md and mirrored in web/src/ui/tokens.css.
 * Keep the two token sources in sync when editing.
 */
val BrandPink = Color(0xFFFFB6CC)
val BrandBlush = Color(0xFFFFE0EC)
val BrandRaspberry = Color(0xFF7B2D5F)
val BrandPlum = Color(0xFF3E1E3C)

/** User-facing theme preference; SYSTEM follows the OS setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Whether the app is currently rendering its dark theme. Unlike
 * isSystemInDarkTheme() this respects an explicit in-app ThemeMode override,
 * so indicator helpers (period/action backgrounds) stay in step with it.
 */
val LocalEbDarkTheme = staticCompositionLocalOf { false }

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
    primary = BrandRaspberry,
    onPrimary = Color.White,
    primaryContainer = BrandBlush,
    onPrimaryContainer = BrandPlum,
    secondary = Color(0xFF915F7D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7DEEA),
    onSecondaryContainer = Color(0xFF3B2231),
    tertiary = Color(0xFFA8702D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3C3),
    onTertiaryContainer = Color(0xFF4A2E06),
    background = Color(0xFFFBF7F9),
    onBackground = Color(0xFF241A21),
    surface = Color.White,
    onSurface = Color(0xFF241A21),
    surfaceVariant = Color(0xFFF4EBF0),
    onSurfaceVariant = Color(0xFF6E5F68),
    outline = Color(0xFFC9B9C2),
    outlineVariant = Color(0xFFEADFE5),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBE7E6),
    onErrorContainer = Color(0xFF5F1412),
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandPink,
    onPrimary = Color(0xFF4B1032),
    primaryContainer = Color(0xFF5C2246),
    onPrimaryContainer = BrandBlush,
    secondary = Color(0xFFDBB6CB),
    onSecondary = Color(0xFF402A37),
    secondaryContainer = Color(0xFF58414E),
    onSecondaryContainer = Color(0xFFF7DEEA),
    tertiary = Color(0xFFE4B87C),
    onTertiary = Color(0xFF402C10),
    tertiaryContainer = Color(0xFF5C4423),
    onTertiaryContainer = Color(0xFFFFE3C3),
    background = Color(0xFF1B1319),
    onBackground = Color(0xFFF2E7EC),
    surface = Color(0xFF241A21),
    onSurface = Color(0xFFF2E7EC),
    surfaceVariant = Color(0xFF2F2229),
    onSurfaceVariant = Color(0xFFC6B3BC),
    outline = Color(0xFF907E88),
    outlineVariant = Color(0xFF3C2E35),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF4A1210),
    errorContainer = Color(0xFF4A2222),
    onErrorContainer = Color(0xFFF2B8B5),
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
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalEbDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/** Return the appropriate background color for a recommended action. */
@Composable
fun actionBackgroundColor(
    action: com.easybc.planner.data.RecommendedAction,
    dark: Boolean = LocalEbDarkTheme.current,
): Color =
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
