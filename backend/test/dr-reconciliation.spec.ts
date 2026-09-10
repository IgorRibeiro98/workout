import { mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import BetterSqlite3 from 'better-sqlite3';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { runReconciliation } from '../src/cli/reconcile-account-deletions';
import {
  ACCOUNT_UID_COLUMNS,
  NON_ACCOUNT_UID_COLUMNS,
} from '../src/modules/account-deletion/account-uid-inventory';
import { createHmac } from 'node:crypto';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'Bob' },
} as const;

const HMAC_KEY = 'chave-hmac-de-teste-para-exclusao-de-conta';

/**
 * T17.13.1 §14–§18, §65 e §66 — a recuperação de desastre tem um comando, e ele é este.
 *
 * ## O que estes testes exercitam
 *
 * [runReconciliation] é o corpo de `dist/cli/reconcile-account-deletions.js` — o comando que
 * `ops/restore.sh --install` roda e que o runbook manda rodar. §65 é explícito em não chamar
 * `service.reconcileTombstones()` diretamente: o que precisa estar coberto é o caminho do
 * operador, incluindo a leitura do ledger, a validação de formato, os portões de integridade e os
 * códigos de saída.
 *
 * O artefato **construído** é exercitado no smoke do CI; aqui roda a mesma função, com a mesma
 * configuração vinda do ambiente.
 *
 * ## Falha fechada
 *
 * ```text
 * ledger ausente      →  exit 2, e nada é tocado no banco
 * ledger malformado   →  exit 2, e nada é tocado no banco
 * ```
 *
 * Nunca "zero exclusões". As duas leituras produzem o mesmo efeito visível — nada é apagado — e
 * uma delas devolve ao ar contas que já tinham sido excluídas.
 */
