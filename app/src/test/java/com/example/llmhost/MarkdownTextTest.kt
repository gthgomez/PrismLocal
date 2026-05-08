package com.example.llmhost

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
