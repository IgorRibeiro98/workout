package com.example.domain.social

/**
 * Um check-in de treino publicado no Feed social (T17.8).
 *
 * ## O que ele carrega — e o que a ausência significa
 *
 * Nenhum campo aqui é dado de treino. Não existe — nem neste tipo, nem em DTO nenhum, nem no
 * servidor — nome do treino, exercício, série, repetição, carga, duração, volume, recorde,
 * horário do treino, nota ou medida corporal. A ausência não é "ainda não implementamos": é o
 * contrato, e é o que faz o Feed poder existir sem publicar a intimidade do histórico.
 *
 * [publishedAt] é **quando a pessoa publicou**, e nunca quando ela treinou. Os dois instantes
 * seriam parecidos no caminho feliz e completamente diferentes quando alguém compartilha pelo
 * Histórico horas depois — e é justamente o instante do treino que não pode circular.
 *
 * ## O que a T17.9 acrescentou
 *
 * Legenda, foto, reações e comentários — sobre **este** agregado (T17.9 §5). Não existe um segundo
 * tipo de publicação e não existe um segundo Feed. Uma publicação da T17.8 continua válida:
 * [caption] e [media] nulos, [reactions] vazio, [commentCount] zero (§6/§60).
 *
 * Vídeo, GIF animado, múltiplas fotos, edição de publicação, mention, hashtag e link clicável
 * continuam fora (§3).
 */
data class WorkoutCheckIn(
    val checkInId: String,
    val author: SocialCheckInAuthor,
    val publishedAt: Long,
    /** `null` em toda publicação sem legenda, inclusive nas da T17.8 (T17.9 §60). */
    val caption: String? = null,
    /** `null` quando não há foto (T17.9 §42). */
    val media: CheckInMedia? = null,
    /**
     * Contagem por tipo, já filtrada **pelo servidor** para este viewer (T17.9 §69).
     *
     * A tela desenha o número que recebe. Ela **não** soma nem completa nada: a contagem exclui
     * quem o viewer não deveria enxergar por bloqueio, e qualquer aritmética local recolocaria a
     * pessoa de volta no número.
     */
    val reactions: Map<ReactionType, Int> = emptyMap(),
    /** A reação do próprio usuário, quando ele tem uma (T17.9 §70). */
    val currentUserReaction: ReactionType? = null,
    /** Comentários **visíveis para este viewer** (T17.9 §92). Nunca a contagem global. */
    val commentCount: Int = 0,
    /** `true` quando a publicação é do próprio usuário. Decidido pelo servidor, não pela tela. */
    val isCurrentUser: Boolean = false,
    /**
     * Se **este** usuário pode reagir e comentar nesta publicação (T17.11 §70/§72/§144).
     *
     * `true` quando ele alcança a publicação por relação direta — é o autor, ou é amigo dele.
     * `false` quando o único caminho até ela é um Squad compartilhado: a T17.11 **não** amplia a
     * autorização de interação para relação puramente de grupo, porque um mesmo check-in em dois
     * Squads e no Feed de amigos passaria a ter uma conversa com três audiências sobrepostas —
     * e resolver isso exige comentários cientes de audiência, que aquela fase não introduz em
     * silêncio (§71).
     *
     * A tela usa o booleano para não desenhar o que não funciona; o servidor recusa de qualquer
     * forma, porque esconder um botão nunca foi controle de acesso (§72). Toda publicação do Feed
     * de amigos vem com `true`.
     */
    val canInteract: Boolean = true
) {
    /** O total de reações que este viewer enxerga. Derivado, nunca enviado pelo servidor. */
    val totalReactions: Int get() = reactions.values.sum()
}

/**
 * A foto de um check-in, como o Feed a descreve (T17.9 §58/§59).
 *
 * `mediaId` e dimensões, e **nada mais**. Não existe URL: os bytes só saem por
 * `GET /v1/social/media/{mediaId}` com token, e um link que funcionasse sem ele transformaria
 * "amigos" em "qualquer um com o endereço" (§48). As dimensões vêm junto para a tela reservar o
 * espaço certo antes de a imagem chegar — sem elas o Feed pularia a cada foto carregada.
 */
data class CheckInMedia(
    val mediaId: String,
    val width: Int,
    val height: Int
) {
    /** A proporção da foto, para o placeholder. `1f` quando o servidor mandou algo impossível. */
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f
}

/**
 * Os tipos de reação (T17.9 §61/§62).
 *
 * Enum **fechado**, espelhando o do servidor. O cliente não envia emoji: ele envia um destes três
 * nomes, e o servidor recusa qualquer outro valor. Qual emoji desenhar é decisão da tela — o
 * protocolo carrega o nome.
 */
enum class ReactionType {
    FIRE,
    MUSCLE,
    CLAP;

    companion object {
        /** `null` para um valor que este app não conhece — um servidor mais novo não quebra a tela. */
        fun fromWire(value: String?): ReactionType? =
            entries.firstOrNull { it.name == value }
    }
}

/**
 * Um comentário (T17.9 §87).
 *
 * [author] é identidade **pública**. Não existe uid aqui, e o servidor também não o envia (§88).
 *
 * [canDelete] é decidido **no servidor**: o autor do comentário e o autor do check-in podem
 * apagar; um terceiro, não (§93/§94/§95). A tela desenha o item de menu a partir deste booleano em
 * vez de recalcular a regra — e o servidor recusa de qualquer forma, porque esconder um botão
 * nunca foi controle de acesso.
 */
