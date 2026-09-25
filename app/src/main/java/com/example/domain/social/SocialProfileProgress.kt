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
    val highlightedAchievementIds: List<String> = emptyList(),
    // --- T19.H3: estatísticas de treino, derivadas no servidor das sessões sincronizadas.
    /** Minutos treinados na semana canônica. */
    val weeklyTrainingMinutes: Int? = null,
    /** Séries de trabalho concluídas na semana canônica (aquecimento não conta). */
    val weeklyCompletedSets: Int? = null,
    /** `Σ peso × reps` da semana canônica, em kg. */
    val weeklyVolumeKg: Double? = null,
    /** Treinos concluídos desde sempre. */
    val totalWorkouts: Int? = null
) {
    /** Não há nada para mostrar? A tela diz "ainda não compartilha", nunca "não treina". */
    val isEmpty: Boolean
        get() = level == null &&
            consistencyStreak == null &&
            weeklyWorkoutCount == null &&
            highlightedAchievementIds.isEmpty() &&
            weeklyTrainingMinutes == null &&
            weeklyCompletedSets == null &&
            weeklyVolumeKg == null &&
            totalWorkouts == null
}

/**
 * A versão do contrato de "Compartilhar progresso" que este APK conhece (T19.H5).
 *
 * ```text
 * 1  T17.2 + T19.2 — os quatro interruptores de progresso geral
 * 2  T19.H3 — estatísticas de treino e detalhes dos check-ins; desde a T19.H5, motivos
 * ```
 *
 * O servidor a **declara** em `contractVersion`. Um servidor que não declara nada é anterior à
 * T19.H5 e é lido como [LEGACY_PROGRESS_SHARING_CONTRACT_VERSION] — mesmo que ele por acaso já
 * conheça os campos da T19.H3: supor que conhece foi exatamente o que fez um APK novo mostrar
 * "Ainda não disponível" e tomar "O servidor recusou esta configuração" contra um servidor antigo.
 */
const val PROGRESS_SHARING_CONTRACT_VERSION = 2

/** O que um servidor que não declara `contractVersion` suporta: os interruptores da T17.2. */
const val LEGACY_PROGRESS_SHARING_CONTRACT_VERSION = 1

/**
 * Cada interruptor de "Compartilhar progresso", pelo nome (T17.2 + T19.H3).
 *
 * Uma lista só: a tela desenha os grupos a partir dela, a ViewModel envia a mudança por ela e o
 * gateway monta o `PATCH` a partir dela. Quinze interruptores escritos à mão em três camadas seriam
 * três chances de um deles salvar e não aparecer, ou aparecer e não salvar.
 */
enum class ProgressSharingField(val group: ProgressSharingGroup) {
    LEVEL(ProgressSharingGroup.GENERAL),
    CONSISTENCY_STREAK(ProgressSharingGroup.GENERAL),
    WEEKLY_WORKOUT_COUNT(ProgressSharingGroup.GENERAL),
    HIGHLIGHTED_ACHIEVEMENTS(ProgressSharingGroup.GENERAL),
    WEEKLY_TRAINING_MINUTES(ProgressSharingGroup.TRAINING_STATS),
    WEEKLY_COMPLETED_SETS(ProgressSharingGroup.TRAINING_STATS),
    WEEKLY_VOLUME(ProgressSharingGroup.TRAINING_STATS),
    TOTAL_WORKOUTS(ProgressSharingGroup.TRAINING_STATS),
    WORKOUT_NAME(ProgressSharingGroup.CHECK_IN_DETAILS),
    WORKOUT_TIME(ProgressSharingGroup.CHECK_IN_DETAILS),
    WORKOUT_DURATION(ProgressSharingGroup.CHECK_IN_DETAILS),
    WORKOUT_EXERCISES(ProgressSharingGroup.CHECK_IN_DETAILS),
    WORKOUT_SETS(ProgressSharingGroup.CHECK_IN_DETAILS),
    WORKOUT_WEIGHTS(ProgressSharingGroup.CHECK_IN_DETAILS),
    WORKOUT_VOLUME(ProgressSharingGroup.CHECK_IN_DETAILS)
}

/**
 * Os três grupos da tela (T19.H3 §23).
 *
 * [TRAINING_STATS] aparece no **perfil**, somado; [CHECK_IN_DETAILS] aparece em **cada check-in**
 * publicado, lido da sessão de origem. A separação é de produto e de privacidade: "como esta
 * pessoa vem treinando?" e "o que aconteceu neste treino?" são perguntas diferentes (§22).
 *
 * [sinceContractVersion] (T19.H5) é a versão de contrato a partir da qual o servidor conhece o
 * grupo. Um servidor abaixo dela recusaria o `PATCH` do interruptor — então a tela não o oferece.
 */
