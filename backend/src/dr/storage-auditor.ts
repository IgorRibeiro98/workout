import { createHash } from 'node:crypto';
import type { Clock } from '../common/clock';
import type { SparkLogger } from '../common/logger';
import type { DbClient } from '../database/postgres.service';
import type {
  ObjectStorageClient,
  StoredObjectSummary,
} from '../object-storage/object-storage.client';
import {
  OBJECT_STORAGE_LIST_PAGE_SIZE,
  OBJECT_STORAGE_ORPHAN_GRACE_MS,
} from '../object-storage/object-storage.limits';
import { DrBackupStore } from './dr-backup.store';

/**
 * As classes de achado (T18.3 §11). `OK` nunca vira linha de achado — só contagem.
 *
 * ```text
 * MISSING_OBJECT          o banco referencia um objeto que não existe (a falha que a ordem
 *                         "objeto antes, metadata depois" existe para impedir)
 * ORPHAN_OBJECT           objeto sem linha válida, mais antigo que a carência
 * RECENT_UNREFERENCED     objeto sem linha, mais novo que a carência — pode ser um upload em voo
 * INVALID_METADATA        metadata e objeto não batem (tamanho), manifesto ilegível, chave malformada
 * HASH_MISMATCH           os bytes lidos não resumem no hash que o banco declara
 * INCOMPLETE_BACKUP       pasta de DR com dump e sem manifesto
 * TOMBSTONE_INCONSISTENT  ledger externo e tabela de tombstones discordam
 * UNKNOWN                 o auditor não conseguiu decidir (falha de leitura, formato inesperado)
 * ```
 */
export type StorageAuditClass =
  | 'MISSING_OBJECT'
  | 'ORPHAN_OBJECT'
  | 'RECENT_UNREFERENCED'
  | 'INVALID_METADATA'
  | 'HASH_MISMATCH'
  | 'INCOMPLETE_BACKUP'
  | 'TOMBSTONE_INCONSISTENT'
  | 'UNKNOWN';

export type StorageAuditDomain =
  | 'social_media'
  | 'backup_payloads'
  | 'dr_postgres'
  | 'deletion_ledger';

export interface StorageAuditFinding {
  readonly domain: StorageAuditDomain;
  readonly class: StorageAuditClass;
  /** A chave do objeto ou o identificador da linha. Vai para o relatório (stdout), nunca para o log. */
  readonly subject: string;
  readonly detail: string;
}

export interface StorageAuditDomainSummary {
  readonly domain: StorageAuditDomain;
  readonly rowsChecked: number;
  readonly objectsListed: number;
  readonly ok: number;
  readonly hashVerified: number;
  readonly findings: number;
  readonly truncated: boolean;
}

export interface StorageAuditReport {
  readonly startedAt: number;
  readonly durationMs: number;
  readonly provider: string;
  readonly domains: readonly StorageAuditDomainSummary[];
  readonly findings: readonly StorageAuditFinding[];
  readonly countsByClass: Readonly<Record<StorageAuditClass, number>>;
  /** `true` quando nenhum achado além de `RECENT_UNREFERENCED` existe. */
  readonly clean: boolean;
}

export interface StorageAuditorOptions {
  /** Quantos objetos, por domínio, têm os bytes lidos e o hash conferido. 0 desliga. */
  readonly hashSampleSize: number;
  /** Quantas páginas de listagem por prefixo. Bounded: um bucket enorme não trava o auditor. */
  readonly maxPagesPerPrefix: number;
}

export const DEFAULT_STORAGE_AUDITOR_OPTIONS: StorageAuditorOptions = {
  hashSampleSize: 25,
  maxPagesPerPrefix: 40,
};

const SOCIAL_NAMESPACE = 'social/';
const SOCIAL_PREFIX = 'social/checkins/';
const BACKUP_PREFIX = 'backups/';
const LEDGER_PREFIX = 'system/deletion-tombstones/';
const HASH_FORMAT = /^[0-9a-f]{64}$/;

/**
 * O auditor de referências PostgreSQL ↔ Object Storage (T18.3 §11). **Somente leitura.**
 *
 * Ele não apaga, não corrige, não migra. Responde a quatro perguntas — "uma linha aponta para um
 * objeto que não existe?", "um objeto existe sem linha?", "metadata e bytes batem?", "o ledger e a
 * tabela de tombstones contam a mesma história?" — e entrega contagens por classe (log) e uma
 * lista de achados (relatório). Quem age sobre um achado é uma pessoa, com o comando específico
 * (`reconcile-account-deletions`, os coletores de órfãos do maintenance, ou uma restauração).
 *
 * Uma listagem por prefixo serve às duas direções (linha → objeto, objeto → linha), bounded por
 * páginas; o banco é consultado por lotes de chaves. A conferência de hash lê bytes, então é
 * amostrada (`hashSampleSize`), nunca o bucket inteiro.
 */
