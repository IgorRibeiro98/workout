import { INestApplication } from '@nestjs/common';
import Database from 'better-sqlite3';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { pushBody, templatePayload, uuid } from './support/sync-fixtures';

const TOKEN_A = 'token-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_A = 'uid-da-conta-a';
const UID_B = 'uid-da-conta-b';

/**
 * `GET /v1/sync/entities/{entityType}/{entitySyncId}` (T16.7.1).
 *
 * A leitura que existe para uma pergunta só: *a cópia remota que o aparelho guardou ainda é a que
 * o servidor tem?* Sem ela, "usar a versão da nuvem" aplicaria localmente uma revision que o
 * servidor já sabe estar superada.
 *
 * O que estes testes protegem é o que a rota **não** pode fazer: revelar dado de outra conta,
 * gastar revision, anexar mudança ao change log, escrever no ledger ou responder sem token.
 */
describe('Sync entity state (/v1/sync/entities)', () => {
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

  const server = () => app!.getHttpServer();

  const push = (body: string, token = TOKEN_A) =>
    request(server())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${token}`)
      .set('Content-Type', 'application/json')
      .send(body);

  const state = (entityType: string, entitySyncId: string, token = TOKEN_A) =>
    request(server())
      .get(`/v1/sync/entities/${entityType}/${entitySyncId}`)
      .set('Authorization', `Bearer ${token}`);

  const createTemplate = async (name = 'Treino A', token = TOKEN_A) => {
    const syncId = uuid();
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          payload: templatePayload(syncId, name),
        },
      ]),
      token,
    );
    return syncId;
  };

  /** As três tabelas que uma leitura jamais pode tocar. */
  const ledgerCounts = () => {
    const db = new Database(temp.path, { readonly: true });
    try {
      const count = (table: string) =>
        (db.prepare(`SELECT COUNT(*) AS total FROM ${table}`).get() as { total: number }).total;
      return {
        entities: count('sync_entities'),
        changes: count('sync_changes'),
        mutations: count('sync_mutations'),
        revision: (
          db.prepare('SELECT MAX(server_revision) AS max FROM sync_entities').get() as {
            max: number | null;
          }
        ).max,
      };
    } finally {
      db.close();
    }
  };

  // ------------------------------------------------------------------ autenticação

  it('sem token não responde nada', async () => {
    const syncId = await createTemplate();

    const response = await request(server()).get(`/v1/sync/entities/WORKOUT_TEMPLATE/${syncId}`);

    expect(response.status).toBe(401);
  });

  it('com token inválido não responde nada', async () => {
    const syncId = await createTemplate();

    expect((await state('WORKOUT_TEMPLATE', syncId, 'token-inventado')).status).toBe(401);
  });

  // ------------------------------------------------------------------ entidade ativa

  it('devolve o estado atual do agregado, com revision, hash e payload', async () => {
    const syncId = await createTemplate('Versão do A');

    const response = await state('WORKOUT_TEMPLATE', syncId);

    expect(response.status).toBe(200);
    expect(response.body.entityType).toBe('WORKOUT_TEMPLATE');
    expect(response.body.entitySyncId).toBe(syncId);
    expect(response.body.entitySchemaVersion).toBe(1);
    expect(response.body.serverRevision).toBe(1);
    expect(response.body.deleted).toBe(false);
    expect(response.body.payloadHash).toMatch(/^[0-9a-f]{64}$/);
    expect((response.body.payload as { name: string }).name).toBe('Versão do A');
  });

  it('a revision devolvida acompanha o servidor, não o que o cliente viu antes', async () => {
    const syncId = await createTemplate('Versão 1');
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          baseRevision: 1,
          payload: templatePayload(syncId, 'Versão 2'),
        },
      ]),
    );

    const response = await state('WORKOUT_TEMPLATE', syncId);

    expect(response.body.serverRevision).toBe(2);
    expect((response.body.payload as { name: string }).name).toBe('Versão 2');
  });

  it('devolve a identidade autenticada, derivada do token', async () => {
    const syncId = await createTemplate();

    // É o que permite ao aparelho provar, depois da resposta, que ela foi autenticada pela mesma
    // conta dona do dataset local. Ele vem do token verificado — nunca de query, header ou corpo.
    expect((await state('WORKOUT_TEMPLATE', syncId)).body.ownerUid).toBe(UID_A);
  });

  // ------------------------------------------------------------------ tombstone

  it('um tombstone responde deleted, sem hash e sem payload', async () => {
    const syncId = await createTemplate();
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: 1,
        },
      ]),
    );

    const response = await state('WORKOUT_TEMPLATE', syncId);

    expect(response.status).toBe(200);
    expect(response.body.deleted).toBe(true);
    expect(response.body.serverRevision).toBe(2);
    // Uma exclusão não afirma conteúdo: devolver o que foi apagado só duplicaria dado pessoal.
    expect(response.body.payloadHash).toBeNull();
    expect(response.body.payload).toBeNull();
  });

  // ------------------------------------------------------------------ isolamento por conta

  it('a conta B não enxerga o agregado da conta A', async () => {
    const syncId = await createTemplate('Segredo do A');

    const response = await state('WORKOUT_TEMPLATE', syncId, TOKEN_B);

    // 404, e não 403: distinguir "não é sua" de "não existe" transformaria a rota num oráculo de
    // existência do dado alheio.
    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('SYNC_ENTITY_NOT_FOUND');
    expect(JSON.stringify(response.body)).not.toContain('Segredo do A');
  });

  it('cada conta lê a própria entidade quando as duas usam o mesmo syncId', async () => {
    // Dois aparelhos que restauraram o mesmo backup em contas diferentes: identidade igual, donos
    // diferentes. Nenhum vê o conteúdo do outro.
    const syncId = uuid();
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          payload: templatePayload(syncId, 'Do A'),
        },
      ]),
      TOKEN_A,
    );
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          payload: templatePayload(syncId, 'Do B'),
        },
      ]),
      TOKEN_B,
    );

    expect((await state('WORKOUT_TEMPLATE', syncId, TOKEN_A)).body.payload.name).toBe('Do A');
    expect((await state('WORKOUT_TEMPLATE', syncId, TOKEN_B)).body.payload.name).toBe('Do B');
  });

  it('nenhum uid do cliente influencia a resposta', async () => {
    const syncId = await createTemplate('Segredo do A');

    const response = await request(server())
      .get(`/v1/sync/entities/WORKOUT_TEMPLATE/${syncId}?ownerUid=${UID_A}&uid=${UID_A}`)
      .set('Authorization', `Bearer ${TOKEN_B}`);

    expect(response.status).toBe(404);
  });

  // ------------------------------------------------------------------ identidade desconhecida

  it('identidade que nunca existiu é 404', async () => {
    expect((await state('WORKOUT_TEMPLATE', uuid())).status).toBe(404);
  });

  it('entityType fora do registry é recusado pelo contrato', async () => {
    const response = await state('EXERCISE_OVERRIDE', uuid());

    // A lista de agregados é contrato público: dizer "não conheço este tipo" não revela dado
    // nenhum. O que precisa ser indistinguível é a **identidade**, e essa continua sendo 404.
    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_SYNC_REQUEST');
  });

  it('entitySyncId acima do teto é recusado', async () => {
    expect((await state('WORKOUT_TEMPLATE', 'x'.repeat(201))).status).toBe(400);
  });

  // ------------------------------------------------------------------ estritamente read-only

  it('consultar não gasta revision, não cria mudança e não escreve no ledger', async () => {
    const syncId = await createTemplate();
    const antes = ledgerCounts();

    await state('WORKOUT_TEMPLATE', syncId);
    await state('WORKOUT_TEMPLATE', syncId);
    await state('WORKOUT_TEMPLATE', syncId);

    expect(ledgerCounts()).toEqual(antes);
  });

  it('consultar um tombstone não o ressuscita nem o altera', async () => {
    const syncId = await createTemplate();
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: 1,
        },
      ]),
    );
    const antes = ledgerCounts();

    await state('WORKOUT_TEMPLATE', syncId);

    expect(ledgerCounts()).toEqual(antes);
    expect((await state('WORKOUT_TEMPLATE', syncId)).body.deleted).toBe(true);
  });

  it('consultar não move o cursor de ninguém', async () => {
    const syncId = await createTemplate();
    const antes = await request(server())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN_A}`);

    await state('WORKOUT_TEMPLATE', syncId);

    const depois = await request(server())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN_A}`);

    expect(depois.body.changes).toEqual(antes.body.changes);
    expect(depois.body.nextCursor).toBe(antes.body.nextCursor);
  });
});
