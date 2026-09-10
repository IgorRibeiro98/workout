import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { programPayload, pushBody, templatePayload, uuid } from './support/sync-fixtures';

const TOKEN_A = 'token-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_A = 'uid-da-conta-a';
const UID_B = 'uid-da-conta-b';

/**
 * `GET /v1/sync/pull` (T16.6).
 *
 * O cursor é posição no change log do **servidor** — nunca um timestamp, nunca o relógio do
 * aparelho. Estes testes cobrem ordem, paginação, isolamento por conta e cursor inválido.
 */
describe('Sync pull (/v1/sync/pull)', () => {
  let temp: TempDb;
  let app: INestApplication | undefined;

  beforeEach(async () => {
    temp = createTempDb();
    const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A }).accept(TOKEN_B, {
      uid: UID_B,
    });
    app = await createTestApp(configFor(temp.path), verifier);
  });

  afterEach(async () => {
    await app?.close().catch(() => undefined);
    app = undefined;
    temp?.cleanup();
  });

  const push = (body: string, token = TOKEN_A) =>
    request(app!.getHttpServer())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${token}`)
      .set('Content-Type', 'application/json')
      .send(body);

  const pull = (query = 'cursor=0', token = TOKEN_A) =>
    request(app!.getHttpServer())
      .get(`/v1/sync/pull?${query}`)
      .set('Authorization', `Bearer ${token}`);

  const createPrograms = async (count: number, token = TOKEN_A) => {
    for (let i = 0; i < count; i += 1) {
      const syncId = uuid();
      await push(
        pushBody([
          {
            entityType: 'WORKOUT_PROGRAM',
            entitySyncId: syncId,
            payload: programPayload(syncId, `Programa ${i}`),
          },
        ]),
        token,
      );
    }
  };

  it('sem token não lê o change log', async () => {
    expect((await request(app!.getHttpServer()).get('/v1/sync/pull?cursor=0')).status).toBe(401);
  });

  it('conta sem nada recebe uma página vazia, não um erro', async () => {
    const response = await pull();

    expect(response.status).toBe(200);
    expect(response.body.changes).toEqual([]);
    expect(response.body.nextCursor).toBe(0);
    expect(response.body.hasMore).toBe(false);
  });

  it('devolve as mudanças em ordem de sequência do servidor', async () => {
    await createPrograms(3);

    const response = await pull();
    const sequences = response.body.changes.map(
      (c: { serverSequence: number }) => c.serverSequence,
    );

    expect(sequences).toHaveLength(3);
    expect([...sequences].sort((a: number, b: number) => a - b)).toEqual(sequences);
    expect(response.body.nextCursor).toBe(sequences[2]);
  });

  it('a página carrega o agregado inteiro, a revision e o dispositivo de origem', async () => {
    const syncId = uuid();
    await push(
      pushBody(
        [
          {
            entityType: 'WORKOUT_TEMPLATE',
            entitySyncId: syncId,
            payload: templatePayload(syncId),
          },
        ],
        'device-origem',
      ),
    );

    const [change] = (await pull()).body.changes;

    expect(change.entityType).toBe('WORKOUT_TEMPLATE');
    expect(change.entitySyncId).toBe(syncId);
    expect(change.entitySchemaVersion).toBe(1);
    expect(change.serverRevision).toBe(1);
    expect(change.operation).toBe('UPSERT');
    expect(change.originDeviceId).toBe('device-origem');
    expect(change.payloadHash).toMatch(/^[0-9a-f]{64}$/);
    expect((change.payload as { exercises: unknown[] }).exercises).toHaveLength(1);
  });

  it('a sequência X sempre representa o estado daquela mudança, não o estado atual', async () => {
    const syncId = uuid();
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_PROGRAM',
          entitySyncId: syncId,
          payload: programPayload(syncId, 'v1'),
        },
      ]),
    );
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_PROGRAM',
          entitySyncId: syncId,
          baseRevision: 1,
          payload: programPayload(syncId, 'v2'),
        },
      ]),
    );

    const changes = (await pull()).body.changes;

    // O log é append-only: a primeira entrada continua descrevendo a v1 depois da v2.
    expect(changes).toHaveLength(2);
    expect((changes[0].payload as { name: string }).name).toBe('v1');
    expect((changes[1].payload as { name: string }).name).toBe('v2');
  });

  it('pagina e retoma sem pular nem repetir', async () => {
    await createPrograms(5);

    const first = await pull('cursor=0&limit=2');
    expect(first.body.changes).toHaveLength(2);
    expect(first.body.hasMore).toBe(true);

    const second = await pull(`cursor=${first.body.nextCursor}&limit=2`);
    expect(second.body.changes).toHaveLength(2);
    expect(second.body.hasMore).toBe(true);

    const third = await pull(`cursor=${second.body.nextCursor}&limit=2`);
    expect(third.body.changes).toHaveLength(1);
    expect(third.body.hasMore).toBe(false);

    const all = [...first.body.changes, ...second.body.changes, ...third.body.changes];
    const ids = all.map((c: { entitySyncId: string }) => c.entitySyncId);
    expect(new Set(ids).size).toBe(5);
  });

  it('o cursor no fim devolve página vazia e o mesmo cursor', async () => {
    await createPrograms(2);
    const first = await pull();

    const empty = await pull(`cursor=${first.body.nextCursor}`);
    expect(empty.body.changes).toEqual([]);
    expect(empty.body.nextCursor).toBe(first.body.nextCursor);
    expect(empty.body.hasMore).toBe(false);
  });

  it('o teto de página é do servidor, não do cliente', async () => {
    await createPrograms(3);
    // 5000 é aceito e reduzido ao teto — não vira despejo da conta inteira sem limite.
    const response = await pull('cursor=0&limit=5000');
    expect(response.status).toBe(200);
    expect(response.body.changes.length).toBeLessThanOrEqual(200);
  });

  // ---------------------------------------------------------------------- cursor inválido

  it('cursor negativo é recusado, não resetado', async () => {
    const response = await pull('cursor=-1');
    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_CURSOR');
  });

  it('cursor não numérico é recusado', async () => {
    const response = await pull('cursor=ontem');
    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_CURSOR');
  });

  it('cursor além do que o servidor emitiu é recusado', async () => {
    await createPrograms(1);
    const response = await pull('cursor=999999');
    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_CURSOR');
  });

  it('limit inválido é recusado', async () => {
    expect((await pull('cursor=0&limit=0')).status).toBe(400);
    expect((await pull('cursor=0&limit=-3')).status).toBe(400);
  });

  // ---------------------------------------------------------------------- isolamento

  it('a conta A nunca recebe mudanças da conta B', async () => {
    await createPrograms(2, TOKEN_A);
    await createPrograms(3, TOKEN_B);

    const a = await pull('cursor=0', TOKEN_A);
    const b = await pull('cursor=0', TOKEN_B);

    expect(a.body.changes).toHaveLength(2);
    expect(b.body.changes).toHaveLength(3);

    const idsA = new Set(a.body.changes.map((c: { entitySyncId: string }) => c.entitySyncId));
    for (const change of b.body.changes) {
      expect(idsA.has((change as { entitySyncId: string }).entitySyncId)).toBe(false);
    }
  });

  it('o cursor de uma conta não expõe a mudança de outra que ficou no meio', async () => {
    // A sequência é global: as mudanças de B ficam entre as de A. O filtro por dono é o que
    // impede que "cursor 1 → cursor 4" entregue o que B escreveu no meio.
    await createPrograms(1, TOKEN_A);
    await createPrograms(2, TOKEN_B);
    await createPrograms(1, TOKEN_A);

    const a = await pull('cursor=0', TOKEN_A);
    expect(a.body.changes).toHaveLength(2);
  });

  it('a conta B não vê o push de A nem pedindo o cursor de A', async () => {
    await createPrograms(1, TOKEN_A);
    const a = await pull('cursor=0', TOKEN_A);
    const sequenceOfA = a.body.changes[0].serverSequence;

    const b = await pull(`cursor=${sequenceOfA - 1}`, TOKEN_B);
    expect(b.body.changes).toEqual([]);
  });
});