export class StorageAuditor {
  constructor(
    private readonly db: DbClient,
    private readonly storage: ObjectStorageClient,
    private readonly clock: Clock,
    private readonly logger: SparkLogger,
    private readonly options: StorageAuditorOptions = DEFAULT_STORAGE_AUDITOR_OPTIONS,
  ) {}

  async audit(): Promise<StorageAuditReport> {
    const startedAt = this.clock.now();
    const findings: StorageAuditFinding[] = [];
    const domains: StorageAuditDomainSummary[] = [];

    domains.push(await this.auditSocialMedia(findings));
    domains.push(await this.auditBackupPayloads(findings));
    domains.push(await this.auditDrPostgres(findings));
    domains.push(await this.auditDeletionLedger(findings));

    const countsByClass: Record<StorageAuditClass, number> = {
      MISSING_OBJECT: 0,
      ORPHAN_OBJECT: 0,
      RECENT_UNREFERENCED: 0,
      INVALID_METADATA: 0,
      HASH_MISMATCH: 0,
      INCOMPLETE_BACKUP: 0,
      TOMBSTONE_INCONSISTENT: 0,
      UNKNOWN: 0,
    };
    for (const finding of findings) {
      countsByClass[finding.class] += 1;
      // Contagem, domínio e classe — nunca a chave (T18.1 §41). A chave vai no relatório.
      this.logger.warn('storage_audit_issue', {
        operation: 'storage_audit',
        domain: finding.domain,
        class: finding.class,
      });
    }
    const clean = findings.every((finding) => finding.class === 'RECENT_UNREFERENCED');
    const durationMs = this.clock.now() - startedAt;
    this.logger.info('storage_audit_completed', {
      operation: 'storage_audit',
      status: clean ? 'CLEAN' : 'ISSUES',
      durationMs,
      provider: this.storage.provider,
      ...countsByClass,
      domains: domains.map((d) => ({
        domain: d.domain,
        rowsChecked: d.rowsChecked,
        objectsListed: d.objectsListed,
        ok: d.ok,
      })),
    });
    return {
      startedAt,
      durationMs,
      provider: this.storage.provider,
      domains,
      findings,
      countsByClass,
      clean,
    };
  }

  // ------------------------------------------------------------------ mídia social

  private async auditSocialMedia(
    findings: StorageAuditFinding[],
  ): Promise<StorageAuditDomainSummary> {
    const domain: StorageAuditDomain = 'social_media';
    const listed = await this.listPrefix(SOCIAL_PREFIX);
    const rows = await this.db.query<{
      id: string;
      storage_key: string;
      byte_size: number;
      content_hash: string;
      status: 'PENDING' | 'ATTACHED' | 'DELETED';
    }>(`SELECT id, storage_key, byte_size, content_hash, status FROM social_checkin_media`);

    const referenced = new Set<string>();
    let ok = 0;
    let hashVerified = 0;
    for (const row of rows.rows) {
      const name = `${SOCIAL_NAMESPACE}${row.storage_key}`;
      if (row.status === 'DELETED') {
        // Não é uma referência viva: o objeto que ainda existir é o que o `SocialMediaCleaner`
        // recolhe — e, velho o bastante, aparece aqui como órfão (um coletor parado fica visível).
        continue;
      }
      referenced.add(name);
      const object = listed.objects.get(name);
      if (object === undefined) {
        if (listed.truncated) {
          findings.push({
            domain,
            class: 'UNKNOWN',
            subject: row.storage_key,
            detail: 'listagem truncada; objeto não decidido',
          });
        } else {
          findings.push({
            domain,
            class: 'MISSING_OBJECT',
            subject: row.storage_key,
            detail: `linha ${row.id} (${row.status}) sem objeto`,
          });
        }
        continue;
      }
      if (object.size !== Number(row.byte_size)) {
        findings.push({
          domain,
          class: 'INVALID_METADATA',
          subject: row.storage_key,
          detail: `byte_size=${row.byte_size} objeto=${object.size}`,
        });
        continue;
      }
      if (hashVerified < this.options.hashSampleSize) {
        hashVerified += 1;
        const verdict = await this.verifyHash(name, row.content_hash);
        if (verdict !== null) {
          findings.push({
            domain,
            class: verdict,
            subject: row.storage_key,
            detail: 'content_hash não confere com os bytes',
          });
          continue;
        }
      }
      ok += 1;
    }

    this.classifyUnreferenced(domain, listed, referenced, findings);
    return {
      domain,
      rowsChecked: rows.rows.length,
      objectsListed: listed.objects.size,
      ok,
      hashVerified,
      findings: findings.filter((f) => f.domain === domain).length,
      truncated: listed.truncated,
    };
  }

