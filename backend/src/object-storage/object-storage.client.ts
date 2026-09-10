import type { Readable } from 'node:stream';

/**
 * A fronteira neutra de Object Storage (T18.1).
 *
 * ## O que ela é
 *
 * A única abstração do processo que sabe **onde os bytes moram**: um sistema de arquivos local
 * (desenvolvimento, teste, CI) ou um bucket privado do Google Cloud Storage (produção). Duas
 * fronteiras de domínio se apoiam nela, e só elas:
 *
 * ```text
 * ObjectStorageClient
 *         │
 *         ├── SocialMediaStore      (modules/social)   social/checkins/xx/yy/<uuid>.webp
 *         └── BackupPayloadStore    (modules/backup)   backups/xx/yy/<backupId>.json
 * ```
 *
 * Social e Backup usam o **mesmo** bucket e continuam não se conhecendo: esta camada mora fora
 * dos dois módulos justamente para que nenhum precise importar o outro. Há teste estrutural.
 *
 * ## Estreita de propósito
 *
 * Gravar (uma vez), ler, existir, apagar (idempotente) e listar (paginado, sempre por prefixo).
 * Nada de URL assinada, ACL, `makePublic`, cópia, versão ou metadata mutável — não existe
 * caso de uso, e cada operação a mais é uma superfície a mais para a foto de alguém virar pública.
 *
 * ## Semântica que toda implementação precisa honrar
 *
 * - **um nome é um objeto imutável.** `write` em nome já ocupado **falha**
 *   ([ObjectAlreadyExistsError]); nunca sobrescreve. No GCS isso é `ifGenerationMatch = 0`; no
 *   disco é `wx`. Mesmo uma colisão improvável de UUID não pode substituir a foto de outra pessoa;
 * - **ausência é `null`/`false`, nunca exceção** — mas só ausência. Timeout, permissão negada,
 *   quota, rede e qualquer outra falha de infraestrutura sobem como
 *   [ObjectStorageUnavailableError]: converter tudo em "não existe" esconderia um bucket fora do
 *   ar atrás de um `404` para o usuário;
 * - **`remove` de objeto ausente é sucesso** — a limpeza e a exclusão de conta repetem remoções;
 * - **a listagem é bounded.** Uma página por chamada, sempre com prefixo, com `createdAt` de cada
 *   objeto — é o que permite ao coletor de órfãos respeitar um período de carência sem uma
 *   segunda requisição por objeto.
 */
export interface ObjectStorageClient {
  readonly provider: 'local' | 'gcs';

  /**
   * Cria o objeto. Falha se [name] já existir — nunca sobrescreve.
   *
   * A implementação verifica a integridade dos bytes gravados quando o provider oferece isso
   * (CRC32C no GCS). [WriteObjectOptions.metadata] é anotação segura do objeto — hash, versão —
   * e nunca substitui essa verificação.
   */
  write(name: string, bytes: Buffer, options: WriteObjectOptions): Promise<void>;

  /** Os bytes inteiros, ou `null` quando o objeto não existe. */
  read(name: string): Promise<Buffer | null>;

  /** Um stream de leitura, ou `null` quando o objeto não existe. */
  openRead(name: string): Promise<Readable | null>;

  exists(name: string): Promise<boolean>;

  /** Idempotente: apagar o que já não existe é sucesso. */
  remove(name: string): Promise<void>;

  /** Uma página dos objetos sob [prefix]. Nunca o bucket inteiro. */
  list(prefix: string, options: ListObjectsOptions): Promise<ObjectPage>;
}

export const OBJECT_STORAGE_CLIENT = Symbol('OBJECT_STORAGE_CLIENT');

export interface WriteObjectOptions {
  readonly contentType: string;
  /** Metadata segura do objeto (ex.: `spark-sha256`). Nunca conteúdo, nunca identidade. */
  readonly metadata?: Readonly<Record<string, string>>;
}

export interface ListObjectsOptions {
  /** Quantos objetos, no máximo, esta página traz. */
  readonly pageSize: number;
  /** O cursor devolvido pela página anterior. Ausente = do começo. */
  readonly pageToken?: string;
}

export interface StoredObjectSummary {
  readonly name: string;
  /** Quando o objeto foi criado, em epoch millis. É o que o período de carência dos órfãos lê. */
  readonly createdAt: number;
  readonly size: number;
}

export interface ObjectPage {
  readonly objects: readonly StoredObjectSummary[];
  /** Ausente quando não há mais páginas. */
  readonly nextPageToken?: string;
}

// ------------------------------------------------------------------ erros

/** `write` sobre um nome já ocupado. O objeto existente **não** foi tocado. */
export class ObjectAlreadyExistsError extends Error {
  constructor() {
    super('o objeto já existe e não pode ser sobrescrito');
    this.name = 'ObjectAlreadyExistsError';
  }
}

/** Nome fora da forma permitida. Sem o valor na mensagem: ele pode acabar em log. */
export class UnsafeObjectNameError extends Error {
  constructor() {
    super('nome de objeto inválido');
    this.name = 'UnsafeObjectNameError';
  }
}

/**
 * O provider não conseguiu responder: rede, timeout, permissão, quota, disco.
 *
 * Distinta de "não existe" de propósito (§40 da T18.1). Quem chama decide o que dizer ao cliente
 * — em geral `503`, nunca `404` — e o log carrega só metadata: operação, provider, nome do erro.
 */
export class ObjectStorageUnavailableError extends Error {
  constructor(
    readonly operation: string,
    readonly cause: unknown,
  ) {
    super(
      `object storage indisponível em ${operation}: ${
        cause instanceof Error ? cause.name : 'erro desconhecido'
      }`,
    );
    this.name = 'ObjectStorageUnavailableError';
  }
}

// ------------------------------------------------------------------ nomes

/**
 * A forma de um nome de objeto aceitável por qualquer provider.
 *
 * Allowlist, e não blocklist: segmentos de `[A-Za-z0-9._-]`, separados por `/`, sem segmento
 * vazio, sem `.`/`..`, sem barra inicial ou final, até 1024 bytes (o teto do GCS). Cada store
 * ainda valida a **sua** forma exata por cima (`checkins/xx/yy/<uuid>.webp`,
 * `backups/xx/yy/<uuid>.json`); esta é a barreira comum, que também protege a tradução de nome
 * para caminho do provider local contra path traversal.
 */
const SEGMENT = /^[A-Za-z0-9_][A-Za-z0-9._-]*$/;
const MAX_NAME_BYTES = 1024;

export function assertSafeObjectName(name: unknown): string {
  if (typeof name !== 'string' || name.length === 0 || Buffer.byteLength(name) > MAX_NAME_BYTES) {
    throw new UnsafeObjectNameError();
  }
  const segments = name.split('/');
  for (const segment of segments) {
    if (!SEGMENT.test(segment) || segment === '.' || segment === '..') {
      throw new UnsafeObjectNameError();
    }
  }
  return name;
}

/**
 * Um prefixo de listagem: a mesma forma de um nome, terminado em `/`.
 *
 * O `/` final é obrigatório para que `backups/` nunca liste `backups-antigos/` por acidente — e
 * para que ninguém liste a raiz: um prefixo vazio é recusado.
 */
export function assertSafeObjectPrefix(prefix: unknown): string {
  if (typeof prefix !== 'string' || !prefix.endsWith('/')) {
    throw new UnsafeObjectNameError();
  }
  assertSafeObjectName(prefix.slice(0, -1));
  return prefix;
}
