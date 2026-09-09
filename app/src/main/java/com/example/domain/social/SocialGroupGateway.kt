package com.example.domain.social

/**
 * A fronteira dos Squads com o Spark Backend (T17.11).
 *
 * Gateway, e não repositório, pela mesma razão do [WorkoutCheckInGateway]: não há banco atrás, não
 * há `Flow` observável, não há Outbox e não há o que reconciliar (§114/§115). Um Squad é
 * **server-authoritative** — quem decide quem está dentro, quem pode convidar e o que aparece no
 * feed é o servidor, a cada leitura.
 *
 * ## O que esta fronteira nunca faz
 *
 * - **não descobre Squads.** Não existe método de busca, e não existe rota para isso (§4/§5). O que
 *   ela lê são os grupos de que o próprio usuário participa e os convites que ele recebeu;
 * - **não decide autorização.** Participação, amizade, bloqueio e perfil ativo são reavaliados
 *   **no servidor**, a cada chamada (§83). Filtrar aqui não seria controle de acesso — o filtro
 *   local existe só para não oferecer o que será recusado;
 * - **não escreve no Room.** Não existe DAO nem entidade de Squad no aparelho, e um Squad não é
 *   `sync_entity` (§114/§115). Uma cópia local seria uma segunda verdade sobre quem está no grupo,
 *   e a primeira divergência seria um card de alguém que já saiu;
 * - **não publica nada sozinha.** [shareCheckIn] só é alcançado por um toque explícito sobre um
 *   check-in que **já** existe (§51/§52). Não há gatilho no fim do treino, no sync ou na abertura
 *   de tela;
 * - **não retenta sozinha.** Uma ação explícita do usuário produz no máximo uma requisição.
 */
interface SocialGroupGateway {

    /** `true` quando existe endereço de Spark Backend neste build. Sem ele, nada é oferecido (§116). */
    val isConfigured: Boolean

    // ------------------------------------------------------------------ squads

    /**
     * Cria um Squad. O criador vira `OWNER` e membro na mesma operação (§17).
     *
     * [clientRequestId] torna o toque duplo idempotente (§146): o mesmo identificador devolve o
     * **mesmo** Squad, em vez de deixar um grupo fantasma na lista.
     */
    suspend fun createGroup(
        name: String,
        clientRequestId: String
    ): SocialGroupOutcome<SocialGroup>

    /** Os Squads de que este usuário participa. Nunca os de terceiros — não existe essa pergunta. */
    suspend fun groups(): SocialGroupOutcome<List<SocialGroup>>

    /** O cabeçalho do detalhe (§135). Exige participação: ter o `groupId` não basta (§59). */
    suspend fun group(groupId: String): SocialGroupOutcome<SocialGroupDetail>

    /** Os participantes, já com a projeção de bloqueio aplicada **pelo servidor** (§34/§136). */
    suspend fun members(groupId: String): SocialGroupOutcome<List<SocialGroupMember>>

    /** Exclui o Squad. Só o dono, e nunca apaga check-in ou treino de ninguém (§46/§47/§48). */
    suspend fun deleteGroup(groupId: String): SocialGroupOutcome<Unit>

    // ------------------------------------------------------------------ convites

    /**
     * Convida um amigo (§22/§23).
     *
     * O alvo é um `socialId` — a identidade pública. Não existe convite por `friendCode`, por nome
     * ou por e-mail (§24), e o servidor **revalida a amizade** mesmo que o seletor do app já filtre
     * (§25): a lista local é conveniência, não autorização.
     */
    suspend fun invite(
        groupId: String,
        socialId: String,
        clientRequestId: String
    ): SocialGroupOutcome<SocialGroupInvitation>

    /** Os convites recebidos e ainda válidos (§138). */
    suspend fun invitations(): SocialGroupOutcome<List<SocialGroupInvitation>>

    /**
     * Aceita um convite (§29).
     *
     * O servidor revalida tudo no instante do aceite: o convite, o prazo, o Squad, a capacidade, a
     * amizade e o bloqueio. Um convite emitido sob uma autorização que já não vale é recusado com
     * [SocialGroupError.INVITATION_NOT_AVAILABLE] (§30).
     */
    suspend fun acceptInvitation(invitationId: String): SocialGroupOutcome<SocialGroup>

    /** Recusa. Só o destinatário. Idempotente. */
    suspend fun declineInvitation(invitationId: String): SocialGroupOutcome<Unit>

    /** Cancela um convite enviado. Só quem enviou. Idempotente. */
    suspend fun cancelInvitation(invitationId: String): SocialGroupOutcome<Unit>

    // ------------------------------------------------------------------ composição

    /** Sai do Squad. Idempotente. O dono **não** sai por aqui: ele transfere ou exclui (§39). */
    suspend fun leaveGroup(groupId: String): SocialGroupOutcome<Unit>

    /**
     * Remove um participante. Só o dono, e nunca a si mesmo (§42/§43).
     *
     * O alvo é o `membershipId` justamente para que o dono possa remover alguém com quem tem
     * bloqueio, sem que a tela precise ter recebido a identidade daquela pessoa (§36).
     */
    suspend fun removeMember(groupId: String, membershipId: String): SocialGroupOutcome<Unit>

    /**
     * Transfere a posse para um participante (§40/§41).
     *
     * Atômica no servidor: nunca zero donos, nunca dois (§150). O alvo é escolhido a partir da
     * lista de membros, e por `membershipId` — um `socialId` permitiria transferir para uma
     * identidade que a tela nunca mostrou (§41).
     */
    suspend fun transferOwnership(
        groupId: String,
        membershipId: String
    ): SocialGroupOutcome<Unit>

    // ------------------------------------------------------------------ feed

    /**
     * Traz um check-in **próprio e já publicado** para este Squad (§51/§53/§56).
     *
     * Idempotente pela chave natural `(squad, check-in)`: compartilhar duas vezes devolve o mesmo
     * vínculo, com a data original (§55). Nada é automático — este método é o único caminho (§52).
     */
    suspend fun shareCheckIn(
        groupId: String,
        checkInId: String
    ): SocialGroupOutcome<SocialGroupFeedItem>

    /**
     * Desfaz o compartilhamento (§128/§130).
     *
     * **Não apaga o check-in**: a publicação continua no Feed de amigos, com legenda, foto, reações
     * e comentários intactos. Some o vínculo, e só ele. Só o autor pode (§130).
     */
    suspend fun unshareCheckIn(groupId: String, checkInId: String): SocialGroupOutcome<Unit>

    /** O feed privado do Squad (§59/§86). Exige participação ativa; não-membro recebe `404`. */
    suspend fun feed(
        groupId: String,
        limit: Int? = null
    ): SocialGroupOutcome<List<SocialGroupFeedItem>>

    /** Em quais Squads este check-in **próprio** já está (§141). Alimenta o seletor da tela. */
    suspend fun groupsForCheckIn(checkInId: String): SocialGroupOutcome<List<String>>
}