  // ------------------------------------------------------------------ documentos de backup

  private async auditBackupPayloads(
    findings: StorageAuditFinding[],
  ): Promise<StorageAuditDomainSummary> {
    const domain: StorageAuditDomain = 'backup_payloads';
    const listed = await this.listPrefix(BACKUP_PREFIX);
    const rows = await this.db.query<{
      backup_id: string;
      storage_key: string;
      size_bytes: number;
      payload_hash: string;
    }>(
      `SELECT backup_id, storage_key, size_bytes, payload_hash FROM backup_snapshots WHERE storage_key IS NOT NULL`,
    );

    const referenced = new Set<string>();
    let ok = 0;
    let hashVerified = 0;
    for (const row of rows.rows) {
      referenced.add(row.storage_key);
      const object = listed.objects.get(row.storage_key);
      if (object === undefined) {
        if (listed.truncated) {
          findings.push({
            domain,
            class: 'UNKNOWN',
            subject: row.storage_key,
            detail: 'listagem truncada; objeto não decidido',
          });
        } else {
          findings.push({
            domain,
            class: 'MISSING_OBJECT',
            subject: row.storage_key,
            detail: `snapshot ${row.backup_id} sem objeto`,
          });
        }
        continue;
      }
      if (object.size !== Number(row.size_bytes)) {
        findings.push({
          domain,
          class: 'INVALID_METADATA',
          subject: row.storage_key,
          detail: `size_bytes=${row.size_bytes} objeto=${object.size}`,
        });
        continue;
      }
      if (hashVerified < this.options.hashSampleSize) {
        hashVerified += 1;
        const verdict = await this.verifyHash(row.storage_key, row.payload_hash);
        if (verdict !== null) {
          findings.push({
            domain,
            class: verdict,
            subject: row.storage_key,
            detail: 'payload_hash não confere com os bytes',
          });
          continue;
        }
      }
      ok += 1;
    }

    this.classifyUnreferenced(domain, listed, referenced, findings);
    return {
      domain,
      rowsChecked: rows.rows.length,
      objectsListed: listed.objects.size,
      ok,
      hashVerified,
      findings: findings.filter((f) => f.domain === domain).length,
      truncated: listed.truncated,
    };
  }

  // ------------------------------------------------------------------ backups de DR

  private async auditDrPostgres(
    findings: StorageAuditFinding[],
  ): Promise<StorageAuditDomainSummary> {
    const domain: StorageAuditDomain = 'dr_postgres';
    const store = new DrBackupStore(this.storage);
    const folders = await store.listFolders();
    let ok = 0;
    for (const folder of folders) {
      const status = await store.statusOf(folder);
      switch (status.kind) {
        case 'valid':
          ok += 1;
          break;
        case 'incomplete':
          findings.push({
            domain,
            class: 'INCOMPLETE_BACKUP',
            subject: folder.backupId,
            detail: 'database.dump sem manifest.json',
          });
          break;
        case 'missing_dump':
          findings.push({
            domain,
            class: 'MISSING_OBJECT',
            subject: folder.backupId,
            detail: 'manifest.json sem database.dump',
          });
          break;
        case 'invalid_manifest':
          findings.push({
            domain,
            class: 'INVALID_METADATA',
            subject: folder.backupId,
            detail: status.reason,
          });
          break;
        case 'size_mismatch':
          findings.push({
            domain,
            class: 'INVALID_METADATA',
            subject: folder.backupId,
            detail: 'tamanho do dump difere do manifesto',
          });
          break;
      }
    }
    return {
      domain,
      rowsChecked: 0,
      objectsListed: folders.length,
      ok,
      hashVerified: 0,
      findings: findings.filter((f) => f.domain === domain).length,
      truncated: false,
    };
  }

  // ------------------------------------------------------------------ ledger anti-ressurreição

