/**
 * Os tetos do sync no servidor (T16.6).
 *
 * Eles existem aqui porque o backend não pode supor que só o APK oficial faz requisições — a mesma
 * razão dos tetos do Coach e do backup. E existem em **um** arquivo para que "quanto cabe num
 * push" seja uma decisão localizável, e não um número mágico repetido em validador, controller e
 * teste.
 *
 * A escala é deliberadamente pequena: o Spark é um app privado para um grupo pequeno (ADR-0001).
 */

/**
 * Teto do corpo de `POST /v1/sync/push`.
 *
 * Bem menor que o do backup (4 MiB): um push carrega as mudanças pendentes, não o dataset. Com
 * `maxMutations` × `maxMutationPayloadBytes` o pior caso legítimo fica confortavelmente abaixo.
 */
export const MAX_SYNC_PUSH_BODY_BYTES = 2 * 1024 * 1024;

export const SYNC_LIMITS = {
  /**
   * Mutações em um push.
   *
   * Nem uma requisição por campo editado, nem um lote ilimitado: o cliente fatia a Outbox em
   * lotes deste tamanho e envia em ordem de `id`.
   */
  maxMutations: 50,

  /** Bytes de um único payload de mutação, já na forma canônica. */
  maxMutationPayloadBytes: 256 * 1024,

  /** `clientMutationId`, `deviceId`, `syncId`. UUID tem 36; a folga é para chaves derivadas. */
  maxIdLength: 200,

  /** Página de pull pedida pelo cliente quando ele não diz nada. */
  defaultPullPageSize: 100,

  /** Teto da página de pull. Uma conta com histórico longo é paginada, não despejada. */
  maxPullPageSize: 200,
} as const;

/**
 * Proteção simples por conta contra um app em laço (§99 da T16.6).
 *
 * Não é rate limiting sofisticado e não pretende ser: o objetivo é que um defeito no cliente não
 * vire milhares de requisições por minuto contra a VPS. Uma janela, uma contagem por `uid`.
 */
export const SYNC_RATE_LIMIT = {
  windowMs: 60_000,
  maxRequestsPerWindow: 60,
} as const;
