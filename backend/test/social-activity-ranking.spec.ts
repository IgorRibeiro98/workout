import { INestApplication } from '@nestjs/common';
import BetterSqlite3 from 'better-sqlite3';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { pushBody, sessionPayload, uuid } from './support/sync-fixtures';
import { canonicalPair } from '../src/modules/social/friendship.repository';
import { DAY_MS } from '../src/modules/social/social-time';

const TOKEN_A = 'token-da-conta-a';
const UID_A = 'uid-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_B = 'uid-da-conta-b';
const TOKEN_C = 'token-da-conta-c';
const UID_C = 'uid-da-conta-c';
const TOKEN_D = 'token-da-conta-d';
const UID_D = 'uid-da-conta-d';

/** Terça-feira, 15h UTC em 2026-09-08 (12h em America/Sao_Paulo). */
const NOW = Date.parse('2026-09-08T15:00:00Z');
const TZ = 'America/Sao_Paulo';

describe('T17.4 — Atividade dos amigos e rankings contextuais', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path),
      new FakeAuthTokenVerifier()
        .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
        .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' })
        .accept(TOKEN_C, { uid: UID_C, email: 'c@example.com' })
        .accept(TOKEN_D, { uid: UID_D, email: 'd@example.com' }),
    );
  });

  afterEach(async () => {
    jest.restoreAllMocks();
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();

  const activate = (token: string, displayName: string) =>
    request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', `Bearer ${token}`)
      .send({ displayName });

  const patchPrivacy = (token: string, body: object) =>
    request(server())
      .patch('/v1/social/me/privacy')
      .set('Authorization', `Bearer ${token}`)
      .send(body);

  const makeFriends = async (token1: string, token2: string) => {
    const p2 = await request(server())
      .get('/v1/social/me')
      .set('Authorization', `Bearer ${token2}`);

    const sent = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', `Bearer ${token1}`)
      .send({ socialId: p2.body.profile.socialId });

    await request(server())
      .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
      .set('Authorization', `Bearer ${token2}`)
      .expect(200);
  };

  const pushSession = (token: string, startedAt: number) => {
    const syncId = uuid();
    return request(server())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${token}`)
      .set('Content-Type', 'application/json')
      .send(
        pushBody([
          {
            entityType: 'WORKOUT_SESSION',
            entitySyncId: syncId,
            payload: sessionPayload(syncId, { startedAt, finishedAt: startedAt + 3_600_000 }),
          },
        ]),
      );
  };

  const freezeClock = () => jest.spyOn(Date, 'now').mockReturnValue(NOW);

  // ------------------------------------------------------------------ Autenticação e Habilitação

  describe('autenticação e guards', () => {
    it('GET /v1/social/activity exige token Bearer', async () => {
      const res = await request(server()).get('/v1/social/activity');
      expect(res.status).toBe(401);
    });

    it('GET /v1/social/rankings/last-7-days exige token Bearer', async () => {
      const res = await request(server()).get('/v1/social/rankings/last-7-days');
      expect(res.status).toBe(401);
    });

    it('conta sem perfil social recebe 404 SOCIAL_NOT_ENABLED', async () => {
      const actRes = await request(server())
        .get('/v1/social/activity')
        .set('Authorization', `Bearer ${TOKEN_A}`);
      expect(actRes.status).toBe(404);
      expect(actRes.body.error.code).toBe('SOCIAL_NOT_ENABLED');

      const rankRes = await request(server())
        .get('/v1/social/rankings/last-7-days')
        .set('Authorization', `Bearer ${TOKEN_A}`);
      expect(rankRes.status).toBe(404);
      expect(rankRes.body.error.code).toBe('SOCIAL_NOT_ENABLED');
    });
  });

  // ------------------------------------------------------------------ Atividade dos amigos

  describe('feed de atividade dos amigos (/v1/social/activity)', () => {
    it('retorna lista vazia quando o usuário não possui amigos ou nenhum amigo treinou', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');

      const res = await request(server())
        .get('/v1/social/activity')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      expect(res.body).toEqual({ items: [] });
    });

    it('não inclui a própria atividade do chamador no feed de amigos', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await patchPrivacy(TOKEN_A, { activitySharingEnabled: true, activityTimeZoneId: TZ });
      await pushSession(TOKEN_A, NOW - 1000);

      const res = await request(server())
        .get('/v1/social/activity')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      expect(res.body.items).toEqual([]);
    });

    it('respeita estritamente o consentimento do amigo: não mostra treinos se activitySharingEnabled for false', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      // Bob treina hoje mas NÃO compartilha atividade
      await pushSession(TOKEN_B, NOW - 1000);

      const res = await request(server())
        .get('/v1/social/activity')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      expect(res.body.items).toEqual([]);
    });

    it('projeta atividade corretamente quando amigo ativa consentimento e fuso horário', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      const bProfile = (await activate(TOKEN_B, 'Bob')).body.profile;
      await makeFriends(TOKEN_A, TOKEN_B);

      await patchPrivacy(TOKEN_B, { activitySharingEnabled: true, activityTimeZoneId: TZ });

      // Bob treina hoje (1h atrás)
      await pushSession(TOKEN_B, NOW - 3600 * 1000);

      const res = await request(server())
        .get('/v1/social/activity')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      expect(res.body.items).toHaveLength(1);
      expect(res.body.items[0]).toEqual({
        type: 'TRAINING_DAY',
        actor: {
          socialId: bProfile.socialId,
          displayName: 'Bob',
        },
        daysAgo: 0,
      });
    });

    it('deduplica múltiplos treinos do mesmo amigo no mesmo dia civil', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      await patchPrivacy(TOKEN_B, { activitySharingEnabled: true, activityTimeZoneId: TZ });

      // Bob treina 2 vezes no mesmo dia
      await pushSession(TOKEN_B, NOW - 3600 * 1000);
      await pushSession(TOKEN_B, NOW - 7200 * 1000);

      const res = await request(server())
        .get('/v1/social/activity')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      expect(res.body.items).toHaveLength(1);
      expect(res.body.items[0].daysAgo).toBe(0);
    });

    it('calcula daysAgo corretamente na janela de 14 dias (0..13) e ignora treinos de 14+ dias atrás', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      await patchPrivacy(TOKEN_B, { activitySharingEnabled: true, activityTimeZoneId: TZ });

      // Ontem: 1 dia atrás
      await pushSession(TOKEN_B, NOW - DAY_MS);
      // 5 dias atrás
      await pushSession(TOKEN_B, NOW - 5 * DAY_MS);
      // 13 dias atrás (no limite dos 14 dias: 0..13)
      await pushSession(TOKEN_B, NOW - 13 * DAY_MS);
      // 15 dias atrás (fora da janela)
      await pushSession(TOKEN_B, NOW - 15 * DAY_MS);

      const res = await request(server())
        .get('/v1/social/activity')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      const daysAgoList = res.body.items.map((it: { daysAgo: number }) => it.daysAgo);
      expect(daysAgoList).toEqual([1, 5, 13]);
    });

    it('ordena por daysAgo ASC (mais recente primeiro) e desempata por displayName ASC', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Carlos');
      await activate(TOKEN_C, 'Bruna');
      await makeFriends(TOKEN_A, TOKEN_B);
      await makeFriends(TOKEN_A, TOKEN_C);

      await patchPrivacy(TOKEN_B, { activitySharingEnabled: true, activityTimeZoneId: TZ });
      await patchPrivacy(TOKEN_C, { activitySharingEnabled: true, activityTimeZoneId: TZ });

      // Ambos treinaram hoje
      await pushSession(TOKEN_B, NOW - 3600 * 1000);
      await pushSession(TOKEN_C, NOW - 7200 * 1000);

      const res = await request(server())
        .get('/v1/social/activity')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      expect(res.body.items).toHaveLength(2);
      expect(res.body.items[0].actor.displayName).toBe('Bruna');
      expect(res.body.items[1].actor.displayName).toBe('Carlos');
    });

    it('nunca vaza UID, e-mail, friendCode nem payload de treino no DTO de atividade', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      const bProfile = (await activate(TOKEN_B, 'Bob')).body.profile;
      await makeFriends(TOKEN_A, TOKEN_B);
      await patchPrivacy(TOKEN_B, { activitySharingEnabled: true, activityTimeZoneId: TZ });
      await pushSession(TOKEN_B, NOW - 1000);

      const res = await request(server())
        .get('/v1/social/activity')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      const item = res.body.items[0];

      expect(Object.keys(item).sort()).toEqual(['actor', 'daysAgo', 'type']);
      expect(Object.keys(item.actor).sort()).toEqual(['displayName', 'socialId']);

      const rawJson = JSON.stringify(res.body);
      expect(rawJson).not.toContain(UID_B);
      expect(rawJson).not.toContain('b@example.com');
      expect(rawJson).not.toContain(bProfile.friendCode);
      expect(rawJson).not.toContain('payload');
      expect(rawJson).not.toContain('startedAt');
    });
  });

  // ------------------------------------------------------------------ Ranking contextual

  describe('ranking contextual de 7 dias (/v1/social/rankings/last-7-days)', () => {
    it('retorna 403 RANKING_NOT_ENABLED se o chamador não optou por participar', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');

      const res = await request(server())
        .get('/v1/social/rankings/last-7-days')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(403);
      expect(res.body.error.code).toBe('RANKING_NOT_ENABLED');
    });

    it('retorna 200 com o próprio chamador no ranking quando habilitado', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await patchPrivacy(TOKEN_A, { friendRankingParticipationEnabled: true });

      const res = await request(server())
        .get('/v1/social/rankings/last-7-days')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      expect(res.body.type).toBe('WORKOUTS_COMPLETED_LAST_7_DAYS');
      expect(res.body.participantCount).toBe(1);
      expect(res.body.entries).toHaveLength(1);
      expect(res.body.entries[0]).toMatchObject({
        displayName: 'Alice',
        score: 0,
        rank: 1,
        isCurrentUser: true,
      });
    });

    it('inclui apenas amigos diretos com friendRankingParticipationEnabled = true', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await activate(TOKEN_C, 'Carol');
      await makeFriends(TOKEN_A, TOKEN_B);
      await makeFriends(TOKEN_A, TOKEN_C);

      await patchPrivacy(TOKEN_A, { friendRankingParticipationEnabled: true });
      // Bob participa; Carol NÃO participa
      await patchPrivacy(TOKEN_B, { friendRankingParticipationEnabled: true });

      await pushSession(TOKEN_B, NOW - 3600 * 1000);
      await pushSession(TOKEN_C, NOW - 3600 * 1000);

      const res = await request(server())
        .get('/v1/social/rankings/last-7-days')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      expect(res.body.participantCount).toBe(2);
      const names = res.body.entries.map((e: { displayName: string }) => e.displayName);
      expect(names).toContain('Bob');
      expect(names).toContain('Alice');
      expect(names).not.toContain('Carol');
    });

    it('conta apenas treinos dentro da janela móvel de 7 dias [now - 7d, now]', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);

      await patchPrivacy(TOKEN_A, { friendRankingParticipationEnabled: true });
      await patchPrivacy(TOKEN_B, { friendRankingParticipationEnabled: true });

      // Treino dentro dos 7 dias (2 dias atrás)
      await pushSession(TOKEN_B, NOW - 2 * DAY_MS);
      // Treino dentro dos 7 dias (6 dias atrás)
      await pushSession(TOKEN_B, NOW - 6 * DAY_MS);
      // Treino fora dos 7 dias (8 dias atrás)
      await pushSession(TOKEN_B, NOW - 8 * DAY_MS);

      const res = await request(server())
        .get('/v1/social/rankings/last-7-days')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      const bobEntry = res.body.entries.find(
        (e: { displayName: string; score: number }) => e.displayName === 'Bob',
      );
      expect(bobEntry.score).toBe(2);
    });

    it('calcula empates corretamente usando competition ranking (1, 1, 3)', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await activate(TOKEN_C, 'Carol');
      await makeFriends(TOKEN_A, TOKEN_B);
      await makeFriends(TOKEN_A, TOKEN_C);

      await patchPrivacy(TOKEN_A, { friendRankingParticipationEnabled: true });
      await patchPrivacy(TOKEN_B, { friendRankingParticipationEnabled: true });
      await patchPrivacy(TOKEN_C, { friendRankingParticipationEnabled: true });

      // Alice e Bob fazem 2 treinos cada; Carol faz 1 treino
      await pushSession(TOKEN_A, NOW - 1 * DAY_MS);
      await pushSession(TOKEN_A, NOW - 2 * DAY_MS);
      await pushSession(TOKEN_B, NOW - 1 * DAY_MS);
      await pushSession(TOKEN_B, NOW - 2 * DAY_MS);
      await pushSession(TOKEN_C, NOW - 1 * DAY_MS);

      const res = await request(server())
        .get('/v1/social/rankings/last-7-days')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      const entries = res.body.entries;
      expect(entries).toHaveLength(3);

      // Empate em 1º: Alice e Bob têm rank 1
      expect(entries[0].rank).toBe(1);
      expect(entries[0].score).toBe(2);
      expect(entries[1].rank).toBe(1);
      expect(entries[1].score).toBe(2);

      // Carol fica com rank 3 (salto de competição: 1, 1, 3)
      expect(entries[2].rank).toBe(3);
      expect(entries[2].score).toBe(1);
      expect(entries[2].displayName).toBe('Carol');
    });

    it('não vaza dados sensíveis (UIDs, e-mails, friendCodes, treinos brutos)', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await patchPrivacy(TOKEN_A, { friendRankingParticipationEnabled: true });

      const res = await request(server())
        .get('/v1/social/rankings/last-7-days')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      const entry = res.body.entries[0];
      expect(Object.keys(entry).sort()).toEqual([
        'displayName',
        'isCurrentUser',
        'rank',
        'score',
        'socialId',
      ]);

      const rawJson = JSON.stringify(res.body);
      expect(rawJson).not.toContain(UID_A);
      expect(rawJson).not.toContain('a@example.com');
    });

    it('permite que o usuário visualize sua posição mesmo quando estiver além do top 50', async () => {
      freezeClock();
      await activate(TOKEN_A, 'Alice');
      await patchPrivacy(TOKEN_A, { friendRankingParticipationEnabled: true });

      // Inserir 54 amigos diretamente no SQLite para testar escala de 55 participantes
      const db = new BetterSqlite3(temp.path);
      const insertProfile = db.prepare(
        `INSERT INTO social_profiles (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'ACTIVE', 1, 1)`,
      );
      const insertPrivacy = db.prepare(
        `INSERT INTO social_privacy_settings (owner_uid, discoverability, friend_requests_enabled, activity_sharing_enabled, activity_time_zone_id, friend_ranking_participation_enabled, updated_at)
         VALUES (?, 'FRIEND_CODE_ONLY', 1, 0, NULL, 1, 1)`,
      );
      const insertFriendship = db.prepare(
        `INSERT INTO friendships (user_a_uid, user_b_uid, created_at)
         VALUES (?, ?, 1)`,
      );
      const insertSync = db.prepare(
        `INSERT INTO sync_entities (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision, last_server_sequence, payload, payload_hash, origin_device_id, deleted, created_at, updated_at)
         VALUES (?, 'WORKOUT_SESSION', ?, 1, 1, 1, ?, 'hash', 'test-device', 0, 1, 1)`,
      );

      for (let i = 1; i <= 54; i++) {
        const friendUid = `uid-friend-${i.toString().padStart(3, '0')}`;
        const socialId = `social-friend-${i.toString().padStart(3, '0')}`;
        const friendCode = `SPK-${i.toString().padStart(8, '0')}`;
        const displayName = `Friend ${i.toString().padStart(3, '0')}`;

        insertProfile.run(friendUid, socialId, friendCode, displayName);
        insertPrivacy.run(friendUid);

        const [low, high] = canonicalPair(UID_A, friendUid);
        insertFriendship.run(low, high);

        // Cada amigo concluiu 5 treinos na semana
        for (let s = 1; s <= 5; s++) {
          const syncId = `sess-${i}-${s}`;
          const payload = JSON.stringify(
            sessionPayload(syncId, { startedAt: NOW - s * 3600 * 1000 }),
          );
          insertSync.run(friendUid, syncId, payload);
        }
      }

      // Alice concluiu apenas 1 treino na semana (posição 55)
      await pushSession(TOKEN_A, NOW - 3600 * 1000);

      const res = await request(server())
        .get('/v1/social/rankings/last-7-days')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(res.status).toBe(200);
      expect(res.body.type).toBe('WORKOUTS_COMPLETED_LAST_7_DAYS');
      expect(res.body.participantCount).toBe(55);
      // Top 50 amigos + 1 entrada para o próprio usuário (Alice)
      expect(res.body.entries).toHaveLength(51);

      // As 50 primeiras posições são dos amigos (isCurrentUser = false)
      for (let i = 0; i < 50; i++) {
        expect(res.body.entries[i].isCurrentUser).toBe(false);
        expect(res.body.entries[i].rank).toBe(1); // Todos empatados com 5 treinos
        expect(res.body.entries[i].score).toBe(5);
      }

      // A 51ª entrada é a própria Alice fora do top 50, com sua posição correta
      const aliceEntry = res.body.entries[50];
      expect(aliceEntry).toMatchObject({
        displayName: 'Alice',
        score: 1,
        rank: 55, // Salto de competição: 54 empatados em 1º -> Alice é 55ª
        isCurrentUser: true,
      });
    });
  });
});
