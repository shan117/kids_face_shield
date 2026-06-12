package com.shantanu.shield.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,
    secondary = BrandSecondary,
    onSecondary = BrandOnSecondary,
    secondaryContainer = BrandSecondaryContainer,
    onSecondaryContainer = BrandOnSecondaryContainer,
    tertiary = BrandTertiary,
    onTertiary = BrandOnTertiary,
    tertiaryContainer = BrandTertiaryContainer,
    onTertiaryContainer = BrandOnTertiaryContainer,
    error = BrandError,
    onError = BrandOnError,
    errorContainer = BrandErrorContainer,
    onErrorContainer = BrandOnErrorContainer,
    background = BrandBackground,
    onBackground = BrandOnBackground,
    surface = BrandSurface,
    onSurface = BrandOnSurface,
    surfaceVariant = BrandSurfaceVariant,
    onSurfaceVariant = BrandOnSurfaceVariant,
    outline = BrandOutline,
    outlineVariant = BrandOutlineVariant,
    surfaceContainer = BrandSurfaceContainer,
    surfaceContainerHigh = BrandSurfaceContainerHigh,
    surfaceContainerHighest = BrandSurfaceContainerHighest
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = BrandOnPrimaryDark,
    primaryContainer = BrandPrimaryContainerDark,
    onPrimaryContainer = BrandOnPrimaryContainerDark,
    secondary = BrandSecondaryDark,
    onSecondary = BrandOnSecondaryDark,
    secondaryContainer = BrandSecondaryContainerDark,
    onSecondaryContainer = BrandOnSecondaryContainerDark,
    tertiary = BrandTertiaryDark,
    onTertiary = BrandOnTertiaryDark,
    tertiaryContainer = BrandTertiaryContainerDark,
    onTertiaryContainer = BrandOnTertiaryContainerDark,
    error = BrandError,
    onError = BrandOnError,
    errorContainer = BrandErrorContainer,
    onErrorContainer = BrandOnErrorContainer,
    background = BrandBackgroundDark,
    onBackground = BrandOnBackgroundDark,
    surface = BrandSurfaceDark,
    onSurface = BrandOnSurfaceDark,
    surfaceVariant = BrandSurfaceVariantDark,
    onSurfaceVariant = BrandOnSurfaceVariantDark,
    outline = BrandOutlineDark,
    outlineVariant = BrandOutlineVariantDark,
    surfaceContainer = BrandSurfaceContainerDark,
    surfaceContainerHigh = BrandSurfaceContainerHighDark,
    surfaceContainerHighest = BrandSurfaceContainerHighestDark
)

/** Premium accent themes. TEAL is the default brand look (no override). */
enum class AppAccent(
    val key: String,
    val label: String,
    val primaryLight: Long, val containerLight: Long,
    val primaryDark: Long, val containerDark: Long
) {
    TEAL("teal", "Teal", 0xFF006C7F, 0xFFA8EDF8, 0xFF54D7EC, 0xFF00404B),
    PURPLE("purple", "Purple", 0xFF6750A4, 0xFFEADDFF, 0xFFD0BCFF, 0xFF4F378B),
    CORAL("coral", "Coral", 0xFFB1382F, 0xFFFFDAD5, 0xFFFFB4AB, 0xFF8C1D18),
    GREEN("green", "Green", 0xFF36693C, 0xFFB8F1B9, 0xFF9DD49E, 0xFF1E5128);

    companion object {
        fun fromKey(key: String): AppAccent = values().firstOrNull { it.key == key } ?: TEAL
    }
}

@Composable
fun AppShieldTheme(
    accent: AppAccent = AppAccent.TEAL,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val base = if (darkTheme) DarkColors else LightColors
    // TEAL keeps the exact brand palette; other accents recolor the primary surfaces.
    val colorScheme = if (accent == AppAccent.TEAL) base else base.copy(
        primary = Color(if (darkTheme) accent.primaryDark else accent.primaryLight),
        onPrimary = if (darkTheme) Color.Black else Color.White,
        primaryContainer = Color(if (darkTheme) accent.containerDark else accent.containerLight),
        onPrimaryContainer = if (darkTheme) Color.White else Color(0xFF071A1F)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
