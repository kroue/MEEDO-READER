package com.waterdistrict.meterreader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand palette — matched to the South Wao Water System logo: a royal-blue
 * ring and droplet on white, with a grass-green band. The admin console uses
 * the same blue for its primary colour, so the two apps read as one product.
 *
 * The app is light by default. It is used outdoors, often in direct sun, and a
 * dark screen in sunlight turns into a mirror; dark text on a white surface is
 * the combination that stays legible there. The dark palette below is for
 * readers who choose it in Settings, or set the app to follow their phone.
 */

// Blue — primary brand colour (logo ring and droplet)
val BrandBlue900 = Color(0xFF0B3C8C) // deepest navy: headers, pressed states
val BrandBlue800 = Color(0xFF1456B8) // primary on light surfaces
val BrandBlue700 = Color(0xFF1C6FD9) // primary on dark surfaces
val BrandBlue200 = Color(0xFF9CC3FF) // text and icons on dark blue
val BrandBlue50 = Color(0xFFE8F0FE)  // tinted fills on light surfaces

// Teal — secondary accent
val BrandTeal700 = Color(0xFF00737C)
val BrandTeal300 = Color(0xFF5FD4DE)

// Green — the logo's hills, and the app's "done / uploaded" colour
val BrandGreen700 = Color(0xFF1E7B34)
val BrandGreen300 = Color(0xFF7FD68F)

// ── Light surfaces ───────────────────────────────────────────────────────────
val LightBackground = Color(0xFFF3F5F9)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE9EDF4)
val LightOnSurface = Color(0xFF111B2B)
val LightOnSurfaceVariant = Color(0xFF4B5667)
val LightOutline = Color(0xFFC2CAD6)
val LightOutlineVariant = Color(0xFFDCE2EA)

// ── Dark surfaces ────────────────────────────────────────────────────────────
val SurfaceDark = Color(0xFF0E1822)
val SurfaceCard = Color(0xFF17232F)
val SurfaceElevated = Color(0xFF21313F)
val TextPrimaryDark = Color(0xFFEAF0F5)
val TextSecondaryDark = Color(0xFF9AABB8)

// ── Status: success / warning / error, in both themes ───────────────────────
// Material3's ColorScheme has an error slot but no success or warning, and
// every screen needs all three — an uploaded reading, an overdue balance, a
// printer that failed. They live in AppStatusColors (Theme.kt) so each screen
// asks for "warning" rather than picking its own amber.
val SuccessLight = Color(0xFF1E7B34)
val SuccessContainerLight = Color(0xFFDDF3E1)
val OnSuccessContainerLight = Color(0xFF0B3A16)
val WarningLight = Color(0xFF9A5B00)
val WarningContainerLight = Color(0xFFFFEFD1)
val OnWarningContainerLight = Color(0xFF4A2A00)
val ErrorLight = Color(0xFFC0272D)
val ErrorContainerLight = Color(0xFFFDE4E4)
val OnErrorContainerLight = Color(0xFF6E1115)

val SuccessDark = Color(0xFF7FD68F)
val SuccessContainerDark = Color(0xFF12351C)
val OnSuccessContainerDark = Color(0xFFBDEFC6)
val WarningDark = Color(0xFFFFC266)
val WarningContainerDark = Color(0xFF3D2A07)
val OnWarningContainerDark = Color(0xFFFFE2B3)
val ErrorDark = Color(0xFFFF8A80)
val ErrorContainerDark = Color(0xFF4C1E1E)
val OnErrorContainerDark = Color(0xFFFFDAD6)
