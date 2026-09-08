import BetterSqlite3, { type Database } from 'better-sqlite3';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { loadMigrations, runMigrations } from '../src/database/migration-runner';
import { canonicalPair } from '../src/modules/social/friendship.repository';
import { configFor, createTempDb, MIGRATIONS_DIR, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';

const TOKEN_A = 'token-a';
const UID_A = 'uid-a';
const TOKEN_B = 'token-b';
const UID_B = 'uid-b';

/**
 * A persistência do grafo social (T17.1).
 *
 * Arquivo real de SQLite, nunca `:memory:`: reinício só prova alguma coisa contra o mesmo tipo de
 * arquivo que a produção usa.
 *
 * O que este arquivo protege é a diferença entre "o serviço toma cuidado" e "o banco não consegue
 * representar o contrário". Amizade duplicada, amizade invertida, amizade consigo mesmo e dois
 * pedidos pendentes iguais são recusados aqui **sem passar pelo serviço** — porque um dia alguém
 * vai escrever um caminho novo, e a garantia precisa continuar valendo nele.
 */
describe('Persistência do grafo social', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  /** Um banco com todas as migrations e dois perfis sociais prontos. */
  const seededDb = (): Database => {
    const db = new BetterSqlite3(temp.path);
    db.pragma('foreign_keys = ON');
    runMigrations(db, loadMigrations(MIGRATIONS_DIR));

    const insertProfile = db.prepare(
      `INSERT INTO social_profiles
         (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
       VALUES (?, ?, ?, ?, 'ACTIVE', 1, 1)`,
    );
    insertProfile.run(UID_A, 'social-a', 'SPK-AAAAAAAA', 'Igor');
    insertProfile.run(UID_B, 'social-b', 'SPK-BBBBBBBB', 'João');
    return db;
  };

  // ------------------------------------------------------------------------- migration

  describe('a migration do grafo é aditiva', () => {
    it('sobe sobre a base T17.0 sem tocar em perfil, privacidade nem dado da T16', () => {
      const db = new BetterSqlite3(temp.path);
      db.pragma('foreign_keys = ON');
      const all = loadMigrations(MIGRATIONS_DIR);
      runMigrations(
        db,
        all.filter((migration) => migration.version <= 7),
      );

      db.prepare(
        `INSERT INTO social_profiles
           (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
         VALUES (?, 'social-a', 'SPK-AAAAAAAA', 'Igor', 'ACTIVE', 10, 11)`,
      ).run(UID_A);
      db.prepare(
        `INSERT INTO social_privacy_settings
           (owner_uid, discoverability, friend_requests_enabled, activity_sharing_enabled,
            updated_at)
         VALUES (?, 'FRIEND_CODE_ONLY', 1, 0, 12)`,
      ).run(UID_A);
      db.prepare(
        `INSERT INTO backup_snapshots
           (backup_id, owner_uid, client_backup_id, device_id, backup_schema_version,
            payload_hash, item_count, size_bytes, captured_at, created_at)
         VALUES ('b-1', ?, 'cb-1', 'device-1', 1, 'hash', 3, 100, 1, 2)`,
      ).run(UID_A);

      const beforeProfiles = db.prepare('SELECT * FROM social_profiles').all();
      const beforePrivacy = db.prepare('SELECT * FROM social_privacy_settings').all() as Array<
        Record<string, unknown>
      >;
      const beforeBackups = db.prepare('SELECT * FROM backup_snapshots').all();

      const applied = runMigrations(db, all);

      // A `0009` (T17.2), `0010` (T17.3), `0011` (T17.4) e `0012` (T17.5) sobem junto e são igualmente aditivas:
      // criam `social_progress_settings`, tabelas de desafio, configurações de atividade/ranking
      // e notificações push, sem tocar em perfil, amizade nem em nada da T16.
      expect(applied.map((migration) => migration.version)).toEqual([8, 9, 10, 11, 12, 13]);
      expect(applied.map((migration) => migration.name)).toEqual([
        'friend_graph',
        'social_progress_profile',
        'social_challenges',
        'social_activity_rankings',
        'social_notifications',
        'social_hardening',
      ]);
      expect(db.prepare('SELECT * FROM social_profiles').all()).toEqual(beforeProfiles);
      expect(db.prepare('SELECT * FROM social_privacy_settings').all()).toEqual(
        beforePrivacy.map((row) => ({
          ...row,
          activity_time_zone_id: null,
          friend_ranking_participation_enabled: 0,
        })),
      );
      expect(db.prepare('SELECT * FROM backup_snapshots').all()).toEqual(beforeBackups);

      const integrity = db.pragma('integrity_check') as Array<{ integrity_check: string }>;
      expect(integrity[0].integrity_check).toBe('ok');
      expect(db.pragma('foreign_key_check')).toEqual([]);
      db.close();
    });

    it('a migration só cria: nenhum DROP, DELETE, ALTER ou UPDATE', () => {
      const migration = loadMigrations(MIGRATIONS_DIR).find((entry) => entry.version === 8);
      // Comentário fora: a documentação da migration cita o que ela não faz, e proibir a menção em
      // prosa apagaria a explicação junto com o defeito.
      const sql = (migration?.sql ?? '').replace(/^\s*--.*$/gm, '').toUpperCase();

      expect(sql).toContain('CREATE TABLE FRIEND_REQUESTS');
      expect(sql).toContain('CREATE TABLE FRIENDSHIPS');
      // Formas de comando, e não palavras soltas: `ON DELETE CASCADE` é uma **declaração** de
      // integridade referencial na criação da tabela, e não uma exclusão de dado.
      for (const destructive of [
        'DROP TABLE',
        'DROP INDEX',
        'DELETE FROM',
        'ALTER TABLE',
        'UPDATE ',
        'TRUNCATE',
        'INSERT INTO',
      ]) {
        expect({ destructive, present: sql.includes(destructive) }).toEqual({
          destructive,
          present: false,
        });
      }
      // E nenhuma tabela da T16 é sequer nomeada.
      for (const table of ['SYNC_ENTITIES', 'SYNC_CHANGES', 'BACKUP_SNAPSHOTS', 'BACKUP_ITEMS']) {
        expect(sql).not.toContain(table);
      }
    });
  });

  // ------------------------------------------------------------------------- constraints

  describe('o banco recusa o que o produto não pode representar', () => {
    const insertFriendship = (db: Database, uidA: string, uidB: string) =>
      db
        .prepare(`INSERT INTO friendships (user_a_uid, user_b_uid, created_at) VALUES (?, ?, 1)`)
        .run(uidA, uidB);

    it('amizade duplicada A-B é impossível', () => {
      const db = seededDb();
      const [a, b] = canonicalPair(UID_A, UID_B);

      insertFriendship(db, a, b);

      expect(() => insertFriendship(db, a, b)).toThrow(/UNIQUE|PRIMARY/i);
      expect(db.prepare('SELECT COUNT(*) AS n FROM friendships').get()).toEqual({ n: 1 });
      db.close();
    });

    it('amizade invertida B-A é impossível — não existe outra forma de escrever o par', () => {
      const db = seededDb();
      const [a, b] = canonicalPair(UID_A, UID_B);
      insertFriendship(db, a, b);

      // O `CHECK (user_a_uid < user_b_uid)` recusa a forma invertida em vez de aceitá-la como uma
      // linha nova. Sem ele, "A-B" e "B-A" seriam duas amizades para a mesma relação.
      expect(() => insertFriendship(db, b, a)).toThrow(/CHECK/i);
      expect(db.prepare('SELECT COUNT(*) AS n FROM friendships').get()).toEqual({ n: 1 });
      db.close();
    });

    it('amizade consigo mesmo é impossível', () => {
      const db = seededDb();
      expect(() => insertFriendship(db, UID_A, UID_A)).toThrow(/CHECK/i);
      db.close();
    });

    it('amizade com quem não tem perfil social é impossível', () => {
      const db = seededDb();
      expect(() => insertFriendship(db, UID_A, 'uid-sem-perfil')).toThrow(/FOREIGN KEY|CHECK/i);
      db.close();
    });

    it('dois pedidos pendentes de A para B são impossíveis', () => {
      const db = seededDb();
      const insert = db.prepare(
        `INSERT INTO friend_requests
           (request_id, requester_uid, recipient_uid, status, created_at, updated_at)
         VALUES (?, ?, ?, 'PENDING', 1, 1)`,
      );

      insert.run('req-1', UID_A, UID_B);

      expect(() => insert.run('req-2', UID_A, UID_B)).toThrow(/UNIQUE/i);
      db.close();
    });

    it('mas um pedido resolvido libera a vaga para um novo', () => {
      const db = seededDb();
      db.prepare(
        `INSERT INTO friend_requests
           (request_id, requester_uid, recipient_uid, status, created_at, updated_at)
         VALUES ('req-1', ?, ?, 'REJECTED', 1, 1)`,
      ).run(UID_A, UID_B);

      // O índice único é parcial: só `PENDING` ocupa a vaga. É isso que permite pedir de novo
      // depois de uma recusa sem apagar o histórico da recusa.
      expect(() =>
        db
          .prepare(
            `INSERT INTO friend_requests
               (request_id, requester_uid, recipient_uid, status, created_at, updated_at)
             VALUES ('req-2', ?, ?, 'PENDING', 2, 2)`,
          )
          .run(UID_A, UID_B),
      ).not.toThrow();
      db.close();
    });

    it('pedido para si mesmo e status inventado são recusados pelo CHECK', () => {
      const db = seededDb();
      const insert = (id: string, requester: string, recipient: string, status: string) =>
        db
          .prepare(
            `INSERT INTO friend_requests
               (request_id, requester_uid, recipient_uid, status, created_at, updated_at)
             VALUES (?, ?, ?, ?, 1, 1)`,
          )
          .run(id, requester, recipient, status);

      expect(() => insert('req-1', UID_A, UID_A, 'PENDING')).toThrow(/CHECK/i);
      expect(() => insert('req-2', UID_A, UID_B, 'TALVEZ')).toThrow(/CHECK/i);
      db.close();
    });
  });

  // ------------------------------------------------------------------------- índices

  describe('as consultas do grafo são indexadas', () => {
    it('o lookup por friendCode usa o índice único, e não varredura', () => {
      const db = seededDb();

      const plan = db
        .prepare(
          `EXPLAIN QUERY PLAN
           SELECT p.owner_uid FROM social_profiles p WHERE p.friend_code = ?`,
        )
        .all('SPK-AAAAAAAA') as Array<{ detail: string }>;

      // Um `SCAN` aqui significaria que varrer o servidor inteiro custa o mesmo que um acerto —
      // e é exatamente esse custo que precisa ser alto para quem estiver tentando enumerar.
      const detail = plan.map((row) => row.detail).join(' ');
      expect(detail).toContain('idx_social_profiles_friend_code');
      expect(detail).not.toMatch(/\bSCAN\b/);
      db.close();
    });

    it('pedidos recebidos e enviados têm índice próprio', () => {
      const db = seededDb();

      const incoming = db
        .prepare(
          `EXPLAIN QUERY PLAN
           SELECT request_id FROM friend_requests
           WHERE recipient_uid = ? AND status = 'PENDING'
           ORDER BY created_at DESC, request_id DESC`,
        )
        .all(UID_B) as Array<{ detail: string }>;
      const outgoing = db
        .prepare(
          `EXPLAIN QUERY PLAN
           SELECT request_id FROM friend_requests
           WHERE requester_uid = ? AND status = 'PENDING'
           ORDER BY created_at DESC, request_id DESC`,
        )
        .all(UID_A) as Array<{ detail: string }>;

      expect(incoming.map((row) => row.detail).join(' ')).toContain('idx_friend_requests_incoming');
      expect(outgoing.map((row) => row.detail).join(' ')).toContain('idx_friend_requests_outgoing');
      db.close();
    });

    it('a amizade é encontrada pela chave do par, pelos dois lados', () => {
      const db = seededDb();

      const byA = db
        .prepare(`EXPLAIN QUERY PLAN SELECT 1 FROM friendships WHERE user_a_uid = ?`)
        .all(UID_A) as Array<{ detail: string }>;
      const byB = db
        .prepare(`EXPLAIN QUERY PLAN SELECT 1 FROM friendships WHERE user_b_uid = ?`)
        .all(UID_B) as Array<{ detail: string }>;

      expect(byA.map((row) => row.detail).join(' ')).toMatch(/SEARCH|sqlite_autoindex/);
      expect(byB.map((row) => row.detail).join(' ')).toContain('idx_friendships_user_b');
      db.close();
    });
  });

  // ------------------------------------------------------------------------- restart

  describe('reiniciar o servidor preserva as relações', () => {
    const startApp = async (): Promise<INestApplication> =>
      createTestApp(
        configFor(temp.path),
        new FakeAuthTokenVerifier()
          .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
          .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' }),
      );

    it('amizade e pedido pendente sobrevivem ao processo morrer', async () => {
      let app = await startApp();

      const activate = (token: string, name: string) =>
        request(app.getHttpServer())
          .post('/v1/social/me/activate')
          .set('Authorization', `Bearer ${token}`)
          .send({ displayName: name });

      await activate(TOKEN_A, 'Igor');
      const b = (await activate(TOKEN_B, 'João')).body.profile;

      const sent = await request(app.getHttpServer())
        .post('/v1/social/friend-requests')
        .set('Authorization', `Bearer ${TOKEN_A}`)
        .send({ socialId: b.socialId });
      const requestId = sent.body.request.requestId;

      // O processo inteiro morre e sobe de novo sobre o mesmo arquivo.
      await app.close();
      app = await startApp();

      const stillPending = await request(app.getHttpServer())
        .get('/v1/social/friend-requests/incoming')
        .set('Authorization', `Bearer ${TOKEN_B}`);
      expect(stillPending.body.requests[0].requestId).toBe(requestId);

      await request(app.getHttpServer())
        .post(`/v1/social/friend-requests/${requestId}/accept`)
        .set('Authorization', `Bearer ${TOKEN_B}`);

      await app.close();
      app = await startApp();

      const friendsOfA = await request(app.getHttpServer())
        .get('/v1/social/friends')
        .set('Authorization', `Bearer ${TOKEN_A}`);
      expect(friendsOfA.body.friends[0].socialId).toBe(b.socialId);

      await app.close();
    });
  });
});
