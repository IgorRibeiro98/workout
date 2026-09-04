package com.example.domain.gamification

import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.repository.GamificationEventRepository
import com.example.domain.gamification.repository.XpTransactionOrigin
import com.example.domain.gamification.repository.XpTransactionRepository

/**
 * Sessão concluída de verdade, vinda do histórico canônico de treinos.
 *
 * O histórico de eventos pode estar incompleto (o app pode ter sido instalado depois, ou uma
 * publicação pode ter falhado); as sessões concluídas em Room são a prova de que o treino existiu.
 */
data class CompletedWorkoutReference(
    val sessionId: Long,
    val completedAt: Long
)

/**
 * Reconstrói o XP a partir do histórico de fatos quando a política de recompensa muda.
 *
 * Regras que este reconciliador precisa preservar:
 * - o rebuild é atômico: nunca existe um estado permanentemente meio apagado;
 * - `xpPolicyVersion` só avança depois de um rebuild concluído com sucesso, então uma interrupção
 *   apenas faz a próxima inicialização tentar de novo;
 * - nada aqui é ganho ao vivo: as transações nascem como [XpTransactionOrigin.RECONCILIATION] e
 *   não produzem feedback na interface.
 */
class XpReconciler(
    private val xpTransactionRepository: XpTransactionRepository,
    private val eventRepository: GamificationEventRepository,
    private val xpCalculatorService: XpCalculatorService,
    private val xpPolicyVersionProvider: suspend () -> Int,
    private val xpPolicyVersionWriter: suspend (Int) -> Unit,
    private val firstCompletedWorkoutProvider: suspend () -> CompletedWorkoutReference?
) {
    suspend fun reconcile() {
        val currentVersion = xpPolicyVersionProvider()
        val shouldRebuild = currentVersion < XpRewardPolicy.VERSION

        ensureHistoricalFirstWorkoutEvent()

        val events = eventRepository.getEvents().sortedBy { it.timestamp }
        val expectedTransactions = events.mapNotNull { xpCalculatorService.transactionFor(it) }

        if (shouldRebuild) {
            // Uma única operação: ou o histórico inteiro é substituído, ou o anterior continua valendo.
            xpTransactionRepository.replaceAllTransactions(expectedTransactions)
            // Só agora a política pode ser declarada aplicada.
            xpPolicyVersionWriter(XpRewardPolicy.VERSION)
            return
        }

        // Sem mudança de política: apenas completa lacunas (ex.: falha ao gravar o XP de um evento),
        // sem apagar nada e sem reprocessar o que já existe.
        for (transaction in expectedTransactions) {
            if (!xpTransactionRepository.hasTransactionForEvent(transaction.eventId)) {
                xpTransactionRepository.saveTransaction(transaction, XpTransactionOrigin.RECONCILIATION)
            }
        }
    }

    /**
     * Recria o fato "primeiro treino" apenas quando o histórico real de sessões concluídas prova
     * que ele aconteceu — usando o instante e a sessão verdadeiros, nunca a posição de um evento
     * dentro de um histórico que pode estar incompleto.
     */
    private suspend fun ensureHistoricalFirstWorkoutEvent() {
        val alreadyRecorded = eventRepository
            .getEventsOfType(GamificationEventType.FIRST_WORKOUT_COMPLETED)
            .isNotEmpty()
        if (alreadyRecorded) return

        val firstWorkout = firstCompletedWorkoutProvider() ?: return

        eventRepository.record(
            GamificationEvents.firstWorkoutCompleted(
                sessionId = firstWorkout.sessionId,
                timestamp = firstWorkout.completedAt
            )
        )
    }
}
