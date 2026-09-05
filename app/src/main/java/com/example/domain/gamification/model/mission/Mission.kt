package com.example.domain.gamification.model.mission

/**
 * O que a missão observa. Cada tipo aponta para uma autoridade que já existe no domínio:
 *
 * ```
 * WORKOUT_COUNT   -> sessões COMPLETED da semana (histórico canônico de treinos)
 * TRAINING_DAYS   -> dias distintos com sessão COMPLETED na semana
 * WEEKLY_GOAL     -> ConsistencyRepository (meta e progresso da semana)
 * TOTAL_WORKOUTS  -> total histórico de sessões COMPLETED
 * ```
 *
 * Nenhum tipo inventa progresso: todos leem fatos que o aplicativo já registrava antes da T13.5.
 */
enum class MissionType {
    WORKOUT_COUNT,
    TRAINING_DAYS,
    WEEKLY_GOAL,
    TOTAL_WORKOUTS
}

/** Janela em que a missão vale. */
enum class MissionPeriod {
    /** Semana da consistência: começa na segunda-feira e termina no domingo. */
    WEEKLY,

    /** Marco acumulado: não expira. */
    ALL_TIME
}

enum class MissionStatus {
    ACTIVE,
    COMPLETED,
    EXPIRED
}

/**
 * Definição canônica da missão.
 *
 * O [id] é a identidade: ele entra na chave de idempotência da recompensa e no histórico. Título e
 * descrição são texto de interface e podem mudar sem afetar nada que já aconteceu.
 *
 * @param target alvo fixo da missão; `null` quando o alvo pertence a outra autoridade (a meta
 *        semanal é definida pela consistência, não pelo catálogo de missões).
 */
data class MissionDefinition(
    val id: String,
    val title: String,
    val description: String,
    val type: MissionType,
    val period: MissionPeriod,
    val target: Int?,
    val rewardXp: Int,
    val order: Int
)

/**
 * Estado do usuário em uma instância de missão (definição + período).
 *
 * É uma projeção: ninguém persiste esta classe. O que sobrevive ao fechamento do aplicativo são os
 * fatos que a originam — sessões concluídas, consistência e o evento de conclusão da missão.
 */
data class MissionProgress(
    val missionId: String,
    val title: String,
    val description: String,
    val periodKey: String,
    val progress: Int,
    val target: Int,
    val status: MissionStatus,
    val rewardXp: Int,
    val completedAt: Long? = null,
    /** Instante em que o período termina; `null` para missões sem prazo. */
    val periodEndsAt: Long? = null
) {
    val progressPercentage: Float
        get() = if (target > 0) (progress.toFloat() / target).coerceIn(0f, 1f) else 0f
}

/**
 * Conclusão realmente registrada: a prova de que a missão foi cumprida naquele período.
 *
 * Cada conclusão nasce de um evento `MISSION_COMPLETED` persistido, cuja chave de idempotência é
 * `missionId + periodKey`. Alterar o catálogo depois não apaga nem reabre uma conclusão histórica,
 * porque alvo e recompensa ficam congelados aqui.
 */
data class MissionCompletion(
    val missionId: String,
    val periodKey: String,
    val completedAt: Long,
    val target: Int,
    val rewardXp: Int
)
