import request from 'supertest';
import { existsSync, readFileSync } from 'node:fs';
import {
  ACCOUNT_A,
  ACCOUNT_B,
  ACCOUNT_C,
  createSocialScenario,
  type AuditAccount,
  type SocialScenario,
} from './support/social-scenario';
import { AccountDeletionService } from '../src/modules/account-deletion/account-deletion.service';
import { jpegWithExifGps } from './support/image-fixtures';
import {
  SOCIAL_MEDIA_STORE,
  type SocialMediaStore,
} from '../src/modules/social/social-media.store';

/**
 * T17.13 — fechamento do Social V2: a auditoria de exclusão e DR **com Squads**.
 *
 * ## Por que este arquivo precisou nascer
 *
 * A auditoria da T17.10 (`social-final-audit.spec.ts`) provou que um restore antigo não ressuscita
 * conta nem mídia — mas ela é anterior aos Squads, e por isso o snapshot que ela restaura contém
 * perfil, check-in e foto, e mais nada. A T17.11 acrescentou quatro tabelas e a T17.12 acrescentou
 * audiência a duas outras; nenhuma delas aparece naquele cenário, e §50 é explícito em que o teste
 * anti-ressurreição precisa ser refeito **agora com Squads**.
 *
 * A diferença não é cosmética. O purge da conta remove os Squads de quem era dono, e as interações
 * `GROUP` de **outras pessoas** dentro deles saem por `ON DELETE CASCADE` da FK `group_id` — um
 * caminho implícito, que só existe enquanto `PRAGMA foreign_keys` estiver ligado e enquanto
 * ninguém trocar a FK por uma coluna solta. Um teste que não exercita esse caminho não o protege.
 *
 * ```text
 * A é dona do Squad X, com B e C dentro
 * A publica um check-in com foto e o compartilha em X
 * B e C reagem e comentam em GROUP(X)
 *          │
 *          ▼  backup (o estado é lido agora)
 *      delete A
 *          ▼  restore do snapshot anterior — as linhas de A voltam
 *   reconcileTombstones
 *          ▼
 * A continua excluída · o Squad de A não volta · a conversa GROUP(X) não volta
 * B e C continuam inteiros, com o conteúdo que era deles
 * ```
 */