enum class ProgressSharingGroup(val sinceContractVersion: Int) {
    GENERAL(LEGACY_PROGRESS_SHARING_CONTRACT_VERSION),
    TRAINING_STATS(PROGRESS_SHARING_CONTRACT_VERSION),
    CHECK_IN_DETAILS(PROGRESS_SHARING_CONTRACT_VERSION)
}

/** O servidor que declarou [contractVersion] conhece este interruptor? */
fun ProgressSharingField.isSupportedBy(contractVersion: Int): Boolean =
    contractVersion >= group.sinceContractVersion

/**
 * O que **eu** compartilho.
 *
 * Todos nascem `false` no servidor e continuam `false` aqui enquanto a leitura não chegar: o
 * estado inicial de uma tela de privacidade nunca pode ser "ligado". Os onze da T19.H3 também —
 * a migration do servidor os criou desligados para toda conta que já existia.
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
    // --- T19.H3: estatísticas de treino (perfil)
    val shareWeeklyTrainingMinutes: Boolean = false,
    val shareWeeklyCompletedSets: Boolean = false,
    val shareWeeklyVolume: Boolean = false,
    val shareTotalWorkouts: Boolean = false,
    // --- T19.H3: detalhes dos check-ins
    val shareWorkoutName: Boolean = false,
    val shareWorkoutTime: Boolean = false,
    val shareWorkoutDuration: Boolean = false,
    val shareWorkoutExercises: Boolean = false,
    val shareWorkoutSets: Boolean = false,
    /** Só tem efeito com [shareWorkoutSets] e [shareWorkoutExercises] — a carga mora na série. */
    val shareWorkoutWeights: Boolean = false,
    val shareWorkoutVolume: Boolean = false,
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

/** O valor gravado de um interruptor. */
fun ProgressSharingSettings.isShared(field: ProgressSharingField): Boolean = when (field) {
    ProgressSharingField.LEVEL -> shareLevel
    ProgressSharingField.CONSISTENCY_STREAK -> shareConsistencyStreak
    ProgressSharingField.WEEKLY_WORKOUT_COUNT -> shareWeeklyWorkoutCount
    ProgressSharingField.HIGHLIGHTED_ACHIEVEMENTS -> shareHighlightedAchievements
    ProgressSharingField.WEEKLY_TRAINING_MINUTES -> shareWeeklyTrainingMinutes
    ProgressSharingField.WEEKLY_COMPLETED_SETS -> shareWeeklyCompletedSets
    ProgressSharingField.WEEKLY_VOLUME -> shareWeeklyVolume
    ProgressSharingField.TOTAL_WORKOUTS -> shareTotalWorkouts
    ProgressSharingField.WORKOUT_NAME -> shareWorkoutName
    ProgressSharingField.WORKOUT_TIME -> shareWorkoutTime
    ProgressSharingField.WORKOUT_DURATION -> shareWorkoutDuration
    ProgressSharingField.WORKOUT_EXERCISES -> shareWorkoutExercises
    ProgressSharingField.WORKOUT_SETS -> shareWorkoutSets
    ProgressSharingField.WORKOUT_WEIGHTS -> shareWorkoutWeights
    ProgressSharingField.WORKOUT_VOLUME -> shareWorkoutVolume
}

