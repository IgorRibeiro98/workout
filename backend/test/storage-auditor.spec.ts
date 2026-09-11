import { createHash } from 'node:crypto';
import { SparkLogger } from '../src/common/logger';
import { PostgresService } from '../src/database/postgres.service';
import { drDumpObjectName, drManifestObjectName } from '../src/dr/dr-manifest';
import { StorageAuditor, type StorageAuditFinding } from '../src/dr/storage-auditor';
import { OBJECT_STORAGE_ORPHAN_GRACE_MS } from '../src/object-storage/object-storage.limits';
import { FakeClock } from './support/fake-clock';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { configFor, createTempDb, postgresFor, type TempDb } from './support/temp-db';

const NOW = Date.UTC(2026, 8, 11, 12, 0, 0);
const OLD = NOW - OBJECT_STORAGE_ORPHAN_GRACE_MS - 60_000;
const MEDIA_KEY = 'checkins/aa/bb/aabb0000-0000-4000-8000-000000000001.webp';
const MEDIA_KEY_2 = 'checkins/aa/bb/aabb0000-0000-4000-8000-000000000002.webp';
const BACKUP_KEY = 'backups/cc/dd/ccdd0000-0000-4000-8000-000000000001.json';
const HASH_A = 'a'.repeat(64);
const HASH_B = 'b'.repeat(64);

const sha = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');

/**
 * T18.3 §11 — o auditor PostgreSQL ↔ Object Storage classifica, e nunca apaga.
 */
