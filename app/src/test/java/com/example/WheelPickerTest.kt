package com.example

import com.example.ui.components.WheelPickerDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

class WheelPickerTest {

    @Test
    fun testWeightStepAndValueCalculations() {
        // Step 0.5f, min 0f
        val min = 0f
        val step = 0.5f
        val totalItems = 1001 // 0.0 to 500.0

        // Test value 80.0
        val index80 = WheelPickerDefaults.valueToIndex(80f, min, step, totalItems)
        assertEquals(160, index80)
        val value80 = WheelPickerDefaults.indexToValue(index80, min, step)
        assertEquals(80f, value80, 0.001f)

        // Test value 80.5
        val index80_5 = WheelPickerDefaults.valueToIndex(80.5f, min, step, totalItems)
        assertEquals(161, index80_5)
        val value80_5 = WheelPickerDefaults.indexToValue(index80_5, min, step)
        assertEquals(80.5f, value80_5, 0.001f)

        // Test value 79.5
        val index79_5 = WheelPickerDefaults.valueToIndex(79.5f, min, step, totalItems)
        assertEquals(159, index79_5)
        val value79_5 = WheelPickerDefaults.indexToValue(index79_5, min, step)
        assertEquals(79.5f, value79_5, 0.001f)
    }

    @Test
    fun testWeightFormattingPtBr() {
        assertEquals("80", WheelPickerDefaults.formatWeight(80.0f))
        assertEquals("80,5", WheelPickerDefaults.formatWeight(80.5f))
        assertEquals("79,5", WheelPickerDefaults.formatWeight(79.5f))
        assertEquals("0", WheelPickerDefaults.formatWeight(0.0f))
    }

    @Test
    fun testRepsCalculationsAndFormatting() {
        val min = 1f
        val step = 1f
        val totalItems = 100

        val index10 = WheelPickerDefaults.valueToIndex(10f, min, step, totalItems)
        assertEquals(9, index10)
        val value10 = WheelPickerDefaults.indexToValue(index10, min, step)
        assertEquals(10f, value10, 0.001f)

        assertEquals("10", WheelPickerDefaults.formatReps(10))
        assertEquals("1", WheelPickerDefaults.formatReps(1))
    }
}
