package com.example.data.sync

/**
 * Os agregados sincronizáveis do Spark (T16.3).
 *
 * A sincronização **não** espelha tabela por tabela do Room. Cada valor aqui é a *raiz* de um
 * agregado: a unidade que ganha identidade global, que é serializada inteira e que produz uma
 * mutação. Os filhos viajam dentro do snapshot da raiz e não têm identidade global própria.
 *
 * ```text
 * WORKOUT_TEMPLATE          WORKOUT_SESSION
 * └── template exercises    └── exercise sessions
 *     (ordem + config)          └── set logs
 * ```
 *
 * O que **não** está aqui é tão importante quanto o que está: exercício canônico (identidade é
 * `canonicalId`), catálogo premium, gamificação, XP, conquistas e recordes pessoais (derivados e
 * recalculáveis) e preferências de aparelho. Ver
 * `docs/architecture/data-classification-matrix.md`.
 */
enum class SyncEntityType {
    WORKOUT_PROGRAM,
    WORKOUT_TEMPLATE,
    WORKOUT_SESSION,
    CUSTOM_EXERCISE,
    BODY_MEASUREMENT,
    CHECK_IN
}

/**
 * As operações que uma mutação da Outbox pode representar.
 *
 * Deliberadamente duas. O sync futuro trabalha com **snapshot de agregado**, então
 * `CHANGE_TEMPLATE_NAME`, `MOVE_EXERCISE` e afins não teriam significado do outro lado: todas
 * produziriam o mesmo push. Renomear um treino, reordenar exercícios e mudar a carga de uma série
 * são, para o servidor, a mesma coisa — "este agregado mudou".
 */
enum class SyncOperation {
    /** O agregado existe e seu estado atual deve ser enviado. Cobre criação e edição. */
    UPSERT,

    /** O agregado foi removido localmente. Não há payload a montar. */
    DELETE
}

/**
 * O estado de uma entrada da Outbox.
 *
 * Um único valor, de propósito. Não existe consumidor remoto na T16.3, e `IN_FLIGHT`, `FAILED` ou
 * `SYNCED` seriam estados que nada produz e nada lê — pior, `SYNCED` seria uma mentira sobre dado
 * que nunca saiu do aparelho. A máquina de estados nasce junto com o worker, na T16.6.
 */
enum class SyncOutboxStatus {
    PENDING
}
