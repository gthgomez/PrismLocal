package com.prismai.llmhost.ui.spen

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

data class SPenHoverState(
    val isHovered: Boolean = false,
    val isStylus: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
)

@Composable
fun rememberSPenHoverState(): SPenHoverState {
    return remember { SPenHoverState() }
}

fun Modifier.sPenHoverable(
    enabled: Boolean = true,
    onHoverChange: (SPenHoverState) -> Unit,
): Modifier = if (!enabled) this else this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val nativeEvent = event.motionEvent
            val isStylus = nativeEvent?.let { motionEvent ->
                (0 until motionEvent.pointerCount).any { idx ->
                    motionEvent.getToolType(idx) == MotionEvent.TOOL_TYPE_STYLUS
                }
            } ?: false

            when (event.type) {
                PointerEventType.Enter, PointerEventType.Move -> {
                    val pos = event.changes.firstOrNull()?.position
                    onHoverChange(
                        SPenHoverState(
                            isHovered = true,
                            isStylus = isStylus,
                            x = pos?.x ?: 0f,
                            y = pos?.y ?: 0f,
                        )
                    )
                }
                PointerEventType.Exit -> {
                    onHoverChange(
                        SPenHoverState(
                            isHovered = false,
                            isStylus = isStylus,
                        )
                    )
                }
            }
        }
    }
}
