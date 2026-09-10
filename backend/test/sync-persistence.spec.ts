import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PostgresService } from '../src/database/postgres.service';
import { SyncRepository } from '../src/modules/sync/sync.repository';
import { SYNC_RATE_LIMIT } from '../src/modules/sync/sync.limits';
import { configFor, createTempDb, postgresFor, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import {
  measurementPayload,
  programPayload,
  pushBody,
  sessionPayload,
  uuid,
} from './support/sync-fixtures';

const TOKEN = 'token-da-conta';
const UID = 'uid-da-conta';

/**
 * Persistência do sync: transação, sequência e sobrevivência a restart (T16.6).
 *
 * Estes testes usam PostgreSQL real — restart só prova alguma coisa
 * contra o mesmo banco que a produção usa.
 */
describe('Persistência do sync', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  // ------------------------------------------------------------------------- transação

  describe('entidade, mudança e ledger são transacionais', () => {
    let postgres: PostgresService;
    let repository: SyncRepository;

    beforeEach(async () => {
      postgres = postgresFor(configFor(temp.path));
      await postgres.initialize();
      repository = new SyncRepository(postgres);
    });

    afterEach(async () => {
      await postgres.close();
    });

    const input = (syncId: string) => ({
      ownerUid: UID,
      deviceId: 'device-a',
      clientMutationId: uuid(),
      entityType: 'WORKOUT_PROGRAM' as const,
      entitySyncId: syncId,
      entitySchemaVersion: 1,
      operation: 'UPSERT' as const,
      baseRevision: null,
      canonicalPayload: JSON.stringify(programPayload(syncId)),
      payloadHash: 'a'.repeat(64),
      nextRevision: 1,
      now: 1_700_000_000_000,
    });

    it('grava as três tabelas juntas', async () => {
      const syncId = uuid();
      const applied = await repository.applyMutation(input(syncId));

      expect(applied.serverRevision).toBe(1);
      expect(await repository.findEntity(UID, 'WORKOUT_PROGRAM', syncId)).not.toBeNull();
      expect((await repository.changesAfter(UID, 0, 10)).changes).toHaveLength(1);
    });

    it('falhar ao registrar o ledger desfaz a entidade e a mudança', async () => {
      // Sem `sync_mutations`, a última escrita da transação falha. Se a transação não cobrisse as
      // três, sobrariam uma entidade e uma mudança sem tentativa correspondente — e um reenvio
      // aplicaria tudo de novo.
      await postgres.query('DROP TABLE sync_mutations');

      const syncId = uuid();
      await expect(repository.applyMutation(input(syncId))).rejects.toThrow();

      expect(await repository.findEntity(UID, 'WORKOUT_PROGRAM', syncId)).toBeNull();
      expect((await repository.changesAfter(UID, 0, 10)).changes).toHaveLength(0);
    });

    it('falhar ao anexar a mudança não deixa a entidade atualizada sozinha', async () => {
      // O inverso: sem change log, nenhum outro aparelho saberia da alteração. O estado remoto não
      // pode avançar sozinho.
      await postgres.query('DROP TABLE sync_changes');

      const syncId = uuid();
      await expect(repository.applyMutation(input(syncId))).rejects.toThrow();
      expect(await repository.findEntity(UID, 'WORKOUT_PROGRAM', syncId)).toBeNull();
    });

    it('a sequência é global, crescente e não reaproveita número', async () => {
      const first = await repository.applyMutation(input(uuid()));
      const second = await repository.applyMutation(input(uuid()));
      const third = await repository.applyMutation({ ...input(uuid()), ownerUid: 'outra-conta' });

      expect(second.serverSequence).toBeGreaterThan(first.serverSequence);
      expect(third.serverSequence).toBeGreaterThan(second.serverSequence);
      expect(await repository.maxSequence()).toBe(third.serverSequence);
    });
  });

  // ------------------------------------------------------------------------- restart

  describe('o estado sobrevive ao restart do processo', () => {
    const start = async (): Promise<INestApplication> =>
      createTestApp(configFor(temp.path), FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }));

    it('push, restart, pull: a mudança continua lá', async () => {
      const syncId = uuid();

      const first = await start();
      const pushed = await request(first.getHttpServer())
        .post('/v1/sync/push')
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'application/json')
        .send(
          pushBody([
            {
              entityType: 'WORKOUT_PROGRAM',
              entitySyncId: syncId,
              payload: programPayload(syncId),
            },
          ]),
        );
      expect(pushed.body.results[0].status).toBe('APPLIED');
      await first.close();

      const second = await start();
      const pulled = await request(second.getHttpServer())
        .get('/v1/sync/pull?cursor=0')
        .set('Authorization', `Bearer ${TOKEN}`);
      expect(pulled.body.changes).toHaveLength(1);
      expect(pulled.body.changes[0].entitySyncId).toBe(syncId);
      await second.close();
    });

    it('o ledger de idempotência sobrevive: o reenvio depois do restart não reaplica', async () => {
      const syncId = uuid();
      const clientMutationId = uuid();
      const body = pushBody([
        {
          clientMutationId,
          entityType: 'WORKOUT_SESSION',
          entitySyncId: syncId,
          payload: sessionPayload(syncId),
        },
      ]);

      const first = await start();
      const applied = await request(first.getHttpServer())
        .post('/v1/sync/push')
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'application/json')
        .send(body);
      await first.close();

      const second = await start();
      const retry = await request(second.getHttpServer())
        .post('/v1/sync/push')
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'application/json')
        .send(body);

      expect(retry.body.results[0].status).toBe('ALREADY_APPLIED');
      expect(retry.body.results[0].serverSequence).toBe(applied.body.results[0].serverSequence);

      const pulled = await request(second.getHttpServer())
        .get('/v1/sync/pull?cursor=0')
        .set('Authorization', `Bearer ${TOKEN}`);
      expect(pulled.body.changes).toHaveLength(1);
      await second.close();
    });

    it('o banco de dados PostgreSQL continua saudável com migrations aplicadas', async () => {
      const app = await start();
      const postgres = app.get(PostgresService);

      expect(postgres.isOpen).toBe(true);
      expect((await postgres.appliedVersions()).length).toBeGreaterThan(0);
      await app.close();
    });
  });

  // ------------------------------------------------------------------------- rate limit

  describe('proteção por conta', () => {
    it('um cliente em laço recebe 429 em vez de martelar a VPS', async () => {
      const app = await createTestApp(
        configFor(temp.path),
        FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
      );

      let limited = false;
      for (let i = 0; i <= SYNC_RATE_LIMIT.maxRequestsPerWindow + 1; i += 1) {
        const response = await request(app.getHttpServer())
          .get('/v1/sync/pull?cursor=0')
          .set('Authorization', `Bearer ${TOKEN}`);
        if (response.status === 429) {
          expect(response.body.error.code).toBe('SYNC_RATE_LIMITED');
          limited = true;
          break;
        }
      }

      expect(limited).toBe(true);
      await app.close();
    });
  });

  // ------------------------------------------------------------------------- observabilidade

  describe('o log do sync não carrega domínio', () => {
    let written: string[];
    let restoreStdout: () => void;

    beforeEach(() => {
      written = [];
      const original = process.stdout.write.bind(process.stdout);
      process.stdout.write = ((chunk: string | Uint8Array, ...rest: unknown[]): boolean => {
        written.push(typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8'));
        return original(chunk as never, ...(rest as []));
      }) as typeof process.stdout.write;
      restoreStdout = () => {
        process.stdout.write = original;
      };
    });

    afterEach(() => {
      restoreStdout();
    });

    it('registra metadata técnica e nunca payload, nome, nota ou token', async () => {
      const app = await createTestApp(
        configFor(temp.path, { LOG_LEVEL: 'debug' }),
        FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
      );
      const syncId = uuid();
      const programSyncId = uuid();

      const pushed = await request(app.getHttpServer())
        .post('/v1/sync/push')
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'application/json')
        .send(
          pushBody([
            {
              entityType: 'BODY_MEASUREMENT',
              entitySyncId: syncId,
              payload: measurementPayload(syncId, 123.45),
            },
            {
              entityType: 'WORKOUT_PROGRAM',
              entitySyncId: programSyncId,
              payload: programPayload(programSyncId, 'Hipertrofia secreta'),
            },
          ]),
        );
      // As duas precisam ter sido aceitas: um log limpo por recusa não provaria nada.
      expect(pushed.body.results.map((r: { status: string }) => r.status)).toEqual([
        'APPLIED',
        'APPLIED',
      ]);
      await request(app.getHttpServer())
        .get('/v1/sync/pull?cursor=0')
        .set('Authorization', `Bearer ${TOKEN}`);

      const logs = written.join('\n');

      expect(logs).toContain('sync.push');
      expect(logs).toContain('sync.pull');
      expect(logs).toContain('mutationCount');

      // O que não pode estar lá.
      expect(logs).not.toContain(TOKEN);
      expect(logs).not.toContain('Bearer');
      expect(logs).not.toContain('Hipertrofia secreta');
      expect(logs).not.toContain('123.45');
      expect(logs).not.toContain('weightKg');
      // Nem o uid inteiro, nem a identidade da entidade.
      expect(logs).not.toContain(UID);
      expect(logs).not.toContain(syncId);

      await app.close();
    });
  });
});
