import { Injectable } from '@nestjs/common';
import { PostgresService } from '../../database/postgres.service';

/**
 * Uma série de uma sessão concluída, como o Social pode conhecê-la (T19.H3 §21/§39).
 *
 * Só o que a projeção social usa. RPE, RIR, instantes de início/fim da série e o número da série
 * **não** existem neste tipo — e não é que estejam escondidos: a consulta nunca os seleciona
 * (ver [SyncedSocialWorkoutFactsSource]).
 */
export interface SocialSetFact {
  /** `NORMAL`, `WARMUP`, `DROP_SET`… — o tipo decide se a série conta (aquecimento não conta). */
  readonly type: string;
  readonly weightKg: number;
  readonly repetitions: number;
  /** Presente e > 0 quando a série é por tempo: então `repetitions` não tem significado. */
  readonly durationSeconds: number | null;
  readonly completed: boolean;
}

/** Um exercício executado, pelo **snapshot** da sessão — o nome que existia no dia do treino. */
export interface SocialExerciseFact {
  readonly name: string;
  readonly primaryMuscle: string | null;
  readonly sets: readonly SocialSetFact[];
}

/**
 * Os fatos de **uma** `WORKOUT_SESSION` concluída que a camada social pode ler (T19.H3 §39).
 *
 * `sessionSyncId` e `ownerUid` existem para o servidor **casar** fato com publicação. Nenhum dos
 * dois cruza a fronteira: o projetor de resumo (`social-workout-summary.ts`) não os copia para DTO
 * nenhum, e o sweep de privacidade procura os dois em toda resposta social.
 */
export interface SocialWorkoutFacts {
  readonly ownerUid: string;
  readonly sessionSyncId: string;
  /** `templateNameSnapshot`: o nome do treino no dia, ou `null` para treino livre. */
  readonly name: string | null;
  readonly startedAt: number;
  readonly finishedAt: number | null;
  readonly exercises: readonly SocialExerciseFact[];
}

/** Uma sessão pedida: o dono (resolvido no servidor) e a identidade canônica dela. */
export interface SessionRef {
  readonly ownerUid: string;
  readonly sessionSyncId: string;
}

/**
 * A fronteira tipada entre o Social e o **conteúdo** de uma sessão sincronizada (T19.H3 §39).
 *
 * ## Por que uma segunda porta, e não mais um método em `CanonicalTrainingSource`
 *
 * `CanonicalTrainingSource` responde **agregado**: contagens, dias ativos, instantes. Há um teste
 * estrutural que a mantém assim (`exercises`, `reps`, `notes` e `JSON.parse` proibidos no arquivo
 * dela), e ele continua valendo. A T19.H3 precisa de outra coisa — exercícios e séries —, e essa
 * outra coisa ganhou uma porta **própria**, com a sua própria regra estrutural: uma whitelist de
 * campos montada no SQL. Misturar as duas faria a regra da primeira ceder para acomodar a segunda.
 *
 * ## O que ela nunca devolve
 *
 * Payload cru, notas da sessão ou do exercício, `machineLabelSnapshot`, `replacementReason`, RPE,
 * RIR, referências de exercício (`plannedExercise`/`actualExercise`, que carregam `syncId`) e
 * `templateSyncId`. O `jsonb_build_object` abaixo é a lista **inteira** do que sai do banco — um
 * campo novo no snapshot de sync não aparece aqui sem alguém escrevê-lo nesta consulta.
 */
export interface SocialWorkoutFactsSource {
  /**
   * Os fatos das sessões pedidas, **se** existirem, forem desta conta, estiverem `COMPLETED` e sem
   * tombstone. Uma consulta para o lote inteiro (o Feed pede até 50 de uma vez, §38).
   *
   * A chave do mapa é [factsKey]: a sessão pertence ao dono pedido **por construção** — o dono
   * está na cláusula `JOIN`, e não é filtrado depois de ler.
   */
  findSessions(refs: readonly SessionRef[]): Promise<Map<string, SocialWorkoutFacts>>;

