import type { ObjectStorageClient } from '../object-storage/object-storage.client';
import { OBJECT_STORAGE_LIST_PAGE_SIZE } from '../object-storage/object-storage.limits';
import {
  DR_DUMP_OBJECT_NAME,
  DR_MANIFEST_OBJECT_NAME,
  DR_POSTGRES_PREFIX,
  DrManifestError,
  drDumpObjectName,
  drManifestObjectName,
  isDrBackupId,
  parseDrManifest,
  serializeDrManifest,
  type DrBackupManifest,
} from './dr-manifest';

/** O que o bucket tem sobre um `backupId`, antes de qualquer validação. */
export interface DrBackupFolder {
  readonly backupId: string;
  readonly dump?: { readonly size: number; readonly createdAt: number | null };
  readonly manifest?: { readonly size: number; readonly createdAt: number | null };
}

/** Um backup **válido**: manifesto íntegro, dump presente, tamanho batendo. */
export interface DrValidBackup {
  readonly backupId: string;
  readonly manifest: DrBackupManifest;
}

export type DrBackupStatus =
  | { readonly kind: 'valid'; readonly backup: DrValidBackup }
  /** `database.dump` sem `manifest.json`: não terminou, ou a retenção parou no meio. */
  | { readonly kind: 'incomplete'; readonly backupId: string }
  /** `manifest.json` sem `database.dump`: metadata sem payload. */
  | { readonly kind: 'missing_dump'; readonly backupId: string }
  | { readonly kind: 'invalid_manifest'; readonly backupId: string; readonly reason: string }
  | { readonly kind: 'size_mismatch'; readonly backupId: string };

/** Quantas páginas de listagem uma varredura do namespace de DR percorre, no máximo. */
const DR_LIST_MAX_PAGES = 20;

/**
 * O namespace `system/dr/postgres/` sobre a fronteira neutra de Object Storage (T18.3 §2).
 *
 * Como `BackupPayloadStore` e `SocialMediaStore`: um adaptador de nomes por cima de
 * `ObjectStorageClient`. Quem decide entre `local` e `gcs` continua sendo a factory — este
 * arquivo não sabe, e não precisa saber, onde os bytes moram.
 *
 * ## Validade é conferida aqui, e só aqui
 *
 * `statusOf` é o único lugar que responde "este backup vale?": manifesto que faz parse **e** dump
 * presente **e** tamanho do objeto igual ao declarado. Retenção, frescor (maintenance), gate de
 * deploy e auditor leem a mesma resposta — quatro consumidores, um critério.
 */
export class DrBackupStore {
  constructor(private readonly client: ObjectStorageClient) {}

  get provider(): string {
    return this.client.provider;
  }

  /** Todas as pastas sob o prefixo, agrupadas por `backupId`. Bounded por páginas. */
  async listFolders(): Promise<DrBackupFolder[]> {
    const folders = new Map<
      string,
      { dump?: DrBackupFolder['dump']; manifest?: DrBackupFolder['manifest'] }
    >();
    let pageToken: string | undefined;
    for (let pages = 0; pages < DR_LIST_MAX_PAGES; pages += 1) {
      const page = await this.client.list(DR_POSTGRES_PREFIX, {
        pageSize: OBJECT_STORAGE_LIST_PAGE_SIZE,
        pageToken,
      });
      for (const object of page.objects) {
        const rest = object.name.slice(DR_POSTGRES_PREFIX.length);
        const [backupId, file, ...deeper] = rest.split('/');
        if (!backupId || !file || deeper.length > 0 || !isDrBackupId(backupId)) {
          // Um objeto estranho sob o prefixo não é um backup — e não é nosso para apagar.
          continue;
        }
        const entry = folders.get(backupId) ?? {};
        const summary = { size: object.size, createdAt: object.createdAt };
        if (file === DR_DUMP_OBJECT_NAME) {
          entry.dump = summary;
        } else if (file === DR_MANIFEST_OBJECT_NAME) {
          entry.manifest = summary;
        }
        folders.set(backupId, entry);
      }
      pageToken = page.nextPageToken;
      if (pageToken === undefined) {
        break;
      }
    }
    return [...folders.entries()]
      .map(([backupId, entry]) => ({ backupId, ...entry }))
      .sort((a, b) => a.backupId.localeCompare(b.backupId));
  }