describe('T17.13 — fechamento do Social V2: exclusão e DR com Squads', () => {
  let s: SocialScenario;

  const auth = (a: AuditAccount) => ({ Authorization: `Bearer ${a.token}` });
  const groupContext = (groupId: string) => ({ type: 'GROUP', groupId });

  beforeEach(async () => {
    s = await createSocialScenario();
    await s.activate(ACCOUNT_A);
    await s.activate(ACCOUNT_B);
    await s.activate(ACCOUNT_C);
    await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
    await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
  });

  afterEach(async () => {
    await s.close();
  });

  const del = (account: AuditAccount, path: string) =>
    request(s.server()).delete(path).set(auth(account));

  const get = (account: AuditAccount, path: string) =>
    request(s.server()).get(path).set(auth(account));

  function comment(
    account: AuditAccount,
    checkInId: string,
    body: string,
    context?: Record<string, unknown>,
  ) {
    return request(s.server())
      .post(`/v1/social/workout-checkins/${checkInId}/comments`)
      .set(auth(account))
      .send(context ? { body, context } : { body });
  }

  function react(
    account: AuditAccount,
    checkInId: string,
    type: string,
    context?: Record<string, unknown>,
  ) {
    return request(s.server())
      .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
      .set(auth(account))
      .send(context ? { type, context } : { type });
  }

  /** As contagens que interessam à auditoria, lidas direto do arquivo. */
  const counts = () =>
    s.inDatabase((db) => {
      const n = (sql: string, ...params: unknown[]) =>
        (db.prepare(sql).get(...params) as { n: number }).n;
      return {
        groupsOfA: n(`SELECT COUNT(*) AS n FROM social_groups WHERE owner_uid = ?`, ACCOUNT_A.uid),
        memberships: n(`SELECT COUNT(*) AS n FROM social_group_memberships`),
        shares: n(`SELECT COUNT(*) AS n FROM social_group_checkin_shares`),
        groupComments: n(
          `SELECT COUNT(*) AS n FROM social_checkin_comments WHERE audience_type = 'GROUP'`,
        ),
        groupReactions: n(
          `SELECT COUNT(*) AS n FROM social_checkin_reactions WHERE audience_type = 'GROUP'`,
        ),
        checkInsOfA: n(
          `SELECT COUNT(*) AS n FROM social_workout_checkins WHERE author_uid = ?`,
          ACCOUNT_A.uid,
        ),
        checkInsOfB: n(
          `SELECT COUNT(*) AS n FROM social_workout_checkins WHERE author_uid = ?`,
          ACCOUNT_B.uid,
        ),
        profilesOfA: n(
          `SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`,
          ACCOUNT_A.uid,
        ),
        mediaOfA: n(
          `SELECT COUNT(*) AS n FROM social_checkin_media WHERE owner_uid = ?`,
          ACCOUNT_A.uid,
        ),
      };
    });

  // ===================================================================================
  // §45–§49 — exclusão de conta do dono de Squad, com a conversa de terceiros dentro dele
  // ===================================================================================

  describe('exclusão de conta do dono de um Squad (§45/§47/§48)', () => {
    it('leva o Squad e a conversa GROUP dele, e não toca o que é de B e C (§48)', async () => {
      const squadX = await s.createGroup(ACCOUNT_A, 'Os Monstros');
      await s.addMember(ACCOUNT_A, squadX, ACCOUNT_B);
      await s.addMember(ACCOUNT_A, squadX, ACCOUNT_C);

      const sessionA = await s.pushSession(ACCOUNT_A);
      const checkInA = await s.publishCheckIn(ACCOUNT_A, sessionA, {});
      await request(s.server())
        .post(`/v1/social/groups/${squadX}/checkins/${checkInA}`)
        .set(auth(ACCOUNT_A))
        .expect(201);

      // B e C conversam **dentro do Squad de A** — conteúdo de terceiro em contexto de A.
      await comment(ACCOUNT_B, checkInA, 'boa!', groupContext(squadX)).expect(201);
      await comment(ACCOUNT_C, checkInA, 'monstro', groupContext(squadX)).expect(201);
      await react(ACCOUNT_B, checkInA, 'FIRE', groupContext(squadX)).expect(200);
      await react(ACCOUNT_C, checkInA, 'CLAP', groupContext(squadX)).expect(200);

      // B tem publicação própria, e C comenta nela no Feed de amigos — nada disso é de A.
      await s.makeFriends(ACCOUNT_B, ACCOUNT_C);
      const sessionB = await s.pushSession(ACCOUNT_B);
      const checkInB = await s.publishCheckIn(ACCOUNT_B, sessionB, {});
      await comment(ACCOUNT_C, checkInB, 'firme', undefined).expect(201);

      expect(counts()).toMatchObject({
        groupsOfA: 1,
        memberships: 3,
        shares: 1,
        groupComments: 2,
        groupReactions: 2,
      });

      await del(ACCOUNT_A, '/v1/account').expect(200);

      const after = counts();
      // O Squad de A e **toda** a audiência dele somem, inclusive a conversa que era de B e C.
      expect(after).toMatchObject({
        groupsOfA: 0,
        memberships: 0,
        shares: 0,
        groupComments: 0,
        groupReactions: 0,
        checkInsOfA: 0,
        profilesOfA: 0,
      });
      // §48 — o que é de B e C continua de pé.
      expect(after.checkInsOfB).toBe(1);
      const friendComments = s.inDatabase(
        (db) =>
          (
            db
              .prepare(
                `SELECT COUNT(*) AS n FROM social_checkin_comments
                  WHERE audience_type = 'FRIEND' AND deleted_at IS NULL`,
              )
              .get() as { n: number }
          ).n,
      );
      expect(friendComments).toBe(1);
      await get(ACCOUNT_B, '/v1/social/me').expect(200);
      await get(ACCOUNT_C, '/v1/social/me').expect(200);

      // §99 — e o banco continua íntegro depois de um purge que atravessou seis tabelas.
      expect(s.inDatabase((db) => db.pragma('foreign_key_check'))).toEqual([]);
      expect(s.inDatabase((db) => db.pragma('integrity_check'))).toEqual([
        { integrity_check: 'ok' },
      ]);
    });

    it('a exclusão de um membro remove só o vínculo — o Squad de A permanece (§46)', async () => {
      const squadX = await s.createGroup(ACCOUNT_A, 'Os Monstros');
      await s.addMember(ACCOUNT_A, squadX, ACCOUNT_B);
      await s.addMember(ACCOUNT_A, squadX, ACCOUNT_C);

      const sessionA = await s.pushSession(ACCOUNT_A);
      const checkInA = await s.publishCheckIn(ACCOUNT_A, sessionA, {});
      await request(s.server())
        .post(`/v1/social/groups/${squadX}/checkins/${checkInA}`)
        .set(auth(ACCOUNT_A))
        .expect(201);
      await comment(ACCOUNT_B, checkInA, 'boa!', groupContext(squadX)).expect(201);
      await comment(ACCOUNT_C, checkInA, 'top', groupContext(squadX)).expect(201);

      await del(ACCOUNT_B, '/v1/account').expect(200);

      const after = counts();
      expect(after.groupsOfA).toBe(1);
      expect(after.memberships).toBe(2); // A e C
      expect(after.groupComments).toBe(1); // só o de C
      expect(after.checkInsOfA).toBe(1);
      // A continua enxergando o próprio Squad, agora sem B.
      const members = await get(ACCOUNT_A, `/v1/social/groups/${squadX}/members`).expect(200);
      expect(members.body.items).toHaveLength(2);
    });
  });

  // ===================================================================================
  // §50/§51/§116 — DR anti-ressurreição, agora com Squads
  // ===================================================================================

  describe('DR anti-ressurreição com Squads (§50/§51/§116)', () => {
    it('restaurar um snapshot anterior não traz de volta o Squad nem a conversa de A', async () => {
      const squadX = await s.createGroup(ACCOUNT_A, 'Os Monstros');
      await s.addMember(ACCOUNT_A, squadX, ACCOUNT_B);
      await s.addMember(ACCOUNT_A, squadX, ACCOUNT_C);

      const sessionA = await s.pushSession(ACCOUNT_A);
      const mediaId = await s.uploadPhoto(ACCOUNT_A, sessionA, await jpegWithExifGps());
      const checkInA = await s.publishCheckIn(ACCOUNT_A, sessionA, { mediaId });
      await request(s.server())
        .post(`/v1/social/groups/${squadX}/checkins/${checkInA}`)
        .set(auth(ACCOUNT_A))
        .expect(201);
      await comment(ACCOUNT_B, checkInA, 'boa!', groupContext(squadX)).expect(201);
      await react(ACCOUNT_C, checkInA, 'FIRE', groupContext(squadX)).expect(200);

      // ---- "backup": o estado de A, lido enquanto ele ainda existe ----
      const filesBackup = s.filesOnDisk();
      expect(filesBackup).toHaveLength(1);
      const restoredBytes = readFileSync(filesBackup[0]);
      const snapshot = s.inDatabase((db) => ({
        profile: db
          .prepare(`SELECT * FROM social_profiles WHERE owner_uid = ?`)
          .get(ACCOUNT_A.uid) as Record<string, unknown>,
        group: db.prepare(`SELECT * FROM social_groups WHERE id = ?`).get(squadX) as Record<
          string,
          unknown
        >,
        memberships: db
          .prepare(`SELECT * FROM social_group_memberships WHERE group_id = ?`)
          .all(squadX) as Array<Record<string, unknown>>,
        checkIn: db
          .prepare(`SELECT * FROM social_workout_checkins WHERE id = ?`)
          .get(checkInA) as Record<string, unknown>,
        share: db
          .prepare(`SELECT * FROM social_group_checkin_shares WHERE group_id = ?`)
          .get(squadX) as Record<string, unknown>,
        comments: db
          .prepare(`SELECT * FROM social_checkin_comments WHERE audience_type = 'GROUP'`)
          .all() as Array<Record<string, unknown>>,
        reactions: db
          .prepare(`SELECT * FROM social_checkin_reactions WHERE audience_type = 'GROUP'`)
          .all() as Array<Record<string, unknown>>,
        media: db
          .prepare(`SELECT * FROM social_checkin_media WHERE owner_uid = ?`)
          .get(ACCOUNT_A.uid) as Record<string, unknown>,
      }));

      const service = s.app.get(AccountDeletionService);
      const hashA = service.hashUid(ACCOUNT_A.uid);

      // ---- exclusão de conta ----
      await del(ACCOUNT_A, '/v1/account').expect(200);
      expect(counts()).toMatchObject({
        groupsOfA: 0,
        shares: 0,
        groupComments: 0,
        groupReactions: 0,
      });
      expect(s.filesOnDisk()).toEqual([]);
      expect(existsSync(s.tombstonesFile)).toBe(true);
      expect(readFileSync(s.tombstonesFile, 'utf8')).toContain(hashA);

      // ---- restore de um snapshot **anterior**: tudo de A volta, Squad e conversa inclusive ----
      const store = s.app.get<SocialMediaStore>(SOCIAL_MEDIA_STORE);
      s.inDatabase((db) => {
        db.pragma('foreign_keys = ON');
        const insert = (table: string, row: Record<string, unknown>) => {
          const cols = Object.keys(row);
          db.prepare(
            `INSERT INTO ${table} (${cols.join(', ')})
             VALUES (${cols.map(() => '?').join(', ')})`,
          ).run(...cols.map((c) => row[c]));
        };
        insert('social_profiles', snapshot.profile);
        insert('social_groups', snapshot.group);
        for (const m of snapshot.memberships) insert('social_group_memberships', m);
        insert('social_workout_checkins', snapshot.checkIn);
        insert('social_group_checkin_shares', snapshot.share);
        for (const c of snapshot.comments) insert('social_checkin_comments', c);
        for (const r of snapshot.reactions) insert('social_checkin_reactions', r);
        insert('social_checkin_media', snapshot.media);
      });
      await store.write(snapshot.media.storage_key as string, restoredBytes);

      // O restore realmente ressuscitou tudo — sem isto o teste seguinte não provaria nada.
      expect(counts()).toMatchObject({
        groupsOfA: 1,
        shares: 1,
        groupComments: 1,
        groupReactions: 1,
        checkInsOfA: 1,
        profilesOfA: 1,
        mediaOfA: 1,
      });
      expect(s.filesOnDisk()).toHaveLength(1);

      // ---- reconciliação: o tombstone do DR reaplica a exclusão inteira ----
      expect(await service.reconcileTombstones(new Set([hashA]))).toBe(1);

      const after = counts();
      expect(after).toMatchObject({
        profilesOfA: 0,
        checkInsOfA: 0,
        mediaOfA: 0,
        // §50 — o Squad de A não reaparece, e o conteúdo GROUP dele não reaparece.
        groupsOfA: 0,
        shares: 0,
        groupComments: 0,
        groupReactions: 0,
      });
      // §49 — a foto ressuscitada sai do disco junto.
      expect(s.filesOnDisk()).toEqual([]);
      // A continua barrada; B e C nunca foram tocados.
      await get(ACCOUNT_A, '/v1/social/me').expect(403);
      await get(ACCOUNT_B, '/v1/social/me').expect(200);
      await get(ACCOUNT_C, '/v1/social/me').expect(200);
      // §99 — sem corrupção de FK depois do ciclo inteiro.
      expect(s.inDatabase((db) => db.pragma('foreign_key_check'))).toEqual([]);
    }, 60_000);

    it('um Squad órfão de um restore parcial ainda é alcançado pelo reconciliador (§51)', async () => {
      // O caso que a enumeração do reconciliador precisa cobrir: um restore inconsistente traz de
      // volta o Squad de A **sem** o perfil dele. Antes da T17.13 `listAllOwnerUidsInDatabase` não
      // varria `social_groups`, e A não era enumerada — o Squad sobrevivia à reconciliação.
      const service = s.app.get(AccountDeletionService);
      const hashA = service.hashUid(ACCOUNT_A.uid);
      const squadX = await s.createGroup(ACCOUNT_A, 'Os Monstros');
      const groupRow = s.inDatabase(
        (db) =>
          db.prepare(`SELECT * FROM social_groups WHERE id = ?`).get(squadX) as Record<
            string,
            unknown
          >,
      );

      await del(ACCOUNT_A, '/v1/account').expect(200);

      // Restore parcial: só a linha do Squad volta, com a FK desligada para simular a
      // inconsistência que um snapshot copiado a quente pode produzir.
      s.inDatabase((db) => {
        db.pragma('foreign_keys = OFF');
        const cols = Object.keys(groupRow);
        db.prepare(
          `INSERT INTO social_groups (${cols.join(', ')})
           VALUES (${cols.map(() => '?').join(', ')})`,
        ).run(...cols.map((c) => groupRow[c]));
      });
      expect(counts().groupsOfA).toBe(1);

      expect(await service.reconcileTombstones(new Set([hashA]))).toBe(1);
      expect(counts().groupsOfA).toBe(0);
    }, 60_000);
  });
});
