package com.facealbum.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Slightly tightened Material 3 type scale — display/headline pulled in a touch
 * tighter for screens where photos are the hero.
 */
private val systemSans = FontFamily.SansSerif

internal val FaceAlbumTypography = Typography(
    displayLarge = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.Normal, fontSize = 52.sp, lineHeight = 60.sp),
    displayMedium = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.Normal, fontSize = 42.sp, lineHeight = 50.sp),
    displaySmall = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 42.sp),

    headlineLarge = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),

    titleLarge = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),

    bodyLarge = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),

    labelLarge = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = systemSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)
