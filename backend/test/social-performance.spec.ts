import BetterSqlite3 from 'better-sqlite3';
import { randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createTempDb, type TempDb } from './support/temp-db';
import { loadMigrations, runMigrations } from './support/legacy-sqlite-migration-runner';
import {
  viewerBlockedCte,
  viewerScopeCte,
  groupInteractionVisibleSql,
  interactionVisibleSql,
  viewerInActiveGroupSql,
} from '../src/modules/social/workout-checkin.access-policy';

const LEGACY_SQLITE_MIGRATIONS_DIR = join(__dirname, '..', 'migrations');
const VIEWER_BLOCKED_CTE = viewerBlockedCte(':viewer');
const VIEWER_SCOPE_CTE = viewerScopeCte(':viewer');

/**
 * T17.10 §36/§123/§124/§125 — as consultas críticas do Social sob volume.
 *
 * ## O que este arquivo é, e o que ele não é
 *
 * **Não** é benchmark: não afirma latência absoluta, não roda em máquina controlada e não define
 * SLA. §123 é explícito sobre isso — o objetivo é *encontrar consulta obviamente ruim para uma VPS
 * pequena*, e a forma barata de fazer isso não é cronometrar, é ler o plano.
 *
 * O que ele afirma:
 *
 * 1. **nenhuma consulta crítica faz varredura completa de tabela** (`SCAN` sem índice), com o
 *    plano lido do próprio SQLite (§36);
 * 2. **o custo não explode com o volume**: com ~500 contas, 5 mil amizades, 10 mil check-ins e
 *    dezenas de milhares de interações, as consultas do Feed, do ranking e dos comentários
 *    continuam respondendo em ordem de milissegundos (§123/§124).
 *
 * O volume é semeado direto no SQLite, e não pelas rotas: 65 mil requisições HTTP levariam minutos
 * e provariam a mesma coisa sobre o plano de consulta.
 */

const USERS = 500;
const CHECKINS = 10_000;
const COMMENTS = 50_000;
const REACTIONS = 50_000;
const FRIENDSHIPS_PER_USER = 10;

/**
 * O volume de Squad da auditoria de fechamento (T17.13 §94): 500 grupos de 20, com conversa dentro.
 *
 * A T17.12 semeava 100 grupos e dezenas de milhares de interações — ordem de grandeza certa, mas
 * abaixo do que §94 pede. Os números aqui são os da auditoria: 500 Squads, 20 participantes cada,
 * e 50 mil interações de cada tipo na audiência `GROUP`.
 *
 * `social_group_checkin_shares` fica em `GROUPS * MEMBERS_PER_GROUP` (10 mil) porque só membro
 * compartilha, e cada um traz um check-in próprio: semear mais arestas exigiria participantes
 * fictícios que nenhuma consulta real leria, e o plano medido não mudaria por causa disso.
 *
 * `GROUPS` continua múltiplo de `MEMBERS_PER_GROUP` — a distribuição da conversa depende disso
 * (veja o laço de comentários abaixo).
 */
const GROUPS = 500;
const MEMBERS_PER_GROUP = 20;
const GROUP_COMMENTS = 50_000;
const GROUP_REACTIONS = 50_000;

interface Seeded {
  readonly db: BetterSqlite3.Database;
  readonly viewer: string;
  readonly uids: string[];
  /** Um Squad de que o `viewer` participa — o alvo das consultas de audiência `GROUP`. */
  readonly groupId: string;
  /** Um check-in compartilhado naquele Squad. */
  readonly sharedCheckInId: string;
}

