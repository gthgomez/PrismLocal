package com.prismai.llmhost.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prismai.llmhost.tools.VoiceState
import com.prismai.llmhost.ui.components.GlassSurface
import com.prismai.llmhost.ui.theme.PrismBlue
import com.prismai.llmhost.ui.theme.PrismViolet
import kotlin.math.sin

/**
 * Animated voice input overlay displaying dynamic RMS decibel waveform and real-time STT transcript.
 */
@Composable
fun VoiceOverlay(
    voiceState: VoiceState,
    onStopListening: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = voiceState.isListening,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        GlassSurface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            radius = 24.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = PrismViolet.copy(alpha = 0.2f),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Canvas(modifier = Modifier.size(16.dp)) {
                                    drawCircle(color = PrismViolet)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Listening...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Speak your prompt naturally",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    androidx.compose.material3.TextButton(onClick = onStopListening) {
                        Text(
                            text = "Done",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Real-time RMS Decibel Audio Waveform Canvas
                RmsWaveformCanvas(
                    rmsDb = voiceState.rmsDb,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                )

                if (!voiceState.partialTranscript.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "\"${voiceState.partialTranscript}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PrismBlue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun RmsWaveformCanvas(
    rmsDb: Float,
    modifier: Modifier = Modifier,
) {
    val normalizedRms = remember(rmsDb) {
        ((rmsDb + 2f) / 14f).coerceIn(0.1f, 1.0f)
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val points = 60

        val wavePath = Path()
        val amplitude = (height / 2f - 4f) * normalizedRms

        wavePath.moveTo(0f, centerY)
        for (i in 0..points) {
            val x = (i.toFloat() / points) * width
            val angle = (i.toFloat() / points) * (Math.PI * 4).toFloat()
            val y = centerY + sin(angle) * amplitude
            wavePath.lineTo(x, y)
        }

        drawPath(
            path = wavePath,
            color = PrismViolet,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
