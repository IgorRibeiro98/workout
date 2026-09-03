package com.example.domain.gamification.model

/**
 * Ponto de extensão para o futuro sistema de pontos.
 *
 * T13.0 apenas prepara o contrato: NÃO existe cálculo de XP, níveis ou barra de progresso.
 * Quando o motor de recompensas for construído, ele consumirá [GamificationEvent] e produzirá
 * transações deste tipo — sem que o motor de treino precise conhecer nada disso.
 */
data class XpTransaction(
    val eventId: String,
    val amount: Int,
    val reason: String,
    val createdAt: Long = 0L
)
