package com.example.presentation.account

import com.example.domain.social.Challenge
import com.example.domain.social.ChallengeDetail
import com.example.domain.social.ChallengeDraft
import com.example.domain.social.ChallengeError
import com.example.domain.social.ChallengeInvite
import com.example.domain.social.ChallengeStatus
import com.example.domain.social.Friend

/**
 * O estado dos desafios na tela (T17.3).
 *
 * ## Fases, e por que não um `isLoading`
 *
 * "Carregando os seus desafios", "você ainda não tem nenhum", "sem internet", "esta conta não
 * ativou o Social" e "o servidor não está configurado neste build" levam a **cinco telas
 * diferentes**. Um booleano colapsaria as cinco — e colapsar "nenhum desafio" com "erro" faria a
 * tela sugerir tentar de novo para um estado que é normal.
 *
 * É a mesma decisão da T17.1 e da T17.2, pelo mesmo motivo.
 */
sealed interface ChallengeListPhase {

    /** Sem endereço de Spark Backend neste build. Nada de social é oferecido. */
    data object NotConfigured : ChallengeListPhase

    /** Sem conta. A tela convida a entrar, e não tenta nada. */
    data object SignedOut : ChallengeListPhase

    /** Nada foi pedido ainda. */
    data object Idle : ChallengeListPhase

    data object Loading : ChallengeListPhase

    /** Carregado. A lista pode estar vazia — e vazio **é** um estado normal, não um erro. */
    data class Ready(val challenges: List<Challenge>) : ChallengeListPhase

    /**
     * Sem internet.
     *
     * A mensagem que importa: nada foi enviado, e nada ficou pendente. Os desafios são
     * server-authoritative; sem servidor não há o que mostrar. **Treinar continua normal.**
     */
    data object Offline : ChallengeListPhase

    /** Esta conta nunca ativou o Social, ou o desativou. */
    data class SocialUnavailable(val disabled: Boolean) : ChallengeListPhase

    data class Error(val error: ChallengeError) : ChallengeListPhase
}

/** O detalhe de um desafio aberto. */
sealed interface ChallengeDetailPhase {
    data object Idle : ChallengeDetailPhase
    data object Loading : ChallengeDetailPhase
    data class Ready(val detail: ChallengeDetail) : ChallengeDetailPhase

    /**
     * O desafio não está disponível.
     *
     * "Não existe", "é de outra pessoa", "eu só fui convidado" e "eu saí" chegam todos aqui, e o
     * app **não** os distingue: o servidor responde a mesma coisa para todos, e adivinhar seria
     * reconstruir na tela a informação que ele recusou dar.
     */
    data object NotAvailable : ChallengeDetailPhase

    data object Offline : ChallengeDetailPhase
    data class Error(val error: ChallengeError) : ChallengeDetailPhase
}

/** A criação, passo a passo. */
sealed interface ChallengeCreationPhase {
    /** A tela de criação está fechada. */
    data object Closed : ChallengeCreationPhase

    /** Preenchendo. O rascunho vive **em memória** — nenhum desafio existe antes do servidor. */
    data object Editing : ChallengeCreationPhase

    /** Enviado; esperando o servidor. É aqui que o botão fica bloqueado contra o toque duplo. */
    data object Submitting : ChallengeCreationPhase

    /** Criado. A tela fecha e a lista recarrega. */
    data class Created(val challenge: Challenge) : ChallengeCreationPhase
}

/**
 * O estado inteiro da área de desafios.
 *
 * ## Ocupação por alvo, e não um booleano global
 *
 * [pendingInvitationIds] e [pendingChallengeIds] guardam **quais** alvos estão com uma requisição
 * em voo. Responder ao convite de um amigo não pode bloquear a resposta ao de outro — a mesma
 * lição da T17.1, onde um `isLoading` único travava a tela inteira por causa de uma linha.
 *
 * O servidor repete a proteção sendo idempotente: dois aceites produzem uma participação, não
 * duas. Os dois lados existem porque um toque duplo rápido chega antes de qualquer estado de tela
 * ter atualizado.
 */
data class ChallengeUiState(
    val listPhase: ChallengeListPhase = ChallengeListPhase.Idle,
    val detailPhase: ChallengeDetailPhase = ChallengeDetailPhase.Idle,
    val creationPhase: ChallengeCreationPhase = ChallengeCreationPhase.Closed,

    /** O desafio aberto agora. Serve para descartar a resposta de um alvo que a tela já trocou. */
    val openedChallengeId: String? = null,

    /** Os convites pendentes desta conta. */
    val invites: List<ChallengeInvite> = emptyList(),
    val isLoadingInvites: Boolean = false,

    /** O rascunho de criação, e os amigos que podem ser escolhidos. */
    val draft: ChallengeDraft = ChallengeDraft(),
    val selectableFriends: List<Friend> = emptyList(),

    /** Alvos com requisição em voo. */
    val pendingInvitationIds: Set<String> = emptySet(),
    val pendingChallengeIds: Set<String> = emptySet(),

    /**
     * O aviso pontual da última ação.
     *
     * Ele **não** substitui a fase: uma ação que falhou não derruba a tela que já estava carregada
     * — o que estava lido continua válido, e o aviso explica o que não aconteceu.
     */
    val notice: ChallengeError? = null
) {
    val isCreating: Boolean get() = creationPhase is ChallengeCreationPhase.Submitting

    /** Quantos convites pendentes — o número que a seção Social mostra sem abrir a lista. */
    val pendingInviteCount: Int get() = invites.size

    /** Os desafios agrupados como a tela os apresenta. A ordem já vem pronta do servidor. */
    val activeChallenges: List<Challenge>
        get() = readyChallenges.filter { it.status == ChallengeStatus.ACTIVE }

    val upcomingChallenges: List<Challenge>
        get() = readyChallenges.filter { it.status == ChallengeStatus.UPCOMING }

    /** Encerrados, cancelados e sem competição: todos "já passaram", para efeito de tela. */
    val finishedChallenges: List<Challenge>
        get() = readyChallenges.filter { it.status.isTerminal }

    private val readyChallenges: List<Challenge>
        get() = (listPhase as? ChallengeListPhase.Ready)?.challenges.orEmpty()
}
