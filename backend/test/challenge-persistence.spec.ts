import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import BetterSqlite3 from 'better-sqlite3';
import request from 'supertest';
import { loadMigrations, runMigrations } from '../src/database/migration-runner';
import { PostgresService } from '../src/database/postgres.service';
import {
  account,
  acceptChallenge,
  befriend,
  createChallenge,
  enableSocial,
  saoPauloInstant,
} from './support/challenge-fixtures';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { configFor, createPostgresSyncDb, createTempDb, MIGRATIONS_DIR, postgresFor, type TempDb } from './support/temp-db';

/**
 * A persistência dos desafios (T17.3 §131–§143/§225).
 *
 * Arquivo real de SQLite, nunca `:memory:` — restart só prova alguma coisa contra o mesmo tipo de
 * arquivo que a produção usa.
 */
describe('Persistência dos desafios', () => {
  let temp: TempDb;

  const igor = account('igor', 'Igor');
  const joao = account('joao', 'João');
  const NOW = saoPauloInstant('2026-09-08T12:00:00');

  const LEGACY_SQLITE_MIGRATIONS_DIR = join(__dirname, '..', 'migrations');

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  // ------------------------------------------------------------------------- migration

  describe('a migration é aditiva (§132)', () => {
    it('sobe sobre a base T17.2 sem tocar em perfil, amizade, privacidade nem T16', () => {
      const sqlitePath = join(temp.directory, 'legacy.db');
      const db = new BetterSqlite3(sqlitePath);
      db.pragma('foreign_keys = ON');
      // O estado de um servidor que ainda não subiu a T17.3.
      runMigrations(
        db,
        loadMigrations(LEGACY_SQLITE_MIGRATIONS_DIR).filter((migration) => migration.version <= 9),
      );

      // Dado das fases anteriores que precisa sobreviver — inclusive o que a T17.3 encosta
      // (perfil e amizade são FK das tabelas novas).
      const insertProfile = (uid: string, socialId: string, code: string, name: string) => {
        db.prepare(
          `INSERT INTO social_profiles
             (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
           VALUES (?, ?, ?, ?, 'ACTIVE', 1, 1)`,
        ).run(uid, socialId, code, name);
        db.prepare(
          `INSERT INTO social_privacy_settings
             (owner_uid, discoverability, friend_requests_enabled, activity_sharing_enabled,
              updated_at)
           VALUES (?, 'FRIEND_CODE_ONLY', 1, 0, 1)`,
        ).run(uid);
        db.prepare(
          `INSERT INTO social_progress_settings
             (owner_uid, share_level, share_consistency_streak, share_weekly_workout_count,
              share_highlighted_achievements, week_time_zone, updated_at)
           VALUES (?, 1, 0, 1, 0, 'America/Sao_Paulo', 1)`,
        ).run(uid);
      };
      insertProfile('uid-a', 'social-a', 'SPK-AAAAAAAA', 'Igor');
      insertProfile('uid-b', 'social-b', 'SPK-BBBBBBBB', 'João');
      db.prepare(
        `INSERT INTO friendships (user_a_uid, user_b_uid, created_at) VALUES ('uid-a','uid-b', 7)`,
      ).run();
      db.prepare(
        `INSERT INTO sync_entities
           (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
            last_server_sequence, payload, payload_hash, origin_device_id, created_at,
            updated_at, deleted)
         VALUES ('uid-a', 'WORKOUT_SESSION', 'sync-1', 1, 5, 9, '{}', 'hash', 'device-1', 2, 3, 0)`,
      ).run();
      db.prepare(
        `INSERT INTO backup_snapshots
           (backup_id, owner_uid, client_backup_id, device_id, backup_schema_version,
            payload_hash, item_count, size_bytes, captured_at, created_at)
         VALUES ('b-1', 'uid-a', 'cb-1', 'device-1', 1, 'hash', 3, 100, 1, 2)`,
      ).run();

      const before = {
        profiles: db.prepare('SELECT * FROM social_profiles ORDER BY owner_uid').all(),
        privacy: db
          .prepare('SELECT * FROM social_privacy_settings ORDER BY owner_uid')
          .all() as Array<Record<string, unknown>>,
        progress: db.prepare('SELECT * FROM social_progress_settings ORDER BY owner_uid').all(),
        friendships: db.prepare('SELECT * FROM friendships').all(),
        entities: db.prepare('SELECT * FROM sync_entities').all(),
        backups: db.prepare('SELECT * FROM backup_snapshots').all(),
      };

      const applied = runMigrations(db, loadMigrations(LEGACY_SQLITE_MIGRATIONS_DIR));

      expect(applied.map((migration) => migration.version)).toEqual([
        10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
      ]);
      expect(applied.map((migration) => migration.name)).toEqual([
        'social_challenges',
        'social_activity_rankings',
        'social_notifications',
        'social_hardening',
        'workout_shares',
        'social_workout_checkins',
        'social_checkin_content',
        'social_checkin_reactions_comments',
        'social_reports_ugc',
        'social_groups',
        'social_interaction_audience',
        'account_deletion_phase',
        'group_invitation_expired',
        'media_input_fingerprint',
      ]);

      // Nada do que existia mudou (§132). `social_id`, `friend_code`, as amizades e os quatro
      // interruptores da T17.2 saem destas migrations exatamente como entraram (além das novas
      // colunas aditivas com defaults).
      expect(db.prepare('SELECT * FROM social_profiles ORDER BY owner_uid').all()).toEqual(
        before.profiles,
      );
      expect(db.prepare('SELECT * FROM social_privacy_settings ORDER BY owner_uid').all()).toEqual(
        before.privacy.map((row) => ({
          ...row,
          activity_time_zone_id: null,
          friend_ranking_participation_enabled: 0,
        })),
      );
      expect(db.prepare('SELECT * FROM social_progress_settings ORDER BY owner_uid').all()).toEqual(
        before.progress,
      );
      expect(db.prepare('SELECT * FROM friendships').all()).toEqual(before.friendships);
      expect(db.prepare('SELECT * FROM sync_entities').all()).toEqual(before.entities);
      expect(db.prepare('SELECT * FROM backup_snapshots').all()).toEqual(before.backups);

      const integrity = db.pragma('integrity_check') as Array<{ integrity_check: string }>;
      expect(integrity[0].integrity_check).toBe('ok');
      expect(db.pragma('foreign_key_check')).toEqual([]);
      db.close();
    });

    it('a migration só cria: nenhum DROP, ALTER, DELETE ou UPDATE (§132)', () => {
      const sql = loadMigrations(LEGACY_SQLITE_MIGRATIONS_DIR).find((m) => m.version === 10)?.sql ?? '';
      const code = sql.replace(/--.*$/gm, '');

      expect(code).toMatch(/CREATE TABLE challenges/);
      expect(code).toMatch(/CREATE TABLE challenge_invitations/);
      expect(code).toMatch(/CREATE TABLE challenge_participants/);
      expect(code).toMatch(/CREATE TABLE challenge_creation_requests/);

      for (const forbidden of [
        /\bDROP\b/i,
        /\bALTER TABLE\b/i,
        /\bDELETE FROM\b/i,
        /\bUPDATE\b/i,
      ]) {
        expect(code).not.toMatch(forbidden);
      }
      // E não toca em nenhuma tabela das fases anteriores, além das FK para `social_profiles`.
      for (const table of [
        'sync_entities',
        'sync_changes',
        'sync_mutations',
        'backup_snapshots',
        'backup_items',
        'social_privacy_settings',
        'social_progress_settings',
        'friendships',
        'friend_requests',
      ]) {
        expect(code).not.toContain(table);
      }
    });
  });

  // ------------------------------------------------------------------------- constraints

  describe('as constraints existem no banco, e não só no código (§40/§41/§43)', () => {
    it('um participante por pessoa, um criador por desafio, um convite por convidado', async () => {
      const postgres = postgresFor(configFor(temp.path));
      await postgres.initialize();
      const db = createPostgresSyncDb(temp.schema);

      const profile = (uid: string, socialId: string, code: string) => {
        db.prepare(
          `INSERT INTO social_profiles
             (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
           VALUES (?, ?, ?, 'Nome', 'ACTIVE', 1, 1)`,
        ).run(uid, socialId, code);
      };
      profile('uid-a', 'social-a', 'SPK-AAAAAAAA');
      profile('uid-b', 'social-b', 'SPK-BBBBBBBB');

      db.prepare(
        `INSERT INTO challenges
           (challenge_id, creator_uid, name, type, target, start_date, end_date, time_zone_id,
            starts_at, ends_at_exclusive, lifecycle, cancelled_at, created_at, updated_at)
         VALUES ('c-1', 'uid-a', 'Desafio', 'WORKOUTS_COMPLETED', 12, '2026-09-10', '2026-10-09',
                 'America/Sao_Paulo', 100, 200, 'OPEN', NULL, 1, 1)`,
      ).run();

      const addParticipant = (uid: string, role: string) =>
        db
          .prepare(
            `INSERT INTO challenge_participants
               (challenge_id, participant_uid, role, status, joined_at, left_at)
             VALUES ('c-1', ?, ?, 'JOINED', 1, NULL)`,
          )
          .run(uid, role);

      addParticipant('uid-a', 'CREATOR');
      // Mesma pessoa duas vezes: chave primária.
      expect(() => addParticipant('uid-a', 'MEMBER')).toThrow(/UNIQUE|PRIMARY/i);
      // Um segundo criador: índice único parcial.
      expect(() => addParticipant('uid-b', 'CREATOR')).toThrow(/UNIQUE/i);
      // Membro normal passa.
      addParticipant('uid-b', 'MEMBER');

      const addInvitation = (id: string, recipient: string) =>
        db
          .prepare(
            `INSERT INTO challenge_invitations
               (invitation_id, challenge_id, inviter_uid, recipient_uid, status, created_at,
                updated_at)
             VALUES (?, 'c-1', 'uid-a', ?, 'PENDING', 1, 1)`,
          )
          .run(id, recipient);

      addInvitation('i-1', 'uid-b');
      // O mesmo convidado, no mesmo desafio: `UNIQUE (challenge_id, recipient_uid)`.
      expect(() => addInvitation('i-2', 'uid-b')).toThrow(/UNIQUE/i);
      // Convite para si mesmo: `CHECK`.
      expect(() => addInvitation('i-3', 'uid-a')).toThrow(/CHECK/i);

      await postgres.close();
    });

    it('tipo desconhecido, meta não positiva e estado inconsistente são irrepresentáveis', async () => {
      const postgres = postgresFor(configFor(temp.path));
      await postgres.initialize();
      const db = createPostgresSyncDb(temp.schema);

      db.prepare(
        `INSERT INTO social_profiles
           (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
         VALUES ('uid-a', 'social-a', 'SPK-AAAAAAAA', 'Nome', 'ACTIVE', 1, 1)`,
      ).run();

      const insert = (overrides: Partial<Record<string, unknown>>) => {
        const row = {
          challenge_id: `c-${Math.random()}`,
          creator_uid: 'uid-a',
          name: 'Desafio',
          type: 'WORKOUTS_COMPLETED',
          target: 12,
          start_date: '2026-09-10',
          end_date: '2026-10-09',
          time_zone_id: 'America/Sao_Paulo',
          starts_at: 100,
          ends_at_exclusive: 200,
          lifecycle: 'OPEN',
          cancelled_at: null,
          created_at: 1,
          updated_at: 1,
          ...overrides,
        };
        db.prepare(
          `INSERT INTO challenges
             (challenge_id, creator_uid, name, type, target, start_date, end_date, time_zone_id,
              starts_at, ends_at_exclusive, lifecycle, cancelled_at, created_at, updated_at)
           VALUES (@challenge_id, @creator_uid, @name, @type, @target, @start_date, @end_date,
                   @time_zone_id, @starts_at, @ends_at_exclusive, @lifecycle, @cancelled_at,
                   @created_at, @updated_at)`,
        ).run(row);
      };

      // Um tipo sem fonte canônica não pode existir como linha.
      expect(() => insert({ type: 'TOTAL_VOLUME' })).toThrow(/CHECK/i);
      expect(() => insert({ type: 'XP_GAINED' })).toThrow(/CHECK/i);
      expect(() => insert({ target: 0 })).toThrow(/CHECK/i);
      expect(() => insert({ target: -1 })).toThrow(/CHECK/i);
      // Janela invertida.
      expect(() => insert({ starts_at: 300, ends_at_exclusive: 200 })).toThrow(/CHECK/i);
      // Cancelado sem data, e data sem cancelamento: os dois são meio-cancelamento.
      expect(() => insert({ lifecycle: 'CANCELLED', cancelled_at: null })).toThrow(/CHECK/i);
      expect(() => insert({ lifecycle: 'OPEN', cancelled_at: 5 })).toThrow(/CHECK/i);
      // E o caminho válido continua passando.
      insert({});

      await postgres.close();
    });

    it('não existe coluna de pontuação em nenhuma tabela de desafio (§82/§134)', async () => {
      const postgres = postgresFor(configFor(temp.path));
      await postgres.initialize();
      const db = createPostgresSyncDb(temp.schema);

      const tables = [
        'challenges',
        'challenge_invitations',
        'challenge_participants',
        'challenge_creation_requests',
      ];
      for (const table of tables) {
        const columns = (
          db.pragma(`table_info(${table})`) as Array<{ name: string }>
        ).map((column) => column.name);

        for (const forbidden of ['score', 'progress', 'points', 'rank', 'winner', 'count']) {
          expect({ table, forbidden, present: columns.includes(forbidden) }).toEqual({
            table,
            forbidden,
            present: false,
          });
        }
      }

      // E a tabela que guardaria um contador não existe.
      const progressTable = db
        .prepare(
          `SELECT name FROM sqlite_master WHERE type='table' AND name = 'challenge_progress'`,
        )
        .get();
      expect(progressTable).toBeUndefined();

      await postgres.close();
    });
  });

  // ------------------------------------------------------------------------- restart

  describe('restart do servidor (§225)', () => {
    it('desafios, convites e participações sobrevivem', async () => {
      const verifier = new FakeAuthTokenVerifier()
        .accept(igor.token, { uid: igor.uid })
        .accept(joao.token, { uid: joao.uid });

      let app: INestApplication = await createTestApp(
        configFor(temp.path),
        verifier,
        undefined,
        new FakeClock(NOW),
      );

      await enableSocial(app, igor);
      await enableSocial(app, joao);
      await befriend(app, igor, joao);
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);

      const before = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', `Bearer ${igor.token}`)
        .expect(200);

      // O processo cai e sobe de novo, sobre o **mesmo arquivo**.
      await app.close();
      app = await createTestApp(configFor(temp.path), verifier, undefined, new FakeClock(NOW));

      const after = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', `Bearer ${igor.token}`)
        .expect(200);

      expect(after.body).toEqual(before.body);
      expect(after.body.challenge.participantCount).toBe(2);
      expect(after.body.challenge.status).toBe('UPCOMING');

      await app.close();
    });

    it('a idempotência de criação sobrevive ao restart (§188)', async () => {
      const verifier = new FakeAuthTokenVerifier()
        .accept(igor.token, { uid: igor.uid })
        .accept(joao.token, { uid: joao.uid });

      let app: INestApplication = await createTestApp(
        configFor(temp.path),
        verifier,
        undefined,
        new FakeClock(NOW),
      );

      await enableSocial(app, igor);
      await enableSocial(app, joao);
      await befriend(app, igor, joao);

      const body = {
        clientRequestId: 'req-que-sobrevive',
        name: '12 treinos',
        type: 'WORKOUTS_COMPLETED',
        target: 12,
        startDate: '2026-09-10',
        endDate: '2026-10-09',
        timeZoneId: 'America/Sao_Paulo',
        invitedSocialIds: [joao.socialId],
      };

      const first = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', `Bearer ${igor.token}`)
        .send(body)
        .expect(200);

      // O ledger está no banco, e não em memória: reiniciar não o esquece.
      await app.close();
      app = await createTestApp(configFor(temp.path), verifier, undefined, new FakeClock(NOW));

      const retry = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', `Bearer ${igor.token}`)
        .send(body)
        .expect(200);

      expect(retry.body.result).toBe('ALREADY_CREATED');
      expect(retry.body.challenge.challengeId).toBe(first.body.challenge.challengeId);

      const list = await request(app.getHttpServer())
        .get('/v1/social/challenges')
        .set('Authorization', `Bearer ${igor.token}`)
        .expect(200);
      expect(list.body.total).toBe(1);

      await app.close();
    });
  });

  // ------------------------------------------------------------------------- fronteiras

  describe('o desafio não entra no sync nem no backup (§140–§142)', () => {
    it('não existe entityType CHALLENGE, e nada de desafio vai para sync_entities', async () => {
      const verifier = new FakeAuthTokenVerifier()
        .accept(igor.token, { uid: igor.uid })
        .accept(joao.token, { uid: joao.uid });
      const app = await createTestApp(
        configFor(temp.path),
        verifier,
        undefined,
        new FakeClock(NOW),
      );

      await enableSocial(app, igor);
      await enableSocial(app, joao);
      await befriend(app, igor, joao);
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);

      const db = createPostgresSyncDb(temp.schema);

      // Criar e aceitar um desafio não escreveu **uma linha** no protocolo de sync.
      for (const table of ['sync_entities', 'sync_changes', 'sync_mutations']) {
        const count = db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get() as {
          n: number | string;
        };
        expect({ table, n: Number(count.n) }).toEqual({ table, n: 0 });
      }
      // Nem no backup.
      const backups = db
        .prepare('SELECT COUNT(*) AS n FROM backup_snapshots')
        .get() as { n: number | string };
      expect(Number(backups.n)).toBe(0);

      // E o servidor recusa um push que tente declarar um desafio como entidade de sync.
      const push = await request(app.getHttpServer())
        .post('/v1/sync/push')
        .set('Authorization', `Bearer ${igor.token}`)
        .set('Content-Type', 'application/json')
        .send(
          JSON.stringify({
            deviceId: 'device-a',
            mutations: [
              {
                clientMutationId: '11111111-2222-4333-8444-555555555555',
                entityType: 'CHALLENGE',
                entitySyncId: challengeId,
                entitySchemaVersion: 1,
                operation: 'UPSERT',
                baseRevision: null,
                payload: { syncId: challengeId },
              },
            ],
          }),
        )
        .expect(200);

      expect(push.body.results[0]).toMatchObject({ status: 'UNSUPPORTED' });

      await app.close();
    });
  });
});
