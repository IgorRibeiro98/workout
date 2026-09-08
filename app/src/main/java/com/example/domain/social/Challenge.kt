package com.example.domain.social

/**
 * Os desafios entre amigos, do ponto de vista do domínio (T17.3).
 *
 * ```text
 * Amizade ──convite──▶ ChallengeInvite ──aceitar──▶ participação
 *                                                        │
 *                              dados canônicos de treino (no servidor)
 *                                                        ▼
 *                                            score · rank · goalReached
 * ```
 *
 * ## O aparelho não calcula nada disto
 *
 * Nenhum número deste arquivo nasce no Android. `score`, `rank`, `goalReached` e o `status` do
 * desafio são **derivados no servidor**, dos dados canônicos de treino que já chegaram por sync.
 * O app envia intenção — quais regras, quais amigos, e a resposta a um convite — e nunca
 * pontuação: não existe, e não pode existir, um caminho que mande "estou com 8 de 12".
 *
 * A razão não é desconfiança do próprio app: é que um placar decidido pelo cliente seria um placar
 * que qualquer APK modificado escreve. O servidor conhece as sessões concluídas de cada
 * participante porque elas já chegaram por sync — e é sobre elas que ele conta.
 *
 * ## O que estes tipos nunca carregam
 *
 * Firebase UID, e-mail, `friendCode`, sessão de treino, `syncId`, exercício, série, carga,
 * repetição, nota, horário de treino, medida corporal e payload de sync ou de backup. **Participar
 * de um desafio dá acesso ao placar, e não aos dados que o produziram.** A ausência é o contrato,
 * e há teste estrutural sobre este arquivo e sobre os DTOs.
 */

/**
 * Os tipos de desafio que este Spark conhece.
 *
 * Dois, porque só estes dois têm autoridade canônica **remota**: o servidor sabe quantas sessões
 * `COMPLETED` cada participante tem, e em que dias. Volume, XP, PR, calorias, séries, repetições e
 * tempo de treino exigiriam abrir o conteúdo da sessão — que o domínio social não alcança — ou
 * dependeriam de gamificação, que nunca sai do aparelho.
 */
enum class ChallengeType {
    /** Cada treino concluído durante o período vale 1. */
    WORKOUTS_COMPLETED,

    /** Um ou mais treinos concluídos no mesmo dia contam como 1 dia ativo. */
    ACTIVE_DAYS
}

/**
 * O estado do desafio, **decidido pelo servidor**.
 *
 * O app não o calcula a partir das datas: ele o recebe. Um relógio de aparelho adiantado faria a
 * tela dizer "começou" antes de o servidor aceitar qualquer pontuação, e as duas telas de duas
 * pessoas discordariam sobre o mesmo desafio.
 */
enum class ChallengeStatus {
    /** Ainda não começou. É a única fase em que dá para aceitar convite e cancelar sem perda. */
    UPCOMING,
    ACTIVE,

    /**
     * A **janela de elegibilidade** fechou.
     *
     * Não significa "resultado final": um treino feito dentro do período e sincronizado depois
     * ainda conta. Ver [ChallengeDetail.resultMayStillChange].
     */
    ENDED,

    /** O criador cancelou. Não há vencedor. */
    CANCELLED,

    /** A janela começou sem gente suficiente para competir. Não há vencedor. */
    VOID;

    val isTerminal: Boolean get() = this == ENDED || this == CANCELLED || this == VOID
}

/** O papel de quem participa. */
enum class ChallengeRole { CREATOR, MEMBER }

/** A participação. `WITHDRAWN` é terminal: não há como voltar a um desafio de que se saiu. */
enum class ChallengeParticipantStatus { JOINED, WITHDRAWN }

/**
 * O estado de um convite, como o servidor o resolve.
 *
 * `EXPIRED` e `CANCELLED` não são gravados no servidor — eles são derivados de um convite pendente
 * cujo desafio já começou ou foi cancelado. Para o app tanto faz: o que chega é o estado final, e
 * a tela mostra o que ele diz.
 */
enum class ChallengeInvitationStatus { PENDING, ACCEPTED, DECLINED, EXPIRED, CANCELLED }

/** As regras de um desafio. Imutáveis depois da criação — quem aceitou aceitou estas. */
data class Challenge(
    val challengeId: String,
    val name: String,
    val type: ChallengeType,
    val target: Int,
    /** Data de calendário `AAAA-MM-DD`, inclusiva. */
    val startDate: String,
    /** Data de calendário `AAAA-MM-DD`, inclusiva — o último dia que conta. */
    val endDate: String,
    /**
     * O fuso IANA **do desafio**, e não do aparelho.
     *
     * Um só, para todos os participantes: com um fuso por pessoa, "dia 8" seria um dia diferente
     * para cada um, e `ACTIVE_DAYS` compararia coisas distintas.
     */
    val timeZoneId: String,
    val status: ChallengeStatus,
    /** Quem criou — identidade social mínima, nunca uid. */
    val creator: SocialProfilePreview,
    /** Participantes ativos agora. */
    val participantCount: Int,
    val createdAt: Long
)

/**
 * Uma linha do placar.
 *
 * `score` vem do servidor. Ele **pode ultrapassar** [Challenge.target] — 15 de 12 é 15 —, e é a
 * barra de progresso que se limita a 100%, não o número.
 */
