import { INestApplication } from '@nestjs/common';
import BetterSqlite3 from 'better-sqlite3';
import request from 'supertest';
import { SparkLogger } from '../src/common/logger';
import { loadMigrations, runMigrations } from '../src/database/migration-runner';
import { SqliteService } from '../src/database/sqlite.service';
import {
  FriendCodeCollisionError,
  SocialRepository,
} from '../src/modules/social/social.repository';
import { SocialService } from '../src/modules/social/social.service';
import * as identity from '../src/modules/social/social.identity';
import { FRIEND_CODE_MAX_GENERATION_ATTEMPTS } from '../src/modules/social/social.limits';
import { configFor, createTempDb, MIGRATIONS_DIR, sqliteFor, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';

const TOKEN = 'token-da-conta';
const UID = 'uid-da-conta';

/**
 * A persistência do domínio social (T17.0).
 *
 * Arquivo real de SQLite, nunca `:memory:` — restart só prova alguma coisa contra o mesmo tipo de
 * arquivo que a produção usa.
 */
describe('Persistência do domínio social', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  // ------------------------------------------------------------------------- migration

  describe('a migration social não toca no que a T16 gravou', () => {
    /** As migrations até a última da T16 — o estado de um servidor que ainda não subiu a T17. */
    const t16Migrations = () =>
      loadMigrations(MIGRATIONS_DIR).filter((migration) => migration.version <= 6);

    it('sobe da base T16 para a base social preservando os dados existentes', () => {
      const db = new BetterSqlite3(temp.path);
      db.pragma('foreign_keys = ON');
      runMigrations(db, t16Migrations());

      // Dado da T16 que precisa sobreviver: um backup e uma entidade de sync com tombstone.
      db.prepare(
        `INSERT INTO backup_snapshots
           (backup_id, owner_uid, client_backup_id, device_id, backup_schema_version,
            payload_hash, item_count, size_bytes, captured_at, created_at)
         VALUES ('b-1', ?, 'cb-1', 'device-1', 1, 'hash', 3, 100, 1, 2)`,
      ).run(UID);
      db.prepare(
        `INSERT INTO sync_entities
           (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
            last_server_sequence, payload, payload_hash, origin_device_id, created_at,
            updated_at, deleted)
         VALUES (?, 'WORKOUT_TEMPLATE', 'sync-1', 1, 5, 9, '{}', 'hash', 'device-1', 2, 3, 1)`,
      ).run(UID);

      const beforeBackup = db.prepare('SELECT * FROM backup_snapshots').all();
      const beforeEntity = db.prepare('SELECT * FROM sync_entities').all();

      // Agora a T17.0.
      const applied = runMigrations(db, loadMigrations(MIGRATIONS_DIR));

      // A T17.0 é a `0007`, a T17.1 acrescentou a `0008` e a T17.2 a `0009`. As três são
      // aditivas, e o que este teste afirma é sobre a T16: nada do que ela gravou muda quando o
      // social sobe.
      expect(applied.map((migration) => migration.version)).toEqual([7, 8, 9]);
      expect(applied.map((migration) => migration.name)).toEqual([
        'social_foundation',
        'friend_graph',
        'social_progress_profile',
      ]);
      expect(db.prepare('SELECT * FROM backup_snapshots').all()).toEqual(beforeBackup);
      expect(db.prepare('SELECT * FROM sync_entities').all()).toEqual(beforeEntity);

      const integrity = db.pragma('integrity_check') as Array<{ integrity_check: string }>;
      expect(integrity[0].integrity_check).toBe('ok');
      expect(db.pragma('foreign_key_check')).toEqual([]);

      db.close();
    });

    it('a migration é aditiva: só cria tabelas, não altera nem apaga as da T16', () => {
      const sql = loadMigrations(MIGRATIONS_DIR).find((m) => m.version === 7)?.sql ?? '';

      expect(sql).toMatch(/CREATE TABLE social_profiles/);
      expect(sql).toMatch(/CREATE TABLE social_privacy_settings/);
      // Nenhuma forma destrutiva, e nenhum toque em tabela da T16.
      for (const forbidden of [
        /\bDROP\b/i,
        /\bALTER TABLE\b/i,
        /\bDELETE FROM\b/i,
        /\bUPDATE\b/i,
      ]) {
        expect(sql.replace(/--.*$/gm, '')).not.toMatch(forbidden);
      }
      for (const t16Table of [
        'backup_snapshots',
        'backup_items',
        'sync_entities',
        'sync_changes',
      ]) {
        expect(sql.replace(/--.*$/gm, '')).not.toContain(t16Table);
      }
    });

    it('as constraints de unicidade existem no banco, e não só no código', () => {
      const sqlite = sqliteFor(configFor(temp.path));
      sqlite.initialize();
      const db = sqlite.connection;

      const insert = (ownerUid: string, socialId: string, friendCode: string) =>
        db
          .prepare(
            `INSERT INTO social_profiles
               (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
             VALUES (?, ?, ?, 'Igor', 'ACTIVE', 1, 1)`,
          )
          .run(ownerUid, socialId, friendCode);

      insert('uid-1', 'social-1', 'SPK-AAAAAAAA');

      // Mesma conta: proibido por chave primária.
      expect(() => insert('uid-1', 'social-2', 'SPK-BBBBBBBB')).toThrow(/UNIQUE|PRIMARY/i);
      // Mesmo socialId em outra conta.
      expect(() => insert('uid-2', 'social-1', 'SPK-CCCCCCCC')).toThrow(/UNIQUE/i);
      // Mesmo friendCode em outra conta.
      expect(() => insert('uid-3', 'social-3', 'SPK-AAAAAAAA')).toThrow(/UNIQUE/i);

      sqlite.close();
    });
  });

  // ------------------------------------------------------------------------- colisão de código

  describe('colisão de friendCode', () => {
    let sqlite: SqliteService;
    let repository: SocialRepository;

    beforeEach(() => {
      sqlite = sqliteFor(configFor(temp.path));
      sqlite.initialize();
      repository = new SocialRepository(sqlite);
    });

    afterEach(() => {
      sqlite.close();
      jest.restoreAllMocks();
    });

    const create = (ownerUid: string, socialId: string, friendCode: string) =>
      repository.create({
        ownerUid,
        socialId,
        friendCode,
        displayName: 'Igor',
        discoverability: 'FRIEND_CODE_ONLY',
        friendRequestsEnabled: true,
        activitySharingEnabled: false,
        now: Date.now(),
      });

    it('o repositório distingue colisão de código de outros erros', () => {
      create('uid-1', 'social-1', 'SPK-AAAAAAAA');

      expect(() => create('uid-2', 'social-2', 'SPK-AAAAAAAA')).toThrow(FriendCodeCollisionError);
    });

    it('o serviço tenta de novo e conclui a ativação', () => {
      create('uid-ocupada', 'social-ocupada', 'SPK-AAAAAAAA');

      const spy = jest
        .spyOn(identity, 'generateFriendCode')
        .mockReturnValueOnce('SPK-AAAAAAAA')
        .mockReturnValueOnce('SPK-AAAAAAAA')
        .mockReturnValueOnce('SPK-BBBBBBBB');

      const service = new SocialService(repository, new SparkLogger(configFor(temp.path)));
      const response = service.activate({ uid: UID }, 'req-1', { displayName: 'Igor' });

      expect(spy).toHaveBeenCalledTimes(3);
      expect(response.profile.friendCode).toBe('SPK-BBBBBBBB');
      expect(repository.find(UID)?.profile.friendCode).toBe('SPK-BBBBBBBB');
    });

    it('colisão persistente vira 503 explícito — nunca 500 e nunca perfil sem código', () => {
      create('uid-ocupada', 'social-ocupada', 'SPK-AAAAAAAA');

      jest.spyOn(identity, 'generateFriendCode').mockReturnValue('SPK-AAAAAAAA');

      const service = new SocialService(repository, new SparkLogger(configFor(temp.path)));

      try {
        service.activate({ uid: UID }, 'req-1', { displayName: 'Igor' });
        throw new Error('a ativação deveria ter falhado');
      } catch (error) {
        const body = (error as { getResponse?: () => unknown }).getResponse?.();
        expect((error as { getStatus?: () => number }).getStatus?.()).toBe(503);
        expect(body).toMatchObject({ code: 'SOCIAL_UNAVAILABLE' });
      }

      // O perfil não foi criado pela metade.
      expect(repository.find(UID)).toBeNull();
      expect(
        sqlite.connection.prepare('SELECT COUNT(*) AS total FROM social_privacy_settings').get(),
      ).toEqual({ total: 1 });
    });

    it('o teto de tentativas é o declarado, e é pequeno', () => {
      expect(FRIEND_CODE_MAX_GENERATION_ATTEMPTS).toBeGreaterThan(1);
      expect(FRIEND_CODE_MAX_GENERATION_ATTEMPTS).toBeLessThanOrEqual(10);
    });
  });

  // ------------------------------------------------------------------------- restart

  describe('restart do servidor', () => {
    it('o perfil, a identidade e a privacidade sobrevivem', async () => {
      const config = configFor(temp.path);
      const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID });

      let app: INestApplication = await createTestApp(config, verifier);
      const created = (
        await request(app.getHttpServer())
          .post('/v1/social/me/activate')
          .set('Authorization', `Bearer ${TOKEN}`)
          .send({ displayName: 'Igor' })
      ).body.profile;
      await request(app.getHttpServer())
        .patch('/v1/social/me/privacy')
        .set('Authorization', `Bearer ${TOKEN}`)
        .send({ friendRequestsEnabled: false });
      await app.close();

      // Processo novo, mesmo arquivo.
      app = await createTestApp(config, FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }));
      const read = await request(app.getHttpServer())
        .get('/v1/social/me')
        .set('Authorization', `Bearer ${TOKEN}`);

      expect(read.body.enabled).toBe(true);
      expect(read.body.profile.socialId).toBe(created.socialId);
      expect(read.body.profile.friendCode).toBe(created.friendCode);
      expect(read.body.profile.displayName).toBe(created.displayName);
      expect(read.body.profile.createdAt).toBe(created.createdAt);
      expect(read.body.profile.privacy.friendRequestsEnabled).toBe(false);

      await app.close();
    });
  });

  // ------------------------------------------------------------------------- isolamento na query

  describe('o isolamento é da consulta, não de uma verificação posterior', () => {
    it('não existe leitura de perfil que não filtre por owner_uid', () => {
      const sqlite = sqliteFor(configFor(temp.path));
      sqlite.initialize();
      const repository = new SocialRepository(sqlite);

      repository.create({
        ownerUid: 'uid-a',
        socialId: 'social-a',
        friendCode: 'SPK-AAAAAAAA',
        displayName: 'Igor',
        discoverability: 'FRIEND_CODE_ONLY',
        friendRequestsEnabled: true,
        activitySharingEnabled: false,
        now: Date.now(),
      });

      expect(repository.find('uid-a')?.profile.socialId).toBe('social-a');
      expect(repository.find('uid-b')).toBeNull();

      // E o repositório não oferece nenhum caminho de enumeração.
      const methods = Object.getOwnPropertyNames(SocialRepository.prototype);
      expect(methods.sort()).toEqual([
        'constructor',
        'create',
        'find',
        'updateDisplayName',
        'updatePrivacy',
        'updateStatus',
      ]);

      sqlite.close();
    });
  });
});