function seed(databasePath: string): Seeded {
  const dbPath = databasePath.startsWith('postgres')
    ? join(tmpdir(), `bench-${Date.now()}-${Math.random().toString(36).substring(2)}.db`)
    : databasePath;
  const db = new BetterSqlite3(dbPath);
  db.pragma('foreign_keys = ON');
  runMigrations(db, loadMigrations(LEGACY_SQLITE_MIGRATIONS_DIR));

  const uids = Array.from(
    { length: USERS },
    (_, index) => `uid-${index.toString().padStart(4, '0')}`,
  );
  const now = Date.now();

  const insertProfile = db.prepare(
    `INSERT INTO social_profiles
       (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
     VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)`,
  );
  const insertFriendship = db.prepare(
    `INSERT OR IGNORE INTO friendships (user_a_uid, user_b_uid, created_at) VALUES (?, ?, ?)`,
  );
  const insertCheckIn = db.prepare(
    `INSERT INTO social_workout_checkins
       (id, author_uid, source_session_sync_id, client_request_id, status, created_at, caption)
     VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?)`,
  );
  const insertComment = db.prepare(
    `INSERT INTO social_checkin_comments (id, checkin_id, author_uid, body, created_at, deleted_at)
     VALUES (?, ?, ?, ?, ?, NULL)`,
  );
  const insertReaction = db.prepare(
    `INSERT OR IGNORE INTO social_checkin_reactions
       (checkin_id, reactor_uid, type, created_at, updated_at)
     VALUES (?, ?, 'FIRE', ?, ?)`,
  );
  // T17.12 — as mesmas duas tabelas, agora na audiência de um Squad.
  const insertGroup = db.prepare(
    `INSERT INTO social_groups (id, owner_uid, name, status, created_at, updated_at)
     VALUES (?, ?, ?, 'ACTIVE', ?, ?)`,
  );
  const insertMembership = db.prepare(
    `INSERT INTO social_group_memberships (id, group_id, member_uid, role, joined_at)
     VALUES (?, ?, ?, ?, ?)`,
  );
  const insertShare = db.prepare(
    `INSERT OR IGNORE INTO social_group_checkin_shares
       (id, group_id, checkin_id, author_uid, created_at)
     VALUES (?, ?, ?, ?, ?)`,
  );
  const insertGroupComment = db.prepare(
    `INSERT INTO social_checkin_comments
       (id, checkin_id, author_uid, body, audience_type, group_id, created_at, deleted_at)
     VALUES (?, ?, ?, ?, 'GROUP', ?, ?, NULL)`,
  );
  const insertGroupReaction = db.prepare(
    `INSERT OR IGNORE INTO social_checkin_reactions
       (checkin_id, reactor_uid, type, audience_type, group_id, created_at, updated_at)
     VALUES (?, ?, 'MUSCLE', 'GROUP', ?, ?, ?)`,
  );

  const checkInIds: string[] = [];
  /** Por Squad: os membros e os check-ins trazidos para lá. */
  const membersByGroup: string[][] = [];
  const sharesByGroup: string[][] = [];

  db.transaction(() => {
    uids.forEach((uid, index) => {
      insertProfile.run(
        uid,
        randomUUID(),
        `SPK-${index.toString().padStart(8, '0')}`,
        `User ${index}`,
        now,
        now,
      );
    });

    // Grafo denso o bastante para que "os amigos do viewer" não seja um conjunto trivial.
    for (let i = 0; i < USERS; i += 1) {
      for (let step = 1; step <= FRIENDSHIPS_PER_USER; step += 1) {
        const other = (i + step) % USERS;
        if (other === i) continue;
        const [a, b] = uids[i] < uids[other] ? [uids[i], uids[other]] : [uids[other], uids[i]];
        insertFriendship.run(a, b, now);
      }
    }

    for (let i = 0; i < CHECKINS; i += 1) {
      const id = `checkin-${i}`;
      checkInIds.push(id);
      insertCheckIn.run(
        id,
        uids[i % USERS],
        randomUUID(),
        randomUUID(),
        // Espalhados por 60 dias: metade cai fora da janela de 30 dias do Feed.
        now - (i % 60) * 24 * 60 * 60 * 1000,
        i % 3 === 0 ? `Treino ${i}` : null,
      );
    }

    for (let i = 0; i < COMMENTS; i += 1) {
      insertComment.run(
        `comment-${i}`,
        checkInIds[i % CHECKINS],
        uids[(i * 7) % USERS],
        `Comentário ${i}`,
        now - (i % 1000),
      );
    }

    for (let i = 0; i < REACTIONS; i += 1) {
      // O reator precisa variar **entre** as voltas sobre `checkInIds`, senão a chave primária
      // `(checkin_id, reactor_uid)` colapsa cada volta na anterior e o volume semeado é um quinto
      // do pedido — sem que nada falhe, porque o `INSERT OR IGNORE` engole a colisão.
      const lap = Math.floor(i / CHECKINS);
      insertReaction.run(checkInIds[i % CHECKINS], uids[(i * 13 + lap) % USERS], now, now);
    }

    // ---------------------------------------------------------------- T17.12 §169
    //
    // 100 Squads de 20 pessoas, cada um com os check-ins **dos próprios membros** trazidos para
    // dentro. Os autores precisam ser membros porque a política do feed de grupo exige isso — semear
    // shares de não-membros produziria volume que nenhuma consulta real leria, e o plano medido não
    // seria o plano de produção.
    for (let g = 0; g < GROUPS; g += 1) {
      const groupId = `group-${g}`;
      const members = Array.from(
        { length: MEMBERS_PER_GROUP },
        (_, m) => uids[(g * 7 + m) % USERS],
      );
      membersByGroup.push(members);
      insertGroup.run(groupId, members[0], `Squad ${g}`, now, now);
      members.forEach((member, index) => {
        insertMembership.run(
          `membership-${g}-${index}`,
          groupId,
          member,
          index === 0 ? 'OWNER' : 'MEMBER',
          now,
        );
      });

      const shares: string[] = [];
      members.forEach((member, index) => {
        // `checkin-${i}` é de `uids[i % USERS]`, então este índice pertence a `member`.
        const memberIndex = (g * 7 + index) % USERS;
        const checkInId = `checkin-${memberIndex + USERS * (g % (CHECKINS / USERS))}`;
        insertShare.run(`share-${g}-${index}`, groupId, checkInId, member, now);
        shares.push(checkInId);
      });
      sharesByGroup.push(shares);
    }

    // O índice **dentro do Squad** (`k`), e não o contador global: como `GROUPS` é múltiplo de
    // `MEMBERS_PER_GROUP`, um `i % shares.length` daria o mesmo share em todas as voltas de um
    // mesmo grupo — a conversa inteira cairia sobre um check-in só, e a unicidade da reação
    // engoliria o resto em silêncio (foi o que o teste de volume pegou).
    for (let i = 0; i < GROUP_COMMENTS; i += 1) {
      const g = i % GROUPS;
      const k = Math.floor(i / GROUPS);
      const shares = sharesByGroup[g];
      const members = membersByGroup[g];
      insertGroupComment.run(
        `group-comment-${i}`,
        shares[k % shares.length],
        members[Math.floor(k / shares.length) % members.length],
        `Comentário de squad ${i}`,
        `group-${g}`,
        now - (i % 1000),
      );
    }

    for (let i = 0; i < GROUP_REACTIONS; i += 1) {
      const g = i % GROUPS;
      const k = Math.floor(i / GROUPS);
      const shares = sharesByGroup[g];
      const members = membersByGroup[g];
      insertGroupReaction.run(
        shares[k % shares.length],
        members[Math.floor(k / shares.length) % members.length],
        `group-${g}`,
        now,
        now,
      );
    }
  })();

  // `uids[0]` é membro (e dono) de `group-0` pela fórmula acima — é dele a leitura medida.
  return { db, viewer: uids[0], uids, groupId: 'group-0', sharedCheckInId: sharesByGroup[0][0] };
}

