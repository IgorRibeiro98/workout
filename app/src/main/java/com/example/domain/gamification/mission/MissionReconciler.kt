package com.example.domain.gamification.mission

import com.example.domain.gamification.repository.MissionEvaluationOrigin
import com.example.domain.gamification.repository.MissionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reconstrói o estado das missões na inicialização.
 *
 * Existe porque um treino pode ter sido concluído sem que a missão fosse avaliada (o app pode ter
 * sido encerrado antes). Como o progresso é derivado das autoridades persistidas, basta reavaliar:
 * o que já estava concluído continua concluído e a recompensa não se repete.
 */
class MissionReconciler(
    private val missionRepository: MissionRepository
) {
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        missionRepository.evaluateAndComplete(MissionEvaluationOrigin.RECONCILIATION)
    }
}
