package com.example.domain.evolution.model.achievement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementCatalogTest {

    data class ExpectedDefinition(
        val id: String,
        val title: String,
        val category: AchievementCategory,
        val tier: AchievementTier,
        val target: Int,
        val order: Int
    )

    private val expectedDefinitions = listOf(
        // TRAINING
        ExpectedDefinition("first_workout", "Primeiro passo", AchievementCategory.TRAINING, AchievementTier.BRONZE, 1, 1),
        ExpectedDefinition("10_workouts", "Criando ritmo", AchievementCategory.TRAINING, AchievementTier.BRONZE, 10, 2),
        ExpectedDefinition("25_workouts", "Compromisso", AchievementCategory.TRAINING, AchievementTier.SILVER, 25, 3),
        ExpectedDefinition("50_workouts", "Cinquenta treinos", AchievementCategory.TRAINING, AchievementTier.GOLD, 50, 4),
        ExpectedDefinition("100_workouts", "Centenário", AchievementCategory.TRAINING, AchievementTier.PLATINUM, 100, 5),

        // CONSISTENCY
        ExpectedDefinition("streak_2_weeks", "Começou a sequência", AchievementCategory.CONSISTENCY, AchievementTier.BRONZE, 2, 1),
        ExpectedDefinition("streak_4_weeks", "Um mês no ritmo", AchievementCategory.CONSISTENCY, AchievementTier.BRONZE, 4, 2),
        ExpectedDefinition("streak_8_weeks", "Consistência real", AchievementCategory.CONSISTENCY, AchievementTier.SILVER, 8, 3),
        ExpectedDefinition("streak_12_weeks", "Três meses firme", AchievementCategory.CONSISTENCY, AchievementTier.SILVER, 12, 4),
        ExpectedDefinition("streak_24_weeks", "Meio ano", AchievementCategory.CONSISTENCY, AchievementTier.GOLD, 24, 5),
        ExpectedDefinition("streak_52_weeks", "Um ano consistente", AchievementCategory.CONSISTENCY, AchievementTier.PLATINUM, 52, 6),

        // PERFORMANCE
        ExpectedDefinition("first_pr", "Primeiro recorde", AchievementCategory.PERFORMANCE, AchievementTier.BRONZE, 1, 1),
        ExpectedDefinition("5_prs", "Superando limites", AchievementCategory.PERFORMANCE, AchievementTier.SILVER, 5, 2),
        ExpectedDefinition("10_prs", "Evolução constante", AchievementCategory.PERFORMANCE, AchievementTier.GOLD, 10, 3),
        ExpectedDefinition("25_prs", "Colecionador de recordes", AchievementCategory.PERFORMANCE, AchievementTier.PLATINUM, 25, 4),

        // BODY
        ExpectedDefinition("first_measurement", "Primeiro registro", AchievementCategory.BODY, AchievementTier.BRONZE, 1, 1),
        ExpectedDefinition("4_measurements", "Acompanhando a evolução", AchievementCategory.BODY, AchievementTier.SILVER, 4, 2),
        ExpectedDefinition("12_measurements", "Dados contam histórias", AchievementCategory.BODY, AchievementTier.GOLD, 12, 3),
        ExpectedDefinition("24_measurements", "Um histórico completo", AchievementCategory.BODY, AchievementTier.PLATINUM, 24, 4)
    )

    @Test
    fun testCatalogVersionAndIntegrity() {
        // Explicitly assert CATALOG_VERSION = 1
        assertEquals("Catalog version must be 1", 1, AchievementCatalog.CATALOG_VERSION)

        // Exactly 19 achievements
        assertEquals("Must have exactly 19 definitions", 19, AchievementCatalog.DEFINITIONS.size)

        // 19 unique IDs
        val uniqueIds = AchievementCatalog.DEFINITIONS.map { it.id }.toSet()
        assertEquals("Must have 19 unique IDs", 19, uniqueIds.size)

        // Check each definition explicitly
        for (expected in expectedDefinitions) {
            val actual = AchievementCatalog.getDefinition(expected.id)
            assertNotNull("Definition with id ${expected.id} must exist in catalog", actual)
            actual!!

            assertEquals("ID mismatch for ${expected.id}", expected.id, actual.id)
            assertEquals("Title mismatch for ${expected.id}", expected.title, actual.title)
            assertEquals("Category mismatch for ${expected.id}", expected.category, actual.category)
            assertEquals("Tier mismatch for ${expected.id}", expected.tier, actual.tier)
            assertEquals("Target mismatch for ${expected.id}", expected.target, actual.target)
            assertEquals("Order mismatch for ${expected.id}", expected.order, actual.order)
            assertTrue("Description must not be blank for ${expected.id}", actual.description.isNotBlank())
            assertTrue("Icon must not be blank for ${expected.id}", actual.icon.isNotBlank())
        }
    }
}
