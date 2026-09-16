package com.example.domain.social

/**
 * O perfil social enriquecido, do ponto de vista do domínio (T17.2).
 *
 * ```text
 * autoridades canônicas do Spark (servidor)
 *          ↓
 * SocialProgressProjection + privacidade do dono
 *          ↓
 * FriendSocialProfile  ← o que chega aqui, já filtrado
 * ```
 *
 * ## O aparelho não decide nada disto
 *
 * Nenhum tipo deste arquivo é calculado no Android. O nível, a sequência e a contagem semanal que
 * o app **mostra do próprio usuário** continuam saindo das autoridades locais de sempre
 * (`UserProgress`, `ConsistencyProgress`); o que está aqui é o que o **servidor** afirmou sobre
 * outra pessoa, e o app não tem como verificar nem completar isso.
 *
 * A consequência prática: um campo ausente é **ausente**. Ele não vira `0`, não vira "nível 1" e
 * não vira "sem conquistas". Por isso cada campo é nulável e a tela renderiza só o que existe —
 * um default numérico aqui seria o app inventando um fato sobre a vida de outra pessoa.
 *
 * ## O que ele nunca carrega
 *
 * Firebase UID, e-mail, `friendCode`, sessão de treino, série, carga, nome de treino, exercício,
 * nota, horário, medida corporal, PR, payload de sync, payload de backup e as flags de privacidade
 * do dono. A ausência é o contrato, e há teste estrutural sobre este arquivo e sobre os DTOs.
 */
data class FriendSocialProfile(
    /** A identidade pública da T17.0. É por ela que a tela pede o perfil. */
    val socialId: String,
    /** O nome social. Visível no contexto autorizado de amizade, sem interruptor próprio. */
    val displayName: String,
    val sharedProgress: SharedProgress
)

/**
 * O progresso que aquela pessoa escolheu compartilhar **e** que o servidor consegue afirmar.
 *
 * Todos nulos por padrão, e o nulo tem um significado só para quem olha: *não está aqui*. O
 * visitante não distingue "escondido" de "indisponível", e isso é deliberado — distinguir contaria
 * a ele a configuração de privacidade de outra pessoa.
 */
data class SharedProgress(
    val level: Int? = null,
    /** Sequência **semanal** de consistência — a semântica canônica do Spark, não dias seguidos. */
    val consistencyStreak: Int? = null,
    val weeklyWorkoutCount: Int? = null,
    val highlightedAchievementIds: List<String> = emptyList()
) {
    /** Não há nada para mostrar? A tela diz "ainda não compartilha", nunca "não treina". */
    val isEmpty: Boolean
        get() = level == null &&
            consistencyStreak == null &&
            weeklyWorkoutCount == null &&
            highlightedAchievementIds.isEmpty()
}

/**
 * O que **eu** compartilho.
 *
 * Os quatro nascem `false` no servidor e continuam `false` aqui enquanto a leitura não chegar:
 * o estado inicial de uma tela de privacidade nunca pode ser "ligado".
 *
 * [weekTimeZone] não é preferência de privacidade — é o fuso que torna a semana canônica
 * reproduzível no servidor. O app o envia junto com a primeira alteração, a partir do próprio
 * aparelho; sem ele, a contagem semanal fica indisponível em vez de virar um palpite.
 */
data class ProgressSharingSettings(
    val shareLevel: Boolean = false,
    val shareConsistencyStreak: Boolean = false,
    val shareWeeklyWorkoutCount: Boolean = false,
    val shareHighlightedAchievements: Boolean = false,
    val weekTimeZone: String? = null,
    /**
     * Os parâmetros de consistência que o servidor conhece deste dono (T19.2A), ou `null`
     * enquanto o app não os enviou. Servem para o app saber **quando** reenviar: quando a meta
     * semanal muda no aparelho, o servidor precisa da nova para derivar a mesma sequência.
     */
    val consistency: SocialConsistencyParameters? = null,
    /** Relógio do **servidor**, epoch millis UTC. */
    val updatedAt: Long = 0L
)

