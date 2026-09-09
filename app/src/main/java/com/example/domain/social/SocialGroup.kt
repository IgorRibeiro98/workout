package com.example.domain.social

/**
 * Um Squad — um grupo privado de treino (T17.11).
 *
 * ## O que ele é, e o que ele deliberadamente não é
 *
 * Um Squad é um pequeno grupo formado **por convite**, com um feed privado onde os membros
 * compartilham explicitamente check-ins que **já** publicaram. Ele não é um chat, não é uma
 * comunidade pública e não é descobrível: não existe busca de Squad, não existe link de convite,
 * não existe QR e não existe código de entrada (T17.11 §4/§5). Uma pessoa conhece um Squad porque
 * está dentro dele ou porque recebeu um convite — e não há terceira porta.
 *
 * ## Nada disso mora no Room (§114/§115/§116)
 *
 * Não existe entidade, DAO nem Outbox de Squad no aparelho, e um Squad **não** é `sync_entity` da
 * T16. A autoridade é o servidor, e a razão é a mesma do Feed da T17.8: uma cópia local continuaria
 * mostrando o grupo que foi excluído, o membro que saiu e o post que o autor descompartilhou. O
 * cache é de memória e com escopo de conta (§112/§113).
 *
 * Sem backend configurado, Squads simplesmente não são oferecidos (§116) — e todo o núcleo de
 * treino continua funcionando offline, como sempre.
 *
 * [memberCount] inclui o dono (§11) e **inclui** membros que o usuário bloqueou (§35): ele é uma
 * contagem, e não identidade individual.
 */
data class SocialGroup(
    val groupId: String,
    val name: String,
    val memberCount: Int,
    /** O papel **deste** usuário neste Squad. */
    val role: SocialGroupRole,
    val createdAt: Long
)

/**
 * O papel dentro do Squad (§13).
 *
 * Dois, e só dois. Não existe `ADMIN` nem `MODERATOR` nesta fase: cada um exigiria decidir o que
 * pode moderar, e moderação de conteúdo é justamente o que a T17.11 mantém fora — quem tem
 * problema com uma publicação usa Bloqueio e Denúncia, que já existem no domínio (§129).
 */
enum class SocialGroupRole {
    OWNER,
    MEMBER;

    companion object {
        /** `null` para um valor que este app não conhece — um servidor mais novo não quebra a tela. */
        fun fromWire(value: String?): SocialGroupRole? = entries.firstOrNull { it.name == value }
    }
}

/** O cabeçalho da tela de detalhe (§135). */
data class SocialGroupDetail(
    val groupId: String,
    val name: String,
    val memberCount: Int,
    val role: SocialGroupRole,
    val createdAt: Long,
    /** Só o dono recebe — só ele convida (§22). `null` para os demais. */
    val pendingInvitationCount: Int?
) {
    val isOwner: Boolean get() = role == SocialGroupRole.OWNER
}

/**
 * Um participante, como a lista o mostra (§34/§36/§136).
 *
 * Quando existe bloqueio entre o usuário e este participante — em qualquer direção —, [available] é
 * `false` e [socialId]/[displayName] vêm nulos: a tela desenha "Participante indisponível" e não
 * oferece navegação de perfil.
 *
 * [membershipId] existe **sempre**, e é o que preserva a integridade administrativa (§36/§37): o
 * dono consegue remover alguém que o bloqueou sem que a tela jamais receba a identidade daquela
 * pessoa. Ele é um identificador opaco válido só dentro deste grupo, e nunca um uid.
 */
data class SocialGroupMember(
    val membershipId: String,
    val role: SocialGroupRole,
    val joinedAt: Long,
    val available: Boolean,
    val socialId: String?,
    val displayName: String?,
    val isCurrentUser: Boolean
) {
    val isOwner: Boolean get() = role == SocialGroupRole.OWNER
}

/**
 * Um convite recebido (§138/§139).
 *
 * A prévia é **mínima e necessária**: o nome do Squad e quantas pessoas já estão lá são o que
 * permite decidir, e quem convidou é o que dá contexto. A **lista de membros** não vem antes do
 * aceite — quem ainda não entrou não é audiência do grupo (§139).
 */
data class SocialGroupInvitation(
    val invitationId: String,
    val groupId: String,
    val groupName: String,
    val memberCount: Int,
    val inviterSocialId: String?,
    val inviterDisplayName: String?,
    val createdAt: Long,
    val expiresAt: Long
)

