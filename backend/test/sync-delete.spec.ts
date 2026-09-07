import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import {
  measurementPayload,
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
 * Exclusão, tombstone e prevenção de ressurreição (T16.7).
 *
 * O defeito que estes testes existem para tornar impossível é sempre o mesmo:
 *
 * ```text
 * A deleta X  →  B (offline, com a cópia antiga) volta  →  B envia X  →  X ressuscita
 * ```
 *
 * Tudo offline: verificador de token dublê, SQLite em arquivo temporário, sem Firebase, sem VPS e
 * sem rede. A aplicação é a real.
 */
describe('Sync delete e tombstones (T16.7)', () => {
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

  const pull = (cursor = 0, token = TOKEN_A) =>
    request(app.getHttpServer())
      .get(`/v1/sync/pull?cursor=${cursor}`)
      .set('Authorization', `Bearer ${token}`);

  /** Cria um treino e devolve a `revision` com que ele nasceu. */
  const createTemplate = async (syncId: string, name = 'Treino A') => {
    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          payload: templatePayload(syncId, name),
        },
      ]),
    );
    expect(response.body.results[0].status).toBe('APPLIED');
    return response.body.results[0].serverRevision as number;
  };

  // ---------------------------------------------------------------------- tombstone

  it('DELETE com baseRevision correta cria tombstone e gasta uma revision', async () => {
    const syncId = uuid();
    const revision = await createTemplate(syncId);
    expect(revision).toBe(1);

    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: revision,
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('APPLIED');
    // Exclusão é uma mudança como outra qualquer: ela avança a revision, e é isso que permite a um
    // aparelho saber que o que ele tem ficou para trás.
    expect(response.body.results[0].serverRevision).toBe(revision + 1);
  });

  it('a exclusão entra no change log como DELETE, sem payload', async () => {
    const syncId = uuid();
    const revision = await createTemplate(syncId);
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: revision,
        },
      ]),
    );

    const response = await pull(0);
    expect(response.body.changes).toHaveLength(2);

    const tombstone = response.body.changes[1];
    expect(tombstone.operation).toBe('DELETE');
    expect(tombstone.entitySyncId).toBe(syncId);
    expect(tombstone.serverRevision).toBe(2);
    // Um tombstone não afirma conteúdo. Devolver o que foi apagado só duplicaria dado pessoal.
    expect(tombstone.payload).toBeNull();

    // E a mudança anterior continua no log, intacta: quem ainda não a leu recebe as duas em ordem.
    expect(response.body.changes[0].operation).toBe('UPSERT');
  });

  it('reenviar a mesma exclusão é idempotente — nenhuma revision nova, nenhuma mudança nova', async () => {
    const syncId = uuid();
    const revision = await createTemplate(syncId);
    const mutationId = uuid();
    const body = pushBody([
      {
        clientMutationId: mutationId,
        entityType: 'WORKOUT_TEMPLATE',
        entitySyncId: syncId,
        operation: 'DELETE',
        baseRevision: revision,
      },
    ]);

    const first = await push(body);
    const second = await push(body);

    expect(first.body.results[0].status).toBe('APPLIED');
    expect(second.body.results[0].status).toBe('ALREADY_APPLIED');
    expect(second.body.results[0].serverRevision).toBe(first.body.results[0].serverRevision);

    const changes = await pull(0);
    expect(changes.body.changes).toHaveLength(2);
  });

  it('uma segunda exclusão, com clientMutationId novo, também é idempotente', async () => {
    // Não é reenvio: é outro aparelho tentando apagar o que já está apagado. O resultado precisa
    // ser o mesmo — reconhecer, e não gastar uma revision descrevendo uma mudança que não houve.
    const syncId = uuid();
    const revision = await createTemplate(syncId);
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: revision,
        },
      ]),
    );

    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: revision + 1,
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('ALREADY_APPLIED');
    expect((await pull(0)).body.changes).toHaveLength(2);
  });

  it('excluir uma identidade que o servidor nunca viu também cria tombstone', async () => {
    // Dois aparelhos podem ter o mesmo `syncId` sem que o servidor o conheça: dataset restaurado
    // do mesmo backup. Sem tombstone aqui, o segundo aparelho criaria a entidade depois e a
    // exclusão do primeiro teria sido desfeita por ninguém.
    const syncId = uuid();

    const deleted = await push(
      pushBody([{ entityType: 'WORKOUT_TEMPLATE', entitySyncId: syncId, operation: 'DELETE' }]),
    );
    expect(deleted.body.results[0].status).toBe('APPLIED');
    expect(deleted.body.results[0].serverRevision).toBe(1);

    const resurrect = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          payload: templatePayload(syncId),
        },
      ]),
    );
    expect(resurrect.body.results[0].status).toBe('REMOTE_DELETED');
  });

  // ---------------------------------------------------------------------- ressurreição

  it('UPSERT contra tombstone nunca recria a entidade', async () => {
    const syncId = uuid();
    const revision = await createTemplate(syncId);
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: revision,
        },
      ]),
    );

    // O aparelho offline volta com a cópia antiga e a `baseRevision` que ele conhecia.
    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          baseRevision: revision,
          payload: templatePayload(syncId, 'Treino que voltou'),
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('REMOTE_DELETED');
    expect(response.body.results[0].reason).toBe('ENTITY_DELETED');
    expect(response.body.results[0].currentRevision).toBe(revision + 1);

    // Nada foi anexado ao log, e a última mudança continua sendo a exclusão.
    const changes = await pull(0);
    expect(changes.body.changes).toHaveLength(2);
    expect(changes.body.changes[1].operation).toBe('DELETE');
  });

  it('UPSERT com a revision do próprio tombstone também é recusado', async () => {
    // O aparelho **sabe** que foi apagado e mandou assim mesmo. Recriar aqui seria o
    // `force=true` que este servidor não tem: recriar é decisão do usuário e nasce com syncId novo.
    const syncId = uuid();
    const revision = await createTemplate(syncId);
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: revision,
        },
      ]),
    );

    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          baseRevision: revision + 1,
          payload: templatePayload(syncId, 'Insistindo'),
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('REMOTE_DELETED');
  });

  it('recriar com syncId novo funciona normalmente', async () => {
    const syncId = uuid();
    const revision = await createTemplate(syncId);
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: revision,
        },
      ]),
    );

    const novo = uuid();
    const response = await push(
      pushBody([
        { entityType: 'WORKOUT_TEMPLATE', entitySyncId: novo, payload: templatePayload(novo) },
      ]),
    );

    expect(response.body.results[0].status).toBe('APPLIED');
    expect(response.body.results[0].serverRevision).toBe(1);
  });

  // ---------------------------------------------------------------------- conflito

  it('exclusão stale não apaga a alteração mais nova de outro aparelho', async () => {
    const syncId = uuid();
    const revision = await createTemplate(syncId);

    // B atualiza primeiro e define a revision seguinte.
    const updated = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          baseRevision: revision,
          payload: templatePayload(syncId, 'Treino renomeado'),
        },
      ]),
    );
    expect(updated.body.results[0].serverRevision).toBe(2);

    // A chega com a exclusão construída sobre a revision antiga.
    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: revision,
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('STALE');
    expect(response.body.results[0].currentRevision).toBe(2);

    // E a entidade continua viva: nenhuma mudança nova, nenhum tombstone.
    const changes = await pull(0);
    expect(changes.body.changes).toHaveLength(2);
    expect(changes.body.changes.every((c: { operation: string }) => c.operation === 'UPSERT')).toBe(
      true,
    );
  });

  it('exclusão com baseRevision adiante do servidor é INVALID, não tombstone', async () => {
    const syncId = uuid();
    await createTemplate(syncId);

    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: 99,
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('INVALID');
    expect(response.body.results[0].reason).toBe('BASE_REVISION_AHEAD');
  });

  // ---------------------------------------------------------------------- política

  it('histórico concluído pode ser excluído — apagar não é reescrever', async () => {
    const syncId = uuid();
    const created = await push(
      pushBody([
        { entityType: 'WORKOUT_SESSION', entitySyncId: syncId, payload: sessionPayload(syncId) },
      ]),
    );
    expect(created.body.results[0].status).toBe('APPLIED');

    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_SESSION',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: 1,
        },
      ]),
    );

    expect(response.body.results[0].status).toBe('APPLIED');
    expect(response.body.results[0].serverRevision).toBe(2);
  });

  it('uma sessão concluída excluída não pode voltar como UPSERT', async () => {
    const syncId = uuid();
    await push(
      pushBody([
        { entityType: 'WORKOUT_SESSION', entitySyncId: syncId, payload: sessionPayload(syncId) },
      ]),
    );
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_SESSION',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: 1,
        },
      ]),
    );

    const response = await push(
      pushBody([
        { entityType: 'WORKOUT_SESSION', entitySyncId: syncId, payload: sessionPayload(syncId) },
      ]),
    );

    expect(response.body.results[0].status).toBe('REMOTE_DELETED');
  });

  it('medidas distintas coexistem — criar duas não é conflito', async () => {
    const primeira = uuid();
    const segunda = uuid();

    const response = await push(
      pushBody([
        {
          entityType: 'BODY_MEASUREMENT',
          entitySyncId: primeira,
          payload: measurementPayload(primeira, 80.5),
        },
        {
          entityType: 'BODY_MEASUREMENT',
          entitySyncId: segunda,
          payload: measurementPayload(segunda, 81),
        },
      ]),
    );

    expect(response.body.results.map((r: { status: string }) => r.status)).toEqual([
      'APPLIED',
      'APPLIED',
    ]);
  });

  // ---------------------------------------------------------------------- ownership

  it('o tombstone de uma conta não aparece para outra', async () => {
    const syncId = uuid();
    const revision = await createTemplate(syncId);
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          operation: 'DELETE',
          baseRevision: revision,
        },
      ]),
    );

    // B nunca vê a exclusão de A...
    const changesB = await pull(0, TOKEN_B);
    expect(changesB.body.changes).toHaveLength(0);

    // ...e o mesmo `syncId` na conta B é uma entidade nova, não algo apagado.
    const response = await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: syncId,
          payload: templatePayload(syncId),
        },
      ]),
      TOKEN_B,
    );
    expect(response.body.results[0].status).toBe('APPLIED');
  });

  // ---------------------------------------------------------------------- offline longo

  it('um cursor antigo recebe todas as mudanças posteriores, exclusão inclusive', async () => {
    const templateA = uuid();
    const templateB = uuid();
    const revisionA = await createTemplate(templateA, 'Treino A');

    // B para de sincronizar aqui.
    const cursorAntigo = (await pull(0)).body.nextCursor as number;

    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: templateA,
          baseRevision: revisionA,
          payload: templatePayload(templateA, 'Treino A editado'),
        },
      ]),
    );
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: templateA,
          operation: 'DELETE',
          baseRevision: revisionA + 1,
        },
      ]),
    );
    await createTemplate(templateB, 'Treino B');

    const response = await pull(cursorAntigo);
    const operations = response.body.changes.map(
      (change: { entitySyncId: string; operation: string }) =>
        `${change.entitySyncId === templateA ? 'A' : 'B'}:${change.operation}`,
    );

    expect(operations).toEqual(['A:UPSERT', 'A:DELETE', 'B:UPSERT']);
  });
});