describe('T17.13.1 — reconciliação de DR pelo comando operacional', () => {
  let temp: TempDb;
  let app: INestApplication | undefined;
  let verifier: FakeAuthTokenVerifier;
  let ledgerPath: string;
  let mediaRoot: string;

  const server = () => app!.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  const inDatabase = <T>(read: (db: BetterSqlite3.Database) => T): T => {
    const db = new BetterSqlite3(temp.path);
    try {
      return read(db);
    } finally {
      db.close();
    }
  };

  /** Roda o comando com o ambiente que o operador daria a ele. */
  async function runCommand(): Promise<number> {
    const saved = { ...process.env };
    Object.assign(process.env, {
      NODE_ENV: 'test',
      LOG_LEVEL: 'silent',
      DATABASE_URL: temp.databaseUrl,
      DATABASE_PATH: temp.path,
      SOCIAL_MEDIA_ROOT: mediaRoot,
      DELETION_TOMBSTONES_FILE_PATH: ledgerPath,
      ACCOUNT_DELETION_HMAC_KEY: HMAC_KEY,
    });
    try {
      return await runReconciliation();
    } finally {
      for (const key of Object.keys(process.env)) delete process.env[key];
      Object.assign(process.env, saved);
    }
  }

  async function boot(): Promise<void> {
    verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: account.email });
    }
    app = await createTestApp(
      configFor(temp.path, {
        DELETION_TOMBSTONES_FILE_PATH: ledgerPath,
        ACCOUNT_DELETION_HMAC_KEY: HMAC_KEY,
        SOCIAL_MEDIA_ROOT: mediaRoot,
      }),
      verifier,
    );
  }

  beforeEach(async () => {
    temp = createTempDb();
    ledgerPath = join(temp.directory, 'deletion_tombstones.tsv');
    mediaRoot = join(temp.directory, 'media');
    await boot();
  });

  afterEach(async () => {
    await app?.close();
    app = undefined;
    temp.cleanup();
  });

  const activate = (account: { token: string; name: string }) =>
    request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(account.token))
      .send({ displayName: account.name })
      .expect(200);

  /** Um "backup" do banco, no estado atual. */
  function snapshot(): void {
    inDatabase((db) => {
      db.exec(`
        DO $$
        DECLARE tbl text;
        BEGIN
          CREATE SCHEMA IF NOT EXISTS "${temp.schema}_snap";
          FOR tbl IN (SELECT tablename FROM pg_tables WHERE schemaname = '${temp.schema}') LOOP
            EXECUTE format('DROP TABLE IF EXISTS "${temp.schema}_snap".%I CASCADE', tbl);
            EXECUTE format('CREATE TABLE "${temp.schema}_snap".%I (LIKE "${temp.schema}".%I INCLUDING ALL)', tbl, tbl);
            EXECUTE format('INSERT INTO "${temp.schema}_snap".%I SELECT * FROM "${temp.schema}".%I', tbl, tbl);
          END LOOP;
        END $$;
      `);
    });
  }

  /** Substitui o banco pelo snapshot — o que um restore faz de verdade. */
  async function restoreSnapshot(): Promise<void> {
    await app?.close();
    app = undefined;
    inDatabase((db) => {
      db.exec(`
        DO $$
        DECLARE tbl text;
        BEGIN
          FOR tbl IN (SELECT tablename FROM pg_tables WHERE schemaname = '${temp.schema}') LOOP
            EXECUTE format('DROP TABLE IF EXISTS "${temp.schema}".%I CASCADE', tbl);
          END LOOP;
          FOR tbl IN (SELECT tablename FROM pg_tables WHERE schemaname = '${temp.schema}_snap') LOOP
            EXECUTE format('CREATE TABLE "${temp.schema}".%I (LIKE "${temp.schema}_snap".%I INCLUDING ALL)', tbl, tbl);
            EXECUTE format('INSERT INTO "${temp.schema}".%I SELECT * FROM "${temp.schema}_snap".%I', tbl, tbl);
          END LOOP;
        END $$;
      `);
    });
  }

  // ================================================================ §65 o cenário principal

  it('restaurar um backup anterior à exclusão não devolve a conta ao ar (§65)', async () => {
    await activate(ACCOUNTS.A);
    await activate(ACCOUNTS.B);

    // 1. o backup é tirado **antes** da exclusão: nele, A existe.
    snapshot();

    // 2. A é excluída pelo caminho real.
    const deleted = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(deleted.body.status).toBe('DELETED');

    // O ledger é o único registro que sobrevive à troca do arquivo do banco.
    const ledgerAfterDeletion = readFileSync(ledgerPath, 'utf8');
    expect(ledgerAfterDeletion).toMatch(/^[0-9a-f]{64}\t\d+\n$/);

    // 3. o desastre: o arquivo do banco volta a ser o de antes. A ressuscitou.
    await restoreSnapshot();
    expect(
      inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`)
              .get(ACCOUNTS.A.uid) as { n: number }
          ).n,
      ),
    ).toBe(1);
    expect(
      inDatabase(
        (db) =>
          (
            db.prepare(`SELECT COUNT(*) AS n FROM account_deletion_tombstones`).get() as {
              n: number;
            }
          ).n,
      ),
    ).toBe(0);

    // 4. o comando operacional — o mesmo que `ops/restore.sh --install` roda.
    await expect(runCommand()).resolves.toBe(0);

    // 5. A sumiu de novo, e o tombstone foi regravado para que o guard volte a bloqueá-la.
    expect(
      inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`)
              .get(ACCOUNTS.A.uid) as { n: number }
          ).n,
      ),
    ).toBe(0);
    expect(
      inDatabase(
        (db) =>
          (
            db.prepare(`SELECT COUNT(*) AS n FROM account_deletion_tombstones`).get() as {
              n: number;
            }
          ).n,
      ),
    ).toBe(1);

    // 6. B sobreviveu ao desastre e à reconciliação.
    await boot();
    await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(403);
  });

  // ================================================================ §17/§18 falha fechada

  it('ledger ausente: falha fechada, e nada é tocado no banco (§17)', async () => {
    await activate(ACCOUNTS.A);
    rmSync(ledgerPath, { force: true });
    await app!.close();
    app = undefined;

    await expect(runCommand()).resolves.toBe(2);

    // O banco não foi tocado: "sem ledger" nunca significa "nenhuma conta excluída".
    expect(
      inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`)
              .get(ACCOUNTS.A.uid) as { n: number }
          ).n,
      ),
    ).toBe(1);
  });

  it.each([
    ['hash curto demais', 'abc\t123\n'],
    ['hash com caractere não-hex', `${'z'.repeat(64)}\t123\n`],
    ['sem timestamp', `${'a'.repeat(64)}\n`],
    ['separador errado', `${'a'.repeat(64)} 123\n`],
    ['timestamp não numérico', `${'a'.repeat(64)}\tontem\n`],
    ['linha em branco no meio', `${'a'.repeat(64)}\t1\n\n${'b'.repeat(64)}\t2\n`],
  ])('ledger malformado (%s): falha fechada (§18)', async (_label, contents) => {
    await activate(ACCOUNTS.A);
    writeFileSync(ledgerPath, contents, 'utf8');
    await app!.close();
    app = undefined;

    await expect(runCommand()).resolves.toBe(2);
    expect(
      inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`)
              .get(ACCOUNTS.A.uid) as { n: number }
          ).n,
      ),
    ).toBe(1);
  });

  it('ledger válido e vazio é sucesso: um servidor onde ninguém excluiu conta', async () => {
    await activate(ACCOUNTS.A);
    writeFileSync(ledgerPath, '', 'utf8');
    await app!.close();
    app = undefined;

    await expect(runCommand()).resolves.toBe(0);
    expect(
      inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`)
              .get(ACCOUNTS.A.uid) as { n: number }
          ).n,
      ),
    ).toBe(1);
  });

  it('o mesmo hash repetido no ledger é reconciliado uma vez (§13)', async () => {
    await activate(ACCOUNTS.A);
    snapshot();
    await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    const line = readFileSync(ledgerPath, 'utf8');
    writeFileSync(ledgerPath, line + line + line, 'utf8');

    await restoreSnapshot();
    await expect(runCommand()).resolves.toBe(0);
    // Um tombstone, e não três: o leitor consome um conjunto e o `ON CONFLICT` do tombstone
    // converge.
    expect(
      inDatabase(
        (db) =>
          (
            db.prepare(`SELECT COUNT(*) AS n FROM account_deletion_tombstones`).get() as {
              n: number;
            }
          ).n,
      ),
    ).toBe(1);
  });

  it('a mídia restaurada de uma conta excluída também é apagada (§14)', async () => {
    await activate(ACCOUNTS.A);

    // Uma foto no disco, com a metadata que a referencia.
    const key = 'checkins/aa/bb/aabb0000-0000-4000-8000-000000000001.webp';
    const absolute = join(mediaRoot, key);
    mkdirSync(join(absolute, '..'), { recursive: true });
    writeFileSync(absolute, Buffer.from('nao-e-uma-imagem-de-verdade'));
    inDatabase((db) => {
      db.prepare(
        `INSERT INTO social_checkin_media
           (id, owner_uid, source_session_sync_id, client_upload_id, storage_key, mime_type,
            byte_size, width, height, content_hash, status, created_at, expires_at,
            attached_checkin_id, deleted_at)
         VALUES (?, ?, ?, ?, ?, 'image/webp', 10, 8, 8, 'hash', 'PENDING', 1, NULL, NULL, NULL)`,
      ).run('media-dr', ACCOUNTS.A.uid, 'sessao-dr', 'upload-dr', key);
    });

    const hash = createHmac('sha256', HMAC_KEY).update(ACCOUNTS.A.uid).digest('hex');
    writeFileSync(ledgerPath, `${hash}\t1\n`, 'utf8');
    await app!.close();
    app = undefined;

    await expect(runCommand()).resolves.toBe(0);
    // O arquivo saiu do disco junto com a metadata: purgar só o SQLite deixaria a foto de uma
    // conta excluída num servidor que jura tê-la apagado.
    expect(existsSync(absolute)).toBe(false);
  });

  // ================================================================ §66 restore parcial

  /**
   * Um restore parcial deixa **um** rastro da conta, sem o perfil.
   *
   * É o cenário que a enumeração precisa cobrir: um snapshot copiado com o processo escrevendo, um
   * `.dump` interrompido, uma cópia sem checkpoint do WAL. A conta não tem `social_profiles`, e
   * mesmo assim precisa ser encontrada e purgada — senão o rastro sobrevive e a identidade
   * continua ocupada.
   */
  it.each([
    [
      'convite de Squad recebido',
      (db: BetterSqlite3.Database, uid: string) => {
        db.prepare(
          `INSERT INTO social_group_invitations
             (id, group_id, sender_uid, recipient_uid, status, created_at, expires_at)
           VALUES ('inv-parcial', 'grupo-parcial', ?, ?, 'PENDING', 1, 9999999999999)`,
        ).run('uid-outro', uid);
      },
      'social_group_invitations',
      'recipient_uid',
    ],
    [
      'comentário em publicação de terceiro',
      (db: BetterSqlite3.Database, uid: string) => {
        db.prepare(
          `INSERT INTO social_checkin_comments
             (id, checkin_id, author_uid, body, created_at, audience_type)
           VALUES ('c-parcial', 'checkin-outro', ?, 'oi', 1, 'FRIEND')`,
        ).run(uid);
      },
      'social_checkin_comments',
      'author_uid',
    ],
    [
      'reação em publicação de terceiro',
      (db: BetterSqlite3.Database, uid: string) => {
        db.prepare(
          `INSERT INTO social_checkin_reactions
             (checkin_id, reactor_uid, type, audience_type, group_id, created_at, updated_at)
           VALUES ('checkin-outro', ?, 'FIRE', 'FRIEND', NULL, 1, 1)`,
        ).run(uid);
      },
      'social_checkin_reactions',
      'reactor_uid',
    ],
    [
      'compartilhamento de treino recebido',
      (db: BetterSqlite3.Database, uid: string) => {
        db.prepare(
          `INSERT INTO workout_shares
             (id, sender_uid, recipient_uid, snapshot_version, snapshot_json, snapshot_hash,
              status, client_request_id, created_at, expires_at)
           VALUES ('share-parcial', 'uid-outro', ?, 1, '{}', 'h', 'PENDING', 'req', 1, 999)`,
        ).run(uid);
      },
      'workout_shares',
      'recipient_uid',
    ],
    [
      'dispositivo de push',
      (db: BetterSqlite3.Database, uid: string) => {
        db.prepare(
          `INSERT INTO social_push_devices
             (id, owner_uid, device_id, fcm_token, platform, enabled, created_at, updated_at,
              last_registered_at)
           VALUES ('dev-parcial', ?, 'device-x', 'token-x', 'ANDROID', 1, 1, 1, 1)`,
        ).run(uid);
      },
      'social_push_devices',
      'owner_uid',
    ],
    [
      'pedido de amizade enviado',
      (db: BetterSqlite3.Database, uid: string) => {
        db.prepare(
          `INSERT INTO friend_requests
             (request_id, requester_uid, recipient_uid, status, created_at, updated_at)
           VALUES ('fr-parcial', ?, 'uid-outro', 'PENDING', 1, 1)`,
        ).run(uid);
      },
      'friend_requests',
      'requester_uid',
    ],
    [
      'participação em desafio',
      (db: BetterSqlite3.Database, uid: string) => {
        db.prepare(
          `INSERT INTO challenge_participants
             (challenge_id, participant_uid, role, status, joined_at, left_at)
           VALUES ('desafio-parcial', ?, 'MEMBER', 'JOINED', 1, NULL)`,
        ).run(uid);
      },
      'challenge_participants',
      'participant_uid',
    ],
    [
      'desafio criado',
      (db: BetterSqlite3.Database, uid: string) => {
        db.prepare(
          `INSERT INTO challenges
             (challenge_id, creator_uid, name, type, target, start_date, end_date, time_zone_id,
              starts_at, ends_at_exclusive, lifecycle, created_at, updated_at)
           VALUES ('desafio-criado', ?, 'nome', 'WORKOUTS_COMPLETED', 10, '2026-09-01', '2026-09-30',
                   'UTC', 1, 999, 'OPEN', 1, 1)`,
        ).run(uid);
      },
      'challenges',
      'creator_uid',
    ],
    [
      'participação em Squad',
      (db: BetterSqlite3.Database, uid: string) => {
        db.prepare(
          `INSERT INTO social_group_memberships (id, group_id, member_uid, role, joined_at)
           VALUES ('m-parcial', 'grupo-parcial', ?, 'MEMBER', 1)`,
        ).run(uid);
      },
      'social_group_memberships',
      'member_uid',
    ],
    [
      'compartilhamento de check-in em Squad',
      (db: BetterSqlite3.Database, uid: string) => {
        db.prepare(
          `INSERT INTO social_group_checkin_shares (id, group_id, checkin_id, author_uid, created_at)
           VALUES ('s-parcial', 'grupo-parcial', 'checkin-outro', ?, 1)`,
        ).run(uid);
      },
      'social_group_checkin_shares',
      'author_uid',
    ],
  ])(
    'restore parcial com só um rastro (%s): a conta é encontrada e purgada (§66)',
    async (_label, seed, table, column) => {
      await app!.close();
      app = undefined;

      const orphanUid = 'uid-fantasma';
      // Sem `social_profiles`: é exatamente isso que torna o rastro invisível para uma enumeração
      // que só olhe o perfil. As FK ficam desligadas porque um restore parcial é, por definição,
      // um banco que as violaria.
      const db = new BetterSqlite3(temp.path);
      try {
        db.pragma('foreign_keys = OFF');
        seed(db, orphanUid);
      } finally {
        db.close();
      }

      const hash = createHmac('sha256', HMAC_KEY).update(orphanUid).digest('hex');
      writeFileSync(ledgerPath, `${hash}\t1\n`, 'utf8');

      await expect(runCommand()).resolves.toBe(0);

      expect(
        inDatabase(
          (d) =>
            (
              d
                .prepare(`SELECT COUNT(*) AS n FROM ${table} WHERE ${column} = ?`)
                .get(orphanUid) as { n: number }
            ).n,
        ),
      ).toBe(0);
    },
  );

  // ================================================================ §24 o portão de schema

  it('toda coluna de uid do schema tem política declarada (§24)', () => {
    const declared = new Set(ACCOUNT_UID_COLUMNS.map((c) => `${c.table}.${c.column}`));
    const excused = new Set(NON_ACCOUNT_UID_COLUMNS.map((c) => `${c.table}.${c.column}`));

    const found = inDatabase((db) => {
      const tables = (
        db
          .prepare(
            `SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'`,
          )
          .all() as Array<{ name: string }>
      ).map((r) => r.name);
      const out: string[] = [];
      for (const table of tables) {
        for (const column of db.pragma(`table_info(${table})`) as Array<{ name: string }>) {
          // A heurística é o sufixo — mas ela **não** decide sozinha (§24): tudo o que ela
          // encontra precisa estar numa das duas listas, com justificativa para a exclusão.
          if (/(^|_)uid$/.test(column.name)) {
            out.push(`${table}.${column.name}`);
          }
        }
      }
      return out;
    });

    expect(found.length).toBeGreaterThan(20);
    const unclassified = found.filter((c) => !declared.has(c) && !excused.has(c));
    expect(unclassified).toEqual([]);

    // E o inverso: nada declarado pode ter deixado de existir no schema.
    const present = new Set(found);
    expect([...declared].filter((c) => !present.has(c))).toEqual([]);
  });
});