  /**
   * As sessões concluídas deste dono cujo início cai em `[startMs, endMsExclusive)`.
   *
   * Bounded: mais de [limit] sessões na janela devolve `null` — quem chama responde "não
   * disponível" em vez de publicar uma soma truncada (um número menor que o real, com a
   * autoridade do servidor, é pior do que campo ausente).
   */
  findCompletedInWindow(
    ownerUid: string,
    startMs: number,
    endMsExclusive: number,
    limit: number,
  ): Promise<readonly SocialWorkoutFacts[] | null>;
}

export const SOCIAL_WORKOUT_FACTS_SOURCE = Symbol('SOCIAL_WORKOUT_FACTS_SOURCE');

/** A chave de uma sessão no mapa de [SocialWorkoutFactsSource.findSessions]. */
export function factsKey(ownerUid: string, sessionSyncId: string): string {
  return `${ownerUid}\u0000${sessionSyncId}`;
}

/**
 * A projeção whitelisted de uma linha de `sync_entities`, em SQL.
 *
 * `doc` é o payload já convertido para `jsonb`. Tudo o que sai daqui está nomeado — nenhum
 * `doc` inteiro, nenhum `e` inteiro, nenhum `st` inteiro é selecionado.
 */
const FACTS_PROJECTION = `
  doc->>'templateNameSnapshot'                  AS name,
  CAST(doc->>'startedAt' AS BIGINT)             AS started_at,
  CAST(doc->>'finishedAt' AS BIGINT)            AS finished_at,
  COALESCE((
    SELECT jsonb_agg(
             jsonb_build_object(
               'name',          e->>'exerciseNameSnapshot',
               'primaryMuscle', e->>'primaryMuscleSnapshot',
               'sets', COALESCE((
                 SELECT jsonb_agg(
                          jsonb_build_object(
                            'type',            st->>'type',
                            'weight',          st->'weight',
                            'repetitions',     st->'repetitions',
                            'durationSeconds', st->'durationSeconds',
                            'completed',       st->'completed'
                          ) ORDER BY set_position)
                   FROM jsonb_array_elements(
                          CASE WHEN jsonb_typeof(e->'sets') = 'array' THEN e->'sets'
                               ELSE '[]'::jsonb END
                        ) WITH ORDINALITY AS set_rows(st, set_position)
               ), '[]'::jsonb)
             ) ORDER BY exercise_position)
      FROM jsonb_array_elements(
             CASE WHEN jsonb_typeof(doc->'exercises') = 'array' THEN doc->'exercises'
                  ELSE '[]'::jsonb END
           ) WITH ORDINALITY AS exercise_rows(e, exercise_position)
  ), '[]'::jsonb)                               AS exercises`;

interface FactsRow {
  owner_uid: string;
  session_sync_id: string;
  name: string | null;
  started_at: string | number | null;
  finished_at: string | number | null;
  exercises: unknown;
}

/**
 * A implementação sobre o estado que de fato chega ao servidor por sync (T16).
 *
 * As mesmas regras de `CanonicalTrainingSource`: `WORKOUT_SESSION`, `deleted = FALSE`, `COMPLETED`,
 * `owner_uid` do alvo resolvido no servidor. Tombstone tem `payload = ''` (T17.8): o `NULLIF` e o
 * filtro de `deleted` vêm **antes** do cast para `jsonb`.
 */
@Injectable()
export class SyncedSocialWorkoutFactsSource implements SocialWorkoutFactsSource {
  constructor(private readonly db: PostgresService) {}

