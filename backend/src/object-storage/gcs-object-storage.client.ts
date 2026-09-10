import { Readable } from 'node:stream';
import { IdempotencyStrategy, Storage, type Bucket } from '@google-cloud/storage';
import { SparkLogger } from '../common/logger';
import {
  assertSafeObjectName,
  assertSafeObjectPrefix,
  ObjectAlreadyExistsError,
  ObjectStorageUnavailableError,
  type ListObjectsOptions,
  type ObjectPage,
  type ObjectStorageClient,
  type StoredObjectSummary,
  type WriteObjectOptions,
} from './object-storage.client';

export interface GcsObjectStorageOptions {
  readonly bucketName: string;
  /** Teto de **uma** requisição HTTP ao GCS. O retry do SDK acontece por cima dele. */
  readonly timeoutMs: number;
}

/**
 * O provider `gcs`: um bucket privado do Google Cloud Storage (T18.1).
 *
 * ## Autenticação: ADC, e nada mais
 *
 * `new Storage()` sem credencial explícita. O SDK resolve Application Default Credentials por
 * conta própria — a service account anexada ao serviço no Cloud Run (T18.2), ou o
 * `gcloud auth application-default login` do operador. Não existe `credentials:`, `keyFilename`,
 * chave privada em variável de ambiente nem JSON de service account em lugar nenhum deste
 * repositório, e há teste estrutural sobre isso. O Android nunca recebe credencial GCS e nunca
 * fala com o bucket: todo byte passa pelo backend, depois da autorização em SQL.
 *
 * ## O bucket é privado, e este arquivo não sabe torná-lo público
 *
 * Nenhum `makePublic`, `acl`, `predefinedAcl`, `allUsers` ou `getSignedUrl` — nem como opção.
 * A única forma de obter um objeto é uma rota autenticada do backend que consulta o PostgreSQL
 * antes de ler os bytes.
 *
 * ## Escrita imutável, verificada
 *
 * `ifGenerationMatch: 0` faz o GCS recusar a gravação se o nome já existir (`412`), e é o SDK
 * que verifica o CRC32C do que subiu contra o que o servidor recebeu — um upload corrompido no
 * caminho é apagado e falha, em vez de virar um objeto que só se descobre errado no restore. A
 * precondição também é o que torna o retry automático seguro: repetir uma escrita condicional
 * nunca produz dois objetos nem sobrescreve o primeiro.
 *
 * ## Falha é falha
 *
 * `404` vira ausência (`null`/`false`); `412` vira [ObjectAlreadyExistsError]; **todo o resto**
 * — timeout, permissão negada, quota, rede — sobe como [ObjectStorageUnavailableError], com um
 * log de metadata (operação, nome do erro, status, duração). Nunca nome de objeto, nunca bytes,
 * nunca URL.
 */
export class GcsObjectStorageClient implements ObjectStorageClient {
  readonly provider = 'gcs' as const;
  private readonly bucket: Bucket;

  constructor(
    options: GcsObjectStorageOptions,
    private readonly logger: SparkLogger,
  ) {
    const storage = new Storage({
      timeout: options.timeoutMs,
      retryOptions: {
        autoRetry: true,
        maxRetries: 3,
        // Em segundos, e bounded: o retry inteiro nunca passa de quatro vezes o teto de uma
        // requisição. Sem laço manual — é o SDK quem tenta de novo, e só o que é seguro repetir.
        totalTimeout: Math.ceil((options.timeoutMs * 4) / 1000),
        idempotencyStrategy: IdempotencyStrategy.RetryConditional,
      },
    });
    this.bucket = storage.bucket(options.bucketName);
  }

