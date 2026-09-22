package com.waterdistrict.meterreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's one theme. Every screen reads its colours, type and shapes from
 * here — `MaterialTheme.colorScheme`, `MaterialTheme.typography`,
 * [AppTheme.status] — rather than defining its own, so a change of palette is
 * a change to this file and nothing else.
 */

private val LightColors = lightColorScheme(
    primary = BrandBlue800,
    onPrimary = Color.White,
    primaryContainer = BrandBlue50,
    onPrimaryContainer = BrandBlue900,
    secondary = BrandTeal700,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F1F3),
    onSecondaryContainer = Color(0xFF003C41),
    tertiary = BrandGreen700,
    onTertiary = Color.White,
    tertiaryContainer = SuccessContainerLight,
    onTertiaryContainer = OnSuccessContainerLight,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    // Material3 tints elevated surfaces from these when they are left unset,
    // using its stock purple palette. Set explicitly so every card, sheet and
    // bar stays in the same cool grey family.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FC),
    surfaceContainer = Color(0xFFF1F4F8),
    surfaceContainerHigh = Color(0xFFEBEFF4),
    surfaceContainerHighest = Color(0xFFE4E9F0),
    surfaceDim = Color(0xFFDCE1E9),
    surfaceBright = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlue700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF12305E),
    onPrimaryContainer = BrandBlue200,
    secondary = BrandTeal300,
    onSecondary = Color(0xFF00363B),
    secondaryContainer = Color(0xFF0F3B40),
    onSecondaryContainer = Color(0xFFB9EEF2),
    tertiary = BrandGreen300,
    onTertiary = Color(0xFF0B3A16),
    tertiaryContainer = SuccessContainerDark,
    onTertiaryContainer = OnSuccessContainerDark,
    background = SurfaceDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceCard,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    outline = Color(0xFF3B4D5E),
    outlineVariant = Color(0xFF2A3A49),
    error = ErrorDark,
    onError = Color(0xFF3B0A0A),
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    surfaceContainerLowest = Color(0xFF0A131B),
    surfaceContainerLow = Color(0xFF131E29),
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerHighest = Color(0xFF2A3B4B),
    surfaceDim = SurfaceDark,
    surfaceBright = Color(0xFF2A3B4B),
)

/** Success and warning, which Material3's ColorScheme has no slots for. */
@Immutable
data class StatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightStatus = StatusColors(
    success = SuccessLight,
    successContainer = SuccessContainerLight,
    onSuccessContainer = OnSuccessContainerLight,
    warning = WarningLight,
    warningContainer = WarningContainerLight,
    onWarningContainer = OnWarningContainerLight,
)

private val DarkStatus = StatusColors(
    success = SuccessDark,
    successContainer = SuccessContainerDark,
    onSuccessContainer = OnSuccessContainerDark,
    warning = WarningDark,
    warningContainer = WarningContainerDark,
    onWarningContainer = OnWarningContainerDark,
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

object AppTheme {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}

/**
 * Type scale. Slightly larger and heavier than Material's defaults at the
 * sizes readers actually read at arm's length — labels, body and titles — and
 * with tabular figures everywhere, so columns of pesos and cubic metres line up
 * digit under digit.
 */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.merge(Tabular).copy(fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.merge(Tabular).copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.merge(Tabular).copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.merge(Tabular).copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.merge(Tabular).copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        titleSmall = base.titleSmall.merge(Tabular).copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        bodyLarge = base.bodyLarge.merge(Tabular).copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.merge(Tabular).copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = base.bodySmall.merge(Tabular).copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.merge(Tabular).copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.merge(Tabular).copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.merge(Tabular).copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** How much "Larger text" in Settings enlarges every piece of text. */
private const val LARGE_TEXT_SCALE = 1.15f

@Composable
fun MeterReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    largeText: Boolean = false,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val scaledDensity = if (largeText) {
        Density(density.density, fontScale = density.fontScale * LARGE_TEXT_SCALE)
    } else {
        density
    }

    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