  async findSessions(refs: readonly SessionRef[]): Promise<Map<string, SocialWorkoutFacts>> {
    const result = new Map<string, SocialWorkoutFacts>();
    if (refs.length === 0) {
      return result;
    }

    const res = await this.db.query<FactsRow>(
      `WITH wanted AS (
         SELECT UNNEST($1::text[]) AS owner_uid, UNNEST($2::text[]) AS session_sync_id
       ),
       sessions AS (
         SELECT s.owner_uid, s.entity_sync_id, NULLIF(s.payload, '')::jsonb AS doc
           FROM sync_entities s
           JOIN wanted w
             ON w.owner_uid = s.owner_uid
            AND w.session_sync_id = s.entity_sync_id
          WHERE s.entity_type = 'WORKOUT_SESSION'
            AND s.deleted = FALSE
       )
       SELECT owner_uid, entity_sync_id AS session_sync_id, ${FACTS_PROJECTION}
         FROM sessions
        WHERE doc->>'status' = 'COMPLETED'`,
      [refs.map((ref) => ref.ownerUid), refs.map((ref) => ref.sessionSyncId)],
    );

    for (const row of res.rows) {
      const facts = toFacts(row);
      if (facts) {
        result.set(factsKey(facts.ownerUid, facts.sessionSyncId), facts);
      }
    }
    return result;
  }

  async findCompletedInWindow(
    ownerUid: string,
    startMs: number,
    endMsExclusive: number,
    limit: number,
  ): Promise<readonly SocialWorkoutFacts[] | null> {
    const res = await this.db.query<FactsRow>(
      `WITH sessions AS (
         SELECT s.owner_uid, s.entity_sync_id, NULLIF(s.payload, '')::jsonb AS doc
           FROM sync_entities s
          WHERE s.owner_uid = $1
            AND s.entity_type = 'WORKOUT_SESSION'
            AND s.deleted = FALSE
            AND (NULLIF(s.payload, '')::jsonb->>'status') = 'COMPLETED'
            AND CAST(NULLIF(s.payload, '')::jsonb->>'startedAt' AS BIGINT) >= $2
            AND CAST(NULLIF(s.payload, '')::jsonb->>'startedAt' AS BIGINT) < $3
          LIMIT $4
       )
       SELECT owner_uid, entity_sync_id AS session_sync_id, ${FACTS_PROJECTION}
         FROM sessions`,
      [ownerUid, startMs, endMsExclusive, limit + 1],
    );

    if (res.rows.length > limit) {
      return null;
    }
    return res.rows.map(toFacts).filter((facts): facts is SocialWorkoutFacts => facts !== null);
  }
}

/**
 * A linha vira tipo, e o que não tem a forma esperada é descartado.
 *
 * O payload já passou pelo schema estrito do sync (zod, `backup-entity.registry.ts`), então isto
 * é a segunda linha de defesa, não a primeira: um número não finito, uma série sem `completed`
 * booleano ou um exercício sem nome não viram "0" nem "sem nome" — somem da projeção.
 */
function toFacts(row: FactsRow): SocialWorkoutFacts | null {
  const startedAt = toFiniteNumber(row.started_at);
  if (startedAt === null) {
    return null;
  }
  const exercises = Array.isArray(row.exercises)
    ? row.exercises.map(toExercise).filter((item): item is SocialExerciseFact => item !== null)
    : [];

  return {
    ownerUid: row.owner_uid,
    sessionSyncId: row.session_sync_id,
    name: typeof row.name === 'string' ? row.name : null,
    startedAt,
    finishedAt: toFiniteNumber(row.finished_at),
    exercises,
  };
}

function toExercise(raw: unknown): SocialExerciseFact | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const item = raw as Record<string, unknown>;
  if (typeof item.name !== 'string') return null;
  const sets = Array.isArray(item.sets)
    ? item.sets.map(toSet).filter((set): set is SocialSetFact => set !== null)
    : [];
  return {
    name: item.name,
    primaryMuscle: typeof item.primaryMuscle === 'string' ? item.primaryMuscle : null,
    sets,
  };
}

function toSet(raw: unknown): SocialSetFact | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const item = raw as Record<string, unknown>;
  const weight = toFiniteNumber(item.weight);
  const repetitions = toFiniteNumber(item.repetitions);
  if (weight === null || repetitions === null || typeof item.completed !== 'boolean') {
    return null;
  }
  return {
    type: typeof item.type === 'string' ? item.type : 'NORMAL',
    weightKg: weight,
    repetitions,
    durationSeconds: toFiniteNumber(item.durationSeconds),
    completed: item.completed,
  };
}

function toFiniteNumber(value: unknown): number | null {
  if (value === null || value === undefined) return null;
  const number = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(number) ? number : null;
}
