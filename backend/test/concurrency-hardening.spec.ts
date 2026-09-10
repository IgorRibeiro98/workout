import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { Pool } from 'pg';
import { PostgresService } from '../src/database/postgres.service';
import { loadMigrations, runMigrations } from '../src/database/postgres-migration-runner';
import {
  configFor,
  createTempDb,
  MIGRATIONS_DIR,
  postgresFor,
  type TempDb,
} from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeAiProviderGateway } from './support/fake-ai-provider';
import * as aiFixtures from './support/ai-fixtures';
import { programPayload, pushBody, uuid } from './support/sync-fixtures';
import { withClientBackupId } from './support/backup-fixtures';
import { FriendshipRepository } from '../src/modules/social/friendship.repository';
import { MIGRATION_ADVISORY_LOCK_KEY } from '../src/database/database.constants';

const TOKEN_A = 'token-user-a';
const UID_A = 'uid-user-a';
const TOKEN_B = 'token-user-b';
const UID_B = 'uid-user-b';

describe('T18.0.1 Concurrency Hardening Suite', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  // ---------------------------------------------------------------------------
  // 1. Migration Runner Concurrency
  // ---------------------------------------------------------------------------
  describe('Concorrência de migrations', () => {
    it('2 instâncias concorrentes de runMigrations não colidem nem quebram o schema', async () => {
      const config = configFor(temp.path);
      const postgres = postgresFor(config);
      await postgres.initialize(MIGRATIONS_DIR);

      const pool1 = new Pool({ connectionString: config.databaseUrl, max: 2 });
      const pool2 = new Pool({ connectionString: config.databaseUrl, max: 2 });

      try {
        const migrations = loadMigrations(MIGRATIONS_DIR);
        const [res1, res2] = await Promise.all([
          runMigrations(pool1, migrations),
          runMigrations(pool2, migrations),
        ]);

        expect(Array.isArray(res1)).toBe(true);
        expect(Array.isArray(res2)).toBe(true);
      } finally {
        await pool1.end();
        await pool2.end();
        await postgres.close();
      }
    });

    // O cenário que importa (T18.0.2): o **primeiro** boot de dois processos ao mesmo tempo —
    // duas réplicas subindo juntas sobre um banco recém-criado. Rodar dois runners sobre um banco
    // já migrado (o teste acima) prova só a idempotência; este prova que a baseline é aplicada
    // exatamente uma vez, sem `CREATE` duplicado, sem migration parcial e sem lock esquecido.
    it('dois runners sobre um schema VAZIO aplicam a baseline exatamente uma vez', async () => {
      const config = configFor(temp.path);
      const cleanUrl = config.databaseUrl.replace(/[?&]options=[^&]+/g, '');
      const admin = new Pool({ connectionString: cleanUrl, max: 1 });
      await admin.query(`CREATE SCHEMA "${temp.schema}"`);

      const pool1 = new Pool({ connectionString: config.databaseUrl, max: 2 });
      const pool2 = new Pool({ connectionString: config.databaseUrl, max: 2 });

      try {
        const migrations = loadMigrations(MIGRATIONS_DIR);
        expect(migrations.length).toBeGreaterThan(0);

        const settled = await Promise.allSettled([
          runMigrations(pool1, migrations),
          runMigrations(pool2, migrations),
        ]);

        // Ambos os chamadores terminam bem: nenhum `relation already exists`, nenhum timeout.
        for (const outcome of settled) {
          if (outcome.status === 'rejected') {
            throw outcome.reason;
          }
        }
        const applied = settled.map((o) => (o.status === 'fulfilled' ? o.value.length : -1));
        // Exatamente um runner aplicou tudo; o outro chegou depois do lock e não aplicou nada.
        expect([...applied].sort((a, b) => a - b)).toEqual([0, migrations.length]);

        // `schema_migrations` consistente: uma linha por migration, com nome e checksum.
        const rows = await pool1.query<{ version: number; name: string; checksum: string | null }>(
          'SELECT version, name, checksum FROM schema_migrations ORDER BY version',
        );
        expect(rows.rows.map((r) => r.version)).toEqual(migrations.map((m) => m.version));
        expect(rows.rows.map((r) => r.name)).toEqual(migrations.map((m) => m.name));
        expect(
          rows.rows.every((r) => typeof r.checksum === 'string' && r.checksum.length === 64),
        ).toBe(true);

        // Nenhuma migration parcialmente aplicada: as tabelas da baseline existem — inclusive as
        // últimas do arquivo, que é onde uma aplicação interrompida deixaria buraco.
        const tables = await pool1.query<{ table_name: string }>(
          `SELECT table_name FROM information_schema.tables WHERE table_schema = $1`,
          [temp.schema],
        );
        const names = new Set(tables.rows.map((r) => r.table_name));
        for (const expected of [
          'server_metadata',
          'sync_entities',
          'sync_changes',
          'sync_mutations',
          'friend_requests',
          'friendships',
          'social_group_memberships',
          'social_checkin_comments',
          'account_deletion_tombstones',
        ]) {
          expect(names.has(expected)).toBe(true);
        }

        // Nenhum advisory lock vazado: com os dois runners terminados (e as conexões ainda
        // abertas nos pools), não pode restar lock de migration no servidor.
        const locks = await admin.query<{ total: string | number }>(
          `SELECT COUNT(*) AS total FROM pg_locks WHERE locktype = 'advisory' AND classid = $1`,
          [MIGRATION_ADVISORY_LOCK_KEY],
        );
        expect(Number(locks.rows[0].total)).toBe(0);

        // E rodar de novo, depois da corrida, não reaplica nada.
        expect(await runMigrations(pool2, migrations)).toEqual([]);
      } finally {
        await pool1.end();
        await pool2.end();
        await admin.end();
      }
    });

    // Mais cedo ainda que o schema vazio: o schema **não existe**. O `CREATE SCHEMA IF NOT EXISTS`
    // do PostgreSQL não é atômico entre sessões, e dois runners que não o veem tentam criá-lo ao
    // mesmo tempo — antes do advisory lock, que mora dentro dele. Um dos dois perde com
    // `duplicate_schema`, e isso não pode derrubar um boot.
    it('dois runners sobre um schema INEXISTENTE também convergem sem erro', async () => {
      const config = configFor(temp.path);
      const pool1 = new Pool({ connectionString: config.databaseUrl, max: 2 });
      const pool2 = new Pool({ connectionString: config.databaseUrl, max: 2 });

      try {
        const migrations = loadMigrations(MIGRATIONS_DIR);
        const [a, b] = await Promise.all([
          runMigrations(pool1, migrations),
          runMigrations(pool2, migrations),
        ]);
        expect([a.length, b.length].sort((x, y) => x - y)).toEqual([0, migrations.length]);

        const rows = await pool1.query<{ total: string | number }>(
          'SELECT COUNT(*) AS total FROM schema_migrations',
        );
        expect(Number(rows.rows[0].total)).toBe(migrations.length);
      } finally {
        await pool1.end();
        await pool2.end();
      }
    });
  });

  // ---------------------------------------------------------------------------
  // 2. Sync Concurrency, CAS, Phantom Change Log & Matrix
  // ---------------------------------------------------------------------------
  describe('Sync Concurrency & Atomic Revision Decision', () => {
    let app: INestApplication;

    beforeEach(async () => {
      const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A }).accept(
        TOKEN_B,
        {
          uid: UID_B,
        },
      );
      app = await createTestApp(configFor(temp.path), verifier);
    });

    afterEach(async () => {
      await app.close();
    });

    const push = (body: string, token = TOKEN_A) =>
      request(app.getHttpServer())
        .post('/v1/sync/push')
        .set('Authorization', `Bearer ${token}`)
        .set('Content-Type', 'application/json')
        .send(body);

    it('2 pushes concorrentes com mesmo baseRevision=5: 1 APPLIED (rev 6), 1 STALE (rev 6), sem phantom change log', async () => {
      const syncId = uuid();

      // 1. Cria a entidade e avança até a revisão 5
      let lastRev = 0;
      for (let rev = 0; rev < 5; rev++) {
        const res = await push(
          pushBody([
            {
              entityType: 'WORKOUT_PROGRAM',
              entitySyncId: syncId,
              baseRevision: rev === 0 ? null : rev,
              payload: programPayload(syncId, `Program v${rev + 1}`),
            },
          ]),
        );
        expect(res.body.results[0].status).toBe('APPLIED');
        lastRev = res.body.results[0].serverRevision;
      }
      expect(lastRev).toBe(5);

      // 2. Dispara duas mutações concorrentes com baseRevision = 5
      const mutId1 = uuid();
      const mutId2 = uuid();

      const [res1, res2] = await Promise.all([
        push(
          pushBody([
            {
              clientMutationId: mutId1,
              entityType: 'WORKOUT_PROGRAM',
              entitySyncId: syncId,
              baseRevision: 5,
              payload: programPayload(syncId, 'Concurrent Winner A'),
            },
          ]),
        ),
        push(
          pushBody([
            {
              clientMutationId: mutId2,
              entityType: 'WORKOUT_PROGRAM',
              entitySyncId: syncId,
              baseRevision: 5,
              payload: programPayload(syncId, 'Concurrent Winner B'),
            },
          ]),
        ),
      ]);

      const outcomes = [res1.body.results[0], res2.body.results[0]];
      const applied = outcomes.filter((o) => o.status === 'APPLIED');
      const stale = outcomes.filter((o) => o.status === 'STALE');

      expect(applied).toHaveLength(1);
      expect(stale).toHaveLength(1);

      expect(applied[0].serverRevision).toBe(6);
      expect(stale[0].currentRevision).toBe(6);

      // Confirma que não há phantom change log nem phantom ledger no banco
      const postgres = app.get(PostgresService);
      const changes = await postgres.query(
        'SELECT server_revision, payload FROM sync_changes WHERE owner_uid = $1 AND entity_sync_id = $2 AND server_revision = 6',
        [UID_A, syncId],
      );
      expect(changes.rows).toHaveLength(1);

      const entity = await postgres.query(
        'SELECT server_revision FROM sync_entities WHERE owner_uid = $1 AND entity_sync_id = $2',
        [UID_A, syncId],
      );
      expect(entity.rows[0].server_revision).toBe(6);

      const mutations = await postgres.query(
        'SELECT client_mutation_id, result_revision FROM sync_mutations WHERE owner_uid = $1 AND entity_sync_id = $2 AND result_revision = 6',
        [UID_A, syncId],
      );
      expect(mutations.rows).toHaveLength(1);
      expect(mutations.rows[0].client_mutation_id).toBe(applied[0].clientMutationId);
    });

    it('Matriz de concorrência: UPSERT x DELETE com mesmo baseRevision', async () => {
      const syncId = uuid();

      const initial = await push(
        pushBody([
          {
            entityType: 'WORKOUT_PROGRAM',
            entitySyncId: syncId,
            baseRevision: null,
            payload: programPayload(syncId, 'Initial'),
          },
        ]),
      );
      expect(initial.body.results[0].status).toBe('APPLIED');

      const [resUpsert, resDelete] = await Promise.all([
        push(
          pushBody([
            {
              entityType: 'WORKOUT_PROGRAM',
              entitySyncId: syncId,
              baseRevision: 1,
              payload: programPayload(syncId, 'Upsert Update'),
            },
          ]),
        ),
        push(
          pushBody([
            {
              entityType: 'WORKOUT_PROGRAM',
              entitySyncId: syncId,
              operation: 'DELETE',
              baseRevision: 1,
            },
          ]),
        ),
      ]);

      const statusUpsert = resUpsert.body.results[0].status;
      const statusDelete = resDelete.body.results[0].status;

      if (statusUpsert === 'APPLIED') {
        expect(resUpsert.body.results[0].serverRevision).toBe(2);
        expect(statusDelete).toBe('STALE');
        expect(resDelete.body.results[0].currentRevision).toBe(2);
      } else {
        expect(statusDelete).toBe('APPLIED');
        expect(resDelete.body.results[0].serverRevision).toBe(2);
        expect(statusUpsert).toBe('REMOTE_DELETED');
        expect(resUpsert.body.results[0].currentRevision).toBe(2);
      }
    });

    it('Matriz de concorrência: DELETE x DELETE com mesmo baseRevision', async () => {
      const syncId = uuid();

      await push(
        pushBody([
          {
            entityType: 'WORKOUT_PROGRAM',
            entitySyncId: syncId,
            baseRevision: null,
            payload: programPayload(syncId, 'Initial'),
          },
        ]),
      );

      const [res1, res2] = await Promise.all([
        push(
          pushBody([
            {
              entityType: 'WORKOUT_PROGRAM',
              entitySyncId: syncId,
              operation: 'DELETE',
              baseRevision: 1,
            },
          ]),
        ),
        push(
          pushBody([
            {
              entityType: 'WORKOUT_PROGRAM',
              entitySyncId: syncId,
              operation: 'DELETE',
              baseRevision: 1,
            },
          ]),
        ),
      ]);

      const statuses = [res1.body.results[0].status, res2.body.results[0].status];
      expect(statuses).toContain('APPLIED');
      expect(statuses).toContain('ALREADY_APPLIED');
    });

    it('Idempotência concorrente no sync: mesmo clientMutationId e mesmo payload converge', async () => {
      const syncId = uuid();
      const clientMutationId = uuid();
      const payload = programPayload(syncId, 'Idempotent Payload');

      const body = pushBody([
        {
          clientMutationId,
          entityType: 'WORKOUT_PROGRAM',
          entitySyncId: syncId,
          baseRevision: null,
          payload,
        },
      ]);

      const [res1, res2] = await Promise.all([push(body), push(body)]);

      expect(res1.status).toBe(200);
      expect(res2.status).toBe(200);

      const statuses = [res1.body.results[0].status, res2.body.results[0].status];
      expect(statuses).toContain('APPLIED');
      expect(statuses).toContain('ALREADY_APPLIED');

      const applied =
        res1.body.results[0].status === 'APPLIED' ? res1.body.results[0] : res2.body.results[0];
      const replayed =
        res1.body.results[0].status === 'ALREADY_APPLIED'
          ? res1.body.results[0]
          : res2.body.results[0];

      expect(applied.serverRevision).toBe(replayed.serverRevision);
      expect(applied.serverSequence).toBe(replayed.serverSequence);
    });

    it('Idempotência concorrente no sync: mesmo clientMutationId com payloads diferentes retorna IDEMPOTENCY_CONFLICT', async () => {
      const syncId = uuid();
      const clientMutationId = uuid();

      const body1 = pushBody([
        {
          clientMutationId,
          entityType: 'WORKOUT_PROGRAM',
          entitySyncId: syncId,
          baseRevision: null,
          payload: programPayload(syncId, 'Payload A'),
        },
      ]);
      const body2 = pushBody([
        {
          clientMutationId,
          entityType: 'WORKOUT_PROGRAM',
          entitySyncId: syncId,
          baseRevision: null,
          payload: programPayload(syncId, 'Payload B - Different!'),
        },
      ]);

      const [res1, res2] = await Promise.all([push(body1), push(body2)]);

      expect(res1.status).toBe(200);
      expect(res2.status).toBe(200);

      const statuses = [res1.body.results[0].status, res2.body.results[0].status];
      expect(statuses).toContain('APPLIED');
      expect(statuses).toContain('IDEMPOTENCY_CONFLICT');
    });
  });

  // ---------------------------------------------------------------------------
  // 3. Backup Concurrency & Idempotency
  // ---------------------------------------------------------------------------
  describe('Backup Concurrency & Idempotency', () => {
    let app: INestApplication;

    beforeEach(async () => {
      const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A });
      app = await createTestApp(configFor(temp.path), verifier);
    });

    afterEach(async () => {
      await app.close();
    });

    const createBackup = (body: unknown) =>
      request(app.getHttpServer())
        .post('/v1/backups')
        .set('Authorization', `Bearer ${TOKEN_A}`)
        .set('Content-Type', 'application/json')
        .send(typeof body === 'string' ? body : JSON.stringify(body));

    it('2 chamadas concorrentes com mesmo clientBackupId e mesmo payload convergem para 201 e 200 sem erro 500', async () => {
      const clientBackupId = '00000000-0000-4000-8000-000000000001';
      const body = withClientBackupId('backup-v1-minimal', clientBackupId);

      const [res1, res2] = await Promise.all([createBackup(body), createBackup(body)]);

      const statuses = [res1.status, res2.status].sort();
      expect(statuses).toEqual([200, 201]);

      const createdRes = res1.status === 201 ? res1 : res2;
      const replayedRes = res1.status === 200 ? res1 : res2;

      expect(createdRes.body.backupId).toBeDefined();
      expect(replayedRes.body.backupId).toBe(createdRes.body.backupId);
    });

    it('2 chamadas concorrentes com mesmo clientBackupId e payloads diferentes retornam conflito (409) sem erro 500', async () => {
      const clientBackupId = '00000000-0000-4000-8000-000000000002';
      const bodyA = withClientBackupId('backup-v1-minimal', clientBackupId);
      const bodyB = {
        ...withClientBackupId('backup-v1-minimal', clientBackupId),
        capturedAt: 1700000000000,
      };

      const [res1, res2] = await Promise.all([createBackup(bodyA), createBackup(bodyB)]);

      const statuses = [res1.status, res2.status].sort();
      expect(statuses).toEqual([201, 409]);
    });
  });

  // ---------------------------------------------------------------------------
  // 4. Social Cross Friend Requests Concurrency
  // ---------------------------------------------------------------------------
  describe('Cross Friend Requests Concurrency', () => {
    let app: INestApplication;

    beforeEach(async () => {
      const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A }).accept(
        TOKEN_B,
        {
          uid: UID_B,
        },
      );
      app = await createTestApp(configFor(temp.path), verifier);
    });

    afterEach(async () => {
      await app.close();
    });

    it('pedidos cruzados simultâneos (A->B e B->A) serializam no par canônico e criam amizade bilateral com 0 pendências', async () => {
      const repo = app.get(FriendshipRepository);
      const postgres = app.get(PostgresService);

      const now = Date.now();
      await postgres.query(`
        INSERT INTO social_profiles (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
        VALUES
          ('${UID_A}', 'soc-a', 'SPK-AAAAAAAA', 'Alice', 'ACTIVE', ${now}, ${now}),
          ('${UID_B}', 'soc-b', 'SPK-BBBBBBBB', 'Bob', 'ACTIVE', ${now}, ${now});
        INSERT INTO social_privacy_settings (owner_uid, discoverability, friend_requests_enabled, activity_sharing_enabled, updated_at)
        VALUES
          ('${UID_A}', 'FRIEND_CODE_ONLY', TRUE, TRUE, ${now}),
          ('${UID_B}', 'FRIEND_CODE_ONLY', TRUE, TRUE, ${now});
      `);

      const reqId1 = uuid();
      const reqId2 = uuid();

      const [res1, res2] = await Promise.all([
        repo.sendRequest({
          requestId: reqId1,
          requesterUid: UID_A,
          recipientUid: UID_B,
          now,
        }),
        repo.sendRequest({
          requestId: reqId2,
          requesterUid: UID_B,
          recipientUid: UID_A,
          now: now + 1,
        }),
      ]);

      const outcomes = [res1.kind, res2.kind];
      expect(outcomes).toContain('CREATED');
      expect(outcomes).toContain('FRIENDSHIP_CREATED');

      const friends = await repo.areFriends(UID_A, UID_B);
      expect(friends).toBe(true);

      const pendingRes = await postgres.query<{ count: string | number }>(
        "SELECT COUNT(*) AS count FROM friend_requests WHERE status = 'PENDING'",
      );
      expect(Number(pendingRes.rows[0].count)).toBe(0);
    });

    // --- a matriz de races do par (T18.0.2) ---------------------------------------------------
    //
    // Todo caminho que muda a relação de um par — enviar, aceitar, rejeitar, cancelar — precisa
    // passar pelo mesmo lock do par canônico e decidir sobre estado lido **depois** dele. O
    // invariante final, em qualquer interleaving:
    //
    //   nunca existe um pedido REJECTED/CANCELLED **e** uma amizade nascida daquele pedido.
    //
    // Cada cenário roda várias vezes com contas novas, porque a corrida é de verdade (duas
    // transações em `Promise.all`) e o interleaving muda de execução para execução.

    interface PairState {
      readonly requests: ReadonlyArray<{
        request_id: string;
        requester_uid: string;
        recipient_uid: string;
        status: string;
      }>;
      readonly friends: boolean;
    }

    async function seedPair(postgres: PostgresService, uidA: string, uidB: string) {
      const now = Date.now();
      await postgres.query(
        `INSERT INTO social_profiles (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
         VALUES ($1, $2, $3, 'A', 'ACTIVE', $7, $7), ($4, $5, $6, 'B', 'ACTIVE', $7, $7)`,
        [
          uidA,
          `soc-${uidA}`,
          `SPK-${uidA.slice(-8).toUpperCase()}`,
          uidB,
          `soc-${uidB}`,
          `SPK-${uidB.slice(-8).toUpperCase()}`,
          now,
        ],
      );
      await postgres.query(
        `INSERT INTO social_privacy_settings (owner_uid, discoverability, friend_requests_enabled, activity_sharing_enabled, updated_at)
         VALUES ($1, 'FRIEND_CODE_ONLY', TRUE, TRUE, $3), ($2, 'FRIEND_CODE_ONLY', TRUE, TRUE, $3)`,
        [uidA, uidB, now],
      );
    }

    async function pairState(
      postgres: PostgresService,
      repo: FriendshipRepository,
      uidA: string,
      uidB: string,
    ): Promise<PairState> {
      const res = await postgres.query<PairState['requests'][number]>(
        `SELECT request_id, requester_uid, recipient_uid, status FROM friend_requests
         WHERE (requester_uid = $1 AND recipient_uid = $2) OR (requester_uid = $2 AND recipient_uid = $1)
         ORDER BY created_at, request_id`,
        [uidA, uidB],
      );
      return { requests: res.rows, friends: await repo.areFriends(uidA, uidB) };
    }

    /** O invariante que nenhuma corrida pode violar. */
    function expectNoContradiction(state: PairState) {
      const resolvedAgainst = state.requests.filter(
        (r) => r.status === 'REJECTED' || r.status === 'CANCELLED',
      );
      const accepted = state.requests.filter((r) => r.status === 'ACCEPTED');
      if (state.friends) {
        // Amizade só existe por um pedido ACCEPTED — nunca por um REJECTED/CANCELLED.
        expect(accepted.length).toBeGreaterThanOrEqual(1);
      } else {
        expect(accepted).toHaveLength(0);
      }
      // Um pedido rejeitado/cancelado nunca coexiste com uma amizade sem um pedido aceito por trás.
      if (resolvedAgainst.length > 0 && state.friends) {
        expect(accepted.length).toBeGreaterThanOrEqual(1);
      }
      // No máximo um pedido PENDING por direção, e nunca PENDING com amizade existente.
      const pending = state.requests.filter((r) => r.status === 'PENDING');
      expect(pending.length).toBeLessThanOrEqual(1);
      if (state.friends) {
        expect(pending).toHaveLength(0);
      }
    }

    const ROUNDS = 6;

    it('send A→B concorrendo com reject B→A: ou o pedido morre e nasce outro, ou vira amizade — nunca os dois', async () => {
      const repo = app.get(FriendshipRepository);
      const postgres = app.get(PostgresService);
      const outcomes = new Set<string>();

      for (let round = 0; round < ROUNDS; round++) {
        const a = `uid-a-${round}-${uuid().slice(0, 8)}`;
        const b = `uid-b-${round}-${uuid().slice(0, 8)}`;
        await seedPair(postgres, a, b);
        const now = Date.now();
        const inverse = await repo.sendRequest({
          requestId: uuid(),
          requesterUid: b,
          recipientUid: a,
          now,
        });
        expect(inverse.kind).toBe('CREATED');
        const inverseId = inverse.kind === 'CREATED' ? inverse.request.requestId : '';

        const [send, rejected] = await Promise.all([
          repo.sendRequest({ requestId: uuid(), requesterUid: a, recipientUid: b, now: now + 1 }),
          repo.resolveRequest(inverseId, 'REJECTED', now + 1),
        ]);

        const state = await pairState(postgres, repo, a, b);
        expectNoContradiction(state);
        const inverseRow = state.requests.find((r) => r.request_id === inverseId);
        expect(inverseRow).toBeDefined();

        if (rejected) {
          // O reject venceu: o pedido inverso morreu, o envio criou um pedido novo A→B, sem amizade.
          expect(inverseRow?.status).toBe('REJECTED');
          expect(send.kind).toBe('CREATED');
          expect(state.friends).toBe(false);
          outcomes.add('reject-first');
        } else {
          // O envio venceu: o cruzamento virou amizade, e o reject não mudou nada.
          expect(inverseRow?.status).toBe('ACCEPTED');
          expect(send.kind).toBe('FRIENDSHIP_CREATED');
          expect(state.friends).toBe(true);
          outcomes.add('send-first');
        }
      }
      expect(outcomes.size).toBeGreaterThanOrEqual(1);
    });

    it('send A→B concorrendo com cancel B→A: nunca CANCELLED + amizade', async () => {
      const repo = app.get(FriendshipRepository);
      const postgres = app.get(PostgresService);

      for (let round = 0; round < ROUNDS; round++) {
        const a = `uid-a-${round}-${uuid().slice(0, 8)}`;
        const b = `uid-b-${round}-${uuid().slice(0, 8)}`;
        await seedPair(postgres, a, b);
        const now = Date.now();
        const inverse = await repo.sendRequest({
          requestId: uuid(),
          requesterUid: b,
          recipientUid: a,
          now,
        });
        const inverseId = inverse.kind === 'CREATED' ? inverse.request.requestId : '';

        const [send, cancelled] = await Promise.all([
          repo.sendRequest({ requestId: uuid(), requesterUid: a, recipientUid: b, now: now + 1 }),
          repo.resolveRequest(inverseId, 'CANCELLED', now + 1),
        ]);

        const state = await pairState(postgres, repo, a, b);
        expectNoContradiction(state);
        const inverseRow = state.requests.find((r) => r.request_id === inverseId);
        if (cancelled) {
          expect(inverseRow?.status).toBe('CANCELLED');
          expect(send.kind).toBe('CREATED');
          expect(state.friends).toBe(false);
        } else {
          expect(inverseRow?.status).toBe('ACCEPTED');
          expect(send.kind).toBe('FRIENDSHIP_CREATED');
          expect(state.friends).toBe(true);
        }
      }
    });

    it('accept concorrendo com reject do mesmo pedido: exatamente um vence, e o banco reflete só ele', async () => {
      const repo = app.get(FriendshipRepository);
      const postgres = app.get(PostgresService);

      for (let round = 0; round < ROUNDS; round++) {
        const a = `uid-a-${round}-${uuid().slice(0, 8)}`;
        const b = `uid-b-${round}-${uuid().slice(0, 8)}`;
        await seedPair(postgres, a, b);
        const now = Date.now();
        const sent = await repo.sendRequest({
          requestId: uuid(),
          requesterUid: b,
          recipientUid: a,
          now,
        });
        const requestId = sent.kind === 'CREATED' ? sent.request.requestId : '';

        const [accept, rejected] = await Promise.all([
          repo.acceptRequest(requestId, now + 1),
          repo.resolveRequest(requestId, 'REJECTED', now + 1),
        ]);

        const state = await pairState(postgres, repo, a, b);
        expectNoContradiction(state);
        const row = state.requests.find((r) => r.request_id === requestId);
        // Exclusão mútua: ou ACCEPTED + amizade, ou REJECTED + sem amizade.
        expect((accept.kind === 'ACCEPTED') !== rejected).toBe(true);
        if (rejected) {
          expect(row?.status).toBe('REJECTED');
          expect(accept.kind).toBe('NOT_PENDING');
          expect(state.friends).toBe(false);
        } else {
          expect(row?.status).toBe('ACCEPTED');
          expect(state.friends).toBe(true);
        }
      }
    });

    it('accept concorrendo com cancel do mesmo pedido: exatamente um vence', async () => {
      const repo = app.get(FriendshipRepository);
      const postgres = app.get(PostgresService);

      for (let round = 0; round < ROUNDS; round++) {
        const a = `uid-a-${round}-${uuid().slice(0, 8)}`;
        const b = `uid-b-${round}-${uuid().slice(0, 8)}`;
        await seedPair(postgres, a, b);
        const now = Date.now();
        const sent = await repo.sendRequest({
          requestId: uuid(),
          requesterUid: b,
          recipientUid: a,
          now,
        });
        const requestId = sent.kind === 'CREATED' ? sent.request.requestId : '';

        const [accept, cancelled] = await Promise.all([
          repo.acceptRequest(requestId, now + 1),
          repo.resolveRequest(requestId, 'CANCELLED', now + 1),
        ]);

        const state = await pairState(postgres, repo, a, b);
        expectNoContradiction(state);
        const row = state.requests.find((r) => r.request_id === requestId);
        expect((accept.kind === 'ACCEPTED') !== cancelled).toBe(true);
        if (cancelled) {
          expect(row?.status).toBe('CANCELLED');
          expect(state.friends).toBe(false);
        } else {
          expect(row?.status).toBe('ACCEPTED');
          expect(state.friends).toBe(true);
        }
      }
    });

    it('send A→B concorrendo com send B→A, repetido: sempre uma amizade e zero pendências', async () => {
      const repo = app.get(FriendshipRepository);
      const postgres = app.get(PostgresService);

      for (let round = 0; round < ROUNDS; round++) {
        const a = `uid-a-${round}-${uuid().slice(0, 8)}`;
        const b = `uid-b-${round}-${uuid().slice(0, 8)}`;
        await seedPair(postgres, a, b);
        const now = Date.now();

        const [x, y] = await Promise.all([
          repo.sendRequest({ requestId: uuid(), requesterUid: a, recipientUid: b, now }),
          repo.sendRequest({ requestId: uuid(), requesterUid: b, recipientUid: a, now: now + 1 }),
        ]);

        expect([x.kind, y.kind].sort()).toEqual(['CREATED', 'FRIENDSHIP_CREATED']);
        const state = await pairState(postgres, repo, a, b);
        expectNoContradiction(state);
        expect(state.friends).toBe(true);
        expect(state.requests).toHaveLength(1);
        expect(state.requests[0].status).toBe('ACCEPTED');
      }
    });
  });

  // ---------------------------------------------------------------------------
  // 5. AI Quota Atomic Global Limit
  // ---------------------------------------------------------------------------
  describe('AI Quota Atomic Global Limit', () => {
    let app: INestApplication;

    afterEach(async () => {
      if (app) await app.close();
    });

    it('limite global diário é respeitado atomicamente sob chamadas concorrentes de múltiplos usuários', async () => {
      const GLOBAL_LIMIT = 3;
      const verifier = new FakeAuthTokenVerifier();
      for (let i = 0; i < 10; i++) {
        verifier.accept(`token-${i}`, { uid: `uid-${i}` });
      }

      const provider = FakeAiProviderGateway.respondingWith(aiFixtures.analysisOutput());
      app = await createTestApp(
        configFor(temp.path, {
          AI_MAX_REQUESTS_GLOBAL_DAY: String(GLOBAL_LIMIT),
          AI_MAX_REQUESTS_PER_USER_DAY: '5',
        }),
        verifier,
        provider,
      );

      const call = (token: string, clientReqId: string) =>
        request(app.getHttpServer())
          .post('/v1/ai/coach')
          .set('Authorization', `Bearer ${token}`)
          .send(
            aiFixtures.requestBody('ANALYZE_WORKOUT', aiFixtures.analysisContext(), {
              clientRequestId: clientReqId,
            }),
          );

      const promises = Array.from({ length: 8 }, (_, i) =>
        call(`token-${i}`, `client-req-00000${i}`).then((res) => res.status),
      );

      const statuses = await Promise.all(promises);
      const successCount = statuses.filter((s) => s === 201).length;
      const quotaExceededCount = statuses.filter((s) => s === 429).length;

      expect(successCount).toBe(GLOBAL_LIMIT);
      expect(quotaExceededCount).toBe(8 - GLOBAL_LIMIT);

      const postgres = app.get(PostgresService);
      const usageRes = await postgres.query<{ total: string | number }>(
        'SELECT COALESCE(SUM(request_count), 0) AS total FROM ai_usage_daily',
      );
      expect(Number(usageRes.rows[0].total)).toBe(GLOBAL_LIMIT);
    });
  });
});
