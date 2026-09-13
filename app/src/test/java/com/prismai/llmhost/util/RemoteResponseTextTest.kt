package com.prismai.llmhost.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader

class RemoteResponseTextTest {

    @Test
    fun readTextBounded_returnsFullBodyUnderLimit() {
        assertEquals("hello world", StringReader("hello world").readTextBounded(64))
    }

    @Test
    fun readTextBounded_allowsBodyExactlyAtLimit() {
        val body = "x".repeat(10)
        assertEquals(body, StringReader(body).readTextBounded(10))
    }

    @Test
    fun readTextBounded_throwsWhenOverLimit() {
        val body = "x".repeat(11)
        assertThrows(ResponseTooLargeException::class.java) {
            StringReader(body).readTextBounded(10)
        }
    }

    @Test
    fun readTextBounded_emptyBody() {
        assertEquals("", StringReader("").readTextBounded(10))
    }

    @Test
    fun readTextTruncated_capsLongerBody() {
        val result = StringReader("x".repeat(100)).readTextTruncated(10)
        assertEquals(10, result.length)
    }

    @Test
    fun readTextTruncated_returnsFullBodyUnderLimit() {
        assertEquals("abc", StringReader("abc").readTextTruncated(10))
    }
}
