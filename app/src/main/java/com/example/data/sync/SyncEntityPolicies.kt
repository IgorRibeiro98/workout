package com.example.data.sync

/**
 * A política de sincronização de cada agregado (T16.7).
 *
 * O espelho TypeScript é `backend/src/modules/sync/sync.policy.ts`, e os dois **precisam**
 * concordar: o servidor é quem recusa, este lado é quem oferece a escolha ao usuário. Uma
 * divergência entre eles produziria um botão que sempre falha — ou, pior, uma tela que promete
 * apagar em todos os aparelhos algo que o servidor nunca aceita.
 *
 * ## Por que um registry, e não `when (entityType)` espalhado
 *
 * "Isto pode ser editado?", "isto pode ser excluído?" e "quem decide quando as duas cópias
 * divergem?" aparecem no builder do push, no apply remoto, na classificação do conflito e na
 * tela. Como condicionais espalhadas, elas divergem; a quarta cópia é a que trata histórico como
 * documento editável. Aqui a resposta é uma tabela.
 *
 * ## Não existe política padrão
 *
 * O mapa cobre [SyncEntityType] inteiro e há teste sobre isso. Acrescentar um agregado ao sync sem
 * declarar a política dele é um erro que o teste pega — e não um `else -> política genérica`, que
 * na prática seria sempre *last write wins*, exatamente o que a T16.7 proíbe.
 */
object SyncEntityPolicies {

    private val policies: Map<SyncEntityType, SyncEntityPolicy> = mapOf(
        SyncEntityType.WORKOUT_PROGRAM to SyncEntityPolicy(
            mutability = SyncMutability.MUTABLE_SNAPSHOT,
            deleteAllowed = true,
            conflictStrategy = SyncConflictStrategy.USER_CHOICE
        ),
        SyncEntityType.WORKOUT_TEMPLATE to SyncEntityPolicy(
            mutability = SyncMutability.MUTABLE_SNAPSHOT,
            deleteAllowed = true,
            conflictStrategy = SyncConflictStrategy.USER_CHOICE
        ),
        SyncEntityType.CUSTOM_EXERCISE to SyncEntityPolicy(
            mutability = SyncMutability.MUTABLE_SNAPSHOT,
            deleteAllowed = true,
            conflictStrategy = SyncConflictStrategy.USER_CHOICE
        ),
        // Cada medida tem `syncId` próprio: duas medidas criadas no mesmo dia em aparelhos
        // diferentes **coexistem**, e transformá-las em conflito inventaria divergência onde há
        // dois fatos. Conflito é editar a **mesma** medida nos dois lados.
        SyncEntityType.BODY_MEASUREMENT to SyncEntityPolicy(
            mutability = SyncMutability.MUTABLE_SNAPSHOT,
            deleteAllowed = true,
            conflictStrategy = SyncConflictStrategy.USER_CHOICE
        ),
        // O Spark não tem caminho de exclusão de check-in: nenhuma tela, nenhum repositório e
        // nenhuma mutação de domínio o produz. O servidor recusa `DELETE` deste tipo, e este lado
        // nem chega a enviá-lo.
        SyncEntityType.CHECK_IN to SyncEntityPolicy(
            mutability = SyncMutability.MUTABLE_SNAPSHOT,
            deleteAllowed = false,
            conflictStrategy = SyncConflictStrategy.USER_CHOICE
        ),
        // Sessão concluída é histórico: divergência é conflito de integridade, nunca uma edição a
        // ser escolhida. Excluir, porém, é direito do usuário — apagar não é reescrever.
        SyncEntityType.WORKOUT_SESSION to SyncEntityPolicy(
            mutability = SyncMutability.IMMUTABLE_HISTORY,
            deleteAllowed = true,
            conflictStrategy = SyncConflictStrategy.IMMUTABLE_CONFLICT
        )
    )

    fun of(entityType: SyncEntityType): SyncEntityPolicy =
        policies[entityType] ?: error("agregado sem política declarada: $entityType")

    fun isDeleteAllowed(entityType: SyncEntityType): Boolean = of(entityType).deleteAllowed

    fun isImmutableHistory(entityType: SyncEntityType): Boolean =
        of(entityType).mutability == SyncMutability.IMMUTABLE_HISTORY
}

/** Como o conteúdo de um agregado evolui. */
enum class SyncMutability {

    /** Plano ou registro editável: uma alteração avança a `revision`. */
    MUTABLE_SNAPSHOT,

    /** Registro do que aconteceu. Nasce em `revision = 1` e nunca muda. */
    IMMUTABLE_HISTORY
}

/**
 * O que se pode oferecer quando as duas cópias divergem.
 *
 * Nenhuma delas é executada sozinha: elas descrevem **que escolha existe**, e quem escolhe é o
 * usuário.
 */
enum class SyncConflictStrategy {

    /** O usuário escolhe entre a versão deste aparelho e a da nuvem. */
    USER_CHOICE,

    /**
     * Divergência é defeito de integridade, não edição concorrente.
     *
     * Não se oferece "manter local"/"usar remoto" para histórico concluído: as duas versões
     * afirmam ter registrado o mesmo treino de formas diferentes, e sobrescrever uma apagaria um
     * fato. O Spark informa e não resolve.
     */
    IMMUTABLE_CONFLICT,

    /**
     * O último a chegar vence, pela **ordem do servidor** (`revision`) — nunca por relógio.
     *
     * Existe como valor declarável e **nenhum agregado do Spark o usa**; há teste sobre isso. Ele
     * só faria sentido para uma preferência escalar de aparelho, e preferências ainda não
     * participam do sync incremental. Nunca é padrão: usar exige escrever aqui, para um tipo
     * específico, com o motivo de domínio junto.
     */
    LAST_WRITE_WINS_ALLOWED
}

data class SyncEntityPolicy(
    val mutability: SyncMutability,
    /** `false` significa que uma exclusão deste agregado não viaja: ela fica local. */
    val deleteAllowed: Boolean,
    val conflictStrategy: SyncConflictStrategy
)