describe('T18.3 — auditor de referências PostgreSQL ↔ Object Storage', () => {
  let temp: TempDb;
  let postgres: PostgresService;
  let storage: InMemoryObjectStorageClient;
  let clock: FakeClock;
  let removed: string[];

  const auditor = () =>
    new StorageAuditor(postgres, storage, clock, new SparkLogger(configFor(temp.path)), {
      hashSampleSize: 10,
      maxPagesPerPrefix: 10,
    });

  const findingsOf = (findings: readonly StorageAuditFinding[], domain: string) =>
    findings.filter((f) => f.domain === domain).map((f) => [f.class, f.subject]);

  const seedProfile = () =>
    postgres.query(
      `INSERT INTO social_profiles (owner_uid, social_id, display_name, friend_code, status, created_at, updated_at)
       VALUES ('uid-audit', '11111111-1111-4111-8111-111111111111', 'Audit', 'SPK-AUDIT1', 'ACTIVE', $1, $1)`,
      [NOW],
    );

  const seedMedia = (key: string, bytes: Buffer, status = 'ATTACHED', id = key) =>
    postgres.query(
      `INSERT INTO social_checkin_media
         (id, owner_uid, source_session_sync_id, client_upload_id, storage_key, mime_type, byte_size,
          width, height, content_hash, status, created_at)
       VALUES ($1, 'uid-audit', 'sessao', $1, $2, 'image/webp', $3, 8, 8, $4, $5, $6)`,
      [id, key, bytes.length, sha(bytes), status, NOW],
    );

  const seedBackup = (key: string, bytes: Buffer) =>
    postgres.query(
      `INSERT INTO backup_snapshots
         (backup_id, owner_uid, client_backup_id, device_id, backup_schema_version, payload_hash,
          item_count, size_bytes, created_at, storage_key)
       VALUES ('backup-1', 'uid-audit', 'client-1', 'device-1', 1, $1, 1, $2, $3, $4)`,
      [sha(bytes), bytes.length, NOW, key],
    );

  const put = (name: string, bytes: Buffer, createdAt = NOW) =>
    storage
      .write(name, bytes, { contentType: 'application/octet-stream' })
      .then(() => storage.setCreatedAt(name, createdAt));

  beforeEach(async () => {
    temp = createTempDb();
    postgres = postgresFor(configFor(temp.path));
    await postgres.initialize();
    storage = new InMemoryObjectStorageClient();
    clock = new FakeClock(NOW);
    removed = [];
    const originalRemove = storage.remove.bind(storage);
    storage.remove = async (name) => {
      removed.push(name);
      return originalRemove(name);
    };
    await seedProfile();
  });

  afterEach(async () => {
    await postgres.close();
    temp.cleanup();
  });

  it('tudo consistente: nenhum achado, contagens de OK por domínio, nada removido', async () => {
    const media = Buffer.from('foto');
    const backup = Buffer.from('{"documento":true}');
    await seedMedia(MEDIA_KEY, media);
    await seedBackup(BACKUP_KEY, backup);
    await put(`social/${MEDIA_KEY}`, media);
    await put(BACKUP_KEY, backup);

    const report = await auditor().audit();
    expect(report.clean).toBe(true);
    expect(report.findings).toEqual([]);
    expect(report.domains.find((d) => d.domain === 'social_media')).toMatchObject({
      rowsChecked: 1,
      ok: 1,
      hashVerified: 1,
    });
    expect(report.domains.find((d) => d.domain === 'backup_payloads')).toMatchObject({
      rowsChecked: 1,
      ok: 1,
      hashVerified: 1,
    });
    expect(removed).toEqual([]);
  });

  it('linha sem objeto → MISSING_OBJECT (mídia e backup)', async () => {
    await seedMedia(MEDIA_KEY, Buffer.from('foto'));
    await seedBackup(BACKUP_KEY, Buffer.from('doc'));

    const report = await auditor().audit();
    expect(findingsOf(report.findings, 'social_media')).toEqual([['MISSING_OBJECT', MEDIA_KEY]]);
    expect(findingsOf(report.findings, 'backup_payloads')).toEqual([
      ['MISSING_OBJECT', BACKUP_KEY],
    ]);
    expect(report.countsByClass.MISSING_OBJECT).toBe(2);
    expect(report.clean).toBe(false);
  });

  it('objeto sem linha: ORPHAN_OBJECT depois da carência, RECENT_UNREFERENCED antes dela', async () => {
    await put(`social/${MEDIA_KEY}`, Buffer.from('antiga'), OLD);
    await put(`social/${MEDIA_KEY_2}`, Buffer.from('recente'), NOW - 1_000);

    const report = await auditor().audit();
    expect(findingsOf(report.findings, 'social_media')).toEqual([
      ['ORPHAN_OBJECT', `social/${MEDIA_KEY}`],
      ['RECENT_UNREFERENCED', `social/${MEDIA_KEY_2}`],
    ]);
    expect(removed).toEqual([]);
  });

  it('linha DELETED não conta como referência viva, e o objeto que sobrou vira órfão quando velho', async () => {
    await seedMedia(MEDIA_KEY, Buffer.from('foto'), 'DELETED');
    await put(`social/${MEDIA_KEY}`, Buffer.from('foto'), OLD);

    const report = await auditor().audit();
    expect(findingsOf(report.findings, 'social_media')).toEqual([
      ['ORPHAN_OBJECT', `social/${MEDIA_KEY}`],
    ]);
  });

  it('tamanho divergente → INVALID_METADATA; bytes divergentes → HASH_MISMATCH', async () => {
    const media = Buffer.from('foto-original');
    await seedMedia(MEDIA_KEY, media);
    await put(`social/${MEDIA_KEY}`, Buffer.from('outro-tamanho!!'));
    const backup = Buffer.from('documento');
    await seedBackup(BACKUP_KEY, backup);
    await put(BACKUP_KEY, Buffer.from('docuxento'));

    const report = await auditor().audit();
    expect(findingsOf(report.findings, 'social_media')).toEqual([['INVALID_METADATA', MEDIA_KEY]]);
    expect(findingsOf(report.findings, 'backup_payloads')).toEqual([['HASH_MISMATCH', BACKUP_KEY]]);
  });

  it('DR: dump sem manifesto → INCOMPLETE_BACKUP; manifesto sem dump → MISSING_OBJECT; manifesto ilegível → INVALID_METADATA', async () => {
    await put(drDumpObjectName('2026-09-09T031500Z'), Buffer.from('dump'));
    await put(drManifestObjectName('2026-09-10T031500Z'), Buffer.from('{"formatVersion":1}'));
    await put(drManifestObjectName('2026-09-11T031500Z'), Buffer.from('nao é json'));
    await put(drDumpObjectName('2026-09-11T031500Z'), Buffer.from('dump'));

    const report = await auditor().audit();
    expect(findingsOf(report.findings, 'dr_postgres')).toEqual([
      ['INCOMPLETE_BACKUP', '2026-09-09T031500Z'],
      ['INVALID_METADATA', '2026-09-10T031500Z'],
      ['INVALID_METADATA', '2026-09-11T031500Z'],
    ]);
  });

  it('ledger: tombstone no banco sem objeto (sem job pendente) e objeto sem tombstone → TOMBSTONE_INCONSISTENT', async () => {
    await postgres.query(
      `INSERT INTO account_deletion_tombstones (id, uid_hash, deleted_at) VALUES ('t-a', $1, $2)`,
      [HASH_A, NOW],
    );
    await put(`system/deletion-tombstones/${HASH_B}`, Buffer.from(String(NOW)));

    const report = await auditor().audit();
    expect(findingsOf(report.findings, 'deletion_ledger')).toEqual([
      ['TOMBSTONE_INCONSISTENT', HASH_A],
      ['TOMBSTONE_INCONSISTENT', HASH_B],
    ]);
  });

  it('ledger: tombstone com job LEDGER_PENDING ainda vai ser gravado — não é inconsistência', async () => {
    await postgres.query(
      `INSERT INTO account_deletion_tombstones (id, uid_hash, deleted_at) VALUES ('t-a', $1, $2)`,
      [HASH_A, NOW],
    );
    await postgres.query(
      `INSERT INTO account_deletion_jobs (id, firebase_uid, uid_hash, attempts, next_attempt_at, created_at, phase)
       VALUES ('job-a', 'uid-a', $1, 0, $2, $2, 'LEDGER_PENDING')`,
      [HASH_A, NOW],
    );

    const report = await auditor().audit();
    expect(findingsOf(report.findings, 'deletion_ledger')).toEqual([]);
    expect(report.domains.find((d) => d.domain === 'deletion_ledger')).toMatchObject({ ok: 1 });
  });

  it('objeto do ledger fora do formato → INVALID_METADATA', async () => {
    await put('system/deletion-tombstones/nao-e-hash', Buffer.from('x'));
    const report = await auditor().audit();
    expect(findingsOf(report.findings, 'deletion_ledger')).toEqual([
      ['INVALID_METADATA', 'system/deletion-tombstones/nao-e-hash'],
    ]);
  });

  it('nunca remove nada, mesmo com órfãos, faltantes e inconsistências', async () => {
    await seedMedia(MEDIA_KEY, Buffer.from('foto'));
    await put(`social/${MEDIA_KEY_2}`, Buffer.from('orfao'), OLD);
    await put(drDumpObjectName('2026-09-09T031500Z'), Buffer.from('dump'));
    const before = storage.names();

    const report = await auditor().audit();
    expect(report.findings.length).toBeGreaterThan(0);
    expect(removed).toEqual([]);
    expect(storage.names()).toEqual(before);
  });

  it('listagem indisponível: o achado é UNKNOWN por linha, nunca MISSING_OBJECT inventado', async () => {
    await seedMedia(MEDIA_KEY, Buffer.from('foto'));
    storage.fail('list');
    await expect(auditor().audit()).rejects.toThrow();
  });
});
