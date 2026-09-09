package com.example.domain.social

/**
 * A fronteira do check-in de treino, do Feed e do conteúdo social com o Spark Backend
 * (T17.8, expandida na T17.9).
 *
 * Gateway, e não repositório, pela mesma razão do [SocialGateway]: não há banco atrás, não há
 * `Flow` observável, não há Outbox e não há o que reconciliar. O check-in é **server-authoritative**
 * — quem decide se ele existe é o servidor, que exige uma sessão canônica sincronizada.
 *
 * ## O que esta fronteira nunca faz
 *
 * - **não sobe treino.** Ela manda identificadores e, na T17.9, os bytes de uma foto. A sessão
 *   sobe pelo sync da T16, que é o único caminho de upload de `WorkoutSession` que existe — não
 *   há, e não pode haver, um segundo (§20/§169 da T17.8). Quem coordena "publicar depois que a
 *   sessão chegar" é o `WorkoutCheckInPublisher`, fora deste pacote, porque é ele que pode olhar o
 *   domínio de treino;
 * - **não declara conclusão.** Não existe `completed` no corpo. O servidor recusa esse campo por
 *   nome, e é a fonte canônica dele que responde se a sessão está concluída;
 * - **não decide visibilidade.** Amizade, bloqueio e perfil ativo são reavaliados **no servidor**,
 *   a cada leitura. Filtrar aqui não seria controle de acesso;
 * - **não escreve no Room.** Não existe DAO nem entidade de check-in no aparelho: uma cópia local
 *   seria uma segunda verdade sobre o que está publicado, e a primeira divergência seria um
 *   "Check-in compartilhado" na tela de alguém que já apagou a publicação em outro aparelho. Vale
 *   igual para foto, legenda, reação e comentário (T17.9 §56/§145);
 * - **não retenta sozinha.** Uma ação explícita do usuário produz no máximo uma requisição.
 */
interface WorkoutCheckInGateway {

    /** `true` quando existe endereço de Spark Backend neste build. Sem ele, nada é oferecido. */
    val isConfigured: Boolean

    /**
     * Envia a foto de um check-in **ainda não publicado** (T17.9 §33).
     *
     * Os bytes já saem do aparelho reduzidos (§46), mas isso é economia de banda e nada mais: o
     * servidor decodifica de verdade, revalida dimensões, remove todo o metadata e re-encoda
     * (§14/§15/§16). O que ele guarda nunca são os bytes que este método enviou.
     *
     * [clientUploadId] torna o retry idempotente (§36): o mesmo identificador, para a mesma
     * sessão, devolve o **mesmo** `mediaId` e não duplica arquivo nenhum.
     *
     * O `mediaId` devolvido **não concede acesso** (§51): ele serve para anexar a foto a um
     * check-in, e quem não pode ver a publicação não baixa os bytes nem conhecendo o identificador.
     */
    suspend fun uploadMedia(
        sessionSyncId: String,
        clientUploadId: String,
        bytes: ByteArray
    ): WorkoutCheckInOutcome<UploadedCheckInMedia>

    /**
     * Baixa os bytes de uma foto pelo endpoint **autenticado** (T17.9 §49/§50).
     *
     * Não existe URL pública para nada disso. A autorização é reavaliada a cada chamada, então
     * desfazer amizade, bloquear e desativar o Social revogam o acesso na requisição seguinte —
     * sem depender de invalidar cache nenhum.
     */
    suspend fun mediaBytes(mediaId: String): WorkoutCheckInOutcome<ByteArray>

    /**
     * Publica o check-in da sessão canônica identificada por [sessionSyncId].
     *
     * Idempotente por desenho: o mesmo [clientRequestId] para a mesma sessão devolve o **mesmo**
     * check-in, e outra requisição para a mesma sessão devolve o que já existe. É isso que faz o
     * toque duplo e o retry de resposta perdida convergirem em uma publicação só.
     *
     * [caption] e [mediaId] são opcionais (T17.9 §7/§42). Um [mediaId] recusado **não** publica
     * sem a foto: o servidor recusa a requisição inteira, e a decisão de publicar sem ela volta
     * para o usuário (§43).
     */
    suspend fun createCheckIn(
        sessionSyncId: String,
        clientRequestId: String,
        caption: String? = null,
        mediaId: String? = null
    ): WorkoutCheckInOutcome<WorkoutCheckIn>

    /**
     * O Feed do próprio usuário: as próprias publicações mais as dos amigos diretos atuais.
     *
     * A audiência é **derivada no servidor** — não existe parâmetro que a amplie, e não existe
     * rota que devolva as publicações de alguém nomeado. Amizade, bloqueio e perfil ativo são
     * reavaliados a cada leitura, e por isso desfazer amizade, bloquear e desativar o Social
     * revogam o acesso já na próxima chamada, sem depender de nada guardado aqui.
     */
    suspend fun feed(limit: Int? = null): WorkoutCheckInOutcome<List<WorkoutCheckIn>>

    /** Uma publicação, para a tela de detalhe (T17.9 §118). Mesma política, mesmo DTO do Feed. */
    suspend fun checkIn(checkInId: String): WorkoutCheckInOutcome<WorkoutCheckIn>

    /** Exclui uma publicação própria. Idempotente: repetir converge. */
    suspend fun deleteCheckIn(checkInId: String): WorkoutCheckInOutcome<Unit>

    /**
     * Adiciona ou **troca** a reação (T17.9 §64/§66).
     *
     * Uma operação só para as duas coisas: a chave é `(check-in, pessoa)` no servidor, então
     * 🔥 → 💪 atualiza a mesma reação e nunca cria uma segunda. Devolve a publicação atualizada,
     * que é o que a tela usa para reconciliar depois de uma atualização otimista (§121).
     */
    suspend fun putReaction(
        checkInId: String,
        type: ReactionType
    ): WorkoutCheckInOutcome<WorkoutCheckIn>

    /** Remove a reação (§65). Idempotente: remover o que já não existe é sucesso. */
    suspend fun removeReaction(checkInId: String): WorkoutCheckInOutcome<WorkoutCheckIn>

    /** Os comentários **visíveis para este usuário** (§89/§92). Bounded pelo servidor (§90). */
    suspend fun comments(
        checkInId: String,
        limit: Int? = null
    ): WorkoutCheckInOutcome<List<CheckInComment>>

    /**
     * Comenta (§81).
     *
     * Devolve o comentário **já persistido**, com o identificador que o servidor gerou. É de
     * propósito: comentário não é otimista (§122) — um que aparece e some é pior do que um que
     * demora um instante, porque quem escreveu acredita que a outra pessoa leu.
     */
    suspend fun createComment(
        checkInId: String,
        body: String
    ): WorkoutCheckInOutcome<CheckInComment>

    /** Apaga um comentário. Só o autor dele ou o autor do post (§93/§94). Idempotente (§97). */
    suspend fun deleteComment(checkInId: String, commentId: String): WorkoutCheckInOutcome<Unit>

    /**
     * Denuncia conteúdo (T17.9 §101–§106).
     *
     * O app envia **o que** está sendo denunciado; o servidor descobre de quem é (§103). Não
     * existe — e não pode existir — um campo que diga qual conta denunciar.
     */
    suspend fun reportContent(
        target: SocialReportTarget,
        targetId: String,
        reason: String
    ): WorkoutCheckInOutcome<Unit>
}

/** O que está sendo denunciado (T17.9 §101). Não existe `MEDIA`: a foto é do check-in (§102). */
enum class SocialReportTarget {
    CHECKIN,
    COMMENT
}