/** O plano de execução, achatado numa string por linha. */
function planOf(db: BetterSqlite3.Database, sql: string, params: unknown): string[] {
  const statement = db.prepare(`EXPLAIN QUERY PLAN ${sql}`);
  const rows = (
    Array.isArray(params) ? statement.all(...(params as unknown[])) : statement.all(params)
  ) as Array<{ detail: string }>;
  return rows.map((row) => row.detail);
}

/**
 * Uma varredura completa de tabela — o padrão que não pode aparecer.
 *
 * `SCAN` sobre uma CTE materializada é aceitável e esperado: a CTE já é o resultado reduzido de
 * uma consulta indexada. O que se procura é `SCAN <tabela real>` **sem** índice.
 */
function fullTableScans(plan: string[], tables: readonly string[]): string[] {
  return plan.filter((line) =>
    tables.some(
      (table) =>
        new RegExp(`SCAN\\s+${table}\\b`).test(line) && !/USING\s+(COVERING\s+)?INDEX/.test(line),
    ),
  );
}

const REAL_TABLES = [
  'social_workout_checkins',
  'social_checkin_comments',
  'social_checkin_reactions',
  'social_checkin_media',
  'friendships',
  'social_blocks',
  'social_profiles',
  'sync_entities',
  'social_groups',
  'social_group_memberships',
  'social_group_checkin_shares',
] as const;

