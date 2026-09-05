package com.example.domain.gamification.model.mission

/**
 * Catálogo canônico das missões.
 *
 * Assim como o catálogo de conquistas, esta é a única origem de definições: nenhuma missão nasce em
 * ViewModel ou Composable. Os ids são estáveis e nunca devem ser reciclados — eles identificam
 * conclusões já registradas no histórico.
 *
 * A primeira versão é deliberadamente pequena: só entram missões que uma autoridade existente
 * consegue avaliar com segurança.
 */
object MissionCatalog {

    const val CATALOG_VERSION = 1

    val DEFINITIONS = listOf(
        MissionDefinition(
            id = "weekly_workouts_3",
            title = "Constância em alta",
            description = "Complete 3 treinos nesta semana.",
            type = MissionType.WORKOUT_COUNT,
            period = MissionPeriod.WEEKLY,
            target = 3,
            rewardXp = 150,
            order = 1
        ),
        MissionDefinition(
            id = "weekly_training_days_3",
            title = "Três dias na semana",
            description = "Treine em 3 dias diferentes nesta semana.",
            type = MissionType.TRAINING_DAYS,
            period = MissionPeriod.WEEKLY,
            target = 3,
            rewardXp = 150,
            order = 2
        ),
        MissionDefinition(
            id = "weekly_goal",
            title = "Meta da semana",
            description = "Complete sua meta semanal de treinos.",
            type = MissionType.WEEKLY_GOAL,
            period = MissionPeriod.WEEKLY,
            // O alvo é a meta semanal vigente: quem decide é a consistência.
            target = null,
            rewardXp = 100,
            order = 3
        ),
        MissionDefinition(
            id = "total_workouts_10",
            title = "Dez treinos",
            description = "Complete 10 treinos no total.",
            type = MissionType.TOTAL_WORKOUTS,
            period = MissionPeriod.ALL_TIME,
            target = 10,
            rewardXp = 200,
            order = 4
        )
    )

    fun getDefinition(id: String): MissionDefinition? = DEFINITIONS.find { it.id == id }
}
