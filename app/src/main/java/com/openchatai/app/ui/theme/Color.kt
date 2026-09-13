package com.openchatai.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Skema warna Material Design 3 lengkap (semua color role) yang diturunkan
 * dari palet tonal brand aplikasi (seed ungu #6C5CE7).
 *
 * Struktur mengikuti spesifikasi M3:
 *  - Palet tonal: 10/20/30/40/50/60/70/80/90/95/99 per hue
 *  - Peran warna: primary/onPrimary/primaryContainer/onPrimaryContainer,
 *    secondary..., tertiary..., error..., background/onBackground,
 *    surface + surfaceContainer (lowest/low/high/highest) + surfaceDim/Bright,
 *    surfaceVariant/onSurfaceVariant, outline/outlineVariant,
 *    inverseSurface/inverseOnSurface/inversePrimary, scrim.
 *
 * Kontras tiap pasangan foreground/background mengikuti target aksesibilitas
 * M3 (>= 4.5:1 untuk teks utama pada light scheme).

 * ===== Palet tonal — Ungu (primary, seed brand #6C5CE7) =====
 */
val Purple10 = Color(0xFF1D1180)
val Purple20 = Color(0xFF322A8F)
val Purple30 = Color(0xFF4A3FA9)
val Purple40 = Color(0xFF5F50DE)
val Purple50 = Color(0xFF6C5CE7)
val Purple60 = Color(0xFF8678F0)
val Purple70 = Color(0xFFA29BFE)
val Purple80 = Color(0xFFC4BCFF)
val Purple90 = Color(0xFFE4DEFF)
val Purple95 = Color(0xFFF1EDFF)
val Purple99 = Color(0xFFFDFAFF)

// ===== Palet tonal — Teal hijau (secondary, brand #00B894) =====
val Teal10 = Color(0xFF05372B)
val Teal20 = Color(0xFF235344)
val Teal30 = Color(0xFF3A6B5B)
val Teal40 = Color(0xFF4C8371)
val Teal80 = Color(0xFFB0CCC0)
val Teal90 = Color(0xFFCCDFD6)

// ===== Palet tonal — Peach hangat (tertiary, kontras dengan ungu) =====
val Peach10 = Color(0xFF371005)
val Peach20 = Color(0xFF5B2A1D)
val Peach30 = Color(0xFF764031)
val Peach40 = Color(0xFF8F5747)
val Peach80 = Color(0xFFFFB697)
val Peach90 = Color(0xFFFFDACE)
val Peach95 = Color(0xFFFFEDE6)

// ===== Palet tonal — Error (standar M3) =====
val Error10 = Color(0xFF410002)
val Error20 = Color(0xFF690005)
val Error40 = Color(0xFFBA1A1A)
val Error80 = Color(0xFFFFB4AB)
val Error90 = Color(0xFFFFDAD6)
val Error100 = Color(0xFFFFFFFF)

// ===== Netral (hue ungu sangat lembut) — light =====
val NeutralLight99 = Color(0xFFFCF9FF)
val NeutralLight95 = Color(0xFFF1EDFF)
val NeutralLight90 = Color(0xFFE5E0F2)
val NeutralLight80 = Color(0xFFC8C4D6)
val NeutralLight60 = Color(0xFF777487)
val NeutralLight50 = Color(0xFF5E5B6E)
val NeutralLight40 = Color(0xFF474554)
val NeutralLight30 = Color(0xFF302F3D)
val NeutralLight20 = Color(0xFF1B1B24)
val NeutralLight10 = Color(0xFF0D0E13)

// ===== Skema TERANG — semua peran M3 =====
val LightPrimary = Purple40
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Purple90
val LightOnPrimaryContainer = Purple10
val LightSecondary = Teal40
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Teal90
val LightOnSecondaryContainer = Teal10
val LightTertiary = Peach40
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Peach90
val LightOnTertiaryContainer = Peach10
val LightError = Error40
val LightOnError = Error100
val LightErrorContainer = Error90
val LightOnErrorContainer = Error10
val LightBackground = NeutralLight99
val LightOnBackground = NeutralLight20
val LightSurface = NeutralLight99
val LightOnSurface = NeutralLight20
val LightSurfaceVariant = NeutralLight90
val LightOnSurfaceVariant = NeutralLight40
val LightSurfaceDim = Color(0xFFDCD9E9)
val LightSurfaceBright = NeutralLight99
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF6F3FC)
val LightSurfaceContainer = Color(0xFFF0EDF7)
val LightSurfaceContainerHigh = Color(0xFFEAE7F1)
val LightSurfaceContainerHighest = Color(0xFFE4E2EB)
val LightOutline = NeutralLight60
val LightOutlineVariant = NeutralLight80
val LightInverseSurface = NeutralLight30
val LightInverseOnSurface = NeutralLight95
val LightInversePrimary = Purple80
val LightScrim = Color(0xFF000000)

// ===== Skema GELAP — semua peran M3 =====
val DarkPrimary = Purple80
val DarkOnPrimary = Purple20
val DarkPrimaryContainer = Purple30
val DarkOnPrimaryContainer = Purple90
val DarkSecondary = Teal80
val DarkOnSecondary = Teal20
val DarkSecondaryContainer = Teal30
val DarkOnSecondaryContainer = Teal90
val DarkTertiary = Peach80
val DarkOnTertiary = Peach20
val DarkTertiaryContainer = Peach30
val DarkOnTertiaryContainer = Peach90
val DarkError = Error80
val DarkOnError = Error20
val DarkErrorContainer = Error40
val DarkOnErrorContainer = Error90
val DarkBackground = NeutralLight20
val DarkOnBackground = Color(0xFFE4E2EB)
val DarkSurface = NeutralLight20
val DarkOnSurface = Color(0xFFE4E2EB)
val DarkSurfaceVariant = NeutralLight40
val DarkOnSurfaceVariant = NeutralLight80
val DarkSurfaceDim = NeutralLight20
val DarkSurfaceBright = Color(0xFF3A3943)
val DarkSurfaceContainerLowest = NeutralLight10
val DarkSurfaceContainerLow = Color(0xFF1B1B23)
val DarkSurfaceContainer = Color(0xFF1F1F27)
val DarkSurfaceContainerHigh = Color(0xFF2A2932)
val DarkSurfaceContainerHighest = Color(0xFF35343D)
val DarkOutline = Color(0xFF918F9F)
val DarkOutlineVariant = NeutralLight40
val DarkInverseSurface = Color(0xFFE4E2EB)
val DarkInverseOnSurface = NeutralLight30
val DarkInversePrimary = Purple40
val DarkScrim = Color(0xFF000000)

// ===== Warna terminal (tidak ikut dynamic color — identitas terminal tetap) =====
val TerminalBackground = Color(0xFF0D1117)
val TerminalForeground = Color(0xFFE6EDF3)
val TerminalGreen = Color(0xFF3FB950)
val TerminalYellow = Color(0xFFD29922)
val TerminalRed = Color(0xFFF85149)
