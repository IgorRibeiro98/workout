import { readFileSync, readdirSync } from 'node:fs';
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
  MAX_CAPTION_LENGTH,
  MAX_COMMENT_LENGTH,
  MAX_COMMENTS_PER_CHECKIN_PER_WINDOW,
  COMMENTS_MAX_LIMIT,
} from '../src/modules/social/social-media.limits';

/**
 * T17.9 — legenda, reações, comentários e denúncia de conteúdo.
 *
 * O cenário que aparece mais vezes aqui é o de **terceiro** (§86/§173/§188):
 *
 * ```text
 * A ── amiga de ── C ── amiga de ── B
 * A bloqueia B
 * ```
 *
 * C continua vendo tudo. A e B continuam interagindo com C. E, no post de C, cada uma deixa de ver
 * a interação da outra — **sem** que nada seja apagado para C. É o caso em que uma implementação
 * que apagasse conteúdo no bloqueio, ou que contasse reações globalmente, falharia; e é por isso
 * que ele é testado em quatro superfícies diferentes.
 */

const TOKEN_A = 'token-a';
const UID_A = 'uid-a';
const TOKEN_B = 'token-b';
const UID_B = 'uid-b';
const TOKEN_C = 'token-c';
const UID_C = 'uid-c';

const NOW = Date.parse('2026-09-08T15:00:00Z');