/**
 * A meta semanal por semana e o início do acompanhamento — os dois insumos da consistência que não
 * são sessão (T19.2A).
 *
 * Como o [ProgressSharingSettings.weekTimeZone], isto **não é progresso**: é a configuração que o
 * próprio aparelho lê (`weekly_goal_history` e o início do acompanhamento) para calcular a
 * sequência do dono. O servidor recebe o parâmetro e deriva a sequência das sessões `COMPLETED`
 * sincronizadas — um parâmetro não fabrica treino. O que o app **nunca** envia é o resultado:
 * sequência, nível, XP ou conquista.
 */
data class SocialConsistencyParameters(
    /** Epoch day do calendário local em que o acompanhamento começou. */
    val trackingStartedAtEpochDay: Long,
    val weeklyGoals: List<SocialWeeklyGoal>
)

/** A meta vigente a partir de uma segunda-feira (`epoch day`). */
data class SocialWeeklyGoal(
    val weekStartEpochDay: Long,
    val goal: Int
)

/**
 * O que o servidor consegue mostrar de cada campo — informação **do dono**, e só dele.
 *
 * Ela nunca chega junto com o perfil de um amigo: lá, escondido e indisponível são a mesma
 * ausência. Aqui ela existe porque quem configura precisa distinguir três frases diferentes:
 *
 * ```text
 * AVAILABLE     "Disponível"
 * UNAVAILABLE   "Ainda não disponível"          → sincronizar resolve
 * UNSUPPORTED   "Não disponível nesta versão"   → sincronizar NÃO resolve
 * ```
 *
 * Colapsar as duas últimas faria a tela prometer que sincronizar publicaria o nível.
 *
 * Desde a T19.2 nenhuma das quatro métricas responde `UNSUPPORTED` no servidor atual: nível,
 * sequência e conquistas ganharam autoridade remota. O valor continua no contrato porque um
 * servidor mais antigo ainda o responde, e porque uma métrica futura pode nascer sem autoridade.
 */
enum class SocialFieldAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNSUPPORTED
}

/** A disponibilidade de cada campo do perfil, como o dono a vê. */
data class ProgressSharingAvailability(
    val level: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    val consistencyStreak: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    val weeklyWorkoutCount: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    val highlightedAchievements: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE
)

/** Preferências e disponibilidade juntas: a tela precisa das duas para dizer a frase certa. */
data class ProgressSharing(
    val settings: ProgressSharingSettings,
    val availability: ProgressSharingAvailability
)

/**
 * Por que uma operação do perfil social não completou.
 *
 * Classes de falha, e não mensagens do servidor — a mesma decisão de [SocialError] e
 * [FriendError], pelo mesmo motivo: mensagem de servidor não é texto de UI.
 */
enum class SocialProfileError {

    /** Não há endereço de Spark Backend neste build. Nenhuma requisição foi feita. */
    NOT_CONFIGURED,

    /** É preciso entrar na Conta Spark. */
    AUTH_REQUIRED,

    /** A conta não ativou os recursos sociais. A tela leva ao Perfil. */
    SOCIAL_NOT_ENABLED,

    /** Os recursos sociais desta conta estão desativados. Reativar no Perfil resolve. */
    SOCIAL_DISABLED,

    /**
     * O perfil enriquecido não está disponível.
     *
     * O servidor responde **a mesma coisa** para quatro situações — `socialId` inexistente, perfil
     * desativado, vocês não são amigos e existe só um pedido pendente —, e o app não tenta
     * distinguir. Distinguir seria pedir ao servidor que contasse sobre a vida de terceiros.
     */
    PROFILE_UNAVAILABLE,

    /** O servidor recusou a configuração enviada. Isso é defeito, e o texto convida a relatar. */
    INVALID_SETTINGS,

    /** Muitas requisições para esta conta. Recuperável: esperar resolve. */
    RATE_LIMITED,

    /** O servidor recusou a requisição. */
    REJECTED,

    /** O Spark Backend respondeu indisponível. Recuperável. */
    UNAVAILABLE,

    /** Sem internet ou servidor inalcançável. **Nada foi enviado**, e nada ficou pendente. */
    NETWORK
}

/** O desfecho de uma operação do perfil social. */
sealed interface SocialProfileOutcome<out T> {
    data class Success<T>(val value: T) : SocialProfileOutcome<T>
    data class Failure(val error: SocialProfileError) : SocialProfileOutcome<Nothing>
}
