package com.example.domain.evolution.model.achievement

import com.example.domain.model.IconKeys

object AchievementCatalog {
    val CATALOG_VERSION = 1

    val DEFINITIONS = listOf(
        // TRAINING
        AchievementDefinition("first_workout", "Primeiro passo", "Complete seu primeiro treino.", IconKeys.TROPHY, AchievementCategory.TRAINING, AchievementTier.BRONZE, 1, 1),
        AchievementDefinition("10_workouts", "Criando ritmo", "Complete 10 treinos.", IconKeys.TROPHY, AchievementCategory.TRAINING, AchievementTier.BRONZE, 10, 2),
        AchievementDefinition("25_workouts", "Compromisso", "Complete 25 treinos.", IconKeys.TROPHY, AchievementCategory.TRAINING, AchievementTier.SILVER, 25, 3),
        AchievementDefinition("50_workouts", "Cinquenta treinos", "Complete 50 treinos.", IconKeys.TROPHY, AchievementCategory.TRAINING, AchievementTier.GOLD, 50, 4),
        AchievementDefinition("100_workouts", "Centenário", "Complete 100 treinos.", IconKeys.TROPHY, AchievementCategory.TRAINING, AchievementTier.PLATINUM, 100, 5),

        // CONSISTENCY
        AchievementDefinition("streak_2_weeks", "Começou a sequência", "2 semanas consistentes.", IconKeys.FIRE, AchievementCategory.CONSISTENCY, AchievementTier.BRONZE, 2, 1),
        AchievementDefinition("streak_4_weeks", "Um mês no ritmo", "4 semanas consistentes.", IconKeys.FIRE, AchievementCategory.CONSISTENCY, AchievementTier.BRONZE, 4, 2),
        AchievementDefinition("streak_8_weeks", "Consistência real", "8 semanas consistentes.", IconKeys.FIRE, AchievementCategory.CONSISTENCY, AchievementTier.SILVER, 8, 3),
        AchievementDefinition("streak_12_weeks", "Três meses firme", "12 semanas consistentes.", IconKeys.FIRE, AchievementCategory.CONSISTENCY, AchievementTier.SILVER, 12, 4),
        AchievementDefinition("streak_24_weeks", "Meio ano", "24 semanas consistentes.", IconKeys.FIRE, AchievementCategory.CONSISTENCY, AchievementTier.GOLD, 24, 5),
        AchievementDefinition("streak_52_weeks", "Um ano consistente", "52 semanas consistentes.", IconKeys.FIRE, AchievementCategory.CONSISTENCY, AchievementTier.PLATINUM, 52, 6),

        // PERFORMANCE
        AchievementDefinition("first_pr", "Primeiro recorde", "Conquiste seu primeiro recorde pessoal.", IconKeys.MEDAL, AchievementCategory.PERFORMANCE, AchievementTier.BRONZE, 1, 1),
        AchievementDefinition("5_prs", "Superando limites", "5 recordes pessoais históricos.", IconKeys.MEDAL, AchievementCategory.PERFORMANCE, AchievementTier.SILVER, 5, 2),
        AchievementDefinition("10_prs", "Evolução constante", "10 recordes pessoais históricos.", IconKeys.MEDAL, AchievementCategory.PERFORMANCE, AchievementTier.GOLD, 10, 3),
        AchievementDefinition("25_prs", "Colecionador de recordes", "25 recordes pessoais históricos.", IconKeys.MEDAL, AchievementCategory.PERFORMANCE, AchievementTier.PLATINUM, 25, 4),

        // BODY
        AchievementDefinition("first_measurement", "Primeiro registro", "1 dia com medição corporal.", IconKeys.SCALE, AchievementCategory.BODY, AchievementTier.BRONZE, 1, 1),
        AchievementDefinition("4_measurements", "Acompanhando a evolução", "Medições em 4 datas distintas.", IconKeys.SCALE, AchievementCategory.BODY, AchievementTier.SILVER, 4, 2),
        AchievementDefinition("12_measurements", "Dados contam histórias", "Medições em 12 datas distintas.", IconKeys.SCALE, AchievementCategory.BODY, AchievementTier.GOLD, 12, 3),
        AchievementDefinition("24_measurements", "Um histórico completo", "Medições em 24 datas distintas.", IconKeys.SCALE, AchievementCategory.BODY, AchievementTier.PLATINUM, 24, 4)
    )

    fun getDefinition(id: String): AchievementDefinition? = DEFINITIONS.find { it.id == id }
}
