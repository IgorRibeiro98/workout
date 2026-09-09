import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import BetterSqlite3 from 'better-sqlite3';
import { MIGRATIONS_DIR, configFor, createTempDb, sqliteFor, type TempDb } from './support/temp-db';
import { loadMigrations, runMigrations } from '../src/database/migration-runner';

/**
 * T17.10 §111–§115 — o ciclo de vida das migrations sociais.
 *
 * Três perguntas que nenhuma suíte respondia inteiras:
 *
 * 1. **from-clean** (§112): um banco criado do zero chega à última migration com o schema que o
 *    código espera?
 * 2. **from-previous** (§113): um banco parado antes da T17.7, da T17.8 e da T17.9 sobe até o fim
 *    sem perder dado?
 * 3. **imutabilidade** (§111): uma migration editada depois de aplicada é recusada?
 *
 * A terceira é a que mais importa em produção. As duas primeiras são o que garante que a resposta
 * da terceira não seja "recusa tudo".
 */

const SOCIAL_TABLES = [
  'social_profiles',
  'social_privacy_settings',
  'social_progress_settings',
  'friend_requests',
  'friendships',
  'challenges',
  'challenge_invitations',
  'challenge_participants',
  'challenge_creation_requests',
  'social_notification_preferences',
  'social_push_devices',
  'social_notification_events',
  'social_notification_deliveries',
  'social_blocks',
  'social_reports',
  'account_deletion_tombstones',
  'account_deletion_jobs',
  'workout_shares',
  'social_workout_checkins',
  'social_checkin_media',
  'social_checkin_reactions',
  'social_checkin_comments',
] as const;

/** Aplica as migrations até `upTo` inclusive, num diretório temporário só com esses arquivos. */
function migrateUpTo(databasePath: string, upTo: number): void {
  const all = loadMigrations(MIGRATIONS_DIR);
  const db = new BetterSqlite3(databasePath);
  try {
    db.pragma('foreign_keys = ON');
    runMigrations(
      db,
      all.filter((migration) => migration.version <= upTo),
    );
  } finally {
    db.close();
  }
}

function tableNames(databasePath: string): Set<string> {
  const db = new BetterSqlite3(databasePath);
  try {
    return new Set(
      (
        db.prepare(`SELECT name FROM sqlite_master WHERE type = 'table'`).all() as Array<{
          name: string;
        }>
      ).map((row) => row.name),
    );
  } finally {
    db.close();
  }
}