/** O mesmo conjunto, com [field] mudado. Semântica de `PATCH`: o resto fica como está. */
fun ProgressSharingSettings.with(field: ProgressSharingField, value: Boolean): ProgressSharingSettings =
    when (field) {
        ProgressSharingField.LEVEL -> copy(shareLevel = value)
        ProgressSharingField.CONSISTENCY_STREAK -> copy(shareConsistencyStreak = value)
        ProgressSharingField.WEEKLY_WORKOUT_COUNT -> copy(shareWeeklyWorkoutCount = value)
        ProgressSharingField.HIGHLIGHTED_ACHIEVEMENTS -> copy(shareHighlightedAchievements = value)
        ProgressSharingField.WEEKLY_TRAINING_MINUTES -> copy(shareWeeklyTrainingMinutes = value)
        ProgressSharingField.WEEKLY_COMPLETED_SETS -> copy(shareWeeklyCompletedSets = value)
        ProgressSharingField.WEEKLY_VOLUME -> copy(shareWeeklyVolume = value)
        ProgressSharingField.TOTAL_WORKOUTS -> copy(shareTotalWorkouts = value)
        ProgressSharingField.WORKOUT_NAME -> copy(shareWorkoutName = value)
        ProgressSharingField.WORKOUT_TIME -> copy(shareWorkoutTime = value)
        ProgressSharingField.WORKOUT_DURATION -> copy(shareWorkoutDuration = value)
        ProgressSharingField.WORKOUT_EXERCISES -> copy(shareWorkoutExercises = value)
        ProgressSharingField.WORKOUT_SETS -> copy(shareWorkoutSets = value)
        ProgressSharingField.WORKOUT_WEIGHTS -> copy(shareWorkoutWeights = value)
        ProgressSharingField.WORKOUT_VOLUME -> copy(shareWorkoutVolume = value)
    }

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
 * UNAVAILABLE   "Ainda não disponível"   → sincronizar resolve
 * UNSUPPORTED   "Em breve"                → sincronizar NÃO resolve
 * ```
 *
 * Colapsar as duas últimas faria a tela prometer que sincronizar publicaria o nível.
 *
 * Desde a T19.2 nenhuma das quatro métricas responde `UNSUPPORTED` no servidor atual: nível,
 * sequência e conquistas ganharam autoridade remota. O valor continua no contrato porque um
 * servidor mais antigo ainda o responde, e porque uma métrica futura pode nascer sem autoridade.
 *
 * A T19.H0 trocou só a apresentação de `UNSUPPORTED`: a etiqueta e o interruptor não podem
 * sugerir "APK antigo" ou "atualização pendente" — é uma limitação arquitetural, não uma versão
 * desatualizada. Ver [com.example.presentation.friends.availabilityLabel].
 */
enum class SocialFieldAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNSUPPORTED
}

/**
 * **Por que** um campo não está disponível — informação do dono, e só dele (T19.H5).
 *
 * Até a T19.H3 a tela dizia "Seu progresso compartilhado é atualizado depois da sincronização"
 * para qualquer indisponibilidade — e sincronizar não resolvia metade delas. Os quatro primeiros
 * valores vêm do servidor (`availabilityReasons`) e correspondem a condições reais da projeção; os
 * dois últimos são do app.
 */
enum class SocialAvailabilityReason {
    /** Nenhum treino concluído desta conta chegou ao servidor. Sincronizar resolve, se houver treino. */
    NO_SYNCED_WORKOUTS,

    /** O servidor não conhece o fuso da semana. O app o declara sozinho ao abrir a tela com conexão. */
    WEEK_TIME_ZONE_MISSING,

    /** Faltam a meta semanal e o início do acompanhamento. O app os declara sozinho, também ao abrir. */
    CONSISTENCY_PARAMETERS_MISSING,

    /** Mais treinos nesta semana do que o servidor soma de uma vez; ele não publica soma truncada. */
    SOURCE_LIMIT_REACHED,

    /**
     * O servidor não declarou conhecer este campo (contrato abaixo da versão do grupo). Derivado
     * aqui — o servidor não sabe que o app o conhece. Não é "ainda não disponível": sincronizar
     * nunca resolve, só a atualização do servidor.
     */
    LEGACY_BACKEND,

    /** O servidor não disse por quê, ou disse algo que este APK não conhece. */
    UNKNOWN
}

/** O estado de um campo e, quando ele não está disponível, o motivo (T19.H5). */
data class SocialFieldAvailabilityDetail(
    val status: SocialFieldAvailability,
    /** `null` quando o campo está [SocialFieldAvailability.AVAILABLE]. */
    val reason: SocialAvailabilityReason?
)

/** A disponibilidade de cada campo do perfil, como o dono a vê. */
data class ProgressSharingAvailability(
    val level: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    val consistencyStreak: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    val weeklyWorkoutCount: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    val highlightedAchievements: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    // --- T19.H3
    val weeklyTrainingMinutes: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    val weeklyCompletedSets: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    val weeklyVolume: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    val totalWorkouts: SocialFieldAvailability = SocialFieldAvailability.UNAVAILABLE,
    /**
     * O motivo de cada campo indisponível (T19.H5). Um campo `UNAVAILABLE` fora do mapa tem motivo
     * [SocialAvailabilityReason.UNKNOWN] — o servidor não disse, e o app não inventa.
     */
    val reasons: Map<ProgressSharingField, SocialAvailabilityReason> = emptyMap()
) {
    /**
     * O estado e o motivo de um campo, ou `null` quando disponibilidade não é pergunta do perfil
     * (os detalhes de check-in — ver [of]).
     */
    fun detailOf(field: ProgressSharingField): SocialFieldAvailabilityDetail? {
        val status = of(field) ?: return null
        val reason = when (status) {
            SocialFieldAvailability.AVAILABLE -> null
            SocialFieldAvailability.UNAVAILABLE -> reasons[field] ?: SocialAvailabilityReason.UNKNOWN
            SocialFieldAvailability.UNSUPPORTED -> reasons[field]
        }
        return SocialFieldAvailabilityDetail(status, reason)
    }

    /**
     * Algum campo falta porque o servidor não tem treino desta conta? É o único motivo que
     * sincronizar resolve — e é quando a tela oferece "Sincronizar dados" (T19.H5 §13).
     */
    val needsSync: Boolean
        get() = ProgressSharingField.entries.any {
            detailOf(it)?.reason == SocialAvailabilityReason.NO_SYNCED_WORKOUTS
        }

    /**
     * A disponibilidade de um campo, ou `null` quando ela não é uma pergunta do perfil.
     *
     * Os detalhes de check-in não têm disponibilidade aqui: eles dependem de cada publicação (a
     * sessão de origem), e a tela explica isso em vez de mostrar "Disponível" para um campo que o
     * servidor não avalia de antemão.
     */
    fun of(field: ProgressSharingField): SocialFieldAvailability? = when (field) {
        ProgressSharingField.LEVEL -> level
        ProgressSharingField.CONSISTENCY_STREAK -> consistencyStreak
        ProgressSharingField.WEEKLY_WORKOUT_COUNT -> weeklyWorkoutCount
        ProgressSharingField.HIGHLIGHTED_ACHIEVEMENTS -> highlightedAchievements
        ProgressSharingField.WEEKLY_TRAINING_MINUTES -> weeklyTrainingMinutes
        ProgressSharingField.WEEKLY_COMPLETED_SETS -> weeklyCompletedSets
        ProgressSharingField.WEEKLY_VOLUME -> weeklyVolume
        ProgressSharingField.TOTAL_WORKOUTS -> totalWorkouts
        else -> null
    }
}

/** Preferências e disponibilidade juntas: a tela precisa das duas para dizer a frase certa. */
data class ProgressSharing(
    val settings: ProgressSharingSettings,
    val availability: ProgressSharingAvailability,
    /**
     * O que o servidor declarou conhecer (T19.H5). Abaixo de [PROGRESS_SHARING_CONTRACT_VERSION], os
     * grupos mais novos não são oferecidos — o `PATCH` deles seria recusado.
     */
    val contractVersion: Int = PROGRESS_SHARING_CONTRACT_VERSION
)

/**
 * O desfecho de "Sincronizar dados" pedido pela tela de compartilhamento (T19.H5), no vocabulário
 * do Social.
 *
 * O Social não conhece o protocolo de sync — Outbox, cursor, revisão — e não pode conhecer: é a
 * fronteira da T17.0, e um teste estrutural a vigia. Quem roda o ciclo é o `SyncCoordinator` da
 * T16, montado **fora** do pacote social; o que atravessa a fronteira é só esta tradução. Não existe
 * sync social: é o mesmo ciclo do "Sincronizar agora" do Perfil.
 */
enum class SocialSyncResult {
    /** O ciclo terminou. A tela relê o compartilhamento: é o servidor quem reprojeta. */
    SYNCED,

    /** O ciclo terminou com itens que precisam de decisão ou de atualização do app (Perfil › Sincronização). */
    NEEDS_ATTENTION,

    /** Este aparelho não sincroniza (nenhum backup ativado para a conta). Nada foi enviado. */
    NOT_ENABLED,

    /** É preciso entrar na Conta Spark. */
    AUTH_REQUIRED,

    /** Os dados deste aparelho pertencem a outra Conta Spark. Nada foi enviado. */
    ACCOUNT_MISMATCH,

    /** Sem rede. A fila local continua intacta; nada se perdeu. */
    OFFLINE,

    /** O servidor recusou ou está indisponível. Recuperável. */
    FAILED,

    /** Já havia um ciclo rodando, e ele não terminou a tempo de a tela esperar por ele. */
    ALREADY_RUNNING
}

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
