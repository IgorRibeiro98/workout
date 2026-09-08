package com.example.domain.social

/**
 * A fronteira dos desafios com o Spark Backend (T17.3).
 *
 * ```text
 * UI → ChallengeViewModel → ChallengeGateway → Spark Backend
 *                                                   │
 *                                              autoridade
 * ```
 *
 * ## Por que separada de [FriendGateway] e [SocialProfileGateway]
 *
 * As três respondem perguntas diferentes, com autorizações diferentes:
 *
 * ```text
 * FriendGateway          com quem eu me relaciono
 * SocialProfileGateway   o que eu publico no perfil, e o que vejo do perfil de um amigo
 * ChallengeGateway       de que disputas eu participo, e como está o placar delas
 * ```
 *
 * Juntá-las produziria uma interface que a T17.4 teria de dividir de novo — e, pior, criaria a
 * tentação de reusar a autorização de uma na outra. A amizade permite **convidar**; aceitar um
 * convite é que permite compartilhar a pontuação daquele desafio. São consentimentos distintos, e
 * a T17.2 é explícita: perfil não é autoridade de pontuação de desafio.
 *
 * ## O que esta fronteira nunca faz
 *
 * - **não envia pontuação.** Nenhum método daqui aceita `score`, `progress`, `rank` ou `winner`.
 *   Não é disciplina de quem chama: o parâmetro não existe, e o servidor recusaria a requisição
 *   inteira se ele existisse;
 * - **não escreve no Room.** Não existe `ChallengeEntity`, DAO ou transação atrás dela. Uma cópia
 *   local seria uma segunda verdade sobre um fato que é de várias pessoas — e continuaria
 *   mostrando um desafio que o criador cancelou;
 * - **não registra mutação na Outbox.** Offline, criar, aceitar, recusar, sair e cancelar **não
 *   acontecem**: não ficam pendentes e não são reenviados. A tela diz que nada foi enviado;
 * - **não dispara sincronização.** Abrir um desafio não pede push nem pull. O placar é o que o
 *   servidor já sabe — e o que ele ainda não sabe chega no próximo sync do dono daquele treino,
 *   por conta própria;
 * - **não guarda o resultado.** O que é lido vive no estado do ViewModel, em memória, e é
 *   descartado na troca de conta antes de a requisição da conta nova sair.
 *
 * ## O treino continua offline
 *
 * Nada disto alcança execução, histórico, Room ou Outbox. Sem servidor, as telas de desafio ficam
 * indisponíveis e **treinar continua exatamente como era** — inclusive durante um desafio: a
 * sessão concluída offline sobe no próximo sync e passa a contar pelo instante em que aconteceu.
 */
interface ChallengeGateway {

    /** `true` quando existe endereço de Spark Backend neste build. Sem ele, nada é oferecido. */
    val isConfigured: Boolean

    /**
     * Cria um desafio e convida amigos.
     *
     * @param clientRequestId identificador da **tentativa**, gerado pelo app. Ele é o que faz o
     * reenvio depois de uma resposta perdida — e o toque duplo — devolverem o desafio que já foi
     * criado, em vez de criarem um segundo com os mesmos amigos convidados de novo.
     * @param timeZoneId o fuso do aparelho, **sugerido**. O servidor valida e é ele que decide.
     */
    suspend fun create(
        clientRequestId: String,
        name: String,
        type: ChallengeType,
        target: Int,
        startDate: String,
        endDate: String,
        timeZoneId: String,
        invitedSocialIds: List<String>
    ): ChallengeOutcome<Challenge>

    /** Os desafios de que esta conta participa. */
    suspend fun list(cursor: String? = null): ChallengeOutcome<ChallengePage>

    /**
     * Um desafio e o placar dele.
     *
     * A autorização é verificada **nesta** requisição: quem não participa recebe "não encontrado",
     * e é assim que sair de um desafio revoga o acesso na leitura seguinte.
     */
    suspend fun detail(challengeId: String): ChallengeOutcome<ChallengeDetail>

    /** Os convites de desafio pendentes desta conta. */
    suspend fun invites(cursor: String? = null): ChallengeOutcome<ChallengeInvitePage>

    /**
     * Aceita um convite.
     *
     * Consentir aqui significa: os outros participantes passam a ver o nome social e a pontuação
     * **daquele** desafio. Treinos, exercícios, cargas, horários e medidas continuam privados.
     */
    suspend fun accept(invitationId: String): ChallengeOutcome<Challenge>

    /** Recusa um convite. */
    suspend fun decline(invitationId: String): ChallengeOutcome<Unit>

    /** Sai do desafio. Só membro: quem criou cancela. */
    suspend fun leave(challengeId: String): ChallengeOutcome<Unit>

    /** Cancela o desafio. Só quem criou. Ele acaba para todos, sem resultado. */
    suspend fun cancel(challengeId: String): ChallengeOutcome<Unit>
}
