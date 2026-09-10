import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { AppConfig } from '../src/config/app-config';
import { PostgresService } from '../src/database/postgres.service';
import { BackupRepository } from '../src/modules/backup/backup.repository';
import type { ValidatedSnapshot } from '../src/modules/backup/backup.validator';
import { randomUUID } from 'node:crypto';
import { configFor, createTempDb, postgresFor, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { fixture, withClientBackupId } from './support/backup-fixtures';

const TOKEN = 'token-da-conta';
const UID = 'uid-da-conta';
/** O fence de conta (T18.1.1) exige um hash; nenhum destes testes grava tombstone, então o valor
 * exato não importa — só precisa ser um argumento válido para a consulta. */
const UID_HASH = 'uid-hash-para-teste-sem-tombstone';

/**
 * Persistência do backup: transação, retenção e sobrevivência a restart (T16.4).
 *
 * Estes testes usam PostgreSQL real — restart e retenção só provam
 * alguma coisa contra o mesmo banco que a produção usa.
 */
describe('Persistência do backup', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  // ------------------------------------------------------------------------- transação

  describe('snapshot e itens são transacionais', () => {
    let postgres: PostgresService;
    let repository: BackupRepository;

    beforeEach(async () => {
      postgres = postgresFor(configFor(temp.path));
      await postgres.initialize();
      repository = new BackupRepository(postgres);
    });

    afterEach(async () => {
      await postgres.close();
    });

    it('falhar ao inserir um item não deixa snapshot nem itens parciais', async () => {
      // A falha é forçada no repositório de propósito: a validação já recusaria isto no HTTP, e o
      // que este teste precisa provar é a camada **de baixo** — que a transação existe de verdade
      // e não é só uma sequência de INSERTs com sorte.
      const duplicated = snapshotWith([
        item('CUSTOM_EXERCISE', UUID_1),
        item('CUSTOM_EXERCISE', UUID_1),
      ]);

      await expect(
        repository.insert(UID, duplicated, Date.now(), identity(), UID_HASH),
      ).rejects.toThrow();

      expect(await repository.countFor(UID)).toBe(0);
      expect(await itemCount(postgres)).toBe(0);
      expect(await repository.findLatest(UID)).toBeNull();
    });

    it('um snapshot válido grava todos os itens de uma vez — sem payload no banco (T18.1)', async () => {
      const snapshot = snapshotWith([
        item('CUSTOM_EXERCISE', UUID_1),
        item('CUSTOM_EXERCISE', UUID_2),
      ]);

      const stored = await repository.insert(
        UID,
        snapshot,
        1_700_000_000_000,
        identity(),
        UID_HASH,
      );

      expect(stored.itemCount).toBe(2);
      expect(await itemCount(postgres)).toBe(2);
      expect(stored.createdAt).toBe(1_700_000_000_000);

      // O documento canônico mora no Object Storage; o banco guarda metadata, hashes e a chave.
      const rows = await postgres.query<{
        payload: string | null;
        storage_key: string | null;
        item_payloads: number | string;
      }>(
        `SELECT s.payload, s.storage_key,
                (SELECT COUNT(*) FROM backup_items i WHERE i.snapshot_id = s.id AND i.payload IS NOT NULL) AS item_payloads
           FROM backup_snapshots s WHERE s.backup_id = $1`,
        [stored.backupId],
      );
      expect(rows.rows[0].payload).toBeNull();
      expect(rows.rows[0].storage_key).toBe(stored.storageKey);
      expect(Number(rows.rows[0].item_payloads)).toBe(0);
    });

    it('a retenção preserva os mais recentes e nunca o recém-criado', async () => {
      const stored = [];
      for (const index of [1, 2, 3, 4, 5, 6, 7]) {
        stored.push(
          await repository.insert(
            UID,
            snapshotWith([], `client-${index}`),
            1_700_000_000_000 + index,
            identity(),
            UID_HASH,
          ),
        );
      }

      const pruned = await repository.pruneOlderThan(UID, 5);

      expect(pruned.count).toBe(2);
      // As chaves dos objetos que ficaram sem linha saem junto: é com elas que o serviço apaga os
      // documentos do Object Storage **depois** deste commit (T18.1 §28).
      expect([...pruned.storageKeys].sort()).toEqual(
        [stored[0].storageKey, stored[1].storageKey].sort(),
      );
      expect(await repository.countFor(UID)).toBe(5);
      // O mais novo continua lá; os dois mais antigos foram embora.
      expect((await repository.findLatest(UID))?.backupId).toBe(stored.at(-1)?.backupId);
      expect(await repository.findByClientBackupId(UID, 'client-1')).toBeNull();
      expect(await repository.findByClientBackupId(UID, 'client-2')).toBeNull();
      expect(await repository.findByClientBackupId(UID, 'client-3')).not.toBeNull();
    });

    it('a retenção de uma conta não toca no backup de outra', async () => {
      for (const index of [1, 2, 3, 4, 5, 6]) {
        await repository.insert(
          UID,
          snapshotWith([], `a-${index}`),
          1_700_000_000_000 + index,
          identity(),
          UID_HASH,
        );
      }
      await repository.insert(
        'outra-conta',
        snapshotWith([], 'b-1'),
        1_700_000_000_000,
        identity(),
        UID_HASH,
      );

      await repository.pruneOlderThan(UID, 5);

      expect(await repository.countFor(UID)).toBe(5);
      expect(await repository.countFor('outra-conta')).toBe(1);
    });

    it('apagar o snapshot leva os itens junto', async () => {
      await repository.insert(
        UID,
        snapshotWith([item('CUSTOM_EXERCISE', UUID_1)], 'client-1'),
        1,
        identity(),
        UID_HASH,
      );
      await repository.insert(UID, snapshotWith([], 'client-2'), 2, identity(), UID_HASH);

      await repository.pruneOlderThan(UID, 1);

      // `ON DELETE CASCADE` no PostgreSQL: nenhum item órfão sobra na tabela.
      expect(await itemCount(postgres)).toBe(0);
    });
  });

  // ---------------------------------------------------------------------------- restart

  describe('sobrevivência a restart', () => {
    let app: INestApplication;
    const config = (): AppConfig => configFor(temp.path);

    const start = async (): Promise<INestApplication> =>
      createTestApp(config(), FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }));

    afterEach(async () => {
      await app?.close();
    });

    it('a metadata continua disponível depois de fechar e reabrir o banco', async () => {
      app = await start();
      const created = await request(app.getHttpServer())
        .post('/v1/backups')
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'application/json')
        .send(JSON.stringify(fixture('backup-v1-complete')));
      expect(created.status).toBe(201);
      await app.close();

      app = await start();
      const latest = await request(app.getHttpServer())
        .get('/v1/backups/latest')
        .set('Authorization', `Bearer ${TOKEN}`);

      expect(latest.status).toBe(200);
      expect(latest.body.backupId).toBe(created.body.backupId);
      expect(latest.body.payloadHash).toBe(created.body.payloadHash);
      expect(latest.body.itemCount).toBe(9);
    });

    it('depois do restart, a mesma tentativa continua sendo reconhecida', async () => {
      const body = withClientBackupId('backup-v1-minimal', '55555555-5555-4555-8555-555555555555');

      app = await start();
      const first = await request(app.getHttpServer())
        .post('/v1/backups')
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'application/json')
        .send(JSON.stringify(body));
      await app.close();

      app = await start();
      const retry = await request(app.getHttpServer())
        .post('/v1/backups')
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'application/json')
        .send(JSON.stringify(body));

      // Idempotência é estado durável, não memória de processo: o servidor pode ter reiniciado
      // entre a gravação e o reenvio, que é exatamente quando a resposta se perde.
      expect(first.status).toBe(201);
      expect(retry.status).toBe(200);
      expect(retry.body.backupId).toBe(first.body.backupId);
    });
  });
});