describe('T17.9 — conteúdo do check-in', () => {
  let temp: TempDb;
  let app: INestApplication;
  let clock: FakeClock;

  beforeEach(async () => {
    temp = createTempDb();
    clock = new FakeClock(NOW);
    app = await createTestApp(
      configFor(temp.path, { SOCIAL_MEDIA_ROOT: `${temp.directory}/media` }),
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

  const inDatabase = <T>(read: (db: BetterSqlite3.Database) => T): T => {
    const db = new BetterSqlite3(temp.path);
    try {
      return read(db);
    } finally {
      db.close();
    }
  };

  const activate = async (token: string, displayName: string): Promise<string> => {
    const res = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(token))
      .send({ displayName })
      .expect(200);
    return res.body.profile.socialId as string;
  };

  const socialIdOf = async (token: string): Promise<string> => {
    const res = await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(token))
      .expect(200);
    return res.body.profile.socialId as string;
  };

  const makeFriends = async (one: string, two: string) => {
    const sent = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(one))
      .send({ socialId: await socialIdOf(two) })
      .expect(200);
    await request(server())
      .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
      .set('Authorization', auth(two))
      .expect(200);
  };

  const block = async (blocker: string, blocked: string) =>
    request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(blocker))
      .send({ blockedSocialId: await socialIdOf(blocked) })
      .expect(200);

  const pushSession = async (token: string): Promise<string> => {
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
            }),
          },
        ]),
      )
      .expect(200);
    expect(res.body.results[0].status).toBe('APPLIED');
    return syncId;
  };

  const publish = async (token: string, extra: Record<string, unknown> = {}): Promise<string> => {
    const syncId = await pushSession(token);
    const res = await request(server())
      .post('/v1/social/workout-checkins')
      .set('Authorization', auth(token))
      .send({ sessionSyncId: syncId, clientRequestId: uuid(), ...extra })
      .expect(201);
    return res.body.checkInId as string;
  };

  const feed = (token: string) =>
    request(server()).get('/v1/social/feed').set('Authorization', auth(token));

  const react = (token: string, checkInId: string, type: string) =>
    request(server())
      .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
      .set('Authorization', auth(token))
      .send({ type });

  const unreact = (token: string, checkInId: string) =>
    request(server())
      .delete(`/v1/social/workout-checkins/${checkInId}/reaction`)
      .set('Authorization', auth(token));

  const comment = (token: string, checkInId: string, body: string) =>
    request(server())
      .post(`/v1/social/workout-checkins/${checkInId}/comments`)
      .set('Authorization', auth(token))
      .send({ body });

  const comments = (token: string, checkInId: string, query = '') =>
    request(server())
      .get(`/v1/social/workout-checkins/${checkInId}/comments${query}`)
      .set('Authorization', auth(token));

  /** A/B amigas de C; A bloqueia B. O cenário de terceiro de §86/§173/§188. */
  const thirdPartyBlockScenario = async (): Promise<string> => {
    await activate(TOKEN_A, 'Alice');
    await activate(TOKEN_B, 'Bruno');
    await activate(TOKEN_C, 'Cris');
    await makeFriends(TOKEN_A, TOKEN_C);
    await makeFriends(TOKEN_B, TOKEN_C);
    const postOfC = await publish(TOKEN_C);
    await block(TOKEN_A, TOKEN_B);
    return postOfC;
  };

  // =====================================================================
  // §7–§11 — legenda
  // =====================================================================

  describe('legenda (§7–§11/§181)', () => {
    it('publica sem legenda, e o campo vem null (§42/§60)', async () => {
      await activate(TOKEN_A, 'Alice');
      await publish(TOKEN_A);

      const [item] = (await feed(TOKEN_A).expect(200)).body.items;
      expect(item.caption).toBeNull();
      expect(item.media).toBeNull();
    });

    it('aceita 280 caracteres e recusa 281 (§7)', async () => {
      await activate(TOKEN_A, 'Alice');

      const limit = 'a'.repeat(MAX_CAPTION_LENGTH);
      const sessionOk = await pushSession(TOKEN_A);
      await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(TOKEN_A))
        .send({ sessionSyncId: sessionOk, clientRequestId: uuid(), caption: limit })
        .expect(201);

      const sessionTooLong = await pushSession(TOKEN_A);
      const res = await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(TOKEN_A))
        .send({
          sessionSyncId: sessionTooLong,
          clientRequestId: uuid(),
          caption: 'a'.repeat(MAX_CAPTION_LENGTH + 1),
        })
        .expect(400);
      expect(res.body.error.code).toBe('INVALID_CONTENT');
    });

    it('conta code points, e não unidades UTF-16 (§7)', async () => {
      await activate(TOKEN_A, 'Alice');
      // 280 emojis são 560 `char` em JavaScript. Contar por `.length` recusaria uma legenda que a
      // tela promete aceitar.
      const emojis = '\u{1F4AA}'.repeat(MAX_CAPTION_LENGTH);
      const syncId = await pushSession(TOKEN_A);

      await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(TOKEN_A))
        .send({ sessionSyncId: syncId, clientRequestId: uuid(), caption: emojis })
        .expect(201);
    });

    it('apara espaço, e uma legenda só de espaço vira ausência (§9)', async () => {
      await activate(TOKEN_A, 'Alice');
      const withSpaces = await pushSession(TOKEN_A);
      await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(TOKEN_A))
        .send({
          sessionSyncId: withSpaces,
          clientRequestId: uuid(),
          caption: '   Hoje rendeu demais   ',
        })
        .expect(201);

      const blank = await pushSession(TOKEN_A);
      await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(TOKEN_A))
        .send({ sessionSyncId: blank, clientRequestId: uuid(), caption: '    ' })
        .expect(201);

      const items = (await feed(TOKEN_A).expect(200)).body.items;
      const captions = items.map((item: { caption: string | null }) => item.caption);
      expect(captions).toContain('Hoje rendeu demais');
      expect(captions).toContain(null);
    });

    it('recusa caracteres de controle e formatação invisível (§9/§78)', async () => {
      await activate(TOKEN_A, 'Alice');

      // Escritos com escapes `\u`: um arquivo de teste que contivesse os próprios caracteres de
      // controle seria ilegível em diff e em revisão, e a primeira ferramenta que os normalizasse
      // apagaria o caso em silêncio.
      for (const hostile of [
        'antes\u0000depois', // NUL
        'antes\u0007depois', // BEL
        'antes\u001bdepois', // ESC
        'antes\u007fdepois', // DEL
        'antes\u202edepois', // override bidirecional
        'antes\u200bdepois', // zero-width space
        'antes\ufeffdepois', // BOM no meio do texto
        'antes\u2066depois', // isolate bidirecional
      ]) {
        const syncId = await pushSession(TOKEN_A);
        const res = await request(server())
          .post('/v1/social/workout-checkins')
          .set('Authorization', auth(TOKEN_A))
          .send({ sessionSyncId: syncId, clientRequestId: uuid(), caption: hostile })
          .expect(400);
        expect(res.body.error.code).toBe('INVALID_CONTENT');
      }
    });

    it('guarda HTML, Markdown e JavaScript como **texto**, sem interpretar e sem escapar (§9)', async () => {
      await activate(TOKEN_A, 'Alice');
      const raw = '<script>alert(1)</script> **negrito** & <b>x</b>';
      const syncId = await pushSession(TOKEN_A);

      await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(TOKEN_A))
        .send({ sessionSyncId: syncId, clientRequestId: uuid(), caption: raw })
        .expect(201);

      const [item] = (await feed(TOKEN_A).expect(200)).body.items;
      // Verbatim: o texto volta como a pessoa escreveu. Escapar produziria `&amp;` visível, que é
      // corromper o texto dela para se defender de um risco que este caminho não tem — o Android
      // desenha com `Text`, que renderiza `String` e não marcação.
      expect(item.caption).toBe(raw);
      expect(
        inDatabase(
          (db) =>
            (db.prepare(`SELECT caption AS c FROM social_workout_checkins`).get() as { c: string })
              .c,
        ),
      ).toBe(raw);
    });

    it('uma URL na legenda continua sendo texto (§10)', async () => {
      await activate(TOKEN_A, 'Alice');
      const raw = 'olha isso https://exemplo.com/x @igor #treino';
      const syncId = await pushSession(TOKEN_A);

      await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(TOKEN_A))
        .send({ sessionSyncId: syncId, clientRequestId: uuid(), caption: raw })
        .expect(201);

      const [item] = (await feed(TOKEN_A).expect(200)).body.items;
      expect(item.caption).toBe(raw);
      // Nada de link preview, entidade de menção ou lista de hashtags: o DTO tem uma string.
      expect(Object.keys(item)).not.toContain('links');
      expect(Object.keys(item)).not.toContain('mentions');
      expect(Object.keys(item)).not.toContain('hashtags');
    });

    it('normaliza para NFC, para que o limite não dependa do teclado (§9)', async () => {
      await activate(TOKEN_A, 'Alice');
      const decomposed = 'é'.repeat(200); // "é" em dois code points
      const syncId = await pushSession(TOKEN_A);

      await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(TOKEN_A))
        .send({ sessionSyncId: syncId, clientRequestId: uuid(), caption: decomposed })
        .expect(201);

      const [item] = (await feed(TOKEN_A).expect(200)).body.items;
      expect(item.caption).toBe('é'.repeat(200));
      expect(Array.from(item.caption as string)).toHaveLength(200);
    });

    it('a legenda não é preenchida a partir do nome do treino (§11)', async () => {
      await activate(TOKEN_A, 'Alice');
      // A fixture de sessão tem `templateNameSnapshot: 'Treino A'` e `notes`.
      await publish(TOKEN_A);

      const [item] = (await feed(TOKEN_A).expect(200)).body.items;
      expect(item.caption).toBeNull();
      expect(JSON.stringify(item)).not.toContain('Treino A');
    });
  });

  // =====================================================================
  // §61–§73 — reações
  // =====================================================================

  describe('reações (§61–§73/§170/§171)', () => {
    it.each(['FIRE', 'MUSCLE', 'CLAP'])('%s funciona', async (type) => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      const res = await react(TOKEN_A, checkInId, type).expect(200);
      expect(res.body.reactions[type]).toBe(1);
      expect(res.body.currentUserReaction).toBe(type);
    });

    it('trocar de reação atualiza a mesma, e não cria uma segunda (§63/§64)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      await react(TOKEN_A, checkInId, 'FIRE').expect(200);
      const res = await react(TOKEN_A, checkInId, 'MUSCLE').expect(200);

      expect(res.body.reactions).toEqual({ MUSCLE: 1 });
      expect(res.body.currentUserReaction).toBe('MUSCLE');
      expect(
        inDatabase((db) => db.prepare(`SELECT COUNT(*) n FROM social_checkin_reactions`).get()),
      ).toEqual({ n: 1 });
    });

    it('repetir a mesma reação converge (§170)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      await react(TOKEN_A, checkInId, 'FIRE').expect(200);
      const res = await react(TOKEN_A, checkInId, 'FIRE').expect(200);
      expect(res.body.reactions).toEqual({ FIRE: 1 });
    });

    it('remover é idempotente (§65/§170)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      await react(TOKEN_A, checkInId, 'FIRE').expect(200);
      const first = await unreact(TOKEN_A, checkInId).expect(200);
      const second = await unreact(TOKEN_A, checkInId).expect(200);

      expect(first.body.reactions).toEqual({});
      expect(second.body.reactions).toEqual({});
      expect(second.body.currentUserReaction).toBeNull();
    });

    it('o enum é fechado: emoji e valor inventado são recusados (§62/§170)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      for (const hostile of ['\u{1F525}', 'LOVE', 'fire', '', 1, null, { type: 'FIRE' }]) {
        const res = await request(server())
          .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
          .set('Authorization', auth(TOKEN_A))
          .send({ type: hostile })
          .expect(400);
        expect(res.body.error.code).toBe('INVALID_REACTION');
      }
    });

    it('um não-amigo não reage e nem sabe que o post existe (§67)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_C, 'Cris');
      const checkInId = await publish(TOKEN_A);

      const res = await react(TOKEN_C, checkInId, 'FIRE').expect(404);
      expect(res.body.error.code).toBe('CHECKIN_NOT_FOUND');
    });

    it('"já reagiu antes" não é permissão depois do unfriend (§68)', async () => {
      await activate(TOKEN_A, 'Alice');
      const socialA = await activate(TOKEN_B, 'Bruno').then(() => socialIdOf(TOKEN_A));
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_A);

      await react(TOKEN_B, checkInId, 'FIRE').expect(200);

      await request(server())
        .post('/v1/social/friends/remove')
        .set('Authorization', auth(TOKEN_B))
        .send({ socialId: socialA })
        .expect(200);

      await react(TOKEN_B, checkInId, 'CLAP').expect(404);
      await unreact(TOKEN_B, checkInId).expect(404);
    });

    it('um par bloqueado não reage (§68)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_A);

      await block(TOKEN_A, TOKEN_B);
      await react(TOKEN_B, checkInId, 'FIRE').expect(404);
    });

    it('post excluído não recebe reação (§170)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      await request(server())
        .delete(`/v1/social/workout-checkins/${checkInId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      await react(TOKEN_A, checkInId, 'FIRE').expect(404);
    });

    it('a contagem não vaza a reação de quem o viewer bloqueou (§69/§171)', async () => {
      const postOfC = await thirdPartyBlockScenario();

      await react(TOKEN_A, postOfC, 'FIRE').expect(200);
      await react(TOKEN_C, postOfC, 'CLAP').expect(200);

      // C vê as duas: ela não bloqueou ninguém.
      const forC = (await feed(TOKEN_C).expect(200)).body.items.find(
        (item: { checkInId: string }) => item.checkInId === postOfC,
      );
      expect(forC.reactions).toEqual({ FIRE: 1, CLAP: 1 });

      // B vê o post de C, mas **não** a participação de A — nem como número. Um `COUNT(*)`
      // global diria a B que existe mais alguém ali, que é a informação que o bloqueio esconde.
      const forB = (await feed(TOKEN_B).expect(200)).body.items.find(
        (item: { checkInId: string }) => item.checkInId === postOfC,
      );
      expect(forB.reactions).toEqual({ CLAP: 1 });
      expect(forB.reactions.FIRE).toBeUndefined();
    });

    it('a resposta não devolve lista de quem reagiu (§70)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_A);

      const res = await react(TOKEN_B, checkInId, 'FIRE').expect(200);
      const serialized = JSON.stringify(res.body);

      expect(serialized).not.toContain('Bruno');
      expect(serialized).not.toContain(UID_B);
      expect(Object.keys(res.body)).not.toContain('reactors');
      expect(typeof res.body.reactions.FIRE).toBe('number');
    });

    it('reagir não dá XP, não vira atividade e não gera push (§71/§72/§73)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);
      await react(TOKEN_A, checkInId, 'FIRE').expect(200);
      await comment(TOKEN_A, checkInId, 'boa').expect(201);

      const counts = inDatabase((db) => ({
        events: (
          db.prepare(`SELECT COUNT(*) n FROM social_notification_events`).get() as { n: number }
        ).n,
        deliveries: (
          db.prepare(`SELECT COUNT(*) n FROM social_notification_deliveries`).get() as {
            n: number;
          }
        ).n,
        entities: (db.prepare(`SELECT COUNT(*) n FROM sync_changes`).get() as { n: number }).n,
      }));

      expect(counts.events).toBe(0);
      expect(counts.deliveries).toBe(0);
      // O `sync_changes` tem só a sessão que o teste empurrou — reagir e comentar não acrescentam.
      expect(counts.entities).toBe(1);
    });
  });

  // =====================================================================
  // §74–§98 — comentários
  // =====================================================================

  describe('comentários (§74–§98/§172/§174)', () => {
    it('cria, apara e devolve o comentário já persistido (§122)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      const res = await comment(TOKEN_A, checkInId, '   Boa!   ').expect(201);

      expect(res.body.body).toBe('Boa!');
      expect(typeof res.body.commentId).toBe('string');
      expect(res.body.isCurrentUser).toBe(true);
      expect(res.body.canDelete).toBe(true);
      expect(Object.keys(res.body.author).sort()).toEqual(['displayName', 'socialId']);
    });

    it('o DTO não carrega uid (§88)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_A);
      await comment(TOKEN_B, checkInId, 'Boa demais').expect(201);

      const res = await comments(TOKEN_A, checkInId).expect(200);
      const serialized = JSON.stringify(res.body);

      for (const forbidden of [
        UID_A,
        UID_B,
        'uid',
        'ownerUid',
        'authorUid',
        'email',
        'friendCode',
      ]) {
        expect(serialized).not.toContain(forbidden);
      }
      expect(Object.keys(res.body.items[0]).sort()).toEqual([
        'author',
        'body',
        'canDelete',
        'commentId',
        'createdAt',
        'isCurrentUser',
      ]);
    });

    it('recusa vazio, só espaço e acima do teto (§76/§172)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      for (const hostile of ['', '   ', '\n\n', 'a'.repeat(MAX_COMMENT_LENGTH + 1)]) {
        const res = await comment(TOKEN_A, checkInId, hostile).expect(400);
        expect(res.body.error.code).toBe('INVALID_CONTENT');
      }

      await comment(TOKEN_A, checkInId, 'a'.repeat(MAX_COMMENT_LENGTH)).expect(201);
    });

    it('recusa caracteres de controle e limita quebras de linha (§77/§78)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      await comment(TOKEN_A, checkInId, 'antes\u0000depois').expect(400);
      await comment(TOKEN_A, checkInId, 'antes\u202edepois').expect(400);
      await comment(TOKEN_A, checkInId, 'a\nb\nc\nd\ne\nf\ng\nh').expect(400);
      // Poucas quebras continuam permitidas, e sequências longas colapsam.
      const ok = await comment(TOKEN_A, checkInId, 'linha 1\n\n\n\nlinha 2').expect(201);
      expect(ok.body.body).toBe('linha 1\n\nlinha 2');
    });

    it('mention e hashtag são apenas texto (§79/§80)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      const res = await comment(TOKEN_A, checkInId, '@igor arrasou #treino').expect(201);
      expect(res.body.body).toBe('@igor arrasou #treino');
      expect(Object.keys(res.body)).not.toContain('mentions');
    });

    it('um não-amigo não comenta (§82/§172)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_C, 'Cris');
      const checkInId = await publish(TOKEN_A);

      await comment(TOKEN_C, checkInId, 'oi').expect(404);
    });

    it('um par bloqueado não comenta (§85/§172)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_A);

      await block(TOKEN_A, TOKEN_B);
      await comment(TOKEN_B, checkInId, 'oi').expect(404);
      await comment(TOKEN_A, checkInId, 'oi').expect(201); // o autor continua comentando o próprio
    });

    it('post excluído não recebe comentário (§172)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);
      await request(server())
        .delete(`/v1/social/workout-checkins/${checkInId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      await comment(TOKEN_A, checkInId, 'oi').expect(404);
    });

    it('ordena por createdAt crescente, com desempate por commentId (§91)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      // Mesmo instante, de propósito: o relógio é injetado e não avança sozinho, então o desempate
      // por identificador é a **única** coisa que decide a ordem — que é exatamente o que §91 pede.
      const created: string[] = [];
      for (const text of ['um', 'dois', 'três']) {
        created.push((await comment(TOKEN_A, checkInId, text).expect(201)).body.commentId);
      }

      const first = (await comments(TOKEN_A, checkInId).expect(200)).body.items.map(
        (item: { commentId: string }) => item.commentId,
      );
      const second = (await comments(TOKEN_A, checkInId).expect(200)).body.items.map(
        (item: { commentId: string }) => item.commentId,
      );

      expect(first).toEqual([...created].sort());
      expect(second).toEqual(first);
    });

    it('a listagem é bounded, e pedir mais que o teto é atendido até o teto (§90)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);
      for (let i = 0; i < 8; i += 1) {
        await comment(TOKEN_A, checkInId, `comentário ${i}`).expect(201);
      }

      expect((await comments(TOKEN_A, checkInId, '?limit=3').expect(200)).body.items).toHaveLength(
        3,
      );
      // Acima do teto não é erro (mesma regra do feed), e não devolve mais que o teto.
      const big = await comments(TOKEN_A, checkInId, `?limit=${COMMENTS_MAX_LIMIT + 500}`).expect(
        200,
      );
      expect(big.body.items.length).toBeLessThanOrEqual(COMMENTS_MAX_LIMIT);
      await comments(TOKEN_A, checkInId, '?users=alguem').expect(400);
    });

    it('o autor apaga o próprio comentário, e repetir converge (§93/§97)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_A);
      const created = await comment(TOKEN_B, checkInId, 'Boa!').expect(201);

      const path = `/v1/social/workout-checkins/${checkInId}/comments/${created.body.commentId}`;
      await request(server()).delete(path).set('Authorization', auth(TOKEN_B)).expect(204);
      await request(server()).delete(path).set('Authorization', auth(TOKEN_B)).expect(204);

      expect((await comments(TOKEN_A, checkInId).expect(200)).body.items).toEqual([]);
    });

    it('o dono do post modera comentário alheio no próprio post (§94)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_A);
      const created = await comment(TOKEN_B, checkInId, 'Boa!').expect(201);

      // O dono do post vê `canDelete: true` mesmo em comentário que não é dele.
      const list = await comments(TOKEN_A, checkInId).expect(200);
      expect(list.body.items[0].canDelete).toBe(true);
      expect(list.body.items[0].isCurrentUser).toBe(false);

      await request(server())
        .delete(`/v1/social/workout-checkins/${checkInId}/comments/${created.body.commentId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      expect((await comments(TOKEN_A, checkInId).expect(200)).body.items).toEqual([]);
    });

    it('um terceiro amigo não apaga comentário de ninguém (§95)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await activate(TOKEN_C, 'Cris');
      await makeFriends(TOKEN_A, TOKEN_C);
      await makeFriends(TOKEN_B, TOKEN_C);
      const postOfC = await publish(TOKEN_C);
      const created = await comment(TOKEN_A, postOfC, 'Boa!').expect(201);

      // B vê o comentário de A no post de C, e não pode apagá-lo.
      const list = await comments(TOKEN_B, postOfC).expect(200);
      expect(list.body.items[0].canDelete).toBe(false);

      await request(server())
        .delete(`/v1/social/workout-checkins/${postOfC}/comments/${created.body.commentId}`)
        .set('Authorization', auth(TOKEN_B))
        .expect(404);

      expect((await comments(TOKEN_C, postOfC).expect(200)).body.items).toHaveLength(1);
    });

    it('apagar comentário não toca o check-in (§98)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A, { caption: 'Hoje rendeu' });
      const created = await comment(TOKEN_A, checkInId, 'oi').expect(201);

      await request(server())
        .delete(`/v1/social/workout-checkins/${checkInId}/comments/${created.body.commentId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      const [item] = (await feed(TOKEN_A).expect(200)).body.items;
      expect(item.checkInId).toBe(checkInId);
      expect(item.caption).toBe('Hoje rendeu');
      expect(item.commentCount).toBe(0);
    });

    it('desfazer a amizade esconde o comentário sem apagá-lo (§83/§84)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_A);
      await comment(TOKEN_B, checkInId, 'Boa!').expect(201);

      expect((await comments(TOKEN_A, checkInId).expect(200)).body.items).toHaveLength(1);

      await request(server())
        .post('/v1/social/friends/remove')
        .set('Authorization', auth(TOKEN_A))
        .send({ socialId: await socialIdOf(TOKEN_B) })
        .expect(200);

      // Some da leitura, e a linha continua no banco (§84: não precisa de hard delete imediato).
      expect((await comments(TOKEN_A, checkInId).expect(200)).body.items).toEqual([]);
      expect(
        inDatabase((db) =>
          db
            .prepare(`SELECT COUNT(*) n FROM social_checkin_comments WHERE deleted_at IS NULL`)
            .get(),
        ),
      ).toEqual({ n: 1 });
    });

    it('o comentário do autor no próprio post sobrevive ao unfriend de terceiros (§83)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_A);
      await comment(TOKEN_A, checkInId, 'meu próprio post').expect(201);

      await request(server())
        .post('/v1/social/friends/remove')
        .set('Authorization', auth(TOKEN_A))
        .send({ socialId: await socialIdOf(TOKEN_B) })
        .expect(200);

      expect((await comments(TOKEN_A, checkInId).expect(200)).body.items).toHaveLength(1);
    });

    it('o teto por publicação contém enxurrada sem bloquear conversa (§158)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);

      let accepted = 0;
      let limited = 0;
      for (let i = 0; i < MAX_COMMENTS_PER_CHECKIN_PER_WINDOW + 3; i += 1) {
        const res = await comment(TOKEN_A, checkInId, `comentário ${i}`);
        if (res.status === 201) accepted += 1;
        if (res.status === 429) limited += 1;
      }

      expect(accepted).toBe(MAX_COMMENTS_PER_CHECKIN_PER_WINDOW);
      expect(limited).toBe(3);

      // E outra publicação continua aceitando: o teto é por post, não uma mordaça geral.
      const other = await publish(TOKEN_A);
      await comment(TOKEN_A, other, 'aqui ainda dá').expect(201);
    });
  });

  // =====================================================================
  // §86/§92/§173/§174 — bloqueio de terceiro
  // =====================================================================

  describe('bloqueio por viewer em post de terceiro (§86/§117/§173/§188)', () => {
    it('A não vê o comentário de B, B não vê o de A, e C vê os dois', async () => {
      const postOfC = await thirdPartyBlockScenario();

      await comment(TOKEN_A, postOfC, 'comentário da Alice').expect(201);
      await comment(TOKEN_B, postOfC, 'comentário do Bruno').expect(201);

      const forC = (await comments(TOKEN_C, postOfC).expect(200)).body.items.map(
        (item: { body: string }) => item.body,
      );
      expect(forC.sort()).toEqual(['comentário da Alice', 'comentário do Bruno']);

      const forA = (await comments(TOKEN_A, postOfC).expect(200)).body.items.map(
        (item: { body: string }) => item.body,
      );
      expect(forA).toEqual(['comentário da Alice']);

      const forB = (await comments(TOKEN_B, postOfC).expect(200)).body.items.map(
        (item: { body: string }) => item.body,
      );
      expect(forB).toEqual(['comentário do Bruno']);
    });

    it('o bloqueio não apaga o comentário de B no post de C (§117)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await activate(TOKEN_C, 'Cris');
      await makeFriends(TOKEN_A, TOKEN_C);
      await makeFriends(TOKEN_B, TOKEN_C);
      const postOfC = await publish(TOKEN_C);
      await comment(TOKEN_B, postOfC, 'comentário do Bruno').expect(201);

      await block(TOKEN_A, TOKEN_B);

      // C continua vendo. Apagar globalmente seria dar a A o poder de moderar o post de C.
      expect((await comments(TOKEN_C, postOfC).expect(200)).body.items).toHaveLength(1);
      expect((await comments(TOKEN_A, postOfC).expect(200)).body.items).toEqual([]);
    });

    it('a contagem de comentários é viewer-safe (§92/§174)', async () => {
      const postOfC = await thirdPartyBlockScenario();

      await comment(TOKEN_A, postOfC, 'da Alice').expect(201);
      await comment(TOKEN_B, postOfC, 'do Bruno').expect(201);
      await comment(TOKEN_C, postOfC, 'da Cris').expect(201);

      const countFor = async (token: string): Promise<number> => {
        const item = (await feed(token).expect(200)).body.items.find(
          (row: { checkInId: string }) => row.checkInId === postOfC,
        );
        return item.commentCount as number;
      };

      // Um card dizendo "3 comentários" e uma lista com 2 seria o bloqueio anunciando a si mesmo.
      expect(await countFor(TOKEN_C)).toBe(3);
      expect(await countFor(TOKEN_A)).toBe(2);
      expect(await countFor(TOKEN_B)).toBe(2);

      for (const token of [TOKEN_A, TOKEN_B, TOKEN_C]) {
        const listed = (await comments(token, postOfC).expect(200)).body.items.length;
        expect(listed).toBe(await countFor(token));
      }
    });
  });

  // =====================================================================
  // §99 — excluir a publicação
  // =====================================================================

  describe('excluir a publicação (§99/§176)', () => {
    it('leva feed, reações e comentários junto, e não toca a sessão de treino', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);

      const syncId = await pushSession(TOKEN_A);
      const created = await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(TOKEN_A))
        .send({ sessionSyncId: syncId, clientRequestId: uuid(), caption: 'Hoje rendeu' })
        .expect(201);
      const checkInId = created.body.checkInId as string;

      await react(TOKEN_B, checkInId, 'FIRE').expect(200);
      await comment(TOKEN_B, checkInId, 'Boa!').expect(201);

      const sessionBefore = inDatabase((db) =>
        db.prepare(`SELECT * FROM sync_entities WHERE entity_sync_id = ?`).get(syncId),
      );

      await request(server())
        .delete(`/v1/social/workout-checkins/${checkInId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      expect((await feed(TOKEN_A).expect(200)).body.items).toEqual([]);
      expect((await feed(TOKEN_B).expect(200)).body.items).toEqual([]);
      await comments(TOKEN_A, checkInId).expect(404);
      await react(TOKEN_B, checkInId, 'CLAP').expect(404);

      // A sessão de treino continua byte a byte como estava (§176).
      expect(
        inDatabase((db) =>
          db.prepare(`SELECT * FROM sync_entities WHERE entity_sync_id = ?`).get(syncId),
        ),
      ).toEqual(sessionBefore);
    });
  });

  // =====================================================================
  // §101–§110 — denúncia
  // =====================================================================

  describe('denúncia de conteúdo (§101–§110/§175)', () => {
    const report = (token: string, body: Record<string, unknown>) =>
      request(server()).post('/v1/social/reports').set('Authorization', auth(token)).send(body);

    it('REPORT USER continua funcionando na forma da T17.6 (regressão)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);

      const res = await report(TOKEN_A, {
        reportedSocialId: await socialIdOf(TOKEN_B),
        reason: 'SPAM',
      }).expect(200);
      expect(res.body.result).toBe('REPORT_RECEIVED');

      const row = inDatabase(
        (db) =>
          db.prepare(`SELECT target_type, reported_uid FROM social_reports`).get() as {
            target_type: string;
            reported_uid: string;
          },
      );
      expect(row.target_type).toBe('USER');
      expect(row.reported_uid).toBe(UID_B);
    });

    it('REPORT CHECKIN resolve o autor no servidor (§101/§103)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_B, { caption: 'legenda problemática' });

      await report(TOKEN_A, {
        targetType: 'CHECKIN',
        targetId: checkInId,
        reason: 'INAPPROPRIATE_BEHAVIOR',
      }).expect(200);

      const row = inDatabase(
        (db) =>
          db.prepare(`SELECT target_type, target_id, reported_uid FROM social_reports`).get() as {
            target_type: string;
            target_id: string;
            reported_uid: string;
          },
      );
      expect(row).toEqual({
        target_type: 'CHECKIN',
        target_id: checkInId,
        reported_uid: UID_B,
      });
    });

    it('REPORT COMMENT resolve o autor do comentário (§101/§103)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_A);
      const created = await comment(TOKEN_B, checkInId, 'comentário problemático').expect(201);

      await report(TOKEN_A, {
        targetType: 'COMMENT',
        targetId: created.body.commentId,
        reason: 'HARASSMENT',
      }).expect(200);

      const row = inDatabase(
        (db) =>
          db.prepare(`SELECT target_type, target_id, reported_uid FROM social_reports`).get() as {
            target_type: string;
            target_id: string;
            reported_uid: string;
          },
      );
      expect(row.target_type).toBe('COMMENT');
      expect(row.target_id).toBe(created.body.commentId);
      expect(row.reported_uid).toBe(UID_B);
    });

    it('não existe REPORT MEDIA — a foto é denunciada como CHECKIN (§102)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_B);

      await report(TOKEN_A, {
        targetType: 'MEDIA',
        targetId: checkInId,
        reason: 'SPAM',
      }).expect(400);
    });

    it('o corpo não pode declarar quem é o denunciado (§103)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await activate(TOKEN_C, 'Cris');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_B);

      // O bloqueante: A tentando registrar uma denúncia **contra C** apontando para um post de B.
      for (const forged of [{ reportedUid: UID_C }, { authorUid: UID_C }, { ownerUid: UID_C }]) {
        await report(TOKEN_A, {
          targetType: 'CHECKIN',
          targetId: checkInId,
          reason: 'SPAM',
          ...forged,
        }).expect(400);
      }

      expect(inDatabase((db) => db.prepare(`SELECT COUNT(*) n FROM social_reports`).get())).toEqual(
        { n: 0 },
      );
    });

    it('conteúdo invisível não é denunciável, e a resposta não revela existência (§104/§105)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await activate(TOKEN_C, 'Cris');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInOfB = await publish(TOKEN_B);
      const commentOfB = (await comment(TOKEN_B, checkInOfB, 'texto').expect(201)).body
        .commentId as string;

      // C não é amiga de B: o post existe, e para C ele é indistinguível de inexistente.
      const known = await report(TOKEN_C, {
        targetType: 'CHECKIN',
        targetId: checkInOfB,
        reason: 'SPAM',
      }).expect(404);
      const unknown = await report(TOKEN_C, {
        targetType: 'CHECKIN',
        targetId: uuid(),
        reason: 'SPAM',
      }).expect(404);
      expect(known.body.error.code).toBe(unknown.body.error.code);

      await report(TOKEN_C, {
        targetType: 'COMMENT',
        targetId: commentOfB,
        reason: 'SPAM',
      }).expect(404);
    });

    it('não se denuncia o próprio conteúdo (§106)', async () => {
      await activate(TOKEN_A, 'Alice');
      const checkInId = await publish(TOKEN_A);
      const created = await comment(TOKEN_A, checkInId, 'meu').expect(201);

      await report(TOKEN_A, {
        targetType: 'CHECKIN',
        targetId: checkInId,
        reason: 'SPAM',
      }).expect(400);
      await report(TOKEN_A, {
        targetType: 'COMMENT',
        targetId: created.body.commentId,
        reason: 'SPAM',
      }).expect(400);
      await report(TOKEN_A, {
        reportedSocialId: await socialIdOf(TOKEN_A),
        reason: 'SPAM',
      }).expect(400);
    });

    it('reusa a taxonomia da T17.6 (§107)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_B);

      await report(TOKEN_A, {
        targetType: 'CHECKIN',
        targetId: checkInId,
        reason: 'MOTIVO_NOVO',
      }).expect(400);
    });

    it('duplicata do mesmo alvo é suprimida; alvo diferente é denúncia nova (§175)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_B);
      const one = (await comment(TOKEN_B, checkInId, 'um').expect(201)).body.commentId as string;
      const two = (await comment(TOKEN_B, checkInId, 'dois').expect(201)).body.commentId as string;

      await report(TOKEN_A, { targetType: 'COMMENT', targetId: one, reason: 'SPAM' }).expect(200);
      const duplicate = await report(TOKEN_A, {
        targetType: 'COMMENT',
        targetId: one,
        reason: 'SPAM',
      }).expect(200);
      expect(duplicate.body.reportId).toBe('duplicate-suppressed');

      // Outro comentário da mesma pessoa, mesmo motivo: é outra denúncia, e a revisão precisa vê-la.
      const other = await report(TOKEN_A, {
        targetType: 'COMMENT',
        targetId: two,
        reason: 'SPAM',
      }).expect(200);
      expect(other.body.reportId).not.toBe('duplicate-suppressed');

      expect(inDatabase((db) => db.prepare(`SELECT COUNT(*) n FROM social_reports`).get())).toEqual(
        { n: 2 },
      );
    });

    it('denunciar não pune, não esconde e não notifica (§108/§109)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_B, { caption: 'legenda' });
      await comment(TOKEN_B, checkInId, 'texto').expect(201);

      // A contagem **antes**: o aceite de amizade da T17.5 já produziu eventos, e eles não têm
      // nada a ver com a denúncia. Medir o absoluto faria este teste falhar por um motivo alheio.
      const eventsBefore = inDatabase(
        (db) =>
          (db.prepare(`SELECT COUNT(*) n FROM social_notification_events`).get() as { n: number })
            .n,
      );

      await report(TOKEN_A, {
        targetType: 'CHECKIN',
        targetId: checkInId,
        reason: 'SPAM',
      }).expect(200);

      // O conteúdo continua exatamente onde estava, para todo mundo.
      expect((await feed(TOKEN_A).expect(200)).body.items).toHaveLength(1);
      expect((await feed(TOKEN_B).expect(200)).body.items).toHaveLength(1);
      expect((await comments(TOKEN_A, checkInId).expect(200)).body.items).toHaveLength(1);

      // E denunciar não notificou ninguém: nem o denunciado, nem o denunciante (§109).
      expect(
        inDatabase(
          (db) =>
            (
              db.prepare(`SELECT COUNT(*) n FROM social_notification_events`).get() as {
                n: number;
              }
            ).n,
        ),
      ).toBe(eventsBefore);
    });

    it('o autor continua podendo apagar conteúdo denunciado (§110)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);
      const checkInId = await publish(TOKEN_B);
      await report(TOKEN_A, {
        targetType: 'CHECKIN',
        targetId: checkInId,
        reason: 'SPAM',
      }).expect(200);

      await request(server())
        .delete(`/v1/social/workout-checkins/${checkInId}`)
        .set('Authorization', auth(TOKEN_B))
        .expect(204);

      // Some do Feed de todo mundo; a linha da denúncia permanece para a revisão operacional.
      expect((await feed(TOKEN_A).expect(200)).body.items).toEqual([]);
      expect(inDatabase((db) => db.prepare(`SELECT COUNT(*) n FROM social_reports`).get())).toEqual(
        { n: 1 },
      );
    });
  });

  // =====================================================================
  // §129/§130 — uma política só
  // =====================================================================

  describe('a política de visibilidade não tem cópias (§129/§130)', () => {
    it('nenhum controller reimplementa amizade, bloqueio ou perfil ativo', () => {
      const dir = join(__dirname, '..', 'src', 'modules', 'social');

      const controllers = readdirSync(dir)
        .filter((name) => name.endsWith('.controller.ts'))
        .map((name) => ({ name, code: readFileSync(join(dir, name), 'utf8') }));

      expect(controllers.length).toBeGreaterThan(5);
      for (const { name, code } of controllers) {
        // Um controller que consulta amizade ou bloqueio por conta própria é a segunda cópia da
        // regra — e §130 chama isso de bloqueante arquitetural.
        for (const forbidden of ['FROM friendships', 'FROM social_blocks', 'SELECT ']) {
          expect(`${name}: ${code.includes(forbidden)}`).toBe(`${name}: false`);
        }
      }
    });

    it('a definição do escopo do viewer existe uma vez só', () => {
      const dir = join(__dirname, '..', 'src', 'modules', 'social');

      // A CTE literal mora em `workout-checkin.access-policy.ts`; todo o resto a **importa**.
      const definers = readdirSync(dir)
        .filter((name) => name.endsWith('.ts'))
        .filter((name) => readFileSync(join(dir, name), 'utf8').includes('eligible_authors AS ('));

      expect(definers).toEqual(['workout-checkin.access-policy.ts']);
    });
  });
});