  /** O veredito sobre uma pasta. Lê o manifesto (pequeno); nunca lê o dump. */
  async statusOf(folder: DrBackupFolder): Promise<DrBackupStatus> {
    if (!folder.manifest) {
      return { kind: 'incomplete', backupId: folder.backupId };
    }
    const bytes = await this.client.read(drManifestObjectName(folder.backupId));
    if (bytes === null) {
      // Listado há um instante e ausente agora: a retenção de outra execução passou por aqui.
      return { kind: 'incomplete', backupId: folder.backupId };
    }
    let manifest: DrBackupManifest;
    try {
      manifest = parseDrManifest(bytes);
    } catch (error) {
      return {
        kind: 'invalid_manifest',
        backupId: folder.backupId,
        reason: error instanceof DrManifestError ? error.message : 'erro desconhecido',
      };
    }
    if (manifest.backupId !== folder.backupId) {
      return {
        kind: 'invalid_manifest',
        backupId: folder.backupId,
        reason: 'manifest.json declara outro backupId',
      };
    }
    if (!folder.dump) {
      return { kind: 'missing_dump', backupId: folder.backupId };
    }
    if (folder.dump.size !== manifest.dumpSizeBytes) {
      return { kind: 'size_mismatch', backupId: folder.backupId };
    }
    return { kind: 'valid', backup: { backupId: folder.backupId, manifest } };
  }

  /** Todos os status, do mais antigo ao mais novo. */
  async listStatuses(): Promise<DrBackupStatus[]> {
    const folders = await this.listFolders();
    const statuses: DrBackupStatus[] = [];
    for (const folder of folders) {
      statuses.push(await this.statusOf(folder));
    }
    return statuses;
  }

  /** O backup válido mais recente, ou `null`. */
  async latestValid(): Promise<DrValidBackup | null> {
    const statuses = await this.listStatuses();
    let latest: DrValidBackup | null = null;
    for (const status of statuses) {
      if (status.kind === 'valid') {
        if (
          latest === null ||
          status.backup.manifest.createdAtEpochMs > latest.manifest.createdAtEpochMs
        ) {
          latest = status.backup;
        }
      }
    }
    return latest;
  }

  async writeDump(backupId: string, bytes: Buffer, sha256: string): Promise<void> {
    await this.client.write(drDumpObjectName(backupId), bytes, {
      contentType: 'application/octet-stream',
      metadata: { 'spark-sha256': sha256, 'spark-dr': 'postgres' },
    });
  }

  async writeManifest(manifest: DrBackupManifest): Promise<void> {
    await this.client.write(
      drManifestObjectName(manifest.backupId),
      serializeDrManifest(manifest),
      {
        contentType: 'application/json',
        metadata: { 'spark-dr': 'postgres-manifest' },
      },
    );
  }

  async readManifest(backupId: string): Promise<DrBackupManifest | null> {
    const bytes = await this.client.read(drManifestObjectName(backupId));
    return bytes === null ? null : parseDrManifest(bytes);
  }

  async readDump(backupId: string): Promise<Buffer | null> {
    return this.client.read(drDumpObjectName(backupId));
  }

  async dumpExists(backupId: string): Promise<boolean> {
    return this.client.exists(drDumpObjectName(backupId));
  }

  /**
   * Remove um backup: o manifesto **primeiro**, o dump depois.
   *
   * A ordem espelha a da escrita: sem manifesto, a pasta já é "incompleta" para qualquer leitor
   * — uma falha entre as duas remoções deixa um dump órfão que a próxima execução reconhece como
   * incompleto, nunca um manifesto apontando para um dump que já não existe.
   */
  async remove(backupId: string): Promise<void> {
    await this.client.remove(drManifestObjectName(backupId));
    await this.client.remove(drDumpObjectName(backupId));
  }
}