const UUID_1 = '11111111-1111-4111-8111-111111111111';
const UUID_2 = '22222222-2222-4222-8222-222222222222';

function item(entityType: 'CUSTOM_EXERCISE', syncId: string) {
  return {
    entityType,
    entitySchemaVersion: 1,
    entitySyncId: syncId,
    canonicalPayload: `{"syncId":"${syncId}"}`,
    contentHash: 'hash-de-teste',
  } as const;
}

function snapshotWith(
  items: ReturnType<typeof item>[],
  clientBackupId = 'client-backup-id',
): ValidatedSnapshot {
  return {
    clientBackupId,
    backupSchemaVersion: 1,
    deviceId: 'device-de-teste',
    capturedAt: null,
    items,
    payloadHash: `hash-${clientBackupId}`,
    sizeBytes: 42,
    canonicalText: `{"clientBackupId":"${clientBackupId}"}`,
  };
}

/** A identidade que o serviço gera antes de gravar: `backupId` do servidor e a chave derivada dele. */
function identity(): { backupId: string; storageKey: string } {
  const backupId = randomUUID();
  return {
    backupId,
    storageKey: `backups/${backupId.slice(0, 2)}/${backupId.slice(2, 4)}/${backupId}.json`,
  };
}

async function itemCount(postgres: PostgresService): Promise<number> {
  const result = await postgres.query<{ total: number }>(
    'SELECT COUNT(*) AS total FROM backup_items',
  );
  return result.rows[0].total;
}
