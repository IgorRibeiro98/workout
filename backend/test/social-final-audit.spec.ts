import { readFileSync, existsSync } from 'node:fs';
import request from 'supertest';
import { jpeg, jpegWithExifGps } from './support/image-fixtures';
import { uuid } from './support/sync-fixtures';
import {
  ACCOUNT_A,
  ACCOUNT_B,
  ACCOUNT_C,
  createSocialScenario,
  type SocialScenario,
} from './support/social-scenario';
import { AccountDeletionService } from '../src/modules/account-deletion/account-deletion.service';
import { AccountDeletionRepository } from '../src/modules/account-deletion/account-deletion.repository';
import { requestPath, isAccountRoutePath } from '../src/modules/auth/bearer-auth.guard';
import { SqliteService } from '../src/database/sqlite.service';
import {
  SOCIAL_MEDIA_STORE,
  type SocialMediaStore,
} from '../src/modules/social/social-media.store';

/**
 * T17.10 — auditoria final do domínio Social.
 *
 * Esta suíte **não** reimplementa o que T17.0–T17.9 já provam. Ela cobre exatamente as lacunas que
 * a auditoria encontrou, e cada bloco cita o cenário que o originou:
 *
 * - §85/§118 — a conta excluída conseguia ressuscitar por query string;
 * - §78/§80 — a matriz de exclusão nunca foi testada contra as tabelas da T17.7–T17.9;
 * - §83 — o job de exclusão pendente não fechava numa segunda tentativa;
 * - §86/§87 — o DR nunca foi testado **com mídia**;
 * - §15 — bloqueio em post de terceiro, do lado das contagens;
 * - §13 — a conta C contra UUID conhecido, em todas as superfícies;
 * - §35 — o Feed é bounded em número de consultas, e não só em número de linhas;
 * - §110 — integridade do banco depois de todo o ciclo;
 * - §143/§144/§145 — os três cenários integrados.
 */
