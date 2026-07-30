package com.prismai.llmhost.ui.components
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.ui.theme.*

import androidx.compose.foundation.isSystemInDarkTheme

@Composable
internal fun PrismBackdrop(modifier: Modifier = Modifier) {
    val darkTheme = isSystemInDarkTheme()
    Canvas(modifier = modifier) {
        if (darkTheme) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF0B0F19),
                        Color(0xFF181825),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            )
            drawCircle(
                color = PrismCyan.copy(alpha = 0.08f),
                radius = size.minDimension * 0.28f,
                center = Offset(size.width * 0.04f, size.height * 0.34f),
            )
            drawCircle(
                color = PrismViolet.copy(alpha = 0.08f),
                radius = size.minDimension * 0.30f,
                center = Offset(size.width * 1.02f, size.height * 0.66f),
            )
        } else {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF8FCFF),
                        Color(0xFFF3F8FF),
                        Color(0xFFFDF6FF),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            )
            drawCircle(
                color = PrismCyan.copy(alpha = 0.14f),
                radius = size.minDimension * 0.28f,
                center = Offset(size.width * 0.04f, size.height * 0.34f),
            )
            drawCircle(
                color = PrismCyan.copy(alpha = 0.10f),
                radius = size.minDimension * 0.22f,
                center = Offset(size.width * 0.02f, size.height * 0.78f),
            )
            drawCircle(
                color = PrismViolet.copy(alpha = 0.14f),
                radius = size.minDimension * 0.30f,
                center = Offset(size.width * 1.02f, size.height * 0.66f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.58f),
                radius = size.minDimension * 0.38f,
                center = Offset(size.width * 0.62f, size.height * 0.48f),
            )
        }
    }
}

@Composable
internal fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(width = 40.dp, height = 4.dp),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            contentColor = Color.Transparent,
            shadowElevation = 0.dp,
        ) {}
    }
}

@Composable
internal fun PrismLogoTile(size: Dp = 62.dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        contentColor = PrismBlue,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            InfinityLoadingIndicator(modifier = Modifier.size(size * 0.68f), color = PrismViolet)
        }
    }
}

@Composable
internal fun DashboardCard(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardColor = if (tint == Color.Unspecified) MaterialTheme.colorScheme.surface else tint
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, prismGlassBorderColor()),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = PrismBlue,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.invoke()
    }
}

@Composable
internal fun MetricGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { (label, value) ->
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        label = label,
                        value = value,
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = PrismGlass.copy(alpha = 0.42f),
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.34f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = PrismText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun InfoBadge(
    text: String,
    color: Color = PrismBlue,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.20f)),
        shadowElevation = 0.dp,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun InfinityLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = PrismBlue,
) {
    val transition = rememberInfiniteTransition(label = "infinityLoading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1300)),
        label = "infinityRotation",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "infinityPulse",
    )

    Canvas(modifier = modifier.size(32.dp)) {
        val strokeWidth = size.minDimension * 0.07f
        val path = launcherInfinityPath(size.width, size.height)
        drawPath(
            path = path,
            color = PrismViolet.copy(alpha = 0.18f),
            style = Stroke(width = strokeWidth * 2.0f, cap = StrokeCap.Round),
        )
        rotate(degrees = rotation) {
            drawPath(
                path = path,
                color = color.copy(alpha = alpha),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawPath(
                path = path,
                color = PrismCyan.copy(alpha = alpha * 0.64f),
                style = Stroke(width = strokeWidth * 0.55f, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = 22.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(radius),
        color = prismGlassColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, prismGlassBorderColor()),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        content = content,
    )
}

private fun launcherInfinityPath(width: Float, height: Float): Path =
    Path().apply {
        fun x(value: Float) = width * value / 108f
        fun y(value: Float) = height * value / 108f

        moveTo(x(27f), y(54f))
        cubicTo(x(27f), y(43f), x(38f), y(39f), x(47f), y(48f))
        lineTo(x(54f), y(55f))
        lineTo(x(61f), y(48f))
        cubicTo(x(70f), y(39f), x(81f), y(43f), x(81f), y(54f))
        cubicTo(x(81f), y(65f), x(70f), y(69f), x(61f), y(60f))
        lineTo(x(54f), y(53f))
        lineTo(x(47f), y(60f))
        cubicTo(x(38f), y(69f), x(27f), y(65f), x(27f), y(54f))
    }
