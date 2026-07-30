package com.prismai.llmhost.ui.theme
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

internal val LlmHostPrismaticDarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFFC084FC),
    onSecondary = Color(0xFF3B0764),
    secondaryContainer = Color(0xFF2D1F3F),
    onSecondaryContainer = Color(0xFFF3E8FF),
    tertiary = Color(0xFF22D3EE),
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF182234),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF64748B),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

@Composable
internal fun PrismLocalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) LlmHostPrismaticDarkColorScheme else LlmHostPrismaticColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

@Composable
internal fun prismCanvasColor(darkTheme: Boolean = isSystemInDarkTheme()): Color {
    return if (darkTheme) Color(0xFF0B0F19) else PrismCanvas
}

@Composable
internal fun prismGlassColor(darkTheme: Boolean = isSystemInDarkTheme()): Color {
    return if (darkTheme) Color(0xDD182234) else PrismGlass
}

@Composable
internal fun prismGlassBorderColor(darkTheme: Boolean = isSystemInDarkTheme()): Color {
    return if (darkTheme) Color(0x4064748B) else PrismGlassBorder
}

@Composable
internal fun userBubbleColor(darkTheme: Boolean = isSystemInDarkTheme()): Color {
    return if (darkTheme) Color(0xFF1E293B) else UserBubble
}

@Composable
internal fun assistantBubbleColor(darkTheme: Boolean = isSystemInDarkTheme()): Color {
    return if (darkTheme) Color(0xCC261E35) else Color.White.copy(alpha = 0.76f)
}

