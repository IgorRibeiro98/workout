package com.example.data.social

import com.example.domain.social.InteractionContext

/**
 * Os caminhos e os códigos de erro do check-in, do Feed e do conteúdo social (T17.8/T17.9).
 *
 * Os caminhos moram aqui e em mais nenhum lugar — há teste estrutural sobre isso. Uma tela que
 * montasse `"v1/social/feed"` seria um segundo dono do contrato.
 *
 * A T17.9 acrescentou rotas ao **mesmo** agregado (§5): não existe `v1/social/posts`, não existe
 * um segundo Feed, e a mídia entra por um caminho próprio só porque o corpo dela é binário — a
 * política de acesso é a mesma (§128).
 */
object WorkoutCheckInContract {

    const val CHECKINS_PATH = "v1/social/workout-checkins"
    const val FEED_PATH = "v1/social/feed"

    /** Upload de foto de um check-in ainda não publicado (T17.9 §33). */
    const val CHECKIN_MEDIA_PATH = "v1/social/checkin-media"

    /** Denúncia de conteúdo — a mesma rota da T17.6, com alvo desde a T17.9 (§101). */
    const val REPORTS_PATH = "v1/social/reports"

    fun checkInPath(checkInId: String): String = "$CHECKINS_PATH/$checkInId"

    /** O Feed aceita **só** `limit`. Não existe `?users=`, e o servidor recusa qualquer outro. */
    fun feedPath(limit: Int? = null): String =
        if (limit == null) FEED_PATH else "$FEED_PATH?limit=$limit"

    /**
     * Os bytes de uma foto (T17.9 §49).
     *
     * Autenticado, sempre. Não existe versão pública deste caminho, e o servidor não serve
     * diretório estático nenhum — conhecer o `mediaId` não concede acesso (§51).
     */
    fun mediaPath(mediaId: String): String = "v1/social/media/$mediaId"

    /**
     * O upload leva os dois identificadores no query string, porque o corpo **são os bytes**
     * (T17.9 §32/§36).
     */
    fun uploadPath(sessionSyncId: String, clientUploadId: String): String =
        "$CHECKIN_MEDIA_PATH?sessionSyncId=${encode(sessionSyncId)}" +
            "&clientUploadId=${encode(clientUploadId)}"

    /**
     * O detalhe de uma publicação lido **na audiência de onde a tela veio** (T17.12 §35).
     *
     * Sem `context` a rota responde o que respondia na T17.11 — e o app manda `context=FRIEND`
     * explicitamente mesmo assim, para que o caminho de amigos e o de Squad tenham exatamente a
     * mesma forma. Um dos dois montado "por omissão" é o que faz o outro ser esquecido depois.
     */
    fun checkInPath(checkInId: String, context: InteractionContext): String =
        withQuery(checkInPath(checkInId), contextQuery(context))

    fun reactionPath(checkInId: String): String = "${checkInPath(checkInId)}/reaction"

    fun commentsPath(
        checkInId: String,
        limit: Int? = null,
        context: InteractionContext
    ): String {
        val params = buildList {
            if (limit != null) add("limit=$limit")
            addAll(contextQuery(context))
        }
        return withQuery("${checkInPath(checkInId)}/comments", params)
    }

    /**
     * O contexto de audiência no query string (T17.12 §33/§34/§66).
     *
     * ```text
     * ?context=FRIEND            → o Feed de amigos
     * ?context=GROUP&groupId=X   → o Squad X
     * ```
     *
     * `GROUP` **sempre** leva o `groupId` junto: o servidor recusa `context=GROUP` sozinho com
     * `400` em vez de assumir amigos (§69, fail-closed), e é exatamente essa recusa que impede
     * uma requisição malformada de virar uma interação na audiência errada.
     */
    private fun contextQuery(context: InteractionContext): List<String> {
        val type = "context=${context.wireType}"
        val groupId = context.groupId ?: return listOf(type)
        return listOf(type, "groupId=${encode(groupId)}")
    }

    private fun withQuery(base: String, params: List<String>): String =
        if (params.isEmpty()) base else "$base?${params.joinToString("&")}"

    fun commentPath(checkInId: String, commentId: String): String =
        "${checkInPath(checkInId)}/comments/$commentId"

    /**
     * Escapa um identificador para o query string.
     *
     * Os valores aqui são UUIDs gerados pelo próprio app, então na prática nada precisa de escape.
     * Ele existe porque "na prática" é uma garantia que some no dia em que o formato mudar, e uma
     * requisição com `&` no meio de um identificador viraria um parâmetro extra — que o servidor
     * recusa, mas com um erro que ninguém entenderia.
     */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    object ErrorCodes {
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE"
        const val API_RATE_LIMITED = "API_RATE_LIMITED"
        const val INVALID_CHECKIN_REQUEST = "INVALID_CHECKIN_REQUEST"
        const val SOCIAL_NOT_ENABLED = "SOCIAL_NOT_ENABLED"
        const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
        const val SESSION_NOT_COMPLETED = "SESSION_NOT_COMPLETED"
        const val CHECKIN_WINDOW_EXPIRED = "CHECKIN_WINDOW_EXPIRED"
        const val CHECKIN_ALREADY_EXISTS = "CHECKIN_ALREADY_EXISTS"
        const val CHECKIN_REQUEST_CONFLICT = "CHECKIN_REQUEST_CONFLICT"
        const val CHECKIN_NOT_FOUND = "CHECKIN_NOT_FOUND"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val SOCIAL_UNAVAILABLE = "SOCIAL_UNAVAILABLE"

        // --- T17.9 ---------------------------------------------------------------------
        const val INVALID_CONTENT = "INVALID_CONTENT"
        const val INVALID_IMAGE = "INVALID_IMAGE"
        const val MEDIA_TOO_LARGE = "MEDIA_TOO_LARGE"
        const val MEDIA_QUOTA_EXCEEDED = "MEDIA_QUOTA_EXCEEDED"
        const val MEDIA_NOT_FOUND = "MEDIA_NOT_FOUND"
        const val INVALID_REACTION = "INVALID_REACTION"
        const val COMMENT_NOT_FOUND = "COMMENT_NOT_FOUND"
        const val INVALID_REPORT_TARGET = "INVALID_REPORT_TARGET"
    }

    /**
     * Os limites do conteúdo, espelhando os do servidor (T17.9 §7/§76).
     *
     * Eles existem aqui para **resposta imediata na tela** — desabilitar o botão, mostrar o
     * contador —, e nunca como autorização: quem decide é o servidor, que revalida tudo. É a mesma
     * relação da normalização de `friendCode` da T17.1.
     */
    object Limits {
        const val MAX_CAPTION_LENGTH = 280
        const val MAX_COMMENT_LENGTH = 300

        /**
         * O teto de bytes que o app tenta respeitar antes de enviar (§46).
         *
         * Metade do teto do servidor, de propósito: reduzir no aparelho economiza banda de quem
         * está em rede móvel, e chegar perto do limite do servidor só para ele recusar seria gastar
         * a banda duas vezes.
         */
        const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024

        /** A maior aresta que o app envia. O servidor reduz de novo para 1600 (§18). */
        const val MAX_UPLOAD_EDGE_PX = 1920
    }
}
