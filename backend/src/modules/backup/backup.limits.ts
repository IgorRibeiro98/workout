/**
 * Os tetos do backup no servidor (T16.4).
 *
 * Eles existem no servidor porque o backend não pode supor que só o APK oficial faz requisições —
 * a mesma razão dos tetos do Coach (`ai-coach.limits.ts`). E existem em **um** arquivo para que
 * "quanto cabe num backup" seja uma decisão localizável, e não um número mágico repetido em
 * validador, controller e teste.
 *
 * A escala é deliberadamente pequena: o Spark é um app privado para um grupo pequeno (ADR-0001,
 * "política de custo"). Não há aqui arquitetura para milhões de usuários que não existem.
 */

/**
 * Teto do corpo de `POST /v1/backups`.
 *
 * Ordem de grandeza real: um histórico de alguns anos com sessões, séries, treinos e medidas fica
 * na casa de centenas de kilobytes na forma canônica. 4 MiB deixa folga larga e ainda recusa um
 * payload absurdo antes de ele virar trabalho — e antes de virar linha no SQLite.
 *
 * Acima disso o cliente recebe `BACKUP_TOO_LARGE`. A T16.4 **não** introduz chunking, upload em
 * sessão, streaming nem object storage: um snapshot, uma requisição.
 */
export const MAX_BACKUP_REQUEST_BODY_BYTES = 4 * 1024 * 1024;

export const BACKUP_LIMITS = {
  /** Agregados em um snapshot. */
  maxItems: 5_000,

  /** Bytes de um único item, já na forma canônica. Uma sessão longa não chega perto disso. */
  maxItemBytes: 256 * 1024,

  /** Qualquer string, em qualquer profundidade do payload. */
  maxStringLength: 4_000,

  /** Qualquer coleção aninhada: exercícios de um treino, séries de um exercício. */
  maxCollectionSize: 500,

  /** `clientBackupId`, `deviceId`, `syncId` e afins. UUID tem 36; a folga é para as chaves derivadas. */
  maxIdLength: 200,

  /** `appVersionName` e outros rótulos curtos de diagnóstico. */
  maxLabelLength: 120,
} as const;

/**
 * Proteção por conta nas rotas de backup e de leitura para restore (T16.8 §84).
 *
 * Dois tetos porque os custos são diferentes, e um número só seria errado dos dois lados:
 *
 * - **escrita** é cara — valida um snapshot inteiro, calcula SHA-256 e escreve numa transação.
 *   Um backup legítimo é *uma* requisição; o operador humano que insiste em tentar de novo faz
 *   três ou quatro. Dez por minuto é folga larga sobre isso e ainda barra um app em laço.
 * - **leitura** é barata e o restore precisa de mais de uma: listar, ler metadata, baixar o
 *   conteúdo. Sessenta por minuto nunca alcança um restore legítimo — e é isso que importa, porque
 *   parar um restore é parar o usuário exatamente quando ele mais precisa do servidor (§84).
 *
 * A chave é sempre o `uid` autenticado, nunca o IP (§85).
 */
export const BACKUP_RATE_LIMIT = {
  write: { windowMs: 60_000, maxRequestsPerWindow: 10 },
  read: { windowMs: 60_000, maxRequestsPerWindow: 60 },
} as const;
