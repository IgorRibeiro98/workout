import type {
  ObjectStorageClient,
  StoredObjectSummary,
} from '../../object-storage/object-storage.client';
import { OBJECT_STORAGE_LIST_PAGE_SIZE } from '../../object-storage/object-storage.limits';

/**
 * A fronteira de armazenamento do documento canônico de um backup (T18.1 §17).
 *
 * ## Por que uma fronteira própria
 *
 * Backup e mídia social usam o **mesmo** bucket e continuam sendo domínios diferentes: o backup
 * não importa `SocialMediaStore`, e o social não importa isto. O que os dois compartilham é a
 * infraestrutura neutra (`ObjectStorageClient`), e cada um acrescenta por cima só o que é seu —
 * aqui, a forma da chave e o namespace `backups/`.
 *
 * ## O que ela guarda
 *
 * O texto canônico do snapshot, **byte a byte** (§22): os mesmos bytes UTF-8 que o
 * `payloadHash` resume e que o Android vai conferir no restore. Nada de `JSON.parse`,
 * `JSON.stringify`, pretty print ou normalização entre o upload e o download — um byte diferente
 * é um hash que não fecha na tela de quem mais precisa do backup.
 *
 * Estreita de propósito: gravar (uma vez), ler, existir, apagar (idempotente) e listar (uma
 * página, sempre sob `backups/`). Não existe "atualizar": um snapshot é imutável.
 */
export interface BackupPayloadStore {
  /**
   * A chave do documento de um snapshot: `backups/xx/yy/<backupId>.json`.
   *
   * Derivada do `backupId` que o **servidor** gerou — nunca de `clientBackupId`, `deviceId`,
   * e-mail, nome ou qualquer coisa que o cliente escolha (§21). O fan-out de dois níveis vem do
   * próprio identificador, como na mídia.
   */
  newStorageKey(backupId: string): string;

  /**
   * Grava **uma vez**. Chave já ocupada é erro, nunca sobrescrita (§10).
   *
   * [sha256] vai como metadata segura do objeto (`spark-sha256`), para diagnóstico. Ele não
   * substitui a verificação de integridade do provider no upload nem a conferência de hash no
   * download (§23/§24): metadata descreve; os bytes provam.
   */
  write(storageKey: string, bytes: Buffer, options: { readonly sha256: string }): Promise<void>;

  /** Os bytes inteiros, ou `null` quando o objeto não existe. Falha de infraestrutura lança. */
  read(storageKey: string): Promise<Buffer | null>;

  exists(storageKey: string): Promise<boolean>;

  /** Idempotente: apagar o que já não existe é sucesso. */
  remove(storageKey: string): Promise<void>;

  /** Uma página das chaves sob `backups/`, com a data de criação — para a coleta de órfãos. */
  listObjects(pageToken?: string): Promise<BackupPayloadPage>;
}

export interface StoredBackupObject {
  readonly storageKey: string;
  readonly createdAt: number;
}

export interface BackupPayloadPage {
  readonly objects: readonly StoredBackupObject[];
  readonly nextPageToken?: string;
}

export const BACKUP_PAYLOAD_STORE = Symbol('BACKUP_PAYLOAD_STORE');

/** O namespace dos documentos de backup no bucket compartilhado (§8/§21). */
const BACKUP_PREFIX = 'backups/';

const KEY_PATTERN =
  /^backups\/[0-9a-f]{2}\/[0-9a-f]{2}\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.json$/;

export class UnsafeBackupStorageKeyError extends Error {
  constructor() {
    // Sem o valor recusado na mensagem: ela pode acabar em log.
    super('chave de armazenamento de backup inválida');
    this.name = 'UnsafeBackupStorageKeyError';
  }
}

/** Allowlist estrita da forma gerada por [ObjectStorageBackupPayloadStore.newStorageKey]. */
export function assertSafeBackupStorageKey(storageKey: unknown): string {
  if (typeof storageKey !== 'string' || !KEY_PATTERN.test(storageKey)) {
    throw new UnsafeBackupStorageKeyError();
  }
  return storageKey;
}

/**
 * O documento canônico sobre o Object Storage do processo.
 *
 * Uma implementação só, para os dois providers: quem decide entre o disco local e o bucket é o
 * `ObjectStorageClient` injetado — escolhido em `object-storage.factory.ts`, nunca aqui.
 */
export class ObjectStorageBackupPayloadStore implements BackupPayloadStore {
  constructor(private readonly client: ObjectStorageClient) {}

  newStorageKey(backupId: string): string {
    const key = `${BACKUP_PREFIX}${backupId.slice(0, 2)}/${backupId.slice(2, 4)}/${backupId}.json`;
    // Um `backupId` fora da forma esperada é um defeito do chamador, não um nome a montar.
    return assertSafeBackupStorageKey(key);
  }

  async write(
    storageKey: string,
    bytes: Buffer,
    options: { readonly sha256: string },
  ): Promise<void> {
    await this.client.write(assertSafeBackupStorageKey(storageKey), bytes, {
      contentType: 'application/json',
      metadata: { 'spark-sha256': options.sha256 },
    });
  }

  async read(storageKey: string): Promise<Buffer | null> {
    return this.client.read(assertSafeBackupStorageKey(storageKey));
  }

  async exists(storageKey: string): Promise<boolean> {
    return this.client.exists(assertSafeBackupStorageKey(storageKey));
  }

  async remove(storageKey: string): Promise<void> {
    await this.client.remove(assertSafeBackupStorageKey(storageKey));
  }

  async listObjects(pageToken?: string): Promise<BackupPayloadPage> {
    const page = await this.client.list(BACKUP_PREFIX, {
      pageSize: OBJECT_STORAGE_LIST_PAGE_SIZE,
      pageToken,
    });
    return {
      // Só o que tem a forma de uma chave de backup: um objeto estranho sob o prefixo não é
      // nosso para apagar.
      objects: page.objects.flatMap((object) => toStoredBackup(object)),
      nextPageToken: page.nextPageToken,
    };
  }
}

function toStoredBackup(object: StoredObjectSummary): StoredBackupObject[] {
  return KEY_PATTERN.test(object.name)
    ? [{ storageKey: object.name, createdAt: object.createdAt }]
    : [];
}
