package com.example.domain.evolution.model.achievement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementCatalogTest {

    @Test
    fun testCatalogHasExpectedItems() {
        val all = AchievementCatalog.DEFINITIONS
        assertTrue("Catalog should not be empty", all.isNotEmpty())
        
        // Verify unique IDs
        val ids = all.map { it.id }
        assertEquals("IDs must be unique", ids.size, ids.toSet().size)
        
        // Validate each has expected order and valid category
        all.forEach { def ->
            assertTrue(def.order >= 0)
            assertTrue(def.target > 0)
        }
    }
}
