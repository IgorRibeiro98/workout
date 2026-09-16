package com.example

import com.example.domain.engine.MuscleVisualResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseMediaRefinementTest {

    @Test
    fun `test muscle visual resolver provides fallback groups for chest and back`() {
        val chestGroup = MuscleVisualResolver.resolveGroup("chest")
        val peitoralGroup = MuscleVisualResolver.resolveGroup("peitoral")
        assertEquals("Peitoral", chestGroup.displayName)
        assertEquals("Peitoral", peitoralGroup.displayName)

        val backGroup = MuscleVisualResolver.resolveGroup("back")
        val costasGroup = MuscleVisualResolver.resolveGroup("costas")
        assertEquals("Costas", backGroup.displayName)
        assertEquals("Costas", costasGroup.displayName)
    }

    @Test
    fun `test muscle visual resolver provides valid colors`() {
        // Desde a T19.7A o grupo muscular só tem cor: o ícone de um exercício responde ao
        // equipamento (`ExerciseVisualResolver`).
        val legsGroup = MuscleVisualResolver.resolveGroup("quadriceps")
        assertNotNull(legsGroup.color)
        assertEquals("Quadríceps", legsGroup.displayName)

        val shouldersGroup = MuscleVisualResolver.resolveGroup("shoulders")
        assertNotNull(shouldersGroup.color)
        assertEquals("Ombros", shouldersGroup.displayName)
    }

    @Test
    fun `test equipment string formatting for compact context card`() {
        val rawEquipment = "halteres, banco inclinado"
        val cleanEquipment = rawEquipment.split(",").firstOrNull()?.trim() ?: rawEquipment
        assertEquals("halteres", cleanEquipment)
    }

    @Test
    fun `test difficulty capitalization for badges`() {
        val difficulty = "intermediário"
        val capitalized = difficulty.replaceFirstChar { it.uppercase() }
        assertEquals("Intermediário", capitalized)
    }
}