describe('T17.10 — desempenho das consultas sociais sob volume', () => {
  let temp: TempDb;
  let seeded: Seeded;

  beforeAll(() => {
    temp = createTempDb();
    seeded = seed(temp.path);
    // `ANALYZE` é o que o SQLite usa em produção depois de um tempo de uso. Sem ele o planejador
    // decide por heurística de tabela vazia, e o plano lido aqui não seria o plano real.
    seeded.db.exec('ANALYZE');
  });

  afterAll(() => {
    seeded?.db.close();
    temp?.cleanup();
  });

  const FEED_SQL = `
    WITH ${VIEWER_SCOPE_CTE}
    SELECT c.id, c.author_uid, p.social_id, p.display_name, c.caption, c.created_at
      FROM social_workout_checkins c
      JOIN eligible_authors ea ON ea.uid = c.author_uid
      JOIN social_profiles p   ON p.owner_uid = c.author_uid
     WHERE c.status = 'PUBLISHED'
       AND c.created_at >= :publishedSince
       AND p.status = 'ACTIVE'
     ORDER BY c.created_at DESC, c.id DESC
     LIMIT :limit`;

  const COMMENTS_SQL = `
    WITH ${VIEWER_SCOPE_CTE}
    SELECT cm.id, cm.author_uid, p.social_id, p.display_name, cm.body, cm.created_at
      FROM social_checkin_comments cm
      JOIN social_workout_checkins c ON c.id = cm.checkin_id
      JOIN social_profiles p         ON p.owner_uid = cm.author_uid
     WHERE cm.checkin_id = :checkInId
       AND cm.deleted_at IS NULL
       AND ${interactionVisibleSql('cm.author_uid', 'c.author_uid')}
     ORDER BY cm.created_at ASC, cm.id ASC
     LIMIT :limit`;

  const REACTION_COUNTS_SQL = `
    WITH ${VIEWER_SCOPE_CTE}
    SELECT r.checkin_id, r.type, COUNT(*) AS total
      FROM social_checkin_reactions r
      JOIN social_workout_checkins c ON c.id = r.checkin_id
     WHERE r.checkin_id IN (@id0, @id1, @id2)
       AND ${interactionVisibleSql('r.reactor_uid', 'c.author_uid')}
     GROUP BY r.checkin_id, r.type`;

  /**
   * T17.12 §168 — as mesmas duas leituras, na audiência de um Squad.
   *
   * São consultas **diferentes** das de amigos, e não variações: onde a de `FRIEND` junta
   * `friendships` para saber quem alcança quem, a de `GROUP` junta `social_group_memberships`. É
   * por isso que elas precisam do próprio plano auditado — um índice que sirva a uma não diz nada
   * sobre a outra. As duas são as consultas reais de `CheckInInteractionRepository`, montadas com
   * os mesmos fragmentos exportados.
   */
  const GROUP_COMMENTS_SQL = `
    WITH ${VIEWER_BLOCKED_CTE}
    SELECT cm.id, cm.author_uid, p.social_id, p.display_name, cm.body, cm.created_at
      FROM social_checkin_comments cm
      JOIN social_profiles p ON p.owner_uid = cm.author_uid
     WHERE cm.checkin_id = :checkInId
       AND cm.audience_type = 'GROUP'
       AND cm.group_id = :groupId
       AND cm.deleted_at IS NULL
       AND ${viewerInActiveGroupSql()}
       AND ${groupInteractionVisibleSql('cm.author_uid', 'cm.group_id')}
     ORDER BY cm.created_at ASC, cm.id ASC
     LIMIT :limit`;

  const GROUP_REACTION_COUNTS_SQL = `
    WITH ${VIEWER_BLOCKED_CTE}
    SELECT r.checkin_id, r.type, COUNT(*) AS total
      FROM social_checkin_reactions r
     WHERE r.checkin_id IN (@id0, @id1, @id2)
       AND r.audience_type = 'GROUP'
       AND r.group_id = :groupId
       AND ${viewerInActiveGroupSql()}
       AND ${groupInteractionVisibleSql('r.reactor_uid', 'r.group_id')}
     GROUP BY r.checkin_id, r.type`;

  const FRIENDS_SQL = `
    SELECT CASE WHEN f.user_a_uid = :viewer THEN f.user_b_uid ELSE f.user_a_uid END AS uid
      FROM friendships f
     WHERE f.user_a_uid = :viewer OR f.user_b_uid = :viewer`;

  const BLOCKED_SQL = `
    SELECT blocked_uid FROM social_blocks WHERE blocker_uid = :viewer
    UNION
    SELECT blocker_uid FROM social_blocks WHERE blocked_uid = :viewer`;

  const surfaces: Array<[string, string, Record<string, unknown>]> = [
    [
      'feed',
      FEED_SQL,
      {
        viewer: '',
        publishedSince: 0,
        limit: 20,
      },
    ],
    ['comentários', COMMENTS_SQL, { viewer: '', checkInId: 'checkin-1', limit: 30 }],
    [
      'contagem de reações',
      REACTION_COUNTS_SQL,
      { viewer: '', id0: 'checkin-1', id1: 'checkin-2', id2: 'checkin-3' },
    ],
    ['lista de amigos', FRIENDS_SQL, { viewer: '' }],
    ['bloqueados', BLOCKED_SQL, { viewer: '' }],
    [
      'comentários de squad',
      GROUP_COMMENTS_SQL,
      { viewer: '', checkInId: '', groupId: '', limit: 30 },
    ],
    [
      'contagem de reações de squad',
      GROUP_REACTION_COUNTS_SQL,
      { viewer: '', groupId: '', id0: '', id1: '', id2: '' },
    ],
  ];

  /** Os parâmetros que só existem depois do seed — o Squad do viewer e um check-in dele. */
  const bind = (params: Record<string, unknown>) => {
    const shared = seeded.sharedCheckInId;
    return {
      ...params,
      viewer: seeded.viewer,
      groupId: params.groupId === '' ? seeded.groupId : params.groupId,
      checkInId: params.checkInId === '' ? shared : params.checkInId,
      ...(params.id0 === '' ? { id0: shared, id1: shared, id2: shared } : {}),
    };
  };

  it.each(surfaces)(
    'a consulta de %s não faz varredura completa de tabela (§36)',
    (label, sql, params) => {
      const plan = planOf(seeded.db, sql, bind(params));
      const scans = fullTableScans(plan, REAL_TABLES);
      expect({ label, scans, plan: scans.length > 0 ? plan : [] }).toEqual({
        label,
        scans: [],
        plan: [],
      });
    },
  );

  it.each(surfaces)('a consulta de %s responde rápido sob volume (§124)', (label, sql, params) => {
    const statement = seeded.db.prepare(sql);
    const bound = {
      ...bind(params),
      publishedSince: Date.now() - 30 * 86_400_000,
    };

    // Uma execução para aquecer o cache de página, e dez medidas.
    statement.all(bound);
    const started = process.hrtime.bigint();
    for (let i = 0; i < 10; i += 1) {
      statement.all(bound);
    }
    const perCallMs = Number(process.hrtime.bigint() - started) / 1e6 / 10;

    // Um teto folgado de propósito: ele não afirma latência de produção, ele pega a consulta que
    // degenerou em varredura — que numa base deste tamanho custa ordens de grandeza mais.
    expect({ label, slow: perCallMs > 150 }).toEqual({ label, slow: false });
  });

  it('o volume semeado é o que a auditoria pediu (§123)', () => {
    const count = (table: string) =>
      (seeded.db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get() as { n: number }).n;
    expect(count('social_profiles')).toBe(USERS);
    expect(count('social_workout_checkins')).toBe(CHECKINS);
    expect(count('friendships')).toBeGreaterThan(2_000);

    // T17.12 §169 — o volume de Squad, e a prova de que as duas audiências coexistem na mesma
    // tabela sem uma esconder a outra.
    expect(count('social_groups')).toBe(GROUPS);
    expect(count('social_group_memberships')).toBe(GROUPS * MEMBERS_PER_GROUP);
    const byAudience = (table: string, audience: string) =>
      (
        seeded.db
          .prepare(`SELECT COUNT(*) AS n FROM ${table} WHERE audience_type = ?`)
          .get(audience) as { n: number }
      ).n;
    expect(byAudience('social_checkin_comments', 'FRIEND')).toBe(COMMENTS);
    expect(byAudience('social_checkin_comments', 'GROUP')).toBe(GROUP_COMMENTS);
    expect(byAudience('social_checkin_reactions', 'FRIEND')).toBeGreaterThan(40_000);
    expect(byAudience('social_checkin_reactions', 'GROUP')).toBe(GROUP_REACTIONS);
  });

  it('o banco continua íntegro depois do volume (§110)', () => {
    expect(seeded.db.pragma('integrity_check')).toEqual([{ integrity_check: 'ok' }]);
    expect(seeded.db.pragma('foreign_key_check')).toEqual([]);
  });
});
