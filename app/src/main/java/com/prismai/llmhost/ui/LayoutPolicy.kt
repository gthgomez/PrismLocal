package com.prismai.llmhost.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height-driven layout decisions.
 *
 * The composer already applies imePadding() and navigationBarsPadding(), which
 * is correct. What was missing is behavior when the keyboard shrinks the
 * window: the header did not adapt, and the attachment tray - a plain Column of
 * cards - could consume the space the input needs.
 */
object LayoutPolicy {

    /** Below this, the window is too short to afford the full layout. */
    private val SHORT_HEIGHT = 480.dp

    fun isShortHeight(maxHeight: Dp): Boolean = maxHeight < SHORT_HEIGHT

    fun headerCompact(availableHeightDp: Dp): Boolean = availableHeightDp < SHORT_HEIGHT

    /** In short layouts the tray is hidden; the count is shown in the composer instead. */
    fun trayVisible(attachmentCount: Int, isShortHeight: Boolean): Boolean =
        attachmentCount > 0 && !isShortHeight
}
