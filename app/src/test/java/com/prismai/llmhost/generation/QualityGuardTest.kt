package com.prismai.llmhost.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityGuardTest {

    @Test
    fun cleanPythonMedianDoesNotAbort() {
        val text = """
            def median(values: list[int]) -> float:
                # Compute the median of a non-empty list of ints.
                if not values:
                    raise ValueError("empty list")
                ordered = sorted(values)
                mid = len(ordered) // 2
                if len(ordered) % 2 == 1:
                    return float(ordered[mid])
                return (ordered[mid - 1] + ordered[mid]) / 2.0

            Edge cases: empty input raises; even length averages the middle pair.
            Notes on sorting stability and integer overflow are documented here.
        """.trimIndent()
        val verdict = QualityGuard.evaluate(text, generatedTokens = 120, isCodingPreset = true)
        assertFalse("unexpected abort: $verdict", verdict.abort)
    }

    @Test
    fun andSoOnLoopAborts() {
        val loop = buildString {
            repeat(40) {
                appendLine("And so on.")
                appendLine("To the end of it.")
                appendLine("and I's here.")
            }
        }
        val verdict = QualityGuard.evaluate(loop, generatedTokens = 200, isCodingPreset = true)
        assertTrue(verdict.abort)
        assertEquals("REPETITION_LOOP", verdict.reasonCode)
    }

    @Test
    fun uniqueWordRatioLoopAborts() {
        val text = (List(80) { "the" } + List(20) { "end" }).joinToString(" ")
        val verdict = QualityGuard.evaluate(text, generatedTokens = 100)
        assertTrue(verdict.abort)
        assertEquals("REPETITION_LOOP", verdict.reasonCode)
    }

    @Test
    fun scriptChaosAborts() {
        // Latin + CJK + Arabic mixed soup (screenshot-class) with unique tokens so
        // phrase-repetition does not fire first.
        val latin = listOf(
            "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
            "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa",
        )
        val cjk = listOf("世界", "测试", "功能", "模型", "代码", "输出", "质量", "错误")
        val arabic = listOf("مرحبا", "كلمات", "نموذج", "جودة", "خطأ", "نص", "لغة", "نظام")
        val text = buildString {
            for (i in 0 until 24) {
                append(latin[i % latin.size]).append(' ')
                append(cjk[i % cjk.size]).append(' ')
                append(arabic[i % arabic.size]).append(' ')
            }
        }
        val verdict = QualityGuard.evaluate(text, generatedTokens = 150)
        assertTrue("expected SCRIPT_CHAOS for mixed scripts, got $verdict", verdict.abort)
        assertEquals("SCRIPT_CHAOS", verdict.reasonCode)
    }

    @Test
    fun belowMinTokensSkipsWhenTokenCountProvided() {
        val loop = buildString { repeat(20) { appendLine("And so on. To the end of it.") } }
        val verdict = QualityGuard.evaluate(loop, generatedTokens = 10)
        assertFalse(verdict.abort)
    }

    @Test
    fun looksDegenerateMatchesEvaluate() {
        val loop = buildString { repeat(40) { appendLine("And so on.") } }
        assertTrue(QualityGuard.looksDegenerate(loop))
        assertFalse(QualityGuard.looksDegenerate("def add(a: int, b: int) -> int:\n    return a + b\n"))
    }

    @Test
    fun validDensePythonStubsDoNotAbort() {
        // Legitimate coding output reuses short constructs (return / if not / self.x =)
        // more than the old sliding-window threshold of 4.
        val text = """
            class Point:
                def __init__(self, x: int, y: int) -> None:
                    self.x = x
                    self.y = y

                def move(self, dx: int, dy: int) -> None:
                    if not isinstance(dx, int):
                        raise TypeError("dx")
                    if not isinstance(dy, int):
                        raise TypeError("dy")
                    self.x = self.x + dx
                    self.y = self.y + dy

            def clamp(value: int, low: int, high: int) -> int:
                if not value >= low:
                    return low
                if not value <= high:
                    return high
                return value

            def abs_diff(a: int, b: int) -> int:
                if not a >= b:
                    return b - a
                return a - b

            def maybe_none(flag: bool) -> int | None:
                if not flag:
                    return None
                return 1

            def normalize(values: list[int]) -> list[int]:
                if not values:
                    return []
                return [clamp(v, 0, 100) for v in values]
        """.trimIndent()
        val verdict = QualityGuard.evaluate(
            text = text,
            generatedTokens = 150,
            isCodingPreset = true,
        )
        assertFalse("unexpected abort on valid stubs: $verdict", verdict.abort)
    }
}
