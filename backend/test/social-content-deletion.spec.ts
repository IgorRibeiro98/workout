import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import BetterSqlite3 from 'better-sqlite3';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { pushBody, sessionPayload, uuid } from './support/sync-fixtures';
import { jpeg } from './support/image-fixtures';
import { AccountDeletionService } from '../src/modules/account-deletion/account-deletion.service';

/**
 * T17.9 — exclusão de conta e recuperação de desastre, com **arquivos** no jogo (§111–§114/§139).
 *
 * O que muda em relação à T17.6: até agora, "excluir a conta" era uma transação SQLite. Agora
 * existe conteúdo que vive fora do banco, e `ON DELETE CASCADE` não alcança o sistema de arquivos.
 * Uma exclusão que apague só a metadata deixa a foto da pessoa no disco de um servidor que jura
 * tê-la apagado — e um restore posterior a traz de volta com metadata nova. §195 lista as duas
 * coisas como bloqueantes.
 */

const TOKEN_A = 'token-a';
const UID_A = 'uid-a';
const TOKEN_B = 'token-b';
const UID_B = 'uid-b';

const NOW = Date.parse('2026-09-08T15:00:00Z');

describe('T17.9 — exclusão de conta e DR com mídia', () => {
  let temp: TempDb;
  let app: INestApplication;
  let clock: FakeClock;
  let mediaRoot: string;

  beforeEach(async () => {
    temp = createTempDb();
    mediaRoot = join(temp.directory, 'media');
    clock = new FakeClock(NOW);
    app = await createTestApp(
      configFor(temp.path, { SOCIAL_MEDIA_ROOT: mediaRoot }),
      new FakeAuthTokenVerifier()
        .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
        .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' }),
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

  const filesOnDisk = (): string[] => {
    const out: string[] = [];
    const walk = (dir: string) => {
      if (!existsSync(dir)) return;
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const path = join(dir, entry.name);
        if (entry.isDirectory()) walk(path);
        else out.push(path);
      }
    };
    walk(mediaRoot);
    return out;
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

  const pushSession = async (token: string): Promise<string> => {
    const syncId = uuid();
    await request(server())
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
    return syncId;
  };

  /** Publica um check-in **com foto e legenda**, e devolve os identificadores. */
  const publishWithPhoto = async (
    token: string,
    caption = 'Hoje rendeu demais',
  ): Promise<{ checkInId: string; mediaId: string }> => {
    const syncId = await pushSession(token);
    const media = await request(server())
      .post('/v1/social/checkin-media')
      .query({ sessionSyncId: syncId, clientUploadId: uuid() })
      .set('Authorization', auth(token))
      .set('Content-Type', 'image/jpeg')
      .send(await jpeg())
      .expect(201);

    const checkIn = await request(server())
      .post('/v1/social/workout-checkins')
      .set('Authorization', auth(token))
      .send({
        sessionSyncId: syncId,
        clientRequestId: uuid(),
        caption,
        mediaId: media.body.mediaId,
      })
      .expect(201);

    return { checkInId: checkIn.body.checkInId, mediaId: media.body.mediaId };
  };

  const react = (token: string, checkInId: string, type: string) =>
    request(server())
      .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
      .set('Authorization', auth(token))
      .send({ type });

  const comment = (token: string, checkInId: string, body: string) =>
    request(server())
      .post(`/v1/social/workout-checkins/${checkInId}/comments`)
      .set('Authorization', auth(token))
      .send({ body });

  const counts = () =>
    inDatabase((db) => {
      const one = (sql: string, ...params: unknown[]) =>
        (db.prepare(sql).get(...(params as never[])) as { n: number }).n;
      return {
        checkInsA: one(
          `SELECT COUNT(*) n FROM social_workout_checkins WHERE author_uid = ?`,
          UID_A,
        ),
        checkInsB: one(
          `SELECT COUNT(*) n FROM social_workout_checkins WHERE author_uid = ?`,
          UID_B,
        ),
        mediaA: one(`SELECT COUNT(*) n FROM social_checkin_media WHERE owner_uid = ?`, UID_A),
        mediaB: one(`SELECT COUNT(*) n FROM social_checkin_media WHERE owner_uid = ?`, UID_B),
        commentsA: one(
          `SELECT COUNT(*) n FROM social_checkin_comments WHERE author_uid = ?`,
          UID_A,
        ),
        commentsB: one(
          `SELECT COUNT(*) n FROM social_checkin_comments WHERE author_uid = ?`,
          UID_B,
        ),
        reactionsA: one(
          `SELECT COUNT(*) n FROM social_checkin_reactions WHERE reactor_uid = ?`,
          UID_A,
        ),
        reactionsB: one(
          `SELECT COUNT(*) n FROM social_checkin_reactions WHERE reactor_uid = ?`,
          UID_B,
        ),
        captions: one(`SELECT COUNT(*) n FROM social_workout_checkins WHERE caption IS NOT NULL`),
      };
    });

  /**
   * O cenário completo de §177: A tem publicações, foto, comentários nos próprios posts e nos de
   * B, e reações. B tem as dele.
   */
  const buildScenario = async () => {
    await activate(TOKEN_A, 'Alice');
    await activate(TOKEN_B, 'Bruno');
    await makeFriends(TOKEN_A, TOKEN_B);

    const postOfA = await publishWithPhoto(TOKEN_A, 'legenda da Alice');
    const postOfB = await publishWithPhoto(TOKEN_B, 'legenda do Bruno');

    await comment(TOKEN_A, postOfA.checkInId, 'comentário da Alice no próprio post').expect(201);
    await comment(TOKEN_A, postOfB.checkInId, 'comentário da Alice no post do Bruno').expect(201);
    await comment(TOKEN_B, postOfA.checkInId, 'comentário do Bruno no post da Alice').expect(201);

    await react(TOKEN_A, postOfB.checkInId, 'FIRE').expect(200);
    await react(TOKEN_B, postOfA.checkInId, 'CLAP').expect(200);

    return { postOfA, postOfB };
  };

  // =====================================================================
  // §111–§114/§177 — exclusão de conta
  // =====================================================================

  describe('exclusão de conta (§111–§114/§177/§189)', () => {
    it('remove posts, legendas, mídia, comentários e reações de A — e preserva os de B', async () => {
      const { postOfA, postOfB } = await buildScenario();

      const before = counts();
      expect(before).toEqual({
        checkInsA: 1,
        checkInsB: 1,
        mediaA: 1,
        mediaB: 1,
        commentsA: 2,
        commentsB: 1,
        reactionsA: 1,
        reactionsB: 1,
        captions: 2,
      });
      expect(filesOnDisk()).toHaveLength(2);

      await request(server()).delete('/v1/account').set('Authorization', auth(TOKEN_A)).expect(200);

      const after = counts();

      // §111/§112/§113 — nada de A sobrou: nem publicação, nem legenda, nem mídia, nem os
      // comentários que ela deixou **no post de B**, nem as reações dela.
      expect(after.checkInsA).toBe(0);
      expect(after.mediaA).toBe(0);
      expect(after.commentsA).toBe(0);
      expect(after.reactionsA).toBe(0);

      // §177 — o que é de B permanece. O comentário de B estava no post de A, que deixou de
      // existir; ele sai junto porque não tem onde existir, e é o único de B que sai.
      expect(after.checkInsB).toBe(1);
      expect(after.mediaB).toBe(1);
      expect(after.captions).toBe(1);
      expect(
        inDatabase(
          (db) =>
            (
              db
                .prepare(`SELECT caption AS c FROM social_workout_checkins WHERE author_uid = ?`)
                .get(UID_B) as { c: string }
            ).c,
        ),
      ).toBe('legenda do Bruno');

      // §114 — o **arquivo** de A saiu do disco; o de B ficou.
      expect(filesOnDisk()).toHaveLength(1);

      // E o post de B continua servível para ele.
      await request(server())
        .get(`/v1/social/media/${postOfB.mediaId}`)
        .set('Authorization', auth(TOKEN_B))
        .expect(200);

      // O de A já não existe para ninguém.
      await request(server())
        .get(`/v1/social/media/${postOfA.mediaId}`)
        .set('Authorization', auth(TOKEN_B))
        .expect(404);
    });

    it('as denúncias envolvendo a conta excluída seguem a política da T17.6', async () => {
      await buildScenario();
      const postOfB = await request(server())
        .get('/v1/social/feed')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);
      const target = postOfB.body.items.find(
        (item: { isCurrentUser: boolean }) => !item.isCurrentUser,
      );

      await request(server())
        .post('/v1/social/reports')
        .set('Authorization', auth(TOKEN_A))
        .send({ targetType: 'CHECKIN', targetId: target.checkInId, reason: 'SPAM' })
        .expect(200);

      await request(server()).delete('/v1/account').set('Authorization', auth(TOKEN_A)).expect(200);

      // A política da T17.6 continua soberana (§111): a denúncia feita por A sai com a conta dela.
      expect(inDatabase((db) => db.prepare(`SELECT COUNT(*) n FROM social_reports`).get())).toEqual(
        { n: 0 },
      );
    });
  });

  // =====================================================================
  // §139/§178 — reconciliação de DR
  // =====================================================================

  describe('restore antigo e reconciliação (§139/§178)', () => {
    it('a mídia ressuscitada de uma conta excluída é purgada junto com o banco', async () => {
      await buildScenario();
      const deletion = app.get(AccountDeletionService);
      const hashA = deletion.hashUid(UID_A);

      // O cenário do §178: um backup **anterior** à exclusão foi restaurado, então o banco e a
      // mídia de A estão de volta como se nada tivesse acontecido. O log de tombstones de DR, que
      // vive fora do banco, é a única coisa que lembra da exclusão.
      expect(counts().checkInsA).toBe(1);
      expect(filesOnDisk()).toHaveLength(2);

      const purged = await deletion.reconcileTombstones(new Set([hashA]));
      expect(purged).toBe(1);

      // O banco de A saiu...
      const after = counts();
      expect(after.checkInsA).toBe(0);
      expect(after.mediaA).toBe(0);
      expect(after.commentsA).toBe(0);
      expect(after.reactionsA).toBe(0);

      // ...e o **arquivo** também. Purgar só o SQLite deixaria a foto ressuscitada no disco, sem
      // metadata que a revogue — invisível para o Feed e presente para quem tivesse acesso ao
      // volume. §195 chama isso de bloqueante.
      expect(filesOnDisk()).toHaveLength(1);

      // O que é de B continua intacto.
      expect(after.checkInsB).toBe(1);
      expect(after.mediaB).toBe(1);
    });

    it('a reconciliação é idempotente e não toca contas vivas', async () => {
      await buildScenario();
      const deletion = app.get(AccountDeletionService);
      const hashA = deletion.hashUid(UID_A);

      expect(await deletion.reconcileTombstones(new Set([hashA]))).toBe(1);
      expect(await deletion.reconcileTombstones(new Set([hashA]))).toBe(0);

      expect(counts().checkInsB).toBe(1);
      expect(filesOnDisk()).toHaveLength(1);
    });
  });

  // =====================================================================
  // §186/§187/§189 — smoke A/B/C
  // =====================================================================

  describe('smoke de mídia com legenda, reação e comentário (§186/§187)', () => {
    it('o amigo vê tudo; o bloqueio revoga tudo, nos dois sentidos', async () => {
      await activate(TOKEN_A, 'Alice');
      const socialB = await activate(TOKEN_B, 'Bruno');
      await makeFriends(TOKEN_A, TOKEN_B);

      const post = await publishWithPhoto(TOKEN_A);
      await react(TOKEN_B, post.checkInId, 'FIRE').expect(200);
      await comment(TOKEN_B, post.checkInId, 'Boa demais!').expect(201);

      const feedOfB = await request(server())
        .get('/v1/social/feed')
        .set('Authorization', auth(TOKEN_B))
        .expect(200);
      const item = feedOfB.body.items[0];
      expect(item.caption).toBe('Hoje rendeu demais');
      expect(item.media.mediaId).toBe(post.mediaId);
      expect(item.reactions).toEqual({ FIRE: 1 });
      expect(item.currentUserReaction).toBe('FIRE');
      expect(item.commentCount).toBe(1);

      await request(server())
        .get(`/v1/social/media/${post.mediaId}`)
        .set('Authorization', auth(TOKEN_B))
        .expect(200);

      // A bloqueia B: some tudo, nos dois sentidos, na leitura seguinte.
      await request(server())
        .post('/v1/social/blocks')
        .set('Authorization', auth(TOKEN_A))
        .send({ blockedSocialId: socialB })
        .expect(200);

      expect(
        (
          await request(server())
            .get('/v1/social/feed')
            .set('Authorization', auth(TOKEN_B))
            .expect(200)
        ).body.items,
      ).toEqual([]);
      await request(server())
        .get(`/v1/social/media/${post.mediaId}`)
        .set('Authorization', auth(TOKEN_B))
        .expect(404);
      await request(server())
        .get(`/v1/social/workout-checkins/${post.checkInId}/comments`)
        .set('Authorization', auth(TOKEN_B))
        .expect(404);

      // E A deixa de ver a interação de B no próprio post — a reação some da contagem dela.
      const feedOfA = await request(server())
        .get('/v1/social/feed')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);
      expect(feedOfA.body.items[0].reactions).toEqual({});
      expect(feedOfA.body.items[0].commentCount).toBe(0);
    });
  });
});
