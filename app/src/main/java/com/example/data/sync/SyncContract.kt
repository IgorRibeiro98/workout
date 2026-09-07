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
 * Na T16.3 havia um valor só: não existia consumidor remoto, e `IN_FLIGHT`, `FAILED` ou `SYNCED`
 * seriam estados que nada produz e nada lê — pior, `SYNCED` seria uma mentira sobre dado que nunca
 * saiu do aparelho.
 *
 * A T16.6 acrescenta **um** estado, e só um. Continua não existindo `IN_FLIGHT`: uma entrada
 * despachada permanece `PENDING` até o servidor confirmar, e é isso que torna uma resposta perdida
 * recuperável — o reenvio carrega o mesmo `clientMutationId` e volta como `ALREADY_APPLIED`. Um
 * estado "em voo" durável só criaria uma linha que ninguém sabe destravar depois de um crash.
 *
 * E continua não existindo `SYNCED`: uma entrada confirmada é **removida**, porque a intenção foi
 * cumprida e guardá-la com um carimbo criaria uma fila que nada mais consome.
 */
enum class SyncOutboxStatus {

    /** A intenção existe e ainda não foi confirmada pelo servidor. É o estado normal. */
    PENDING,

    /**
     * O servidor recusou esta mutação de um jeito que reenviar **não** resolve.
     *
     * Escrita stale, conflito de histórico imutável, payload inválido ou operação não suportada.
     * A entrada não é apagada — ela é a alteração local, e apagá-la seria descartar em silêncio o
     * que o usuário fez. Ela sai da fila de envio e passa a esperar a T16.7, que é quem resolve
     * conflito. O lado remoto correspondente está em [SyncConflictEntity].
     */
    BLOCKED
}