/**
 * Um item do feed do Squad (§69/§85/§143).
 *
 * Ele **contém** o [WorkoutCheckIn] da T17.8/T17.9 em vez de redeclarar os campos: é a mesma
 * publicação, e uma segunda forma para o mesmo objeto divergiria no próximo campo novo.
 *
 * [sharedToGroupAt] é a **ação social de compartilhar**, e nunca o horário do treino (§85). É por
 * ela que o feed é ordenado (§84): compartilhar hoje um check-in de ontem precisa aparecer no topo,
 * ou o ato de compartilhar não teria efeito visível para quem já rolou a lista.
 */
data class SocialGroupFeedItem(
    val checkIn: WorkoutCheckIn,
    val sharedToGroupAt: Long
)

/**
 * Por que uma operação de Squad não completou.
 *
 * Classes de falha, e não mensagens do servidor — o mesmo desenho de [WorkoutCheckInError]: cada
 * uma leva a um conselho diferente na tela, e um tipo genérico obrigaria a UI a reinterpretar texto
 * de erro para descobrir qual delas é.
 */
enum class SocialGroupError {

    /** Não há endereço de Spark Backend neste build. Nenhuma requisição foi feita (§116). */
    NOT_CONFIGURED,

    /** É preciso entrar na Conta Spark. */
    AUTH_REQUIRED,

    /** A conta não tem perfil social ativo (§16/§96). */
    SOCIAL_NOT_ENABLED,

    /**
     * O Squad não existe, foi excluído, ou este usuário não é membro dele.
     *
     * O servidor responde a mesma coisa para os três, de propósito (§59/§60): distinguir
     * transformaria a rota num oráculo de existência, e conhecer um `groupId` passaria a valer
     * alguma coisa.
     */
    GROUP_NOT_FOUND,

    /** A ação existe, mas exige ser o dono do Squad (§22/§42/§46). */
    FORBIDDEN,

    /** A conta atingiu o teto de Squads criados (§18). */
    OWNED_LIMIT_REACHED,

    /** A conta atingiu o teto de participações (§18). */
    MEMBERSHIP_LIMIT_REACHED,

    /** O Squad está cheio (§11/§29). */
    GROUP_FULL,

    /**
     * Não é possível convidar esta pessoa (§23/§24/§25/§26).
     *
     * Não é amiga, há bloqueio, ela desativou o Social, ou é o próprio usuário: a mesma resposta
     * para todos. Distinguir transformaria a rota num verificador de `socialId`.
     */
    INVITE_NOT_ALLOWED,

    /** A pessoa já participa do Squad (§27). */
    ALREADY_MEMBER,

    /** O Squad já tem convites pendentes demais (§126). */
    INVITE_LIMIT_REACHED,

    /**
     * O convite não está disponível (§29/§30).
     *
     * Inexistente, já respondido, expirado, Squad cheio, amizade desfeita desde o envio e bloqueio
     * superveniente: todos respondem isto. §30 é explícito sobre o caso central — convidar, deixar
     * de ser amigo, e tentar aceitar.
     */
    INVITATION_NOT_AVAILABLE,

    /** O dono precisa transferir a posse ou excluir o Squad antes (§39/§43/§98). */
    OWNER_ACTION_REQUIRED,

    /** O participante alvo não existe mais neste Squad (§40/§42). */
    MEMBER_NOT_FOUND,

    /** O check-in não existe, não é deste usuário, ou não está publicado (§51/§56). */
    CHECKIN_NOT_FOUND,

    /** O check-in já está em Squads demais (§68). */
    SHARE_LIMIT_REACHED,

    /** O nome do Squad não passou na validação do servidor (§8). */
    INVALID_NAME,

    /** O servidor recusou a requisição. Isso é defeito, e o texto convida a relatar. */
    REJECTED,

    /** O Spark Backend respondeu indisponível. Recuperável. */
    UNAVAILABLE,

    /** Muitas requisições para esta conta. Recuperável. */
    RATE_LIMITED,

    /** Sem internet ou servidor inalcançável. **Nada foi enviado**, e nada ficou pendente. */
    NETWORK
}

/**
 * O desfecho de uma operação de Squad.
 *
 * Não existe estado "pendente" e não existe fila (§115): o social é server-authoritative, e uma
 * operação que não aconteceu simplesmente não aconteceu. O treino, esse, continua local e salvo —
 * a falha aqui não toca nada do domínio de treino.
 */
sealed interface SocialGroupOutcome<out T> {
    data class Success<T>(val data: T) : SocialGroupOutcome<T>
    data class Failure(val error: SocialGroupError) : SocialGroupOutcome<Nothing>
}
