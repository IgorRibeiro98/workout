package com.example.domain.social

/**
 * O perfil social do usuário, do ponto de vista do domínio do Spark (T17.0).
 *
 * ## Ele é uma **cópia lida**, nunca a autoridade
 *
 * O treino do Spark é local-first: a ação vai para o Room e a tela observa o Room. O social é o
 * contrário — a autoridade é o Spark Backend, e o que existe no aparelho é o resultado da última
 * leitura. Por isso este tipo não tem `localId`, não tem entidade Room correspondente, não entra
 * na Outbox e não é salvo em lugar nenhum: guardá-lo criaria uma segunda verdade sobre a
 * identidade social, e a primeira divergência seria um `friendCode` que a pessoa mostra para um
 * amigo e que o servidor não reconhece.
 *
 * ## As identidades, e o que cada uma é
 *
 * ```text
 * Firebase UID  -> quem é o usuário           identidade PRIVADA de infraestrutura
 * socialId      -> quem é o usuário no social identidade PÚBLICA e estável
 * friendCode    -> como convidar essa pessoa  compartilhável, não é credencial
 * deviceId      -> qual instalação            nunca social
 * syncId        -> qual entidade de treino    nunca social
 * ```
 *
 * O Firebase UID **não aparece aqui**, e a ausência é o contrato: ele é identidade de conta, não
 * identidade pública. Ver `docs/architecture/identity-contract.md`.
 */
data class SocialProfile(
    /** Identidade social estável, gerada pelo servidor. Imutável — nem o app nem o usuário a mudam. */
    val socialId: String,
    /** Código humano de convite, na forma canônica `SPK-XXXXXXXX`. Também do servidor. */
    val friendCode: String,
    /** O nome social. Independente do nome da conta Google, e não precisa ser único. */
    val displayName: String,
    val status: SocialProfileStatus,
    val privacy: SocialPrivacySettings,
    /** Relógio do **servidor**, epoch millis UTC. O relógio do aparelho não decide nada aqui. */
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * O estado do perfil social.
 *
 * A ausência de perfil — "nunca ativei" — não é um valor deste enum: ela é um estado da tela
 * (`SocialPhase.NotEnabled`), porque não existe perfil para carregar um status.
 */
enum class SocialProfileStatus {
    ACTIVE,
    DISABLED
}

/**
 * Quem pode encontrar o perfil.
 *
 * Um valor só, e é o padrão. Não existe `PUBLIC_SEARCH` nem `GLOBAL_PROFILE` porque não existe
 * busca pública, busca por e-mail nem listagem de usuários no Spark Backend — oferecer a opção
 * seria descrever um comportamento que não existe.
 */
enum class SocialDiscoverability {
    FRIEND_CODE_ONLY
}

/**
 * As configurações de privacidade, com os defaults conservadores da T17.0.
 *
 * [activitySharingEnabled] nasce `false`: nenhum feed, ranking ou atividade existe hoje, e quando
 * existir (T17.4) ele lê esta preferência. Um default `true` publicaria, no dia em que a feature
 * nascesse, a atividade de quem nunca escolheu publicar.
 */
data class SocialPrivacySettings(
    val discoverability: SocialDiscoverability = SocialDiscoverability.FRIEND_CODE_ONLY,
    val friendRequestsEnabled: Boolean = true,
    val activitySharingEnabled: Boolean = false,
    val activityTimeZoneId: String? = null,
    val friendRankingParticipationEnabled: Boolean = false,
    /** Relógio do servidor, epoch millis UTC. */
    val updatedAt: Long = 0L
)
