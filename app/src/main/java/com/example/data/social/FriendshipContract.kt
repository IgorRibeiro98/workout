package com.example.data.social

/**
 * O contrato do grafo social, do lado do Android (T17.1).
 *
 * O espelho TypeScript é `backend/src/modules/social/friendship.contract.ts`, e a descrição
 * legível está em `docs/architecture/social-domain.md`. Mudou de um lado, muda do outro — e as
 * duas versões precisam do mesmo commit.
 *
 * ## O formato do `friendCode` está aqui, e a regra continua sendo do servidor
 *
 * Este arquivo conhece o prefixo, o alfabeto e o comprimento do código. Isso **não** é uma segunda
 * implementação da regra: é o mesmo que a T17.0 já fez com os limites do nome social, e pela mesma
 * razão — a tela precisa responder na hora ("o botão Procurar liga?", "este QR é do Spark?") sem
 * uma ida ao servidor.
 *
 * O que continua valendo:
 *
 * - **quem decide um lookup é o servidor.** O app manda o que o usuário digitou; normalizar aqui
 *   serve para o QR e para o botão, nunca para dizer "esse código não existe";
 * - **os dois lados são amarrados por uma fixture compartilhada** —
 *   `contracts/social/v1/friend-code-normalization.json`, lida pelo teste daqui
 *   (`FriendCodeContractTest`) e pelo do backend (`friend-code-contract.spec.ts`). Uma mudança
 *   unilateral quebra o teste de quem mudou, em vez de virar "o convite do meu amigo não funciona".
 */
object FriendshipContract {

    /** `POST` — resolve um `friendCode` exato. O código vai no **corpo**, nunca na URL. */
    const val LOOKUP_PATH = "v1/social/friends/lookup"

    /** `GET` — meus amigos. */
    const val FRIENDS_PATH = "v1/social/friends"

    /** `POST` — desfaz a amizade. O `socialId` vai no corpo, pelo mesmo motivo do lookup. */
    const val REMOVE_FRIEND_PATH = "v1/social/friends/remove"

    /** `POST` — envia um pedido para um `socialId`. */
    const val FRIEND_REQUESTS_PATH = "v1/social/friend-requests"

    /** `GET` — pedidos recebidos, pendentes. */
    const val INCOMING_PATH = "v1/social/friend-requests/incoming"

    /** `GET` — pedidos enviados, pendentes. */
    const val OUTGOING_PATH = "v1/social/friend-requests/outgoing"

    fun acceptPath(requestId: String): String = "$FRIEND_REQUESTS_PATH/$requestId/accept"

    fun rejectPath(requestId: String): String = "$FRIEND_REQUESTS_PATH/$requestId/reject"

    fun cancelPath(requestId: String): String = "$FRIEND_REQUESTS_PATH/$requestId/cancel"

    // ------------------------------------------------------------------ formato do friendCode

    /** O prefixo fixo. Não carrega entropia: existe para o código ser reconhecível como do Spark. */
    const val FRIEND_CODE_PREFIX = "SPK"

    /** O separador da forma canônica: `SPK-7K2P9D8Q`. A entrada sem ele também é aceita. */
    const val FRIEND_CODE_SEPARATOR = "-"

    /**
     * O alfabeto, sem `0`, `O`, `1`, `I` e `L`.
     *
     * Um código é lido em voz alta, digitado de uma foto e copiado de um bilhete, e cada par
     * ambíguo vira um convite que não funciona. Nenhum mapeamento de confusão é feito na
     * normalização: `O` **não** vira `0` — adivinhar qual das duas leituras a pessoa quis seria
     * inventar o código de outra pessoa.
     */
    const val FRIEND_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Símbolos aleatórios do código. */
    const val FRIEND_CODE_RANDOM_LENGTH = 8

    /**
     * A forma canônica de um código digitado, ou `null` quando ele não é um código.
     *
     * ```text
     * spk-7k2p9d8q   ┐
     * SPK7K2P9D8Q    ├──▶  SPK-7K2P9D8Q
     *  SPK 7K2P 9D8Q ┘
     * ```
     *
     * Maiúsculas em `Locale.US`, e **não** `uppercase()` dependente do locale do aparelho: em
     * turco, `i` vira `İ`, e um app cuja normalização depende do idioma do celular é um app que
     * recusa o código do amigo dependendo de quem está segurando o telefone.
     *
     * `null` em vez de exceção, e sem correção de ambíguos: é a mesma decisão do servidor.
     */
    fun normalizeFriendCode(raw: String): String? {
        val compact = raw.uppercase(java.util.Locale.US).filterNot { it.isWhitespace() || it == '-' }
        if (compact.length != FRIEND_CODE_PREFIX.length + FRIEND_CODE_RANDOM_LENGTH) return null
        if (!compact.startsWith(FRIEND_CODE_PREFIX)) return null

        val random = compact.substring(FRIEND_CODE_PREFIX.length)
        if (random.any { it !in FRIEND_CODE_ALPHABET }) return null

        return "$FRIEND_CODE_PREFIX$FRIEND_CODE_SEPARATOR$random"
    }

    /**
     * O texto digitado já tem forma de código?
     *
     * Só habilita o botão "Procurar". O veredito é sempre do servidor — e um código bem formado
     * que não existe recebe exatamente a mesma resposta de um malformado.
     */
    fun isFriendCodeAcceptable(raw: String): Boolean = normalizeFriendCode(raw) != null

    // ------------------------------------------------------------------ erros

    /** Os códigos do envelope de erro do servidor que o app traduz. */
    object ErrorCodes {
        const val INVALID_FRIEND_REQUEST = "INVALID_FRIEND_REQUEST"
        const val SOCIAL_PROFILE_DISABLED = "SOCIAL_PROFILE_DISABLED"
        const val SOCIAL_PROFILE_NOT_FOUND = "SOCIAL_PROFILE_NOT_FOUND"
        const val SELF_FRIEND_REQUEST = "SELF_FRIEND_REQUEST"
        const val FRIEND_REQUESTS_DISABLED = "FRIEND_REQUESTS_DISABLED"
        const val ALREADY_FRIENDS = "ALREADY_FRIENDS"
        const val FRIEND_REQUEST_NOT_FOUND = "FRIEND_REQUEST_NOT_FOUND"
        const val FRIEND_REQUEST_NOT_PENDING = "FRIEND_REQUEST_NOT_PENDING"
        const val NOT_REQUEST_RECIPIENT = "NOT_REQUEST_RECIPIENT"
        const val NOT_REQUEST_SENDER = "NOT_REQUEST_SENDER"
        const val FRIENDSHIP_NOT_FOUND = "FRIENDSHIP_NOT_FOUND"
        const val SOCIAL_RATE_LIMITED = "SOCIAL_RATE_LIMITED"
    }
}