data class CheckInComment(
    val commentId: String,
    val author: SocialCheckInAuthor,
    val body: String,
    val createdAt: Long,
    val isCurrentUser: Boolean,
    val canDelete: Boolean
)

/**
 * Uma foto já enviada e aguardando publicação (T17.9 §33/§41).
 *
 * Ela existe no servidor com estado `PENDING` e um prazo: se a pessoa fechar a tela sem publicar,
 * o servidor a limpa (§38/§39). O app **não** guarda nada disso em disco — o rascunho vive no
 * estado da tela (§146).
 */
data class UploadedCheckInMedia(
    val mediaId: String,
    val width: Int,
    val height: Int,
    val byteSize: Int
)

/** O autor de um check-in: identidade **pública**, e só ela. */
data class SocialCheckInAuthor(
    val socialId: String,
    val displayName: String
)

/**
 * Por que uma operação de check-in não completou.
 *
 * Classes de falha, e não mensagens do servidor — o mesmo desenho de [SocialError]: cada uma leva
 * a um conselho diferente na tela, e um tipo genérico obrigaria a UI a reinterpretar texto de erro
 * para descobrir qual delas é.
 */
enum class WorkoutCheckInError {

    /** Não há endereço de Spark Backend neste build. Nenhuma requisição foi feita. */
    NOT_CONFIGURED,

    /** É preciso entrar na Conta Spark. */
    AUTH_REQUIRED,

    /** A conta não tem perfil social ativo. */
    SOCIAL_NOT_ENABLED,

    /**
     * O servidor não reconhece esta sessão.
     *
     * Ele responde a mesma coisa para "não existe", "é de outra conta" e "ainda não sincronizou",
     * de propósito — distinguir contaria a quem perguntou o que existe na conta dos outros. Quem
     * conclui "então ela ainda não subiu" é **o app**, que sabe ter a sessão localmente; e a
     * conclusão vira [SESSION_NOT_SYNCED] depois de um ciclo de sync que não resolveu.
     */
    SESSION_NOT_FOUND,

    /** A sessão não está concluída. */
    SESSION_NOT_COMPLETED,

    /**
     * A sessão existe no aparelho e o servidor continua não a reconhecendo depois de um ciclo de
     * sync. **Estado do cliente**, não código do servidor: o servidor nunca afirma isso.
     */
    SESSION_NOT_SYNCED,

    /** O treino é antigo demais para virar check-in. */
    CHECKIN_WINDOW_EXPIRED,

    /** Este treino já teve um check-in, e ele foi excluído. Um treino não recebe outro. */
    CHECKIN_ALREADY_EXISTS,

    /** O identificador da requisição já foi usado para outra sessão. */
    CHECKIN_REQUEST_CONFLICT,

    /** O check-in não existe ou não é desta conta — a mesma resposta para os dois. */
    CHECKIN_NOT_FOUND,

    /** O servidor recusou a requisição. Isso é defeito, e o texto convida a relatar. */
    REJECTED,

    /** O Spark Backend respondeu indisponível. Recuperável. */
    UNAVAILABLE,

    /** Muitas requisições para esta conta. Recuperável. */
    RATE_LIMITED,

    /** Sem internet ou servidor inalcançável. **Nada foi enviado**, e nada ficou pendente. */
    NETWORK,

    // --- T17.9 -----------------------------------------------------------------------------

    /** A legenda ou o comentário não passaram na sanitização do servidor (§9/§77). */
    INVALID_CONTENT,

    /** Os bytes enviados não são uma imagem que o servidor aceita (§13/§14/§20). */
    INVALID_IMAGE,

    /** A imagem é maior que o teto do servidor (§18). */
    MEDIA_TOO_LARGE,

    /** O espaço de fotos desta conta acabou (§29). */
    MEDIA_QUOTA_EXCEEDED,

    /**
     * A foto não existe, não é desta conta, não é desta sessão ou já foi usada (§34/§35/§149).
     *
     * O servidor responde a mesma coisa para os quatro. Para a tela, isso significa "a foto que
     * você escolheu não pode ser publicada nesta sessão" — e a decisão volta para o usuário (§43).
     */
    MEDIA_NOT_FOUND,

    /** O comentário não existe, ou quem pediu não pode vê-lo/apagá-lo (§95). */
    COMMENT_NOT_FOUND,

    /** A imagem não pôde ser lida do aparelho antes do envio. Erro local, não do servidor. */
    LOCAL_IMAGE_UNREADABLE
}

/**
 * O desfecho de uma operação de check-in.
 *
 * Não existe estado "pendente" e não existe fila: o social é server-authoritative, e uma
 * publicação que não aconteceu simplesmente não aconteceu (§22). O treino, esse, continua
 * concluído e salvo — a falha aqui não toca nada do domínio de treino.
 */
sealed interface WorkoutCheckInOutcome<out T> {
    data class Success<T>(val data: T) : WorkoutCheckInOutcome<T>
    data class Failure(val error: WorkoutCheckInError) : WorkoutCheckInOutcome<Nothing>
}
