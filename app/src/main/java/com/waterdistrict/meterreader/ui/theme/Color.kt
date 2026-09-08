package com.waterdistrict.meterreader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand palette — matched to the South Wao Water System logo (a royal-blue
 * ring and droplet on white, with a grass-green band), extended from the
 * dark navy/teal palette [ui.reading.ReadingEntryScreen] already established
 * before this file existed. The admin console (water-billing-admin) uses the
 * same blue family for its own primary color, so both apps read as one
 * product even though one is a light web console and this one is a dark
 * field app.
 */

// Blue — primary brand color (logo ring/droplet)
val BrandBlue900 = Color(0xFF0D47A1) // deepest navy, decorative gradients
val BrandBlue800 = Color(0xFF1565C0) // logo ring blue
val BrandBlue700 = Color(0xFF1976D2) // primary interactive blue
val BrandBlue200 = Color(0xFF90CAF9) // light blue, for text/icons on dark surfaces

// Teal — secondary accent (already established as the app's interactive highlight)
val BrandTeal500 = Color(0xFF00BCD4)
val BrandTeal300 = Color(0xFF4DD0E1)

// Green — from the logo's hill illustration and "WAO LANAO DEL SUR" band;
// also already independently used by every screen as the "synced/read" color.
val BrandGreen500 = Color(0xFF4CAF50)
val BrandGreen300 = Color(0xFF81C784)

// Semantic — Material3's ColorScheme has no built-in "warning" slot, so this
// is exposed as a plain constant rather than a theme color. Previously two
// different screens used two different ambers for the same "overdue" concept
// (ReadingEntryScreen 0xFFFF9800, DigitalBillScreen 0xFFB26A00) — unified here.
val WarningAmber = Color(0xFFFFA726)
val ErrorRed = Color(0xFFF44336)

// Dark surfaces — from ReadingEntryScreen's original palette
val SurfaceDark = Color(0xFF0F1923)
val SurfaceCard = Color(0xFF1C2C3A)
val SurfaceElevated = Color(0xFF243447)
val TextPrimaryDark = Color(0xFFECF0F1)
val TextSecondaryDark = Color(0xFF90A4AE)
