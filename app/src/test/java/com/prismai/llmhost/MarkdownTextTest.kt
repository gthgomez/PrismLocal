package com.prismai.llmhost
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MarkdownTextTest {
    @Test
    fun markdownPlainLinesStripVisibleSyntax() {
        val lines = markdownPlainLinesForTesting(
            """
            A **quadratic equation** is:

            > **ax² + bx + c = 0**
            ---
            ## Methods
            - Factoring
            ### 1. **Factoring** *(when possible)*
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "A quadratic equation is:",
                "ax² + bx + c = 0",
                "Methods",
                "• Factoring",
                "1. Factoring (when possible)",
            ),
            lines,
        )
        assertFalse(lines.joinToString("\n").contains("**"))
        assertFalse(lines.joinToString("\n").contains("##"))
        assertFalse(lines.joinToString("\n").contains("*("))
    }
}