describe('T17.10 — migrations sociais', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  // ---------------------------------------------------------------- §112 from-clean

  it('banco do zero chega à última migration com todas as tabelas sociais (§112)', () => {
    const sqlite = sqliteFor(configFor(temp.path));
    sqlite.initialize(MIGRATIONS_DIR);

    const expected = sqlite.expectedVersions();
    expect(sqlite.appliedVersions()).toEqual(expected);
    expect(expected.at(-1)).toBe(loadMigrations(MIGRATIONS_DIR).length);

    const tables = tableNames(temp.path);
    for (const table of SOCIAL_TABLES) {
      expect(tables.has(table)).toBe(true);
    }

    const db = sqlite.connection;
    expect(db.pragma('integrity_check')).toEqual([{ integrity_check: 'ok' }]);
    expect(db.pragma('foreign_key_check')).toEqual([]);
    sqlite.close();
  });

  it('nenhuma migration perde dado: todo DROP TABLE é parte de um rebuild que copia antes (§172)', () => {
    for (const migration of loadMigrations(MIGRATIONS_DIR)) {
      // Comentários explicam decisões e citam palavras que o SQL não executa; a varredura precisa
      // olhar o **código**, não a prosa.
      const sql = migration.sql.replace(/--[^\n]*/g, '').replace(/\/\*[\s\S]*?\*\//g, '');

      // Um `DELETE FROM` numa migration apaga dado de usuário e não tem como ser revertido.
      expect(sql).not.toMatch(/\bDELETE\s+FROM\b/i);

      // `DROP TABLE` é aceito **apenas** no rebuild em 12 passos que o SQLite exige para alterar
      // uma `CHECK` — e só quando as linhas foram copiadas para a tabela nova antes. Foi assim
      // que a 0014 acrescentou `WORKOUT_SHARE_RECEIVED` ao enum de notificações.
      const dropped = [...sql.matchAll(/\bDROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?([a-z0-9_]+)/gi)].map(
        (match) => match[1],
      );
      for (const table of dropped) {
        const copiedBefore = new RegExp(
          `INSERT\\s+INTO\\s+${table}_new\\b[\\s\\S]*?FROM\\s+${table}\\b`,
          'i',
        ).test(sql);
        const renamedBack = new RegExp(
          `ALTER\\s+TABLE\\s+${table}_new\\s+RENAME\\s+TO\\s+${table}\\b`,
          'i',
        ).test(sql);
        expect({ migration: migration.name, table, copiedBefore, renamedBack }).toEqual({
          migration: migration.name,
          table,
          copiedBefore: true,
          renamedBack: true,
        });
      }
    }
  });

  // ---------------------------------------------------------------- §113 from-previous

  it.each([
    ['pré-T17.7 (workout shares)', 13],
    ['pré-T17.8 (check-ins)', 14],
    ['pré-T17.9 (conteúdo do check-in)', 15],
  ])('um banco parado em %s sobe até o fim preservando o dado (§113)', (_label, stoppedAt) => {
    migrateUpTo(temp.path, stoppedAt);

    // Dado real gravado na versão antiga, pelo schema daquela versão.
    const before = new BetterSqlite3(temp.path);
    before.pragma('foreign_keys = ON');
    before
      .prepare(
        `INSERT INTO social_profiles
           (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)`,
      )
      .run('uid-antigo', 'social-antigo', 'SPK-OLDCODE', 'Antiga', 10, 10);
    before
      .prepare(
        `INSERT INTO social_profiles
           (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)`,
      )
      .run('uid-alvo', 'social-alvo', 'SPK-TARGETX', 'Alvo', 10, 10);
    before
      .prepare(
        `INSERT INTO social_reports (id, reporter_uid, reported_uid, reason, created_at)
         VALUES (?, ?, ?, 'SPAM', ?)`,
      )
      .run('report-antigo', 'uid-antigo', 'uid-alvo', 10);
    before.close();

    // Sobe até o fim.
    const sqlite = sqliteFor(configFor(temp.path));
    sqlite.initialize(MIGRATIONS_DIR);
    expect(sqlite.appliedVersions()).toEqual(sqlite.expectedVersions());

    const db = sqlite.connection;
    // O perfil antigo continua lá, inteiro.
    const profile = db
      .prepare(`SELECT display_name AS name, status FROM social_profiles WHERE owner_uid = ?`)
      .get('uid-antigo') as { name: string; status: string };
    expect(profile).toEqual({ name: 'Antiga', status: 'ACTIVE' });

    // §114 — o backfill da 0018 preenche `target_type`/`target_id` sem inventar dado: uma
    // denúncia da T17.6 é uma denúncia de conta, com a própria conta como alvo.
    const report = db
      .prepare(`SELECT target_type AS type, target_id AS id FROM social_reports WHERE id = ?`)
      .get('report-antigo') as { type: string; id: string };
    expect(report).toEqual({ type: 'USER', id: 'uid-alvo' });

    // §114 — os interruptores de compartilhamento de progresso nascem **fechados**, mesmo para
    // quem já tinha perfil antes da coluna existir.
    const sharing = db
      .prepare(`SELECT * FROM social_progress_settings WHERE owner_uid = ?`)
      .get('uid-antigo') as Record<string, unknown> | undefined;
    if (sharing) {
      for (const [key, value] of Object.entries(sharing)) {
        if (key.startsWith('share_')) {
          expect(value).toBe(0);
        }
      }
    }

    // Privacidade: nada nasce socialmente permissivo.
    const privacy = db
      .prepare(`SELECT * FROM social_privacy_settings WHERE owner_uid = ?`)
      .get('uid-antigo') as Record<string, unknown> | undefined;
    if (privacy) {
      expect(privacy.activity_sharing_enabled ?? 0).toBe(0);
      expect(privacy.friend_ranking_participation_enabled ?? 0).toBe(0);
    }

    expect(db.pragma('integrity_check')).toEqual([{ integrity_check: 'ok' }]);
    expect(db.pragma('foreign_key_check')).toEqual([]);
    sqlite.close();
  });

  it('o schema final é o mesmo vindo do zero e vindo de uma versão anterior (§112/§113)', () => {
    const fromScratch = createTempDb();
    const upgraded = createTempDb();
    try {
      const clean = sqliteFor(configFor(fromScratch.path));
      clean.initialize(MIGRATIONS_DIR);
      clean.close();

      migrateUpTo(upgraded.path, 14);
      const stepped = sqliteFor(configFor(upgraded.path));
      stepped.initialize(MIGRATIONS_DIR);
      stepped.close();

      const schemaOf = (path: string) => {
        const db = new BetterSqlite3(path);
        try {
          return (
            db
              .prepare(
                `SELECT type, name, sql FROM sqlite_master
                  WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name`,
              )
              .all() as Array<{ type: string; name: string; sql: string | null }>
          ).map((row) => `${row.type} ${row.name}: ${row.sql ?? ''}`);
        } finally {
          db.close();
        }
      };

      expect(schemaOf(upgraded.path)).toEqual(schemaOf(fromScratch.path));
    } finally {
      fromScratch.cleanup();
      upgraded.cleanup();
    }
  });

  // ---------------------------------------------------------------- §111 imutabilidade

  describe('uma migration já aplicada não pode ser reescrita (§111)', () => {
    let mirror: string;

    beforeEach(() => {
      mirror = mkdtempSync(join(tmpdir(), 'spark-migrations-'));
      cpSync(MIGRATIONS_DIR, mirror, { recursive: true });
    });

    afterEach(() => {
      rmSync(mirror, { recursive: true, force: true });
    });

    it('recusa o arranque quando o conteúdo de uma migration aplicada mudou', () => {
      const first = sqliteFor(configFor(temp.path));
      first.initialize(mirror);
      first.close();

      // Edita uma migration social **já aplicada**, mantendo o nome do arquivo.
      const target = join(mirror, '0015_social_workout_checkins.sql');
      writeFileSync(target, `${readFileSync(target, 'utf8')}\n-- alteração retroativa\n`, 'utf8');

      const second = sqliteFor(configFor(temp.path));
      expect(() => second.initialize(mirror)).toThrow(/editada depois de aplicada/);
    });

    it('um banco anterior ao checksum é adotado sem quebrar, e passa a ser protegido', () => {
      // Simula o estado de produção antes desta tarefa: histórico sem coluna de checksum.
      const seed = sqliteFor(configFor(temp.path));
      seed.initialize(mirror);
      seed.close();

      const raw = new BetterSqlite3(temp.path);
      raw.exec('UPDATE schema_migrations SET checksum = NULL');
      raw.close();

      // Primeiro arranque depois da mudança: adota o que está aplicado, sem recusar.
      const adopting = sqliteFor(configFor(temp.path));
      expect(() => adopting.initialize(mirror)).not.toThrow();
      adopting.close();

      const stored = new BetterSqlite3(temp.path);
      const nulls = stored
        .prepare(`SELECT COUNT(*) AS n FROM schema_migrations WHERE checksum IS NULL`)
        .get() as { n: number };
      stored.close();
      expect(nulls.n).toBe(0);

      // E a partir daqui uma edição é recusada.
      const target = join(mirror, '0017_social_checkin_reactions_comments.sql');
      writeFileSync(target, `${readFileSync(target, 'utf8')}\n-- editada\n`, 'utf8');
      const protectedRun = sqliteFor(configFor(temp.path));
      expect(() => protectedRun.initialize(mirror)).toThrow(/editada depois de aplicada/);
    });
  });
});