  private async auditDeletionLedger(
    findings: StorageAuditFinding[],
  ): Promise<StorageAuditDomainSummary> {
    const domain: StorageAuditDomain = 'deletion_ledger';
    const listed = await this.listPrefix(LEDGER_PREFIX);
    const ledger = new Set<string>();
    for (const name of listed.objects.keys()) {
      const hash = name.slice(LEDGER_PREFIX.length);
      if (!HASH_FORMAT.test(hash)) {
        findings.push({
          domain,
          class: 'INVALID_METADATA',
          subject: name,
          detail: 'objeto do ledger fora do formato <hash hex 64>',
        });
        continue;
      }
      ledger.add(hash);
    }

    const tombstones = await this.db.query<{ uid_hash: string }>(
      `SELECT uid_hash FROM account_deletion_tombstones`,
    );
    const pendingJobs = await this.db.query<{ uid_hash: string }>(
      `SELECT uid_hash FROM account_deletion_jobs WHERE phase = 'LEDGER_PENDING'`,
    );
    const pending = new Set(pendingJobs.rows.map((row) => row.uid_hash));
    const inDatabase = new Set(tombstones.rows.map((row) => row.uid_hash));

    let ok = 0;
    for (const hash of inDatabase) {
      if (ledger.has(hash)) {
        ok += 1;
      } else if (pending.has(hash)) {
        ok += 1; // o job ainda vai gravar o ledger — é o estado LEDGER_PENDING, não uma inconsistência
      } else if (listed.truncated) {
        findings.push({
          domain,
          class: 'UNKNOWN',
          subject: hash,
          detail: 'listagem do ledger truncada',
        });
      } else {
        findings.push({
          domain,
          class: 'TOMBSTONE_INCONSISTENT',
          subject: hash,
          detail: 'tombstone no banco sem objeto no ledger externo (e sem job LEDGER_PENDING)',
        });
      }
    }
    for (const hash of ledger) {
      if (!inDatabase.has(hash)) {
        // O cenário de ressurreição: o ledger conhece uma exclusão que o banco (restaurado?) não
        // conhece. A resposta é `reconcile-account-deletions`, nunca o auditor.
        findings.push({
          domain,
          class: 'TOMBSTONE_INCONSISTENT',
          subject: hash,
          detail:
            'ledger externo conhece uma exclusão sem tombstone no banco — rode reconcile-account-deletions',
        });
      }
    }
    return {
      domain,
      rowsChecked: inDatabase.size,
      objectsListed: listed.objects.size,
      ok,
      hashVerified: 0,
      findings: findings.filter((f) => f.domain === domain).length,
      truncated: listed.truncated,
    };
  }

  // ------------------------------------------------------------------ helpers

  private async listPrefix(
    prefix: string,
  ): Promise<{ objects: Map<string, StoredObjectSummary>; truncated: boolean }> {
    const objects = new Map<string, StoredObjectSummary>();
    let pageToken: string | undefined;
    let truncated = false;
    for (let pages = 0; ; pages += 1) {
      if (pages >= this.options.maxPagesPerPrefix) {
        truncated = true;
        break;
      }
      const page = await this.storage.list(prefix, {
        pageSize: OBJECT_STORAGE_LIST_PAGE_SIZE,
        pageToken,
      });
      for (const object of page.objects) {
        objects.set(object.name, object);
      }
      pageToken = page.nextPageToken;
      if (pageToken === undefined) {
        break;
      }
    }
    return { objects, truncated };
  }

  private classifyUnreferenced(
    domain: StorageAuditDomain,
    listed: { objects: Map<string, StoredObjectSummary> },
    referenced: Set<string>,
    findings: StorageAuditFinding[],
  ): void {
    const now = this.clock.now();
    for (const [name, object] of listed.objects) {
      if (referenced.has(name)) {
        continue;
      }
      if (object.createdAt === null) {
        findings.push({
          domain,
          class: 'UNKNOWN',
          subject: name,
          detail: 'objeto sem linha e sem data de criação conhecida',
        });
      } else if (now - object.createdAt >= OBJECT_STORAGE_ORPHAN_GRACE_MS) {
        findings.push({
          domain,
          class: 'ORPHAN_OBJECT',
          subject: name,
          detail: 'objeto sem linha, mais antigo que a carência',
        });
      } else {
        findings.push({
          domain,
          class: 'RECENT_UNREFERENCED',
          subject: name,
          detail: 'objeto sem linha, dentro da carência (pode ser um upload em voo)',
        });
      }
    }
  }

  /** `null` quando os bytes conferem; a classe do achado quando não. */
  private async verifyHash(name: string, expected: string): Promise<StorageAuditClass | null> {
    let bytes: Buffer | null;
    try {
      bytes = await this.storage.read(name);
    } catch {
      return 'UNKNOWN';
    }
    if (bytes === null) {
      return 'MISSING_OBJECT';
    }
    return createHash('sha256').update(bytes).digest('hex') === expected ? null : 'HASH_MISMATCH';
  }
}
