package com.example

import com.example.domain.engine.RirFormatter
import org.junit.Assert.*
import org.junit.Test

class RirFormatterTest {

    @Test
    fun testRirZeroFormatsAsFailure() {
        assertEquals("🔥 Falha", RirFormatter.formatRir(0, full = false))
        assertEquals("🔥 Até a falha", RirFormatter.formatRir(0, full = true))
        assertTrue(RirFormatter.isFailure(0))
    }

    @Test
    fun testRirPositiveValues() {
        assertEquals("RIR 1", RirFormatter.formatRir(1))
        assertEquals("RIR 2", RirFormatter.formatRir(2))
        assertEquals("RIR 3", RirFormatter.formatRir(3))
        assertFalse(RirFormatter.isFailure(2))
    }

    @Test
    fun testRirFourPlusValues() {
        assertEquals("RIR 4+", RirFormatter.formatRir(4))
        assertEquals("RIR 4+", RirFormatter.formatRir(5))
    }

    @Test
    fun testNullRirReturnsNull() {
        assertNull(RirFormatter.formatRir(null))
        assertFalse(RirFormatter.isFailure(null))
    }
}
