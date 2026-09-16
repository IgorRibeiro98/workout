package com.example.presentation.coach

import com.example.domain.ai.model.AiCapability

/**
 * Estado de apresentação das capabilities de IA da conta atual (T19.0 §17).
 *
 * Diferencia explicitamente carregando, sem conta, permitido/negado (dentro de [Loaded]) e falha
 * ao carregar — uma falha nunca deve ser lida como permissão (§18), e é por isso que ela é o
 * próprio estado [LoadFailed], em vez de um `Loaded(allowed = emptySet())` indistinguível de uma
 * conta sem nenhuma capability liberada.
 */
sealed interface AiCapabilitiesUiState {
    /** Ainda não houve tentativa de carregar nesta sessão da tela. */
    data object Idle : AiCapabilitiesUiState

    data object Loading : AiCapabilitiesUiState

    data class Loaded(val allowed: Set<AiCapability>) : AiCapabilitiesUiState {
        fun isAllowed(capability: AiCapability): Boolean = capability in allowed
    }

    /** Sem conta conectada. Não é falha — é o mesmo convite que o resto do Coach já mostra. */
    data object SignedOut : AiCapabilitiesUiState

    data object LoadFailed : AiCapabilitiesUiState
}

/**
 * O que uma tela do Coach deve mostrar para uma capability específica — a abstração de
 * apresentação equivalente a "permitido / negado / carregando / falha ao determinar" (T19.0 §17).
 *
 * [SignedOut] cai em [DETERMINING]: sem conta, a resposta não é "negado por entitlement" — é o
 * convite de login que o Coach já mostra desde a T16.2, e esta tarefa não o substitui. Um clique
 * nesse estado segue o caminho de sempre e recebe `AUTH_REQUIRED` do jeito que já recebia.
 */
enum class CoachActionAvailability { ALLOWED, DENIED, DETERMINING, UNKNOWN }

fun AiCapabilitiesUiState.availabilityOf(capability: AiCapability): CoachActionAvailability =
    when (this) {
        AiCapabilitiesUiState.Idle, AiCapabilitiesUiState.Loading, AiCapabilitiesUiState.SignedOut ->
            CoachActionAvailability.DETERMINING

        is AiCapabilitiesUiState.Loaded ->
            if (isAllowed(capability)) CoachActionAvailability.ALLOWED else CoachActionAvailability.DENIED

        AiCapabilitiesUiState.LoadFailed -> CoachActionAvailability.UNKNOWN
    }

/**
 * `true` só quando o servidor **já respondeu** que esta conta não tem [capability].
 *
 * É o que uma tela usa para não disparar uma operação de IA que o backend recusaria — a
 * explicação do Coach, por exemplo, sai local nesse caso, sem requisição. Carregando, sem conta ou
 * falha ao carregar são `false`: nesses estados a tela não sabe, e quem decide continua sendo o
 * backend, a cada chamada real (T19.0).
 */
fun AiCapabilitiesUiState.isKnownDenied(capability: AiCapability): Boolean =
    availabilityOf(capability) == CoachActionAvailability.DENIED
