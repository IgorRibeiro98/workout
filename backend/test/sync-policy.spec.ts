import { INestApplication } from '@nestjs/common';
import Database from 'better-sqlite3';
import request from 'supertest';
import { SYNC_ENTITY_TYPES } from '../src/modules/sync/sync.contract';
import { SyncEntityPolicyRegistry } from '../src/modules/sync/sync.policy';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { pushBody, templatePayload, uuid } from './support/sync-fixtures';

const TOKEN_A = 'token-da-conta-a';
const UID_A = 'uid-da-conta-a';

/**
 * A política por agregado e o cursor expirado (T16.7).
 *
 * Estes testes olham para as duas decisões que, se erradas, custam dado em silêncio: escolher um
 * vencedor sem política declarada, e recomeçar um cursor do zero fingindo que nada se perdeu.
 */
describe('Política de sync por agregado (T16.7)', () => {
  it('todo agregado sincronizado declara uma política — não existe padrão', () => {
    for (const entityType of SYNC_ENTITY_TYPES) {
      const policy = SyncEntityPolicyRegistry.policyFor(entityType);
      expect(policy).toBeDefined();
      expect(['MUTABLE_SNAPSHOT', 'IMMUTABLE_HISTORY']).toContain(policy.mutability);
      expect(typeof policy.deleteAllowed).toBe('boolean');
    }
  });

  it('last-write-wins não é a estratégia de nenhum agregado', () => {
    // Ele existe como valor declarável e ninguém o usa. O dia em que alguém o usar precisa ser um
    // dia em que essa linha foi escrita de propósito, com o motivo de domínio junto — e não o
    // efeito colateral de um `default` que ninguém leu.
    const usando = SYNC_ENTITY_TYPES.filter(
      (type) =>
        SyncEntityPolicyRegistry.policyFor(type).conflictStrategy === 'LAST_WRITE_WINS_ALLOWED',
    );

    expect(usando).toEqual([]);
  });

  it('histórico concluído é imutável e ainda assim excluível', () => {
    // As duas coisas juntas são o ponto: editar reescreveria um fato, apagar remove o registro.
    // Tratar as duas como "mudar a sessão" faria o Spark ou perder histórico ou proibir o usuário
    // de apagar o próprio.
    const sessao = SyncEntityPolicyRegistry.policyFor('WORKOUT_SESSION');

    expect(sessao.mutability).toBe('IMMUTABLE_HISTORY');
    expect(sessao.conflictStrategy).toBe('IMMUTABLE_CONFLICT');
    expect(sessao.deleteAllowed).toBe(true);
  });

  it('check-in não aceita exclusão remota, porque o domínio não a produz', () => {
    expect(SyncEntityPolicyRegistry.isDeleteAllowed('CHECK_IN')).toBe(false);
  });

  it('todo agregado mutável resolve conflito por escolha do usuário', () => {
    const mutaveis = SYNC_ENTITY_TYPES.filter(
      (type) => SyncEntityPolicyRegistry.policyFor(type).mutability === 'MUTABLE_SNAPSHOT',
    );

    expect(mutaveis.length).toBeGreaterThan(0);
    for (const type of mutaveis) {
      expect(SyncEntityPolicyRegistry.policyFor(type).conflictStrategy).toBe('USER_CHOICE');
    }
  });
});

describe('Cursor expirado (T16.7)', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path),
      FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A }),
    );
  });

  afterEach(async () => {
    await app.close();
    temp.cleanup();
  });

  const push = (body: string) =>
    request(app.getHttpServer())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${TOKEN_A}`)
      .set('Content-Type', 'application/json')
      .send(body);

  it('o cursor exatamente na borda continua válido — nada foi pulado', async () => {
    const primeiro = uuid();
    const segundo = uuid();
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: primeiro,
          payload: templatePayload(primeiro),
        },
      ]),
    );
    await push(
      pushBody([
        {
          entityType: 'WORKOUT_TEMPLATE',
          entitySyncId: segundo,
          payload: templatePayload(segundo),
        },
      ]),
    );

    // Simula o único caso que produz isso hoje: o banco do servidor voltou de uma cópia em que a
    // primeira mudança desta conta já não existia. Nada no Spark compacta o change log.
    const db = new Database(temp.path);
    db.prepare('DELETE FROM sync_changes WHERE server_sequence <= 1').run();
    db.close();

    // O aparelho parou **em** 1: a próxima mudança que ele espera é a 2, e ela existe. Recusar
    // aqui obrigaria a um rebaseline que não é necessário.
    const response = await request(app.getHttpServer())
      .get('/v1/sync/pull?cursor=1')
      .set('Authorization', `Bearer ${TOKEN_A}`);

    expect(response.status).toBe(200);
    expect(response.body.changes).toHaveLength(1);
    expect(response.body.changes[0].entitySyncId).toBe(segundo);
  });

  it('CURSOR_EXPIRED em vez de pular mudanças em silêncio', async () => {
    const ids = [uuid(), uuid(), uuid()];
    for (const id of ids) {
      await push(
        pushBody([
          { entityType: 'WORKOUT_TEMPLATE', entitySyncId: id, payload: templatePayload(id) },
        ]),
      );
    }

    // O aparelho parou no cursor 1. O servidor perde as duas primeiras mudanças.
    const db = new Database(temp.path);
    db.prepare('DELETE FROM sync_changes WHERE server_sequence <= 2').run();
    db.close();

    const response = await request(app.getHttpServer())
      .get('/v1/sync/pull?cursor=1')
      .set('Authorization', `Bearer ${TOKEN_A}`);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('CURSOR_EXPIRED');
  });
});
