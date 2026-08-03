package com.commute.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** The [Activity] behind a Compose view's context, unwrapping however many [ContextWrapper]s the
 * platform layered on. Returns null rather than throwing, so a theme hosted somewhere without an
 * activity (a preview, a test) simply skips the system-bar tinting instead of crashing. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Role assignments, so the mapping is in one place instead of inferred from call sites:
 *
 * - `primary`   근무 (verdigris) — the weekly bar's fill, 출근, the working status card
 * - `secondary` 연차·외출·퇴근 (slate) — declared, deliberate, cool and quiet
 * - `tertiary`  초과근무·이석 (brass) — warm, meaning "more than planned"
 * - `error`     휴일·오류 (oxide)
 *
 * 점심 is deliberately *not* a scheme role: it's the chart's grey gap and lives in [ExcludedLight]
 * / [ExcludedDark]. It used to borrow `tertiary`, which collided with the 이석 status card — one
 * role trying to be both "a gap that doesn't count" and "something warm to look at".
 */
private val DarkColorScheme = darkColorScheme(
    primary = CobaltBright,
    onPrimary = Color(0xFF00214D),
    primaryContainer = CobaltContainerDark,
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = SteelBright,
    onSecondary = Color(0xFF1A2537),
    secondaryContainer = SteelContainerDark,
    onSecondaryContainer = Color(0xFFD5DEF0),
    tertiary = GoldBright,
    onTertiary = Color(0xFF3B2A00),
    tertiaryContainer = GoldContainerDark,
    onTertiaryContainer = Color(0xFFFFE7A3),
    error = EmberBright,
    onError = Color(0xFF4E1508),
    errorContainer = EmberContainerDark,
    onErrorContainer = Color(0xFFFFDAD3),
    background = Midnight,
    onBackground = PaperOnDark,
    surface = Midnight,
    onSurface = PaperOnDark,
    surfaceVariant = MidnightVariant,
    onSurfaceVariant = Color(0xFFC3C9D4),
    outline = Color(0xFF78808F),
    outlineVariant = Color(0xFF333B47)
)

private val LightColorScheme = lightColorScheme(
    primary = Cobalt,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = CobaltContainer,
    onPrimaryContainer = Color(0xFF001849),
    secondary = Steel,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = SteelContainer,
    onSecondaryContainer = Color(0xFF0D1930),
    tertiary = Gold,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = GoldContainer,
    onTertiaryContainer = Color(0xFF2A1800),
    error = Ember,
    onError = Color(0xFFFFFFFF),
    errorContainer = EmberContainer,
    onErrorContainer = Color(0xFF410002),
    background = Paper,
    onBackground = InkOnPaper,
    surface = Paper,
    onSurface = InkOnPaper,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = Color(0xFF434A57),
    outline = Color(0xFF737B88),
    outlineVariant = Color(0xFFC3C7D2)
)

/**
 * @param dynamicColor off by default. Material You would repaint the whole app from the user's
 * wallpaper, which meant Commute had no colour of its own — every device showed a different app,
 * and none of them matched the Play Store screenshots. Worse for this app than for most: 근무 /
 * 초과 / 이석 are told apart by hue, and wallpaper-derived hues don't guarantee that separation.
 */
@Composable
fun CommuteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // The app draws behind the status and navigation bars (enableEdgeToEdge), so the system's own
    // clock, battery and gesture bar sit directly on top of this theme's background. Their tint
    // otherwise follows the *device's* light/dark setting, not this one — so a phone in dark mode
    // with the app set to 밝게 drew white icons on a white surface, invisible. Tie the tint to the
    // theme actually being rendered instead. SideEffect, not LaunchedEffect: this writes to a
    // window property and should re-run on every successful recomposition that changes darkTheme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
