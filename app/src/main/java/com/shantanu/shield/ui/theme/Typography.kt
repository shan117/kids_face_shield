package com.shantanu.shield.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.shantanu.shield.R

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val robotoFont = GoogleFont("Roboto")

val RobotoFamily = FontFamily(
    Font(googleFont = robotoFont, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = robotoFont, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = robotoFont, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = robotoFont, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = robotoFont, fontProvider = provider, weight = FontWeight.ExtraBold)
)

private val baseline = Typography()

val AppTypography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = RobotoFamily),
    displayMedium = baseline.displayMedium.copy(fontFamily = RobotoFamily),
    displaySmall = baseline.displaySmall.copy(fontFamily = RobotoFamily),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = RobotoFamily),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = RobotoFamily),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = RobotoFamily),
    titleLarge = baseline.titleLarge.copy(fontFamily = RobotoFamily),
    titleMedium = baseline.titleMedium.copy(fontFamily = RobotoFamily),
    titleSmall = baseline.titleSmall.copy(fontFamily = RobotoFamily),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = RobotoFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = RobotoFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = RobotoFamily),
    labelLarge = baseline.labelLarge.copy(fontFamily = RobotoFamily),
    labelMedium = baseline.labelMedium.copy(fontFamily = RobotoFamily),
    labelSmall = baseline.labelSmall.copy(fontFamily = RobotoFamily)
)
