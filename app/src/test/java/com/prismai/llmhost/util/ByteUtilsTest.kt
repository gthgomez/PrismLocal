package com.prismai.llmhost.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ByteUtilsTest {

    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun formatByteSize_belowOneMiB_usesExactBytes() {
        assertEquals("0 B", formatByteSize(0L))
        assertEquals("512 B", formatByteSize(512L))
        assertEquals("1048575 B", formatByteSize(1_048_575L))
    }

    @Test
    fun formatByteSize_exactlyOneMiB_usesMegabytes() {
        assertEquals("1.0 MB", formatByteSize(1_048_576L))
    }

    @Test
    fun formatByteSize_fractionalMegabytes() {
        assertEquals("1.5 MB", formatByteSize(1_572_864L))
    }

    @Test
    fun toHex_lowercaseAndZeroPadded() {
        assertEquals(
            "000fa5ff",
            byteArrayOf(0, 15, 0xA5.toByte(), 0xFF.toByte()).toHex(),
        )
    }

    @Test
    fun toHex_emptyArray() {
        assertEquals("", ByteArray(0).toHex())
    }

    @Test
    fun toHex_producesTwoCharsPerByte() {
        val hex = ByteArray(256) { it.toByte() }.toHex()
        assertEquals(512, hex.length)
        assertEquals("00", hex.substring(0, 2))
        assertEquals("ff", hex.substring(510, 512))
    }
}
