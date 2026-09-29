package com.binaa.ocr.ui.theme

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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * رموز التصميم (tokens) كما في تصميم Phase 0: حبر أزرق مخضرّ على خلفية ورقية دافئة.
 * ألوان الحالات (FOUND / UNCERTAIN / NOT_FOUND) معرّفة من الآن لتبقى الهوية واحدة في كل المراحل.
 */
@Immutable
data class BinaaColors(
    val brand: Color,
    val onBrand: Color,
    val background: Color,
    val card: Color,
    val text: Color,
    val textMuted: Color,
    val divider: Color,
    val found: Color,
    val uncertainBorder: Color,
    val uncertainBackground: Color,
    val notFoundBorder: Color,
    val error: Color,
)

private val Light = BinaaColors(
    brand = Color(0xFF0F4C5C),
    onBrand = Color(0xFFFFFFFF),
    background = Color(0xFFF7F6F2),
    card = Color(0xFFFFFFFF),
    text = Color(0xFF16202A),
    textMuted = Color(0xFF5B6770),
    divider = Color(0xFFE3E1DA),
    found = Color(0xFF2E7D5B),
    uncertainBorder = Color(0xFFC08A2B),
    uncertainBackground = Color(0xFFFFF8EC),
    notFoundBorder = Color(0xFFA3ACB2),
    error = Color(0xFFB3261E),
)

private val Dark = BinaaColors(
    brand = Color(0xFF5FB3C4),
    onBrand = Color(0xFF0E1418),
    background = Color(0xFF0E1418),
    card = Color(0xFF172027),
    text = Color(0xFFE8ECEE),
    textMuted = Color(0xFF9AA6AE),
    divider = Color(0xFF26323A),
    found = Color(0xFF6CC49A),
    uncertainBorder = Color(0xFFE0B35C),
    uncertainBackground = Color(0xFF2A2418),
    notFoundBorder = Color(0xFF56636B),
    error = Color(0xFFF2B8B5),
)

val LocalBinaaColors = staticCompositionLocalOf { Light }

object Binaa {
    val colors: BinaaColors @Composable get() = LocalBinaaColors.current
}

private val BinaaTypography = Typography(
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 12.sp),
)

private val BinaaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
)

/** عربي أولًا: الاتجاه RTL ثابت مهما كانت لغة الهاتف (الفرنسية تأتي لاحقًا بموارد مستقلة). */
@Composable
fun BinaaTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (colors === Dark) {
        darkColorScheme(primary = colors.brand, onPrimary = colors.onBrand, background = colors.background, surface = colors.card, onBackground = colors.text, onSurface = colors.text, error = colors.error)
    } else {
        lightColorScheme(primary = colors.brand, onPrimary = colors.onBrand, background = colors.background, surface = colors.card, onBackground = colors.text, onSurface = colors.text, error = colors.error)
    }
    CompositionLocalProvider(LocalBinaaColors provides colors, LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = scheme, typography = BinaaTypography, shapes = BinaaShapes, content = content)
    }
}
