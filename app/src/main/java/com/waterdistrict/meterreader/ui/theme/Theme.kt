package com.waterdistrict.meterreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The one Material3 [androidx.compose.material3.ColorScheme] for the whole
 * app. Previously there was no shared theme at all — MainActivity wrapped
 * everything in a bare `MaterialTheme { }`, so most screens rendered on
 * Compose's stock baseline purple while only ReadingEntryScreen opted out
 * with its own hand-rolled dark palette. This makes that palette the app's
 * actual theme, so every screen (via `MaterialTheme.colorScheme.*`) renders
 * consistently instead of two unrelated color languages side by side.
 */
private val MeterReaderDarkColorScheme = darkColorScheme(
    primary = BrandBlue700,
    onPrimary = Color.White,
    primaryContainer = BrandBlue900,
    onPrimaryContainer = BrandBlue200,
    secondary = BrandTeal500,
    onSecondary = Color.Black,
    secondaryContainer = SurfaceElevated,
    onSecondaryContainer = BrandTeal300,
    tertiary = BrandGreen500,
    onTertiary = Color.Black,
    tertiaryContainer = SurfaceElevated,
    onTertiaryContainer = BrandGreen300,
    background = SurfaceDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceCard,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFF4C1E1E),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = TextSecondaryDark,
    // Material3 derives TopAppBar/Card elevation tints from these surface
    // container tones when they're left unset, using its own stock
    // dark-purple tonal palette — which doesn't match this app's navy, and
    // shows up as a visibly mismatched gray band (e.g. behind TopAppBar).
    // Overriding the full ramp keeps every elevated surface in the same
    // navy family as `background`/`surface` above.
    surfaceContainerLowest = Color(0xFF0A141C),
    surfaceContainerLow = Color(0xFF162330),
    surfaceContainer = SurfaceElevated,
    surfaceContainerHigh = Color(0xFF2C3E52),
    surfaceContainerHighest = Color(0xFF34495E),
    surfaceDim = SurfaceDark,
    surfaceBright = Color(0xFF34495E),
)

@Composable
fun MeterReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeterReaderDarkColorScheme,
        content = content
    )
}
