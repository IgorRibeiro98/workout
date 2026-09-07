import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import {
  customExercisePayload,
  measurementPayload,
  programPayload,
  pushBody,
  sessionPayload,
  templatePayload,
  uuid,
} from './support/sync-fixtures';

const TOKEN_A = 'token-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_A = 'uid-da-conta-a';
const UID_B = 'uid-da-conta-b';

/**
 * `POST /v1/sync/push` (T16.6).
 *
 * Tudo offline: verificador de token dublê, SQLite em arquivo temporário, sem Firebase, sem VPS e
 * sem rede. A aplicação é a real — mesmo bootstrap, mesmo versionamento, mesmo filtro de erro.
 */
describe('Sync push (/v1/sync/push)', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A }).accept(TOKEN_B, {
      uid: UID_B,
    });
    app = await createTestApp(configFor(temp.path), verifier);
  });

  afterEach(async () => {
    await app.close();
    temp.cleanup();
  });

  const push = (body: string, token = TOKEN_A) =>
    request(app.getHttpServer())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${token}`)
      .set('Content-Type', 'application/json')
      .send(body);

  // ---------------------------------------------------------------------- autenticação

  it('sem token não sincroniza', async () => {
    const response = await request(app.getHttpServer())
      .post('/v1/sync/push')
      .set('Content-Type', 'application/json')
      .send(pushBody([{ entityType: 'WORKOUT_PROGRAM', entitySyncId: uuid() }]));

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('UNAUTHENTICATED');
  });

  it('ownerUid no corpo não é obedecido: o dono sai do token', async () => {
    const syncId = uuid();
    const body = JSON.parse(
      pushBody([
        { entityType: 'WORKOUT_PROGRAM', entitySyncId: syncId, payload: programPayload(syncId) },
      ]),
    ) as Record<string, unknown>;
    // Um cliente malicioso tentando escrever na conta B enquanto autenticado como A.
    body.ownerUid = UID_B;

    const response = await push(JSON.stringify(body));

    // `.strict()` no envelope: um campo de dono não é ignorado em silêncio, é recusado.
    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_SYNC_REQUEST');
  });

  // ---------------------------------------------------------------------- criação e revision

  it('cria a entidade com revision 1 e sequência do servidor', async () => {
    const syncId = uuid();
    const response = await push(
      pushBody([
        { entityType: 'WORKOUT_TEMPLATE', entitySyncId: syncId, payload: templatePayload(syncId) },
      ]),
    );

    expect(response.status).toBe(200);
    const [result] = response.body.results;
    expect(result.status).toBe('APPLIED');
    expect(result.serverRevision).toBe(1);
    expect(result.serverSequence).toBeGreaterThan(0);
  });

  it('atualização com baseRevision correta incrementa a revision', async () => {
    const syncId = uuid();
    await push(
      pushBody([
        { entityType: 'WORKOUT_TEMPLATE', entitySyncId: syncId, payload: templatePayload(syncId) },
      ]),
    );

    const second = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          baseRevision: 1,
          payload: templatePayload(syncId, 'Treino A renomeado'),
        },
      ]),
    );

    expect(second.body.results[0].status).toBe('APPLIED');
    expect(second.body.results[0].serverRevision).toBe(2);
  });

  it('baseRevision desatualizada é STALE e não sobrescreve o remoto', async () => {
    const syncId = uuid();
    await push(
      pushBody([
        { entityType: 'WORKOUT_TEMPLATE', entitySyncId: syncId, payload: templatePayload(syncId) },
      ]),
    );
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          baseRevision: 1,
          payload: templatePayload(syncId, 'Escrito por A'),
        },
      ]),
    );

    // B ainda conhecia a revision 1.
    const stale = await push(
      pushBody(
        [
          {
            entityType: 'WORKOUT_TEMPLATE',
            entitySyncId: syncId,
            baseRevision: 1,
            payload: templatePayload(syncId, 'Escrito por B'),
          },
        ],
        'device-b',
      ),
    );

    expect(stale.body.results[0].status).toBe('STALE');
    expect(stale.body.results[0].currentRevision).toBe(2);

    // Nada de last-write-wins: o conteúdo de A continua sendo o do servidor.
    const pull = await request(app.getHttpServer())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN_A}`);
    const last = pull.body.changes[pull.body.changes.length - 1];
    expect(last.serverRevision).toBe(2);
    expect((last.payload as { name: string }).name).toBe('Escrito por A');
  });

  it('baseRevision adiante do servidor é INVALID, não uma criação', async () => {
    const syncId = uuid();
    await push(
      pushBody([
        { entityType: 'WORKOUT_TEMPLATE', entitySyncId: syncId, payload: templatePayload(syncId) },
      ]),
    );

    const ahead = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          baseRevision: 9,
          payload: templatePayload(syncId, 'Do futuro'),
        },
      ]),
    );

    expect(ahead.body.results[0].status).toBe('INVALID');
    expect(ahead.body.results[0].reason).toBe('BASE_REVISION_AHEAD');
  });

  it('criação sobre entidade existente com conteúdo diferente é STALE, não segunda entidade', async () => {
    const syncId = uuid();
    await push(
      pushBody([
        { entityType: 'WORKOUT_PROGRAM', entitySyncId: syncId, payload: programPayload(syncId) },
      ]),
    );

    const again = await push(
      pushBody([
        {
          entityType: 'WORKOUT_PROGRAM',
          entitySyncId: syncId,
          baseRevision: null,
          payload: programPayload(syncId, 'Outro nome'),
        },
      ]),
    );

    expect(again.body.results[0].status).toBe('STALE');
    expect(again.body.results[0].currentRevision).toBe(1);
  });

  // ---------------------------------------------------------------------- idempotência

  it('o mesmo clientMutationId reenviado devolve o resultado original', async () => {
    const syncId = uuid();
    const clientMutationId = uuid();
    const body = pushBody([
      {
        clientMutationId,
        entityType: 'BODY_MEASUREMENT',
        entitySyncId: syncId,
        payload: measurementPayload(syncId),
      },
    ]);

    const first = await push(body);
    const retry = await push(body);

    expect(first.body.results[0].status).toBe('APPLIED');
    expect(retry.body.results[0].status).toBe('ALREADY_APPLIED');
    expect(retry.body.results[0].serverRevision).toBe(first.body.results[0].serverRevision);
    expect(retry.body.results[0].serverSequence).toBe(first.body.results[0].serverSequence);
  });

  it('o reenvio não cria uma segunda medida nem uma segunda mudança', async () => {
    const syncId = uuid();
    const clientMutationId = uuid();
    const body = pushBody([
      {
        clientMutationId,
        entityType: 'BODY_MEASUREMENT',
        entitySyncId: syncId,
        payload: measurementPayload(syncId),
      },
    ]);

    await push(body);
    await push(body);
    await push(body);

    const pull = await request(app.getHttpServer())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN_A}`);
    expect(pull.body.changes).toHaveLength(1);
  });

  it('o mesmo clientMutationId com conteúdo diferente é conflito de idempotência', async () => {
    const syncId = uuid();
    const clientMutationId = uuid();

    await push(
      pushBody([
        {
          clientMutationId,
          entityType: 'BODY_MEASUREMENT',
          entitySyncId: syncId,
          payload: measurementPayload(syncId, 80.5),
        },
      ]),
    );
    const conflicting = await push(
      pushBody([
        {
          clientMutationId,
          entityType: 'BODY_MEASUREMENT',
          entitySyncId: syncId,
          payload: measurementPayload(syncId, 99.9),
        },
      ]),
    );

    expect(conflicting.body.results[0].status).toBe('IDEMPOTENCY_CONFLICT');

    const pull = await request(app.getHttpServer())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN_A}`);
    expect(pull.body.changes).toHaveLength(1);
    expect((pull.body.changes[0].payload as { weightKg: number }).weightKg).toBe(80.5);
  });

  it('conteúdo idêntico com outra tentativa converge sem gastar revision', async () => {
    const syncId = uuid();
    const payload = customExercisePayload(syncId);

    const first = await push(
      pushBody([{ entityType: 'CUSTOM_EXERCISE', entitySyncId: syncId, payload }]),
    );
    const same = await push(
      pushBody([{ entityType: 'CUSTOM_EXERCISE', entitySyncId: syncId, baseRevision: 1, payload }]),
    );

    expect(first.body.results[0].serverRevision).toBe(1);
    expect(same.body.results[0].status).toBe('ALREADY_APPLIED');
    expect(same.body.results[0].serverRevision).toBe(1);
  });

  // ---------------------------------------------------------------------- histórico imutável

  it('sessão concluída nasce em revision 1 e o mesmo conteúdo é idempotente', async () => {
    const syncId = uuid();
    const payload = sessionPayload(syncId);

    const first = await push(
      pushBody([{ entityType: 'WORKOUT_SESSION', entitySyncId: syncId, payload }]),
    );
    const again = await push(
      pushBody([{ entityType: 'WORKOUT_SESSION', entitySyncId: syncId, payload }]),
    );

    expect(first.body.results[0].serverRevision).toBe(1);
    expect(again.body.results[0].status).toBe('ALREADY_APPLIED');
    expect(again.body.results[0].serverRevision).toBe(1);
  });

  it('sessão concluída com conteúdo divergente é conflito de integridade, nunca revision++', async () => {
    const syncId = uuid();
    await push(
      pushBody([
        { entityType: 'WORKOUT_SESSION', entitySyncId: syncId, payload: sessionPayload(syncId) },
      ]),
    );

    const rewritten = await push(
      pushBody([
        {
          entityType: 'WORKOUT_SESSION',
          entitySyncId: syncId,
          baseRevision: 1,
          payload: sessionPayload(syncId, { notes: 'história reescrita' }),
        },
      ]),
    );

    expect(rewritten.body.results[0].status).toBe('IMMUTABLE_HISTORY_CONFLICT');
    expect(rewritten.body.results[0].currentRevision).toBe(1);

    const pull = await request(app.getHttpServer())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN_A}`);
    expect(pull.body.changes).toHaveLength(1);
    expect((pull.body.changes[0].payload as { notes: string | null }).notes).toBeNull();
  });

  it('sessão não concluída é recusada pelo contrato', async () => {
    const syncId = uuid();
    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_SESSION',
          entitySyncId: syncId,
          payload: sessionPayload(syncId, { status: 'IN_PROGRESS' }),
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('INVALID');
  });

  // ---------------------------------------------------------------------- validação

  it('entityType desconhecido é recusado sem virar linha', async () => {
    const response = await push(
      pushBody([
        { entityType: 'TABELA_INVENTADA', entitySyncId: uuid(), payload: { qualquer: 'coisa' } },
      ]),
    );

    expect(response.body.results[0].status).toBe('UNSUPPORTED');
    expect(response.body.results[0].reason).toBe('UNKNOWN_ENTITY_TYPE');
  });

  it('agregado que só o backup cobre ainda não sincroniza', async () => {
    // `EXERCISE_OVERRIDE`, `WEEKLY_GOAL` e `USER_PREFERENCES` entram no snapshot completo e não
    // têm mutação incremental na T16.6. Recusar explicitamente é melhor do que aceitar pela
    // metade um agregado que o outro aparelho não saberia aplicar.
    const response = await push(
      pushBody([
        {
          entityType: 'WEEKLY_GOAL',
          entitySyncId: 'week:19000',
          payload: { effectiveFromWeekStartEpochDay: 19000, goal: 4, createdAt: 1 },
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('UNSUPPORTED');
  });

  it('entitySchemaVersion futura não é aplicada', async () => {
    const syncId = uuid();
    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          entitySchemaVersion: 99,
          payload: templatePayload(syncId),
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('UNSUPPORTED');
    expect(response.body.results[0].reason).toBe('UNSUPPORTED_ENTITY_SCHEMA_VERSION');
  });

  it('identidade que não corresponde ao payload é recusada sem aproximação', async () => {
    const declared = uuid();
    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: declared,
          payload: templatePayload(uuid()),
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('INVALID');
    expect(response.body.results[0].reason).toBe('IDENTITY_MISMATCH');
  });

  it('campo desconhecido no payload é recusado, não guardado', async () => {
    const syncId = uuid();
    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_PROGRAM',
          entitySyncId: syncId,
          payload: { ...programPayload(syncId), campoDoFuturo: true },
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('INVALID');
  });

  // ---------------------------------------------------------------------- delete

  it('DELETE de um agregado que o domínio não apaga é recusado, e nada vira tombstone', async () => {
    // O Spark não tem caminho de exclusão de check-in — nenhuma tela, nenhum repositório. Um
    // `DELETE` deste tipo só pode vir de um cliente defeituoso, e criar tombstone para ele
    // esconderia o dado de todos os aparelhos sem que ninguém tenha pedido isso.
    const syncId = uuid();
    const response = await push(
      pushBody([{ entityType: 'CHECK_IN', entitySyncId: syncId, operation: 'DELETE' }]),
    );

    expect(response.body.results[0].status).toBe('UNSUPPORTED');
    expect(response.body.results[0].reason).toBe('DELETE_NOT_ALLOWED');

    const pull = await request(app.getHttpServer())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN_A}`);
    expect(pull.body.changes).toHaveLength(0);
  });

  // ---------------------------------------------------------------------- lote

  it('uma mutação stale não impede as outras do mesmo lote', async () => {
    const template = uuid();
    const measurement = uuid();
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: template,
          payload: templatePayload(template),
        },
      ]),
    );
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: template,
          baseRevision: 1,
          payload: templatePayload(template, 'Avançado'),
        },
      ]),
    );

    const mixed = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: template,
          baseRevision: 1,
          payload: templatePayload(template, 'Concorrente'),
        },
        {
          entityType: 'BODY_MEASUREMENT',
          entitySyncId: measurement,
          payload: measurementPayload(measurement),
        },
      ]),
    );

    expect(mixed.body.results[0].status).toBe('STALE');
    expect(mixed.body.results[1].status).toBe('APPLIED');
  });

  it('a ordem do lote é preservada para o mesmo agregado', async () => {
    const syncId = uuid();
    const response = await push(
      pushBody([
        { entityType: 'WORKOUT_PROGRAM', entitySyncId: syncId, payload: programPayload(syncId) },
        {
          entityType: 'WORKOUT_PROGRAM',
          entitySyncId: syncId,
          baseRevision: 1,
          payload: programPayload(syncId, 'Programa B'),
        },
      ]),
    );

    expect(response.body.results.map((r: { status: string }) => r.status)).toEqual([
      'APPLIED',
      'APPLIED',
    ]);
    expect(response.body.results[0].serverRevision).toBe(1);
    expect(response.body.results[1].serverRevision).toBe(2);
    expect(response.body.results[1].serverSequence).toBeGreaterThan(
      response.body.results[0].serverSequence,
    );
  });

  // ---------------------------------------------------------------------- limites

  it('push sem mutações é recusado', async () => {
    const response = await push(pushBody([]));
    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_SYNC_REQUEST');
  });

  it('lote acima do teto é recusado', async () => {
    const mutations = Array.from({ length: 51 }, () => {
      const syncId = uuid();
      return {
        entityType: 'WORKOUT_PROGRAM',
        entitySyncId: syncId,
        payload: programPayload(syncId),
      };
    });

    const response = await push(pushBody(mutations));
    expect(response.status).toBe(413);
    expect(response.body.error.code).toBe('SYNC_PAYLOAD_TOO_LARGE');
  });

  it('clientMutationId repetido no mesmo corpo é recusado', async () => {
    const clientMutationId = uuid();
    const a = uuid();
    const b = uuid();
    const response = await push(
      pushBody([
        {
          clientMutationId,
          entityType: 'WORKOUT_PROGRAM',
          entitySyncId: a,
          payload: programPayload(a),
        },
        {
          clientMutationId,
          entityType: 'WORKOUT_PROGRAM',
          entitySyncId: b,
          payload: programPayload(b),
        },
      ]),
    );

    expect(response.status).toBe(400);
  });

  it('corpo que não é JSON é recusado', async () => {
    const response = await request(app.getHttpServer())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${TOKEN_A}`)
      .set('Content-Type', 'application/json')
      .send('isto não é json');

    expect(response.status).toBe(400);
  });
});
