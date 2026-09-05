package com.example.domain.gamification.repository

import com.example.domain.gamification.model.mission.MissionCompletion
import com.example.domain.gamification.model.mission.MissionProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Camada de objetivos sobre fatos que o domínio já reconhece.
 *
 * O repositório não guarda progresso: ele projeta as autoridades existentes (sessões concluídas e
 * consistência) e persiste apenas o que não pode ser derivado — a conclusão da missão, que é um
 * fato do histórico de gamificação.
 */
interface MissionRepository {

    /** Conclusões acontecendo agora, para comemoração na interface. Silencioso na reconciliação. */
    val liveCompletions: SharedFlow<MissionCompletion>

    /** Estado das missões, recalculado sempre que uma autoridade observada muda. */
    fun getMissionsFlow(): Flow<List<MissionProgress>>

    suspend fun getMissions(): List<MissionProgress>

    /**
     * Avalia as missões e registra as conclusões que ainda não existem no histórico.
     *
     * @return apenas as conclusões novas — repetir a chamada não devolve nada e não concede XP de
     *         novo.
     */
    suspend fun evaluateAndComplete(
        origin: MissionEvaluationOrigin = MissionEvaluationOrigin.LIVE
    ): List<MissionCompletion>
}

/** Contexto em que a avaliação acontece. */
enum class MissionEvaluationOrigin {
    /** O usuário está usando o app agora: recompensa e comemoração fazem sentido. */
    LIVE,

    /** Reconstrução do que já havia acontecido: recompensa em silêncio, sem comemorar de novo. */
    RECONCILIATION
}