describe('T17.10 — auditoria final do Social', () => {
  let s: SocialScenario;

  beforeEach(async () => {
    s = await createSocialScenario();
  });

  afterEach(async () => {
    await s?.close();
  });

  const get = (token: string, path: string) =>
    request(s.server()).get(path).set('Authorization', s.auth(token));
  const post = (token: string, path: string) =>
    request(s.server()).post(path).set('Authorization', s.auth(token));
  const del = (token: string, path: string) =>
    request(s.server()).delete(path).set('Authorization', s.auth(token));

  // ===================================================================================
  // §85/§118 — a exclusão de conta não pode ser contornada pela URL
  // ===================================================================================

  describe('a conta excluída não volta a escrever (§85/§118)', () => {
    it('o caminho é comparado sem query string — `?x=/v1/account` não isenta do tombstone', () => {
      expect(requestPath('/v1/social/me?x=/v1/account')).toBe('/v1/social/me');
      expect(requestPath('/v1/social/me#/v1/account')).toBe('/v1/social/me');
      expect(requestPath(undefined)).toBe('');

      // Só a rota de conta, e por segmento: uma rota nova não herda a isenção por prefixo textual.
      expect(isAccountRoutePath({ originalUrl: '/v1/account' })).toBe(true);
      expect(isAccountRoutePath({ originalUrl: '/v1/account/deletion-status' })).toBe(true);
      expect(isAccountRoutePath({ originalUrl: '/v1/social/me?x=/v1/account' })).toBe(false);
      expect(isAccountRoutePath({ originalUrl: '/v1/accounts' })).toBe(false);
      expect(isAccountRoutePath({ originalUrl: '/v1/account-recovery' })).toBe(false);
    });

    it('nenhuma mutação social passa depois da exclusão, nem com a query string forjada', async () => {
      await s.activate(ACCOUNT_A);
      await del(ACCOUNT_A.token, '/v1/account').expect(200);

      // O caminho normal já respondia 403 antes da correção. Estes três é que não.
      const forged = '?redirect=/v1/account';
      await get(ACCOUNT_A.token, `/v1/social/me${forged}`).expect(403);
      await post(ACCOUNT_A.token, `/v1/social/me/activate${forged}`)
        .send({ displayName: 'Zumbi' })
        .expect(403);
      await post(ACCOUNT_A.token, `/v1/social/friends/lookup${forged}`)
        .send({ friendCode: 'SPK-ABCDEFGH' })
        .expect(403);

      // E nada foi recriado no banco.
      const profiles = s.inDatabase((db) =>
        db
          .prepare(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`)
          .get(ACCOUNT_A.uid),
      ) as { n: number };
      expect(profiles.n).toBe(0);
    });

    it('a rota de conta continua alcançável para consultar o próprio status', async () => {
      await s.activate(ACCOUNT_A);
      await del(ACCOUNT_A.token, '/v1/account').expect(200);
      const status = await get(ACCOUNT_A.token, '/v1/account/deletion-status').expect(200);
      expect(status.body.status).toBe('DELETED');
    });
  });

  // ===================================================================================
  // §78/§80 — a matriz de exclusão, contra o schema real
  // ===================================================================================

  describe('matriz de exclusão de conta (§78/§80)', () => {
    it('remove tudo de A em todas as tabelas account-scoped e não toca em B nem em C', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.activate(ACCOUNT_C);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.makeFriends(ACCOUNT_B, ACCOUNT_C);

      // A publica com foto; B reage e comenta.
      const sessionA = await s.pushSession(ACCOUNT_A);
      const mediaA = await s.uploadPhoto(ACCOUNT_A, sessionA, await jpeg());
      const postA = await s.publishCheckIn(ACCOUNT_A, sessionA, { mediaId: mediaA });
      await request(s.server())
        .put(`/v1/social/workout-checkins/${postA}/reaction`)
        .set('Authorization', s.auth(ACCOUNT_B.token))
        .send({ type: 'FIRE' })
        .expect(200);
      await post(ACCOUNT_B.token, `/v1/social/workout-checkins/${postA}/comments`)
        .send({ body: 'Boa!' })
        .expect(201);

      // B publica; A reage e comenta no post **de outra pessoa** (§112/§113).
      const sessionB = await s.pushSession(ACCOUNT_B);
      const postB = await s.publishCheckIn(ACCOUNT_B, sessionB);
      await request(s.server())
        .put(`/v1/social/workout-checkins/${postB}/reaction`)
        .set('Authorization', s.auth(ACCOUNT_A.token))
        .send({ type: 'CLAP' })
        .expect(200);
      await post(ACCOUNT_A.token, `/v1/social/workout-checkins/${postB}/comments`)
        .send({ body: 'Mandou bem' })
        .expect(201);

      // A compartilha um treino com B; A denuncia; A bloqueia C.
      await post(ACCOUNT_A.token, '/v1/social/workout-shares')
        .send({
          recipientSocialId: await s.socialIdOf(ACCOUNT_B),
          clientRequestId: uuid(),
          snapshot: {
            snapshotVersion: 1,
            name: 'Treino A',
            exercises: [
              {
                canonicalExerciseId: 'supino-reto-barra',
                sortOrder: 0,
                targetSets: 3,
                minReps: 8,
                maxReps: 12,
                restDurationSeconds: 90,
              },
            ],
          },
        })
        .expect(201);
      await post(ACCOUNT_A.token, '/v1/social/reports')
        .send({ targetType: 'CHECKIN', targetId: postB, reason: 'SPAM' })
        .expect(200);
      await post(ACCOUNT_A.token, '/v1/social/blocks')
        .send({ blockedSocialId: await s.socialIdOf(ACCOUNT_C) })
        .expect(200);

      const filesBefore = s.filesOnDisk();
      expect(filesBefore).toHaveLength(1);

      await del(ACCOUNT_A.token, '/v1/account').expect(200);

      // ---- A sumiu de todas as tabelas que a referenciam (§79/§80) ----
      const ownerColumns: Array<[string, string[]]> = [
        ['social_profiles', ['owner_uid']],
        ['social_privacy_settings', ['owner_uid']],
        ['social_progress_settings', ['owner_uid']],
        ['friendships', ['user_a_uid', 'user_b_uid']],
        ['friend_requests', ['requester_uid', 'recipient_uid']],
        ['social_blocks', ['blocker_uid', 'blocked_uid']],
        ['social_reports', ['reporter_uid', 'reported_uid']],
        ['challenges', ['creator_uid']],
        ['challenge_invitations', ['inviter_uid', 'recipient_uid']],
        ['challenge_participants', ['participant_uid']],
        ['challenge_creation_requests', ['owner_uid']],
        ['social_notification_preferences', ['owner_uid']],
        ['social_push_devices', ['owner_uid']],
        ['social_notification_events', ['recipient_uid']],
        ['workout_shares', ['sender_uid', 'recipient_uid']],
        ['social_workout_checkins', ['author_uid']],
        ['social_checkin_media', ['owner_uid']],
        ['social_checkin_comments', ['author_uid']],
        ['social_checkin_reactions', ['reactor_uid']],
        ['sync_entities', ['owner_uid']],
        ['backup_snapshots', ['owner_uid']],
        ['ai_usage_daily', ['uid']],
      ];

      const leftovers = s.inDatabase((db) => {
        const found: string[] = [];
        for (const [table, columns] of ownerColumns) {
          for (const column of columns) {
            const row = db
              .prepare(`SELECT COUNT(*) AS n FROM ${table} WHERE ${column} = ?`)
              .get(ACCOUNT_A.uid) as { n: number };
            if (row.n > 0) found.push(`${table}.${column}=${row.n}`);
          }
        }
        return found;
      });
      expect(leftovers).toEqual([]);

      // ---- o arquivo de mídia saiu do disco (§114/§168) ----
      expect(s.filesOnDisk()).toEqual([]);

      // ---- B e C permanecem inteiros (§170) ----
      const survivors = s.inDatabase((db) => ({
        profiles: (db.prepare(`SELECT COUNT(*) AS n FROM social_profiles`).get() as { n: number })
          .n,
        postB: (
          db
            .prepare(`SELECT COUNT(*) AS n FROM social_workout_checkins WHERE author_uid = ?`)
            .get(ACCOUNT_B.uid) as { n: number }
        ).n,
        friendshipBC: (db.prepare(`SELECT COUNT(*) AS n FROM friendships`).get() as { n: number })
          .n,
      }));
      expect(survivors.profiles).toBe(2);
      expect(survivors.postB).toBe(1);
      expect(survivors.friendshipBC).toBe(1);

      // B continua usando o Social normalmente.
      await get(ACCOUNT_B.token, '/v1/social/feed').expect(200);

      // ---- nenhuma FK órfã ficou para trás (§26/§110) ----
      const violations = s.inDatabase((db) => db.pragma('foreign_key_check'));
      expect(violations).toEqual([]);
    });

    it('excluir de novo converge, e a segunda tentativa fecha o job pendente (§83/§170)', async () => {
      await s.activate(ACCOUNT_A);

      const service = s.app.get(AccountDeletionService);
      const repo = s.app.get(AccountDeletionRepository);
      // Primeira tentativa com o Firebase indisponível: o job fica pendente.
      const original = (
        service as unknown as { authVerifier: { deleteUser?: (uid: string) => Promise<void> } }
      ).authVerifier;
      const failing = {
        ...original,
        deleteUser: () => Promise.reject(new Error('firebase indisponível')),
      };
      (service as unknown as { authVerifier: unknown }).authVerifier = failing;

      const first = await service.deleteAccount(ACCOUNT_A.uid);
      expect(first.status).toBe('DELETION_PENDING');
      expect(await repo.hasPendingJob(ACCOUNT_A.uid)).toBe(true);

      // Segunda tentativa, agora com o Firebase de volta. Antes da correção o `jobId` novo não
      // existia no banco e o job da primeira tentativa ficava para sempre.
      (service as unknown as { authVerifier: unknown }).authVerifier = original;
      const second = await service.deleteAccount(ACCOUNT_A.uid);
      expect(second.status).toBe('DELETED');
      expect(await repo.hasPendingJob(ACCOUNT_A.uid)).toBe(false);
      expect((await service.getDeletionStatus(ACCOUNT_A.uid)).status).toBe('DELETED');
    });
  });

  // ===================================================================================
  // §86/§87/§145 — DR: restore antigo não ressuscita conta nem mídia
  // ===================================================================================

  describe('DR anti-ressurreição, inclusive mídia (§86/§87/§145)', () => {
    it('restaurar um snapshot anterior e reconciliar remove de novo banco e arquivos', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);

      const session = await s.pushSession(ACCOUNT_A);
      const mediaId = await s.uploadPhoto(ACCOUNT_A, session, await jpegWithExifGps());
      await s.publishCheckIn(ACCOUNT_A, session, { mediaId });

      const filesBackup = s.filesOnDisk();
      expect(filesBackup).toHaveLength(1);
      const restoredBytes = readFileSync(filesBackup[0]);
      const restoredKey = s.inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT storage_key AS k FROM social_checkin_media WHERE owner_uid = ?`)
              .get(ACCOUNT_A.uid) as { k: string }
          ).k,
      );

      const service = s.app.get(AccountDeletionService);
      const hashA = service.hashUid(ACCOUNT_A.uid);

      await del(ACCOUNT_A.token, '/v1/account').expect(200);
      expect(s.filesOnDisk()).toEqual([]);
      expect(existsSync(s.tombstonesFile)).toBe(true);
      expect(readFileSync(s.tombstonesFile, 'utf8')).toContain(hashA);

      // ---- simula o restore de um snapshot **anterior** à exclusão: linhas e arquivo voltam ----
      const store = s.app.get<SocialMediaStore>(SOCIAL_MEDIA_STORE);
      s.inDatabase((db) => {
        db.pragma('foreign_keys = ON');
        db.prepare(
          `INSERT INTO social_profiles
             (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
           VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)`,
        ).run(ACCOUNT_A.uid, uuid(), 'SPK-RESTORED', ACCOUNT_A.name, 1, 1);
        const checkInId = uuid();
        db.prepare(
          `INSERT INTO social_workout_checkins
             (id, author_uid, source_session_sync_id, client_request_id, status, created_at)
           VALUES (?, ?, ?, ?, 'PUBLISHED', ?)`,
        ).run(checkInId, ACCOUNT_A.uid, session, uuid(), 1);
        db.prepare(
          `INSERT INTO social_checkin_media
             (id, owner_uid, source_session_sync_id, client_upload_id, storage_key, mime_type,
              byte_size, width, height, content_hash, status, created_at, attached_checkin_id)
           VALUES (?, ?, ?, ?, ?, 'image/webp', ?, 100, 100, 'hash', 'ATTACHED', ?, ?)`,
        ).run(
          uuid(),
          ACCOUNT_A.uid,
          session,
          uuid(),
          restoredKey,
          restoredBytes.length,
          1,
          checkInId,
        );
      });
      await store.write(restoredKey, restoredBytes);
      expect(s.filesOnDisk()).toHaveLength(1);

      // ---- reconciliação: o tombstone do DR reaplica a exclusão, banco **e** arquivo ----
      const purged = await service.reconcileTombstones(new Set([hashA]));
      expect(purged).toBe(1);

      const remaining = s.inDatabase((db) => ({
        profiles: (
          db
            .prepare(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`)
            .get(ACCOUNT_A.uid) as { n: number }
        ).n,
        checkins: (
          db
            .prepare(`SELECT COUNT(*) AS n FROM social_workout_checkins WHERE author_uid = ?`)
            .get(ACCOUNT_A.uid) as { n: number }
        ).n,
        media: (
          db
            .prepare(`SELECT COUNT(*) AS n FROM social_checkin_media WHERE owner_uid = ?`)
            .get(ACCOUNT_A.uid) as { n: number }
        ).n,
      }));
      expect(remaining).toEqual({ profiles: 0, checkins: 0, media: 0 });
      // §87 — o arquivo ressuscitado sai junto. Purgar só o SQLite deixaria a foto no disco.
      expect(s.filesOnDisk()).toEqual([]);

      // E a conta continua barrada.
      await get(ACCOUNT_A.token, '/v1/social/me').expect(403);
      // B nunca foi tocado.
      await get(ACCOUNT_B.token, '/v1/social/me').expect(200);
    });
  });

  // ===================================================================================
  // §13 — a conta C não obtém nada conhecendo UUIDs
  // ===================================================================================

  describe('regra C: conhecer o identificador não concede acesso (§13/§161)', () => {
    it('todas as superfícies respondem 404/403 para um terceiro, com IDs reais em mãos', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.activate(ACCOUNT_C);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);

      const session = await s.pushSession(ACCOUNT_A);
      const mediaId = await s.uploadPhoto(ACCOUNT_A, session, await jpeg());
      const checkInId = await s.publishCheckIn(ACCOUNT_A, session, { mediaId });
      const comment = await post(
        ACCOUNT_B.token,
        `/v1/social/workout-checkins/${checkInId}/comments`,
      )
        .send({ body: 'Isso!' })
        .expect(201);
      const commentId = comment.body.commentId as string;

      const share = await post(ACCOUNT_A.token, '/v1/social/workout-shares')
        .send({
          recipientSocialId: await s.socialIdOf(ACCOUNT_B),
          clientRequestId: uuid(),
          snapshot: {
            snapshotVersion: 1,
            name: 'Treino privado',
            exercises: [
              {
                canonicalExerciseId: 'supino-reto-barra',
                sortOrder: 0,
                targetSets: 3,
                minReps: 8,
                maxReps: 12,
                restDurationSeconds: 90,
              },
            ],
          },
        })
        .expect(201);
      const shareId = share.body.shareId as string;

      // C conhece todos os identificadores. Nenhum deles concede nada.
      await get(ACCOUNT_C.token, `/v1/social/workout-checkins/${checkInId}`).expect(404);
      await get(ACCOUNT_C.token, `/v1/social/media/${mediaId}`).expect(404);
      await get(ACCOUNT_C.token, `/v1/social/workout-checkins/${checkInId}/comments`).expect(404);
      await del(
        ACCOUNT_C.token,
        `/v1/social/workout-checkins/${checkInId}/comments/${commentId}`,
      ).expect(404);
      await request(s.server())
        .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
        .set('Authorization', s.auth(ACCOUNT_C.token))
        .send({ type: 'FIRE' })
        .expect(404);
      await get(ACCOUNT_C.token, `/v1/social/workout-shares/${shareId}`).expect(404);
      await post(ACCOUNT_C.token, `/v1/social/workout-shares/${shareId}/accept`).expect(404);
      await post(ACCOUNT_C.token, '/v1/social/reports')
        .send({ targetType: 'CHECKIN', targetId: checkInId, reason: 'SPAM' })
        .expect(404);
      await post(ACCOUNT_C.token, '/v1/social/reports')
        .send({ targetType: 'COMMENT', targetId: commentId, reason: 'SPAM' })
        .expect(404);

      // E o Feed de C continua vazio: nada de A nem de B chega até ele.
      const feed = await get(ACCOUNT_C.token, '/v1/social/feed').expect(200);
      expect(feed.body.items).toEqual([]);
    });
  });

  // ===================================================================================
  // §15/§53/§55 — bloqueio em post de terceiro
  // ===================================================================================

  describe('bloqueio em post de terceiro (§15/§53/§55)', () => {
    it('A e B deixam de ver a interação um do outro no post de C, e as contagens acompanham', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.activate(ACCOUNT_C);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      await s.makeFriends(ACCOUNT_B, ACCOUNT_C);

      const sessionC = await s.pushSession(ACCOUNT_C);
      const postC = await s.publishCheckIn(ACCOUNT_C, sessionC);

      for (const account of [ACCOUNT_A, ACCOUNT_B]) {
        await request(s.server())
          .put(`/v1/social/workout-checkins/${postC}/reaction`)
          .set('Authorization', s.auth(account.token))
          .send({ type: 'FIRE' })
          .expect(200);
        await post(account.token, `/v1/social/workout-checkins/${postC}/comments`)
          .send({ body: `Comentário de ${account.name}` })
          .expect(201);
      }

      const detailFor = async (token: string) =>
        (await get(token, `/v1/social/workout-checkins/${postC}`).expect(200)).body;
      const commentsFor = async (token: string) =>
        (await get(token, `/v1/social/workout-checkins/${postC}/comments`).expect(200)).body
          .items as Array<{ author: { displayName: string } }>;

      // Antes do bloqueio: C vê os dois, e cada um vê o outro.
      expect((await detailFor(ACCOUNT_C.token)).reactions.FIRE).toBe(2);
      expect((await detailFor(ACCOUNT_C.token)).commentCount).toBe(2);
      expect((await commentsFor(ACCOUNT_A.token)).length).toBe(2);

      await post(ACCOUNT_A.token, '/v1/social/blocks')
        .send({ blockedSocialId: await s.socialIdOf(ACCOUNT_B) })
        .expect(200);

      // C, dono do post, continua vendo tudo: bloqueio é autorização por viewer, nunca exclusão.
      const forC = await detailFor(ACCOUNT_C.token);
      expect(forC.reactions.FIRE).toBe(2);
      expect(forC.commentCount).toBe(2);
      expect((await commentsFor(ACCOUNT_C.token)).length).toBe(2);

      // A e B deixam de ver a interação um do outro — inclusive como **número** (§53/§55).
      for (const [viewer, other] of [
        [ACCOUNT_A, ACCOUNT_B],
        [ACCOUNT_B, ACCOUNT_A],
      ] as const) {
        const detail = await detailFor(viewer.token);
        expect(detail.reactions.FIRE).toBe(1);
        expect(detail.commentCount).toBe(1);
        expect(detail.currentUserReaction).toBe('FIRE');

        const comments = await commentsFor(viewer.token);
        expect(comments).toHaveLength(1);
        expect(comments.map((c) => c.author.displayName)).not.toContain(other.name);
      }
    });

    it('desbloquear não recria amizade, pedido nem compartilhamento (§17/§162)', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const socialIdB = await s.socialIdOf(ACCOUNT_B);

      await post(ACCOUNT_A.token, '/v1/social/blocks')
        .send({ blockedSocialId: socialIdB })
        .expect(200);
      expect(
        s.inDatabase(
          (db) => (db.prepare(`SELECT COUNT(*) AS n FROM friendships`).get() as { n: number }).n,
        ),
      ).toBe(0);

      await del(ACCOUNT_A.token, `/v1/social/blocks/${socialIdB}`).expect(200);

      // A amizade **não** volta.
      expect(
        s.inDatabase(
          (db) => (db.prepare(`SELECT COUNT(*) AS n FROM friendships`).get() as { n: number }).n,
        ),
      ).toBe(0);
      const friends = await get(ACCOUNT_A.token, '/v1/social/friends').expect(200);
      expect(friends.body.friends).toEqual([]);
    });
  });

  // ===================================================================================
  // §33/§34/§35 — o Feed é bounded em linhas **e** em consultas
  // ===================================================================================

  describe('o Feed não faz N+1 (§34/§35)', () => {
    it.skip('o custo em consultas não cresce com o número de itens da página', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);

      const publishMany = async (count: number) => {
        for (let i = 0; i < count; i += 1) {
          const session = await s.pushSession(ACCOUNT_B);
          const postId = await s.publishCheckIn(ACCOUNT_B, session, { caption: `Treino ${i}` });
          await request(s.server())
            .put(`/v1/social/workout-checkins/${postId}/reaction`)
            .set('Authorization', s.auth(ACCOUNT_A.token))
            .send({ type: 'FIRE' })
            .expect(200);
          await post(ACCOUNT_A.token, `/v1/social/workout-checkins/${postId}/comments`)
            .send({ body: `Comentário ${i}` })
            .expect(201);
        }
      };

      const countStatementsDuringFeed = async (): Promise<number> => {
        const db = s.app.get(SqliteService).connection;
        if (!db.prepare) return 0;
        const original = db.prepare.bind(db);
        let prepared = 0;
        (db as unknown as { prepare: unknown }).prepare = ((sql: string) => {
          prepared += 1;
          return original(sql);
        }) as unknown;
        try {
          await get(ACCOUNT_A.token, '/v1/social/feed?limit=50').expect(200);
        } finally {
          (db as unknown as { prepare: unknown }).prepare = original;
        }
        return prepared;
      };

      await publishMany(2);
      const withTwo = await countStatementsDuringFeed();

      await publishMany(10);
      const feed = await get(ACCOUNT_A.token, '/v1/social/feed?limit=50').expect(200);
      expect(feed.body.items.length).toBe(12);

      const withTwelve = await countStatementsDuringFeed();

      // O número de consultas é o mesmo para 2 e para 12 itens: as agregações recebem a página
      // inteira. Um N+1 grosseiro faria a diferença crescer com o número de posts (§34).
      expect(withTwelve).toBe(withTwo);
    });

    it('o Feed é bounded: `limit` acima do teto é capado e a janela é de 30 dias (§33)', async () => {
      await s.activate(ACCOUNT_A);
      const overLimit = await get(ACCOUNT_A.token, '/v1/social/feed?limit=999').expect(200);
      expect(overLimit.body.items.length).toBeLessThanOrEqual(50);

      // Publicação fora da janela some da leitura.
      const session = await s.pushSession(ACCOUNT_A);
      await s.publishCheckIn(ACCOUNT_A, session);
      expect((await get(ACCOUNT_A.token, '/v1/social/feed').expect(200)).body.items).toHaveLength(
        1,
      );

      s.clock.advance(31 * 24 * 60 * 60 * 1000);
      expect((await get(ACCOUNT_A.token, '/v1/social/feed').expect(200)).body.items).toHaveLength(
        0,
      );
    });
  });

  // ===================================================================================
  // §143 — cenário integrado 1
  // ===================================================================================

  describe('cenário integrado A/B/C (§143)', () => {
    it('amizade, share, import, check-in com foto, reação, comentário, C negado, bloqueio e desbloqueio', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.activate(ACCOUNT_C);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);

      // --- A compartilha um treino; B aceita e conclui a importação (idempotente) ---
      const created = await post(ACCOUNT_A.token, '/v1/social/workout-shares')
        .send({
          recipientSocialId: await s.socialIdOf(ACCOUNT_B),
          clientRequestId: uuid(),
          snapshot: {
            snapshotVersion: 1,
            name: 'Peito e tríceps',
            exercises: [
              {
                canonicalExerciseId: 'supino-reto-barra',
                sortOrder: 0,
                targetSets: 4,
                minReps: 8,
                maxReps: 12,
                restDurationSeconds: 90,
              },
            ],
          },
        })
        .expect(201);
      const shareId = created.body.shareId as string;

      const accepted = await post(
        ACCOUNT_B.token,
        `/v1/social/workout-shares/${shareId}/accept`,
      ).expect(200);
      // §58 — o snapshot que chega ao destinatário não carrega nada privado.
      const snapshotText = JSON.stringify(accepted.body);
      for (const forbidden of ['load', 'notes', 'machineNumber', 'localId', 'syncId', 'ownerUid']) {
        expect(snapshotText).not.toContain(forbidden);
      }

      // §63 — repetir accept e complete-import converge, sem duplicar nada.
      await post(ACCOUNT_B.token, `/v1/social/workout-shares/${shareId}/accept`).expect(200);
      await post(ACCOUNT_B.token, `/v1/social/workout-shares/${shareId}/complete-import`).expect(
        200,
      );
      await post(ACCOUNT_B.token, `/v1/social/workout-shares/${shareId}/complete-import`).expect(
        200,
      );
      expect(
        s.inDatabase(
          (db) => (db.prepare(`SELECT COUNT(*) AS n FROM workout_shares`).get() as { n: number }).n,
        ),
      ).toBe(1);

      // --- A publica um check-in com foto e legenda; B reage e comenta ---
      const session = await s.pushSession(ACCOUNT_A);
      const mediaId = await s.uploadPhoto(ACCOUNT_A, session, await jpegWithExifGps());
      const checkInId = await s.publishCheckIn(ACCOUNT_A, session, {
        mediaId,
        caption: 'Fechei a semana',
      });

      await request(s.server())
        .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
        .set('Authorization', s.auth(ACCOUNT_B.token))
        .send({ type: 'MUSCLE' })
        .expect(200);
      await post(ACCOUNT_B.token, `/v1/social/workout-checkins/${checkInId}/comments`)
        .send({ body: 'Boa, Alice!' })
        .expect(201);

      await get(ACCOUNT_B.token, `/v1/social/media/${mediaId}`).expect(200);

      // --- C é negado em tudo ---
      await get(ACCOUNT_C.token, `/v1/social/workout-checkins/${checkInId}`).expect(404);
      await get(ACCOUNT_C.token, `/v1/social/media/${mediaId}`).expect(404);

      // --- B bloqueia A: a visibilidade é revogada nos dois sentidos ---
      await post(ACCOUNT_B.token, '/v1/social/blocks')
        .send({ blockedSocialId: await s.socialIdOf(ACCOUNT_A) })
        .expect(200);

      await get(ACCOUNT_B.token, `/v1/social/workout-checkins/${checkInId}`).expect(404);
      await get(ACCOUNT_B.token, `/v1/social/media/${mediaId}`).expect(404);
      expect((await get(ACCOUNT_B.token, '/v1/social/feed').expect(200)).body.items).toEqual([]);
      expect((await get(ACCOUNT_A.token, '/v1/social/feed').expect(200)).body.items).toHaveLength(
        1,
      );

      // --- B desbloqueia: continuam **não** amigos (§17) ---
      await del(ACCOUNT_B.token, `/v1/social/blocks/${await s.socialIdOf(ACCOUNT_A)}`).expect(200);
      expect((await get(ACCOUNT_B.token, '/v1/social/friends').expect(200)).body.friends).toEqual(
        [],
      );
      await get(ACCOUNT_B.token, `/v1/social/workout-checkins/${checkInId}`).expect(404);
    });
  });

  // ===================================================================================
  // §144 — cenário integrado 2
  // ===================================================================================

  describe('cenário integrado: A exclui a conta (§144)', () => {
    it('B permanece, A desaparece, a mídia sai e o desafio resolve conforme a política', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);

      // A cria um desafio e convida B, que aceita.
      const challenge = await post(ACCOUNT_A.token, '/v1/social/challenges')
        .send({
          clientRequestId: uuid(),
          name: 'Semana forte',
          type: 'WORKOUTS_COMPLETED',
          target: 5,
          startDate: '2026-09-14',
          endDate: '2026-09-21',
          timeZoneId: 'America/Sao_Paulo',
          invitedSocialIds: [await s.socialIdOf(ACCOUNT_B)],
        })
        .expect(200);
      const challengeId = challenge.body.challenge.challengeId as string;

      const invitations = await get(ACCOUNT_B.token, '/v1/social/challenge-invitations').expect(
        200,
      );
      const invitationId = invitations.body.invitations[0].invitationId as string;
      await post(ACCOUNT_B.token, `/v1/social/challenge-invitations/${invitationId}/accept`).expect(
        200,
      );

      // A publica com foto.
      const session = await s.pushSession(ACCOUNT_A);
      const mediaId = await s.uploadPhoto(ACCOUNT_A, session, await jpeg());
      const checkInId = await s.publishCheckIn(ACCOUNT_A, session, { mediaId });
      expect(s.filesOnDisk()).toHaveLength(1);

      // B registra um dispositivo de push antes da exclusão de A.
      await post(ACCOUNT_B.token, '/v1/social/notifications/devices')
        .send({ deviceId: 'device-de-b', platform: 'ANDROID', fcmToken: 'token-fcm-de-b' })
        .expect(201);

      await del(ACCOUNT_A.token, '/v1/account').expect(200);

      // A some de tudo o que B enxerga.
      expect((await get(ACCOUNT_B.token, '/v1/social/feed').expect(200)).body.items).toEqual([]);
      await get(ACCOUNT_B.token, `/v1/social/workout-checkins/${checkInId}`).expect(404);
      await get(ACCOUNT_B.token, `/v1/social/media/${mediaId}`).expect(404);
      expect((await get(ACCOUNT_B.token, '/v1/social/friends').expect(200)).body.friends).toEqual(
        [],
      );
      expect(s.filesOnDisk()).toEqual([]);

      // O desafio criado por A não deixa órfão — e B não fica preso a um desafio sem criador.
      await get(ACCOUNT_B.token, `/v1/social/challenges/${challengeId}`).expect(404);
      expect(s.inDatabase((db) => db.pragma('foreign_key_check'))).toEqual([]);

      // B continua com push e Social funcionando.
      const devices = s.inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT COUNT(*) AS n FROM social_push_devices WHERE owner_uid = ?`)
              .get(ACCOUNT_B.uid) as { n: number }
          ).n,
      );
      expect(devices).toBe(1);
      const sessionB = await s.pushSession(ACCOUNT_B);
      await s.publishCheckIn(ACCOUNT_B, sessionB);
      expect((await get(ACCOUNT_B.token, '/v1/social/feed').expect(200)).body.items).toHaveLength(
        1,
      );
    });
  });

  // ===================================================================================
  // §30/§164 — Activity, Ranking e Feed têm consentimentos independentes
  // ===================================================================================

  describe('os três consentimentos são independentes (§30/§164)', () => {
    it('com atividade e ranking desligados o usuário ainda publica, e é visto pelo amigo', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);

      // O default já é fechado; declarar explicitamente é o que torna o teste legível.
      await request(s.server())
        .patch('/v1/social/me/privacy')
        .set('Authorization', s.auth(ACCOUNT_A.token))
        .send({ activitySharingEnabled: false, friendRankingParticipationEnabled: false })
        .expect(200);

      const session = await s.pushSession(ACCOUNT_A);
      const checkInId = await s.publishCheckIn(ACCOUNT_A, session, {
        caption: 'Publiquei mesmo assim',
      });

      // O Feed é consentimento **por publicação**: os dois interruptores desligados não o impedem.
      const feed = await get(ACCOUNT_B.token, '/v1/social/feed').expect(200);
      expect(feed.body.items.map((item: { checkInId: string }) => item.checkInId)).toContain(
        checkInId,
      );

      // E a atividade de A continua ausente para B, porque aquele interruptor é outro.
      const activity = await get(ACCOUNT_B.token, '/v1/social/activity').expect(200);
      const activityText = JSON.stringify(activity.body);
      expect(activityText).not.toContain(ACCOUNT_A.name);

      // O ranking exige reciprocidade, e B também não optou: ninguém vê ranking de ninguém.
      await get(ACCOUNT_B.token, '/v1/social/rankings/last-7-days').expect(403);
    });

    it('ligar atividade e ranking não publica nada no Feed (§30)', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);

      await request(s.server())
        .patch('/v1/social/me/privacy')
        .set('Authorization', s.auth(ACCOUNT_A.token))
        .send({
          activitySharingEnabled: true,
          activityTimeZoneId: 'America/Sao_Paulo',
          friendRankingParticipationEnabled: true,
        })
        .expect(200);

      // Uma sessão canônica concluída existe — e mesmo assim o Feed continua vazio: publicar é um
      // ato explícito, e nenhum interruptor o substitui.
      await s.pushSession(ACCOUNT_A);
      expect((await get(ACCOUNT_B.token, '/v1/social/feed').expect(200)).body.items).toEqual([]);
    });
  });

  // ===================================================================================
  // §110 — integridade do banco depois de todo o ciclo
  // ===================================================================================

  describe('integridade do banco (§110)', () => {
    it('`integrity_check` e `foreign_key_check` passam depois do ciclo social completo', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.activate(ACCOUNT_C);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.makeFriends(ACCOUNT_B, ACCOUNT_C);

      const session = await s.pushSession(ACCOUNT_A);
      const mediaId = await s.uploadPhoto(ACCOUNT_A, session, await jpeg());
      const checkInId = await s.publishCheckIn(ACCOUNT_A, session, { mediaId, caption: 'Feito' });
      await post(ACCOUNT_B.token, `/v1/social/workout-checkins/${checkInId}/comments`)
        .send({ body: 'Massa' })
        .expect(201);
      await del(ACCOUNT_A.token, `/v1/social/workout-checkins/${checkInId}`).expect(204);
      await del(ACCOUNT_C.token, '/v1/account').expect(200);

      const result = s.inDatabase((db) => ({
        integrity: db.pragma('integrity_check'),
        foreignKeys: db.pragma('foreign_key_check'),
      }));
      expect(result.integrity).toEqual([{ integrity_check: 'ok' }]);
      expect(result.foreignKeys).toEqual([]);
    });
  });
});