  async write(name: string, bytes: Buffer, options: WriteObjectOptions): Promise<void> {
    assertSafeObjectName(name);
    const startedAt = Date.now();
    try {
      await this.bucket.file(name).save(bytes, {
        // Um PUT só: os objetos do Spark cabem numa requisição (≤ 4 MiB), e o upload resumível
        // custaria uma ida a mais por objeto sem nada a retomar.
        resumable: false,
        contentType: options.contentType,
        metadata: { contentType: options.contentType, metadata: { ...options.metadata } },
        preconditionOpts: { ifGenerationMatch: 0 },
        validation: 'crc32c',
      });
    } catch (error) {
      if (statusOf(error) === 412) {
        // A precondição falhou por um de dois motivos: o nome já era de outro objeto, ou **esta**
        // escrita já tinha vencido e a resposta se perdeu antes de o SDK tentar de novo. Os bytes
        // distinguem os dois: conteúdo idêntico é a segunda, e ela é sucesso — o retry seguro que
        // a precondição existe para permitir. Qualquer diferença é colisão, e colisão é erro.
        const existing = await this.read(name).catch(() => null);
        if (existing !== null && existing.equals(bytes)) {
          return;
        }
        throw new ObjectAlreadyExistsError();
      }
      throw this.unavailable('write', error, startedAt, bytes.length);
    }
  }

  async read(name: string): Promise<Buffer | null> {
    assertSafeObjectName(name);
    const startedAt = Date.now();
    try {
      const [bytes] = await this.bucket.file(name).download({ validation: 'crc32c' });
      return bytes;
    } catch (error) {
      if (statusOf(error) === 404) {
        return null;
      }
      throw this.unavailable('read', error, startedAt);
    }
  }

  /**
   * Os objetos servidos em stream (a mídia) são pequenos por contrato — 1,5 MB no máximo —, e
   * baixá-los inteiros antes de responder é o que permite representar `404` como `null` **antes**
   * de a resposta HTTP começar, em vez de um erro no meio de um stream já iniciado.
   */
  async openRead(name: string): Promise<Readable | null> {
    const bytes = await this.read(name);
    return bytes === null ? null : Readable.from(bytes);
  }

  async exists(name: string): Promise<boolean> {
    assertSafeObjectName(name);
    const startedAt = Date.now();
    try {
      const [exists] = await this.bucket.file(name).exists();
      return exists;
    } catch (error) {
      throw this.unavailable('exists', error, startedAt);
    }
  }

  async remove(name: string): Promise<void> {
    assertSafeObjectName(name);
    const startedAt = Date.now();
    try {
      await this.bucket.file(name).delete({ ignoreNotFound: true });
    } catch (error) {
      throw this.unavailable('remove', error, startedAt);
    }
  }

  async list(prefix: string, options: ListObjectsOptions): Promise<ObjectPage> {
    assertSafeObjectPrefix(prefix);
    const startedAt = Date.now();
    try {
      // `autoPaginate: false`: **uma** página por chamada. O default do SDK seguiria os cursores
      // até o fim do prefixo — exatamente a listagem sem limite que §14 proíbe.
      const [files, nextQuery] = await this.bucket.getFiles({
        prefix,
        maxResults: options.pageSize,
        pageToken: options.pageToken,
        autoPaginate: false,
      });
      const objects: StoredObjectSummary[] = files.map((file) => ({
        name: file.name,
        createdAt: Date.parse(file.metadata.timeCreated ?? '') || 0,
        size: Number(file.metadata.size ?? 0),
      }));
      const token = (nextQuery as { pageToken?: string } | undefined)?.pageToken;
      return { objects, nextPageToken: token || undefined };
    } catch (error) {
      throw this.unavailable('list', error, startedAt);
    }
  }

  private unavailable(
    operation: string,
    error: unknown,
    startedAt: number,
    byteSize?: number,
  ): ObjectStorageUnavailableError {
    // Metadata operacional, e só ela (§41): sem nome de objeto, sem bucket, sem URL, sem corpo.
    this.logger.warn('object_storage.operation_failed', {
      provider: this.provider,
      operation,
      status: statusOf(error),
      errorName: error instanceof Error ? error.name : 'UnknownError',
      durationMs: Date.now() - startedAt,
      ...(byteSize !== undefined ? { byteSize } : {}),
    });
    return new ObjectStorageUnavailableError(operation, error);
  }
}

/** O status HTTP de um `ApiError` do SDK, quando ele é um número. Erros de rede não têm. */
function statusOf(error: unknown): number | undefined {
  if (typeof error !== 'object' || error === null || !('code' in error)) {
    return undefined;
  }
  const code = (error as { code: unknown }).code;
  return typeof code === 'number' ? code : undefined;
}