data class ChallengeParticipantScore(
    val socialId: String,
    val displayName: String,
    val score: Int,
    /** `score >= target`. Separado de "líder": várias pessoas podem bater a meta. */
    val goalReached: Boolean,
    /**
     * Posição, em *competition ranking*: `1, 1, 3`.
     *
     * Empate permanece empate. Não há desempate por quem sincronizou primeiro — isso puniria quem
     * treinou offline.
     */
    val rank: Int,
    val role: ChallengeRole,
    /** É a linha de quem está olhando. A tela a destaca discretamente. */
    val isViewer: Boolean
)

/** O que quem está olhando pode fazer. Decidido pelo servidor, e não pela tela. */
data class ChallengeViewer(
    val role: ChallengeRole,
    val status: ChallengeParticipantStatus,
    val canCancel: Boolean,
    val canLeave: Boolean
)

/** O desafio aberto: regras, placar e o que dá para fazer. */
data class ChallengeDetail(
    val challenge: Challenge,
    /** Ordenado por pontuação. Quem saiu não aparece. */
    val participants: List<ChallengeParticipantScore>,
    /** Quantos convites ainda não foram respondidos. Só o criador recebe — `null` para os demais. */
    val pendingInvitationCount: Int?,
    val withdrawnCount: Int,
    val viewer: ChallengeViewer,
    /**
     * O resultado ainda pode mudar quando alguém sincronizar treinos do período.
     *
     * `true` depois que a janela fecha. A tela **precisa** dizer isso quando for verdade: prometer
     * um resultado irrevogável que a sincronização de amanhã pode alterar seria mentir sobre a
     * única coisa que o desafio afirma.
     */
    val resultMayStillChange: Boolean
)

/**
 * O convite, como ele chega antes do aceite.
 *
 * Ele carrega as regras e quem convidou — o suficiente para decidir — e **não** carrega placar,
 * progresso de ninguém nem os nomes dos outros participantes. Ver o progresso dos outros antes de
 * consentir em mostrar o próprio é a assimetria que o consentimento existe para impedir.
 */
data class ChallengeInvite(
    val invitationId: String,
    val challenge: Challenge,
    val status: ChallengeInvitationStatus,
    val createdAt: Long
)

/** Uma página de desafios. */
data class ChallengePage(
    val challenges: List<Challenge>,
    val total: Int,
    val nextCursor: String? = null
)

/** Uma página de convites. */
data class ChallengeInvitePage(
    val invites: List<ChallengeInvite>,
    val total: Int,
    val nextCursor: String? = null
)

/**
 * O rascunho de criação, enquanto a pessoa preenche a tela.
 *
 * Vive **em memória**, e não é persistido: nenhum desafio existe antes de o servidor confirmar, e
 * perder o rascunho ao fechar o app é aceitável — o que não seria aceitável é um desafio meio
 * criado sobrevivendo a um crash.
 */
data class ChallengeDraft(
    val name: String = "",
    val type: ChallengeType = ChallengeType.WORKOUTS_COMPLETED,
    val target: Int = 12,
    val startDate: String = "",
    val endDate: String = "",
    val timeZoneId: String = "",
    val invitedSocialIds: Set<String> = emptySet()
)

/** O desfecho de uma operação de desafio. */
sealed interface ChallengeOutcome<out T> {
    data class Success<T>(val value: T) : ChallengeOutcome<T>
    data class Failure(val error: ChallengeError) : ChallengeOutcome<Nothing>
}

/**
 * Os erros tipados do desafio.
 *
 * O app **não** tenta distinguir o que o servidor colapsou de propósito: "não existe", "não é seu"
 * e "só fui convidado" chegam todos como [NOT_FOUND], e reconstruir a diferença na tela seria
 * recriar a informação que o servidor recusou dar.
 */
enum class ChallengeError {
    /** Sem endereço de Spark Backend neste build. */
    NOT_CONFIGURED,

    /** Sem sessão do Firebase: a requisição nem sai. */
    AUTH_REQUIRED,

    /** Sem internet. **Nada aconteceu** — a ação não fica pendente e não é reenviada. */
    NETWORK,

    /** Esta conta nunca ativou o Social. */
    SOCIAL_NOT_ENABLED,

    /** Esta conta desativou o Social. */
    SOCIAL_DISABLED,

    /** O servidor está indisponível agora. Tentar de novo é a ação certa. */
    UNAVAILABLE,

    /** Teto de requisições da conta. Esperar resolve. */
    RATE_LIMITED,

    /** O desafio não existe — ou não existe para esta conta. */
    NOT_FOUND,

    /** O convite não existe, ou não é desta conta. */
    INVITATION_NOT_FOUND,

    /** O convite já foi respondido. */
    INVITATION_NOT_PENDING,

    /** O desafio já começou: não dá mais para entrar. */
    ALREADY_STARTED,

    /** O desafio foi cancelado. */
    CANCELLED,

    /** Só quem criou pode cancelar. */
    NOT_CREATOR,

    /** Quem criou precisa cancelar em vez de sair. */
    CANNOT_LEAVE_AS_CREATOR,

    /** Um dos amigos escolhidos não está disponível para participar. */
    PARTICIPANT_NOT_AVAILABLE,

    /** Mais gente do que o desafio comporta. */
    TOO_MANY_PARTICIPANTS,

    /** Esta conta já tem desafios demais em andamento. */
    TOO_MANY_OPEN_CHALLENGES,

    /** Tipo, meta, período ou fuso fora das regras. */
    INVALID_CHALLENGE,

    /** O servidor recusou a requisição por outro motivo. */
    REJECTED
}
