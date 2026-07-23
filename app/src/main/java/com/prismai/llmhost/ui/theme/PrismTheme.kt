package com.prismai.llmhost.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val PrismCyan = Color(0xFF06B6D4)
internal val PrismBlue = Color(0xFF2563EB)
internal val PrismViolet = Color(0xFFA855F7)
internal val PrismGreen = Color(0xFF16A34A)
internal val PrismAmber = Color(0xFFD97706)
internal val PrismRed = Color(0xFFDC2626)
internal val PrismText = Color(0xFF0F172A)
internal val PrismSlate = Color(0xFF1E293B)
internal val PrismOnDark = Color(0xFFF8FAFC)
internal val PrismCanvas = Color(0xFFF8FCFF)
internal val PrismGlass = Color(0xDFFFFFFF)
internal val PrismGlassBorder = Color(0x8FBFDBFE)
internal val UserBubble = Color(0xDDE0F7FF)
internal val AssistantBubble = Color(0xFFF4E8FF)

internal val LlmHostPrismaticColorScheme = lightColorScheme(
    primary = PrismBlue,
    onPrimary = Color.White,
    primaryContainer = UserBubble,
    onPrimaryContainer = Color(0xFF082F49),
    secondary = PrismViolet,
    onSecondary = Color.White,
    secondaryContainer = AssistantBubble,
    onSecondaryContainer = Color(0xFF3B0764),
    tertiary = PrismCyan,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF042F2E),
    background = Color(0xFFFBFCFF),
    onBackground = PrismText,
    surface = Color.White,
    onSurface = PrismText,
    surfaceVariant = Color(0xFFEFF6FF),
    onSurfaceVariant = Color(0xFF334155),
    outline = Color(0xFF93C5FD),
    outlineVariant = Color(0xFFD8B4FE),
    error = Color(0xFFDC2626),
    onError = Color.White,
)
