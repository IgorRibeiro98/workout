import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import BetterSqlite3 from 'better-sqlite3';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { pushBody, sessionPayload, uuid } from './support/sync-fixtures';
import {
  CHECKIN_WINDOW_MS,
  FEED_MAX_LIMIT,
  FEED_WINDOW_MS,
} from '../src/modules/social/workout-checkin.contract';

/**
 * T17.8 — Check-ins de treino + Feed Social.
 *
 * As sessões destes testes entram pelo **caminho real** (`POST /v1/sync/push`), e não por um
 * `INSERT` em `sync_entities`. É deliberado: o que a T17.8 afirma é que só uma sessão canônica
 * sincronizada vira check-in, e um teste que semeasse a tabela à mão provaria a afirmação contra
 * uma fixture em vez de contra o protocolo.
 */

const TOKEN_A = 'token-da-conta-a';
const UID_A = 'uid-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_B = 'uid-da-conta-b';
const TOKEN_C = 'token-da-conta-c';
const UID_C = 'uid-da-conta-c';

/** Terça-feira, 15h UTC. O relógio é injetado: nenhum teste aqui dorme. */
const NOW = Date.parse('2026-09-08T15:00:00Z');

describe('T17.8 — Check-ins de treino e Feed Social', () => {
  let temp: TempDb;
  let app: INestApplication;
  let clock: FakeClock;

  beforeEach(async () => {
    temp = createTempDb();
    clock = new FakeClock(NOW);
    app = await createTestApp(
      configFor(temp.path),
      new FakeAuthTokenVerifier()
        .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
        .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' })
        .accept(TOKEN_C, { uid: UID_C, email: 'c@example.com' }),
      undefined,
      clock,
    );
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  /** Uma leitura direta do banco, para provar o que a API deliberadamente não expõe. */
  const inDatabase = <T>(read: (db: BetterSqlite3.Database) => T): T => {
    const db = new BetterSqlite3(temp.path);
    try {
      return read(db);
    } finally {
      db.close();
    }
  };

  /**
   * Semeia publicações direto na tabela.
   *
   * Usado **só** pelos testes de teto do feed, cujo assunto é o `LIMIT` da consulta e não o
   * caminho de criação — que os testes de §132 já exercitam inteiro, pelo protocolo real. Criar 55
   * check-ins pela rota consumiria o teto de criação por hora (§117) e faria um teste sobre SQL
   * falhar por causa de um limitador que ele não está medindo.
   */
  const seedCheckIns = (authorUid: string, count: number): void => {
    inDatabase((db) => {
      const insert = db.prepare(
        `INSERT INTO social_workout_checkins
           (id, author_uid, source_session_sync_id, client_request_id, status, created_at, deleted_at)
         VALUES (?, ?, ?, ?, 'PUBLISHED', ?, NULL)`,
      );
      for (let index = 0; index < count; index += 1) {
        insert.run(
          `checkin-${index}`,
          authorUid,
          `sessao-${index}`,
          `requisicao-${index}`,
          NOW - index * 1000,
        );
      }
    });
  };

  // --------------------------------------------------------------------- helpers

  const activate = async (token: string, displayName: string): Promise<string> => {
    const res = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(token))
      .send({ displayName })
      .expect(200);
    return res.body.profile.socialId as string;
  };

  const makeFriends = async (tokenOne: string, tokenTwo: string) => {
    const other = await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(tokenTwo))
      .expect(200);

    const sent = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(tokenOne))
      .send({ socialId: other.body.profile.socialId })
      .expect(200);

    await request(server())
      .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
      .set('Authorization', auth(tokenTwo))
      .expect(200);
  };

  /** Sobe uma sessão pelo protocolo de sync e devolve `{ syncId, revision }`. */
  const pushSession = async (
    token: string,
    overrides: Record<string, unknown> = {},
  ): Promise<{ syncId: string; revision: number }> => {
    const syncId = uuid();
    const res = await request(server())
      .post('/v1/sync/push')
      .set('Authorization', auth(token))
      .set('Content-Type', 'application/json')
      .send(
        pushBody([
          {
            entityType: 'WORKOUT_SESSION',
            entitySyncId: syncId,
            payload: sessionPayload(syncId, {
              startedAt: NOW - 60 * 60 * 1000,
              finishedAt: NOW - 30 * 60 * 1000,
              ...overrides,
            }),
          },
        ]),
      )
      .expect(200);

    expect(res.body.results[0].status).toBe('APPLIED');
    return { syncId, revision: res.body.results[0].serverRevision as number };
  };

  const createCheckIn = (token: string, sessionSyncId: string, clientRequestId = uuid()) =>
    request(server())
      .post('/v1/social/workout-checkins')
      .set('Authorization', auth(token))
      .send({ sessionSyncId, clientRequestId });

  const readFeed = (token: string, query = '') =>
    request(server()).get(`/v1/social/feed${query}`).set('Authorization', auth(token));

  /** Uma conta ativa, com amizade opcional, pronta para publicar. */
  const setupAuthor = async () => {
    await activate(TOKEN_A, 'Alice');
    const { syncId } = await pushSession(TOKEN_A);
    return syncId;
  };

  // =====================================================================
  // §132 — criação: o que é elegível e o que não é
  // =====================================================================

  describe('criação — elegibilidade da sessão (§132)', () => {
    it('publica a partir de uma sessão COMPLETED da própria conta', async () => {
      const syncId = await setupAuthor();

      const res = await createCheckIn(TOKEN_A, syncId).expect(201);

      expect(res.body.type).toBe('WORKOUT_CHECK_IN');
      expect(typeof res.body.checkInId).toBe('string');
      expect(res.body.publishedAt).toBe(NOW);
      expect(res.body.isCurrentUser).toBe(true);
    });

    it.each(['PLANNED', 'IN_PROGRESS', 'PAUSED', 'CANCELLED'])(
      'uma sessão %s nem chega ao servidor, e o check-in é recusado',
      async (status) => {
        await activate(TOKEN_A, 'Alice');
        const syncId = uuid();

        // O protocolo de sync só aceita `COMPLETED` (`backup-entity.registry.ts`): estado vivo de
        // execução não sobe. A recusa da T17.8 acontece, portanto, **antes** dela — a sessão não
        // existe em `sync_entities`, e o check-in não tem o que validar.
        const push = await request(server())
          .post('/v1/sync/push')
          .set('Authorization', auth(TOKEN_A))
          .set('Content-Type', 'application/json')
          .send(
            pushBody([
              {
                entityType: 'WORKOUT_SESSION',
                entitySyncId: syncId,
                payload: sessionPayload(syncId, { status }),
              },
            ]),
          )
          .expect(200);
        expect(push.body.results[0].status).not.toBe('APPLIED');

        const res = await createCheckIn(TOKEN_A, syncId).expect(404);
        expect(res.body.error.code).toBe('SESSION_NOT_FOUND');
      },
    );

    it('recusa uma sessão não concluída que exista no estado sincronizado (defesa em profundidade)', async () => {
      await activate(TOKEN_A, 'Alice');

      // O protocolo impede que este estado nasça pelo push. A validação de status existe assim
      // mesmo: ela é a garantia de §17, e não pode depender de outro módulo continuar restritivo
      // para sempre. Este teste semeia a linha diretamente para exercitar **essa** ramificação.
      const seeded = 'sessao-nao-concluida';
      inDatabase((db) => {
        db.prepare(
          `INSERT INTO sync_entities
             (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
              last_server_sequence, payload, payload_hash, origin_device_id, deleted,
              created_at, updated_at)
           VALUES (?, 'WORKOUT_SESSION', ?, 1, 1, 1, ?, 'hash', 'device-a', 0, ?, ?)`,
        ).run(
          UID_A,
          seeded,
          JSON.stringify({ syncId: seeded, status: 'IN_PROGRESS', startedAt: NOW - 60_000 }),
          NOW,
          NOW,
        );
      });

      const res = await createCheckIn(TOKEN_A, seeded).expect(422);
      expect(res.body.error.code).toBe('SESSION_NOT_COMPLETED');
    });

    it('recusa uma sessão apagada (tombstone do sync)', async () => {
      await activate(TOKEN_A, 'Alice');
      const { syncId, revision } = await pushSession(TOKEN_A);

      await request(server())
        .post('/v1/sync/push')
        .set('Authorization', auth(TOKEN_A))
        .set('Content-Type', 'application/json')
        .send(
          pushBody([
            {
              entityType: 'WORKOUT_SESSION',
              entitySyncId: syncId,
              operation: 'DELETE',
              baseRevision: revision,
            },
          ]),
        )
        .expect(200);

      const res = await createCheckIn(TOKEN_A, syncId).expect(404);
      expect(res.body.error.code).toBe('SESSION_NOT_FOUND');
    });

    it('a sessão de outra conta é indistinguível de inexistente (§116)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      const { syncId: sessionOfB } = await pushSession(TOKEN_B);

      const foreign = await createCheckIn(TOKEN_A, sessionOfB).expect(404);
      const unknown = await createCheckIn(TOKEN_A, uuid()).expect(404);

      expect(foreign.body.error.code).toBe('SESSION_NOT_FOUND');
      expect(unknown.body.error.code).toBe('SESSION_NOT_FOUND');
      // A mensagem também precisa ser a mesma: uma diferença de texto seria o mesmo oráculo.
      expect(foreign.body.error.message).toBe(unknown.body.error.message);
    });

    it('recusa quem não tem perfil social ativo', async () => {
      await activate(TOKEN_A, 'Alice');
      const { syncId } = await pushSession(TOKEN_A);

      await request(server())
        .post('/v1/social/me/disable')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);

      const res = await createCheckIn(TOKEN_A, syncId).expect(403);
      expect(res.body.error.code).toBe('SOCIAL_NOT_ENABLED');
    });

    it('recusa um treino de mais de 48 horas (§28)', async () => {
      await activate(TOKEN_A, 'Alice');
      const finishedAt = NOW - CHECKIN_WINDOW_MS - 60_000;
      const { syncId } = await pushSession(TOKEN_A, {
        startedAt: finishedAt - 60_000,
        finishedAt,
      });

      const res = await createCheckIn(TOKEN_A, syncId).expect(422);
      expect(res.body.error.code).toBe('CHECKIN_WINDOW_EXPIRED');
    });

    it('aceita um treino no limite da janela e recusa logo depois — o relógio é do servidor (§26/§27)', async () => {
      await activate(TOKEN_A, 'Alice');
      const finishedAt = NOW - CHECKIN_WINDOW_MS + 60_000;
      const { syncId } = await pushSession(TOKEN_A, {
        startedAt: finishedAt - 60_000,
        finishedAt,
      });

      // Nada mudou na sessão: só o relógio do servidor andou.
      clock.advance(30_000);
      await createCheckIn(TOKEN_A, syncId).expect(201);

      const { syncId: other } = await pushSession(TOKEN_A, {
        startedAt: finishedAt - 60_000,
        finishedAt,
      });
      clock.advance(CHECKIN_WINDOW_MS);
      const res = await createCheckIn(TOKEN_A, other).expect(422);
      expect(res.body.error.code).toBe('CHECKIN_WINDOW_EXPIRED');
    });

    it('usa startedAt quando finishedAt é nulo, e o fallback só encurta a janela (§25)', async () => {
      await activate(TOKEN_A, 'Alice');
      // Começou há 47h e o agregado chegou sem `finishedAt`: ainda elegível pelo `startedAt`.
      const recent = await pushSession(TOKEN_A, {
        startedAt: NOW - 47 * 60 * 60 * 1000,
        finishedAt: null,
      });
      await createCheckIn(TOKEN_A, recent.syncId).expect(201);

      // Começou há 49h, também sem `finishedAt`: fora da janela.
      const old = await pushSession(TOKEN_A, {
        startedAt: NOW - 49 * 60 * 60 * 1000,
        finishedAt: null,
      });
      const res = await createCheckIn(TOKEN_A, old.syncId).expect(422);
      expect(res.body.error.code).toBe('CHECKIN_WINDOW_EXPIRED');
    });
  });

  // =====================================================================
  // §11/§14/§71 — o cliente não é autoridade
  // =====================================================================

  describe('o cliente não declara nada (§11/§14)', () => {
    it.each([
      ['ownerUid', { ownerUid: UID_B }],
      ['authorUid', { authorUid: UID_A }],
      ['completed', { completed: true }],
      ['status', { status: 'COMPLETED' }],
      ['publishedAt', { publishedAt: 1 }],
      ['checkInId', { checkInId: 'forjado' }],
      // `caption` saiu desta lista na T17.9: ela virou um campo legítimo, com sanitização e teto
      // próprios (T17.9 §7/§9). `photoUrl` **continua** aqui, e a distinção é o ponto — a foto
      // entra por `mediaId`, um identificador que o servidor emitiu, e nunca por uma URL que o
      // cliente escolhe (T17.9 §24/§25/§59).
      ['photoUrl', { photoUrl: 'https://exemplo/foto.jpg' }],
      ['mediaUrl', { mediaUrl: 'https://exemplo/foto.jpg' }],
      ['storageKey', { storageKey: '../../etc/passwd' }],
      ['reactions', { reactions: { FIRE: 99 } }],
      ['commentCount', { commentCount: 42 }],
      ['audience', { audience: 'PUBLIC' }],
    ])('recusa a requisição inteira quando o corpo declara %s', async (_label, extra) => {
      const syncId = await setupAuthor();

      const res = await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(TOKEN_A))
        .send({ sessionSyncId: syncId, clientRequestId: uuid(), ...extra })
        .expect(400);

      expect(res.body.error.code).toBe('INVALID_CHECKIN_REQUEST');

      // Recusar a requisição inteira, e não ignorar o campo: nada foi publicado.
      const feed = await readFeed(TOKEN_A).expect(200);
      expect(feed.body.items).toEqual([]);
    });

    it('o feed não aceita uma lista de usuários (§71)', async () => {
      await activate(TOKEN_A, 'Alice');

      for (const query of ['?users=uid-da-conta-b', '?socialIds=abc', '?authors=abc']) {
        const res = await readFeed(TOKEN_A, query).expect(400);
        expect(res.body.error.code).toBe('INVALID_CHECKIN_REQUEST');
      }
    });
  });

  // =====================================================================
  // §30–§34 — idempotência
  // =====================================================================

  describe('idempotência (§30–§34)', () => {
    it('o mesmo clientRequestId na mesma sessão produz um único check-in (§31/§134)', async () => {
      const syncId = await setupAuthor();
      const clientRequestId = uuid();

      const first = await createCheckIn(TOKEN_A, syncId, clientRequestId).expect(201);
      const second = await createCheckIn(TOKEN_A, syncId, clientRequestId).expect(201);

      expect(second.body.checkInId).toBe(first.body.checkInId);

      const feed = await readFeed(TOKEN_A).expect(200);
      expect(feed.body.items).toHaveLength(1);
    });

    it('outro clientRequestId na mesma sessão devolve o check-in existente (§33/§135)', async () => {
      const syncId = await setupAuthor();

      const first = await createCheckIn(TOKEN_A, syncId, uuid()).expect(201);
      const second = await createCheckIn(TOKEN_A, syncId, uuid()).expect(201);

      expect(second.body.checkInId).toBe(first.body.checkInId);

      const feed = await readFeed(TOKEN_A).expect(200);
      expect(feed.body.items).toHaveLength(1);
    });

    it('o mesmo clientRequestId para outra sessão é conflito (§32/§136)', async () => {
      await activate(TOKEN_A, 'Alice');
      const first = await pushSession(TOKEN_A);
      const second = await pushSession(TOKEN_A);
      const clientRequestId = uuid();

      await createCheckIn(TOKEN_A, first.syncId, clientRequestId).expect(201);
      const res = await createCheckIn(TOKEN_A, second.syncId, clientRequestId).expect(409);

      expect(res.body.error.code).toBe('CHECKIN_REQUEST_CONFLICT');

      const feed = await readFeed(TOKEN_A).expect(200);
      expect(feed.body.items).toHaveLength(1);
    });

    it('o toque duplo nunca gera duas publicações (§34)', async () => {
      const syncId = await setupAuthor();
      const clientRequestId = uuid();

      const [first, second] = await Promise.all([
        createCheckIn(TOKEN_A, syncId, clientRequestId),
        createCheckIn(TOKEN_A, syncId, clientRequestId),
      ]);

      expect([first.status, second.status].every((status) => status === 201)).toBe(true);
      expect(first.body.checkInId).toBe(second.body.checkInId);

      const feed = await readFeed(TOKEN_A).expect(200);
      expect(feed.body.items).toHaveLength(1);
    });

    it('um treino cujo check-in foi excluído não recebe outro (§29)', async () => {
      const syncId = await setupAuthor();
      const created = await createCheckIn(TOKEN_A, syncId).expect(201);

      await request(server())
        .delete(`/v1/social/workout-checkins/${created.body.checkInId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      const res = await createCheckIn(TOKEN_A, syncId, uuid()).expect(409);
      expect(res.body.error.code).toBe('CHECKIN_ALREADY_EXISTS');
    });
  });

  // =====================================================================
  // §137 — o feed e quem o vê
  // =====================================================================

  describe('feed — audiência (§137)', () => {
    it('o autor vê o próprio check-in, e ele vem marcado como dele', async () => {
      const syncId = await setupAuthor();
      await createCheckIn(TOKEN_A, syncId).expect(201);

      const feed = await readFeed(TOKEN_A).expect(200);
      expect(feed.body.items).toHaveLength(1);
      expect(feed.body.items[0].isCurrentUser).toBe(true);
    });

    it('o amigo direto vê, e não vem marcado como dele', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      const { syncId } = await pushSession(TOKEN_A);
      await createCheckIn(TOKEN_A, syncId).expect(201);

      const feed = await readFeed(TOKEN_B).expect(200);
      expect(feed.body.items).toHaveLength(1);
      expect(feed.body.items[0].author.displayName).toBe('Alice');
      expect(feed.body.items[0].isCurrentUser).toBe(false);
    });

    it('quem não é amigo não vê — mesmo conhecendo o checkInId e o socialId (§162)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_C, 'Carol');

      const { syncId } = await pushSession(TOKEN_A);
      const created = await createCheckIn(TOKEN_A, syncId).expect(201);

      const feed = await readFeed(TOKEN_C).expect(200);
      expect(feed.body.items).toEqual([]);

      // E não existe rota que devolva o check-in por id para um terceiro.
      await request(server())
        .get(`/v1/social/workout-checkins/${created.body.checkInId}`)
        .set('Authorization', auth(TOKEN_C))
        .expect(404);
    });

    it('pedido de amizade pendente não concede feed', async () => {
      await activate(TOKEN_A, 'Alice');
      const socialB = await activate(TOKEN_B, 'Bob');

      await request(server())
        .post('/v1/social/friend-requests')
        .set('Authorization', auth(TOKEN_A))
        .send({ socialId: socialB })
        .expect(200);

      const { syncId } = await pushSession(TOKEN_A);
      await createCheckIn(TOKEN_A, syncId).expect(201);

      const feed = await readFeed(TOKEN_B).expect(200);
      expect(feed.body.items).toEqual([]);
    });

    it('desfazer a amizade revoga o feed na leitura seguinte, sem apagar a publicação (§55/§56)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      const { syncId } = await pushSession(TOKEN_A);
      await createCheckIn(TOKEN_A, syncId).expect(201);
      expect((await readFeed(TOKEN_B).expect(200)).body.items).toHaveLength(1);

      const profileA = await request(server())
        .get('/v1/social/me')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);

      await request(server())
        .post('/v1/social/friends/remove')
        .set('Authorization', auth(TOKEN_B))
        .send({ socialId: profileA.body.profile.socialId })
        .expect(200);

      expect((await readFeed(TOKEN_B).expect(200)).body.items).toEqual([]);
      // O autor continua vendo o próprio check-in (§56).
      expect((await readFeed(TOKEN_A).expect(200)).body.items).toHaveLength(1);
    });

    it.each([
      ['A bloqueia B', TOKEN_B, TOKEN_A],
      ['B bloqueia A', TOKEN_A, TOKEN_B],
    ])(
      'bloqueio revoga o feed nas duas direções — %s (§57/§58)',
      async (_label, blockerToken, blockedToken) => {
        await activate(TOKEN_A, 'Alice');
        await activate(TOKEN_B, 'Bob');
        await makeFriends(TOKEN_A, TOKEN_B);

        const sessionA = await pushSession(TOKEN_A);
        await createCheckIn(TOKEN_A, sessionA.syncId).expect(201);
        const sessionB = await pushSession(TOKEN_B);
        await createCheckIn(TOKEN_B, sessionB.syncId).expect(201);

        const blockedProfile = await request(server())
          .get('/v1/social/me')
          .set('Authorization', auth(blockedToken))
          .expect(200);

        await request(server())
          .post('/v1/social/blocks')
          .set('Authorization', auth(blockerToken))
          .send({ blockedSocialId: blockedProfile.body.profile.socialId })
          .expect(200);

        // Nenhum dos dois vê o outro; cada um continua vendo o próprio.
        const blockerFeed = await readFeed(blockerToken).expect(200);
        const blockedFeed = await readFeed(blockedToken).expect(200);

        expect(
          blockerFeed.body.items.every((item: { isCurrentUser: boolean }) => item.isCurrentUser),
        ).toBe(true);
        expect(
          blockedFeed.body.items.every((item: { isCurrentUser: boolean }) => item.isCurrentUser),
        ).toBe(true);
      },
    );

    it('autor com Social desativado some do feed dos amigos, e volta ao reativar (§60/§61)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      const { syncId } = await pushSession(TOKEN_A);
      await createCheckIn(TOKEN_A, syncId).expect(201);

      await request(server())
        .post('/v1/social/me/disable')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);
      expect((await readFeed(TOKEN_B).expect(200)).body.items).toEqual([]);

      await request(server())
        .post('/v1/social/me/enable')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);
      expect((await readFeed(TOKEN_B).expect(200)).body.items).toHaveLength(1);
    });

    it('check-in excluído some do feed dos amigos', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      const { syncId } = await pushSession(TOKEN_A);
      const created = await createCheckIn(TOKEN_A, syncId).expect(201);

      await request(server())
        .delete(`/v1/social/workout-checkins/${created.body.checkInId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      expect((await readFeed(TOKEN_B).expect(200)).body.items).toEqual([]);
      expect((await readFeed(TOKEN_A).expect(200)).body.items).toEqual([]);
    });

    it('quem não tem perfil social ativo não lê o feed', async () => {
      await activate(TOKEN_A, 'Alice');
      await request(server())
        .post('/v1/social/me/disable')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);

      const res = await readFeed(TOKEN_A).expect(403);
      expect(res.body.error.code).toBe('SOCIAL_NOT_ENABLED');
    });
  });

  // =====================================================================
  // §138 — privacidade do DTO
  // =====================================================================

  describe('privacidade do DTO (§138)', () => {
    it('o item do feed carrega exatamente os campos do contrato, e nenhum é privado', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      const { syncId } = await pushSession(TOKEN_A);
      await createCheckIn(TOKEN_A, syncId).expect(201);

      const feed = await readFeed(TOKEN_B).expect(200);
      const [item] = feed.body.items;

      // A T17.9 acrescentou cinco campos ao **mesmo** DTO (§58). A lista continua fechada: o que
      // este teste protege não é o número, é que nada de treino e nada de identidade privada
      // entrem por um campo novo.
      expect(Object.keys(item).sort()).toEqual([
        'author',
        'caption',
        'checkInId',
        'commentCount',
        'currentUserReaction',
        'isCurrentUser',
        'media',
        'publishedAt',
        'reactions',
        'type',
      ]);
      // Uma publicação da T17.8 continua válida: sem legenda, sem foto, sem reação, sem
      // comentário — e nenhum backfill inventou nada (§6/§60).
      expect(item.caption).toBeNull();
      expect(item.media).toBeNull();
      expect(item.reactions).toEqual({});
      expect(item.currentUserReaction).toBeNull();
      expect(item.commentCount).toBe(0);
      expect(Object.keys(item.author).sort()).toEqual(['displayName', 'socialId']);

      // A varredura textual pega o campo que alguém acrescentar em um DTO aninhado no futuro.
      const serialized = JSON.stringify(feed.body);
      for (const forbidden of [
        'uid',
        'ownerUid',
        'authorUid',
        'firebaseUid',
        'email',
        'friendCode',
        'sessionSyncId',
        'sourceSessionSyncId',
        'startedAt',
        'finishedAt',
        'workoutId',
        'templateId',
        'templateName',
        'programId',
        'exercises',
        'sets',
        'reps',
        'repetitions',
        'load',
        'weight',
        'duration',
        'volume',
        'notes',
        'measurements',
        // A T17.9 acrescentou conteúdo, e **não** acrescentou URL pública nem base64 (§59/§133/
        // §134): o Feed devolve `mediaId`, e os bytes só saem pelo endpoint autenticado.
        'photoUrl',
        'imageUrl',
        'mediaUrl',
        'storageKey',
        'data:image',
        'base64',
      ]) {
        expect(serialized).not.toContain(forbidden);
      }

      // E os valores concretos também não aparecem.
      expect(serialized).not.toContain(UID_A);
      expect(serialized).not.toContain(syncId);
      expect(serialized).not.toContain('Treino A');
      expect(serialized).not.toContain('a@example.com');
    });

    it('a resposta de criação não devolve o sessionSyncId de volta (§51)', async () => {
      const syncId = await setupAuthor();
      const created = await createCheckIn(TOKEN_A, syncId).expect(201);

      expect(JSON.stringify(created.body)).not.toContain(syncId);
      expect(JSON.stringify(created.body)).not.toContain(UID_A);
    });

    it('nenhuma mensagem de erro repete o sessionSyncId', async () => {
      await activate(TOKEN_A, 'Alice');
      const unknown = uuid();

      const res = await createCheckIn(TOKEN_A, unknown).expect(404);
      expect(JSON.stringify(res.body)).not.toContain(unknown);
    });
  });

  // =====================================================================
  // §139/§140 — limites e ordenação
  // =====================================================================

  describe('o feed é bounded e ordenado (§139/§140)', () => {
    it('publicação com mais de 30 dias não aparece', async () => {
      const syncId = await setupAuthor();
      await createCheckIn(TOKEN_A, syncId).expect(201);
      expect((await readFeed(TOKEN_A).expect(200)).body.items).toHaveLength(1);

      clock.advance(FEED_WINDOW_MS + 1000);
      expect((await readFeed(TOKEN_A).expect(200)).body.items).toEqual([]);
    });

    it('respeita o teto máximo mesmo quando o cliente pede mais', async () => {
      await activate(TOKEN_A, 'Alice');
      seedCheckIns(UID_A, FEED_MAX_LIMIT + 5);

      const capped = await readFeed(TOKEN_A, '?limit=500').expect(200);
      expect(capped.body.items).toHaveLength(FEED_MAX_LIMIT);

      const asked = await readFeed(TOKEN_A, '?limit=3').expect(200);
      expect(asked.body.items).toHaveLength(3);
    });

    it('usa o default quando o cliente não pede número', async () => {
      await activate(TOKEN_A, 'Alice');
      seedCheckIns(UID_A, 25);

      const feed = await readFeed(TOKEN_A).expect(200);
      expect(feed.body.items).toHaveLength(20);
    });

    it('ordena por publishedAt DESC com desempate determinístico por checkInId (§76)', async () => {
      await activate(TOKEN_A, 'Alice');

      // Três publicações no **mesmo** instante: o relógio não anda entre elas.
      const ids: string[] = [];
      for (let index = 0; index < 3; index += 1) {
        const { syncId } = await pushSession(TOKEN_A);
        const created = await createCheckIn(TOKEN_A, syncId).expect(201);
        ids.push(created.body.checkInId);
      }

      // Uma quarta, mais nova, precisa vir na frente das três.
      clock.advance(1000);
      const { syncId: newest } = await pushSession(TOKEN_A);
      const newestCheckIn = await createCheckIn(TOKEN_A, newest).expect(201);

      const feed = await readFeed(TOKEN_A).expect(200);
      const returned = feed.body.items.map((item: { checkInId: string }) => item.checkInId);

      expect(returned[0]).toBe(newestCheckIn.body.checkInId);
      expect(returned.slice(1)).toEqual([...ids].sort().reverse());

      // Estável: a mesma leitura devolve a mesma ordem.
      const again = await readFeed(TOKEN_A).expect(200);
      expect(again.body.items.map((item: { checkInId: string }) => item.checkInId)).toEqual(
        returned,
      );
    });
  });

  // =====================================================================
  // §141 — exclusão
  // =====================================================================

  describe('exclusão (§141)', () => {
    it('o autor exclui a própria publicação', async () => {
      const syncId = await setupAuthor();
      const created = await createCheckIn(TOKEN_A, syncId).expect(201);

      await request(server())
        .delete(`/v1/social/workout-checkins/${created.body.checkInId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      expect((await readFeed(TOKEN_A).expect(200)).body.items).toEqual([]);
    });

    it('outra conta recebe 404, e a publicação continua de pé (§65)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      const { syncId } = await pushSession(TOKEN_A);
      const created = await createCheckIn(TOKEN_A, syncId).expect(201);

      const res = await request(server())
        .delete(`/v1/social/workout-checkins/${created.body.checkInId}`)
        .set('Authorization', auth(TOKEN_B))
        .expect(404);
      expect(res.body.error.code).toBe('CHECKIN_NOT_FOUND');

      expect((await readFeed(TOKEN_B).expect(200)).body.items).toHaveLength(1);
    });

    it('um checkInId inexistente responde igual ao de outra conta (§65)', async () => {
      await activate(TOKEN_B, 'Bob');
      const res = await request(server())
        .delete(`/v1/social/workout-checkins/${uuid()}`)
        .set('Authorization', auth(TOKEN_B))
        .expect(404);
      expect(res.body.error.code).toBe('CHECKIN_NOT_FOUND');
    });

    it('excluir é idempotente (§66)', async () => {
      const syncId = await setupAuthor();
      const created = await createCheckIn(TOKEN_A, syncId).expect(201);
      const path = `/v1/social/workout-checkins/${created.body.checkInId}`;

      await request(server()).delete(path).set('Authorization', auth(TOKEN_A)).expect(204);
      await request(server()).delete(path).set('Authorization', auth(TOKEN_A)).expect(204);
    });

    it('excluir o check-in não toca a sessão de treino (§67)', async () => {
      const syncId = await setupAuthor();
      const created = await createCheckIn(TOKEN_A, syncId).expect(201);

      const before = await request(server())
        .get(`/v1/sync/entities/WORKOUT_SESSION/${syncId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(200);

      await request(server())
        .delete(`/v1/social/workout-checkins/${created.body.checkInId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      const after = await request(server())
        .get(`/v1/sync/entities/WORKOUT_SESSION/${syncId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(200);

      expect(after.body).toEqual(before.body);
    });
  });

  // =====================================================================
  // §142 — exclusão de conta
  // =====================================================================

  describe('exclusão de conta (§142)', () => {
    it('apaga os check-ins de quem saiu e preserva os dos outros', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await activate(TOKEN_C, 'Carol');
      await makeFriends(TOKEN_A, TOKEN_B);
      await makeFriends(TOKEN_B, TOKEN_C);

      const sessionA = await pushSession(TOKEN_A);
      await createCheckIn(TOKEN_A, sessionA.syncId).expect(201);
      const sessionC = await pushSession(TOKEN_C);
      await createCheckIn(TOKEN_C, sessionC.syncId).expect(201);

      expect((await readFeed(TOKEN_B).expect(200)).body.items).toHaveLength(2);

      await request(server()).delete('/v1/account').set('Authorization', auth(TOKEN_A)).expect(200);

      const remaining = inDatabase(
        (db) =>
          db
            .prepare(`SELECT COUNT(*) AS total FROM social_workout_checkins WHERE author_uid = ?`)
            .get(UID_A) as { total: number },
      );
      expect(remaining.total).toBe(0);

      // B continua vendo o de Carol, e nenhuma referência órfã restou (§63).
      const feed = await readFeed(TOKEN_B).expect(200);
      expect(feed.body.items).toHaveLength(1);
      expect(feed.body.items[0].author.displayName).toBe('Carol');
    });
  });

  // =====================================================================
  // §117 — teto
  // =====================================================================

  describe('teto de criação (§117)', () => {
    it('um laço de cliente é contido, e a resposta convida a esperar', async () => {
      await activate(TOKEN_A, 'Alice');
      const { syncId } = await pushSession(TOKEN_A);

      let limited: request.Response | undefined;
      for (let attempt = 0; attempt < 40; attempt += 1) {
        const res = await createCheckIn(TOKEN_A, syncId, uuid());
        if (res.status === 429) {
          limited = res;
          break;
        }
      }

      expect(limited).toBeDefined();
      expect(limited?.body.error.code).toBe('RATE_LIMITED');
    });
  });

  // =====================================================================
  // §133 — a fonte canônica é uma só
  // =====================================================================

  describe('nenhum parser paralelo de sessão (§133)', () => {
    const socialDir = join(__dirname, '..', 'src', 'modules', 'social');
    const checkInSources = [
      'workout-checkin.contract.ts',
      'workout-checkin.controller.ts',
      'workout-checkin.errors.ts',
      'workout-checkin.limits.ts',
      'workout-checkin.rate-limit.ts',
      'workout-checkin.repository.ts',
      'workout-checkin.service.ts',
      'workout-checkin.validator.ts',
    ];

    it('nenhum arquivo do check-in consulta sync_entities por conta própria', () => {
      for (const name of checkInSources) {
        const code = readFileSync(join(socialDir, name), 'utf8');
        const statements = code.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');

        for (const forbidden of ['sync_entities', 'json_extract', "'WORKOUT_SESSION'"]) {
          expect(`${name}:${statements.includes(forbidden)}`).toBe(`${name}:false`);
        }
      }
    });

    it('o serviço fala com a fonte canônica, e é ela que define o que é uma sessão elegível', () => {
      const service = readFileSync(join(socialDir, 'workout-checkin.service.ts'), 'utf8');
      expect(service).toContain('CANONICAL_TRAINING_SOURCE');
      expect(service).toContain('findSessionForCheckIn');
    });

    it('o módulo social continua sem importar sync, backup e IA', () => {
      // A varredura é sobre os `import` e sobre o array `imports`, e não sobre o texto do
      // arquivo: a documentação do módulo **cita** os três justamente para dizer que eles não
      // entram, e um teste que proibisse a palavra proibiria a explicação.
      const moduleCode = readFileSync(join(socialDir, 'social.module.ts'), 'utf8');
      const importLines = moduleCode
        .split('\n')
        .filter((line) => line.trimStart().startsWith('import '))
        .join('\n');
      const importsArray = /imports:\s*\[([^\]]*)\]/.exec(moduleCode)?.[1] ?? '';

      for (const forbidden of ['SyncModule', 'BackupModule', 'AiModule']) {
        expect(importLines).not.toContain(forbidden);
        expect(importsArray).not.toContain(forbidden);
      }
      expect(importsArray).toContain('AuthModule');
    });
  });

  // =====================================================================
  // §95/§119–§121 — o que um check-in NÃO faz
  // =====================================================================

  describe('um check-in não é evento de domínio (§95/§119–§121)', () => {
    it('não gera notificação push nem evento de notificação', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      const countEvents = () =>
        inDatabase(
          (db) =>
            db.prepare(`SELECT COUNT(*) AS total FROM social_notification_events`).get() as {
              total: number;
            },
        ).total;
      const before = countEvents();

      const { syncId } = await pushSession(TOKEN_A);
      await createCheckIn(TOKEN_A, syncId).expect(201);

      expect(countEvents()).toBe(before);

      // E não existe preferência nova de notificação nesta fase (§97).
      const prefs = await request(server())
        .get('/v1/social/notifications/preferences')
        .set('Authorization', auth(TOKEN_B))
        .expect(200);
      expect(JSON.stringify(prefs.body)).not.toContain('workoutCheckIn');
    });

    it('concluir outro treino sem compartilhar não acrescenta nada ao Feed (§157)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      const shared = await pushSession(TOKEN_A);
      await createCheckIn(TOKEN_A, shared.syncId).expect(201);
      expect((await readFeed(TOKEN_B).expect(200)).body.items).toHaveLength(1);

      // O segundo treino sobe pelo sync como qualquer outro — e não vira publicação (§3).
      await pushSession(TOKEN_A);

      expect((await readFeed(TOKEN_B).expect(200)).body.items).toHaveLength(1);
    });

    it('não escreve em nenhuma tabela de desafio (§153)', async () => {
      const syncId = await setupAuthor();

      const challengeRows = () =>
        inDatabase((db) => ({
          challenges: (
            db.prepare(`SELECT COUNT(*) AS total FROM challenges`).get() as { total: number }
          ).total,
          participants: (
            db.prepare(`SELECT COUNT(*) AS total FROM challenge_participants`).get() as {
              total: number;
            }
          ).total,
        }));

      const before = challengeRows();
      await createCheckIn(TOKEN_A, syncId).expect(201);
      expect(challengeRows()).toEqual(before);
    });

    it('não altera o consentimento de atividade nem o de ranking (§151/§152)', async () => {
      await activate(TOKEN_A, 'Alice');
      const before = await request(server())
        .get('/v1/social/me')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);

      const { syncId } = await pushSession(TOKEN_A);
      await createCheckIn(TOKEN_A, syncId).expect(201);

      const after = await request(server())
        .get('/v1/social/me')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);

      expect(after.body.privacy).toEqual(before.body.privacy);
    });

    it('não escreve nada no domínio de treino', async () => {
      const syncId = await setupAuthor();

      const snapshot = () =>
        inDatabase((db) => ({
          entities: (
            db.prepare(`SELECT COUNT(*) AS total FROM sync_entities`).get() as { total: number }
          ).total,
          changes: (
            db.prepare(`SELECT COUNT(*) AS total FROM sync_changes`).get() as { total: number }
          ).total,
        }));

      const before = snapshot();
      await createCheckIn(TOKEN_A, syncId).expect(201);

      expect(snapshot()).toEqual(before);
    });
  });
});
