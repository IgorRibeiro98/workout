import { Inject, Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import {
  CANONICAL_TRAINING_SOURCE,
  type CanonicalTrainingSource,
  SyncedCanonicalTrainingSource,
} from './canonical-training.source';
import {
  calendarDateAsUtc,
  DAY_MS,
  isValidTimeZone,
  localMidnightToInstant,
  parseCalendarDate,
} from './social-time';

/**
 * A fronteira entre a pontuação de desafio e o estado canônico de treino (T17.3 §77–§79).
 *
 * ```text
 * WorkoutSession canônica (Room → Outbox → sync_entities)
 *          │
 *          ▼
 * ChallengeProgressSource     ← este arquivo: um escalar por pergunta
 *          │
 *          ▼
 * ChallengeScoringService     ← ranking, empates, goalReached
 *          │
 *          ▼
 * placar
 * ```
 *
 * ## Por que ela não é `SocialProgressSource`
 *
 * Porque as duas respondem perguntas diferentes, e **a T17.2 não é autoridade de pontuação**
 * (§segundo princípio, e o invariante 22 do `ARCHITECTURE.md §18`).
 *
 * ```text
 * PROIBIDO                          CORRETO
 * SocialProgressProjection          dados canônicos de treino
 *          ↓                              ├── SocialProgressSource  → perfil
 * pontuação do desafio                    └── ChallengeProgressSource → desafio
 * ```
 *
 * A diferença não é organizacional. `SocialProgressSource.getWeeklyWorkoutCount` responde sobre a
 * **semana canônica do dono**, filtrada pela privacidade dele, e devolve `UNAVAILABLE` quando o
 * dono nunca sincronizou — três decisões que existem para uma tela de perfil e que estariam
 * erradas aqui. Um desafio tem janela própria, ignora os interruptores de perfil (§terceiro
 * princípio) e precisa que "zero" seja zero: um participante que não treinou tem 0 de 12, e não um
 * campo ausente.
 *
 * ## As duas regras que este arquivo existe para não quebrar
 *
 * 1. **`AGGREGATE_ONLY`** (`social.projection.ts`). Cada método devolve **um número**. Não há —
 *    e não pode haver — `getSessions()`, `List<RawSyncEntity>` ou qualquer coisa que materialize
 *    uma linha de treino em JavaScript (§79). As duas consultas abaixo selecionam `COUNT(*)`, e
 *    `json_extract` aparece só na cláusula `WHERE`: o conteúdo de nenhuma sessão — carga,
 *    exercício, nota, horário — sai do SQLite;
 * 2. **`SINGLE_AUTHORITY`**. A definição de "treino que conta" não foi escolhida aqui: ela é a do
 *    schema canônico (`workoutSessionSchema` só aceita `status: 'COMPLETED'`), e a de "em que dia
 *    o treino aconteceu" é a do `ConsistencyCalculator`, que agrupa por `startedAt`. O Social não
 *    cria uma segunda regra de conclusão de treino — esse é um bloqueante literal da tarefa.
 *
 * ## `startedAt`, e não `finishedAt` — a divergência resolvida
 *
 * A tarefa fala em `completedAt`. **O Spark não tem esse campo**, e o código é a autoridade sobre
 * o que existe:
 *
 * ```text
 * WorkoutSessionSyncDto     status: String       (o servidor só aceita 'COMPLETED')
 *                           startedAt: Long      obrigatório
 *                           finishedAt: Long?    NULÁVEL
 * ```
 *
 * O instante canônico que atribui um treino a um dia é `startedAt`, e isso está estabelecido em
 * três lugares independentes do repositório: `ConsistencyRepositoryImpl` alimenta o
 * `ConsistencyCalculator` com `session.startedAt`; a fixture
 * `contracts/social/v1/weekly-window.json` declara `"counts": "... pelo campo startedAt"`; e a
 * T17.2 conta a semana por ele.
 *
 * Usar `finishedAt` teria dois defeitos, e os dois são bloqueantes:
 *
 * - seria uma **segunda regra** de atribuição de treino a dia. Um treino que começa 23h30 e
 *   termina 00h15 cairia em dias diferentes no desafio e na tela de consistência da própria
 *   pessoa — e a pessoa veria dois números para o mesmo treino;
 * - `finishedAt` é **nulável** no schema canônico. Sessões concluídas com ele nulo simplesmente
 *   deixariam de contar, sem que ninguém percebesse. §207 manda não contar sessão sem instante
 *   válido; com `startedAt` isso é a regra correta e vazia (o campo é obrigatório), e com
 *   `finishedAt` seria uma perda silenciosa de treinos reais.
 *
 * ## Sincronização tardia (§71/§72)
 *
 * Nenhuma consulta aqui olha `created_at`, `updated_at` ou `last_server_sequence` de
 * `sync_entities`. A elegibilidade é o instante em que o treino **aconteceu**, e não o instante em
 * que ele chegou. É isto — e só isto — que faz um participante que treinou offline durante o
 * desafio ter o placar corrigido quando sincronizar, mesmo depois do fim (§237).
 *
 * ## Idempotência (§5/§208)
 *
 * Não há deduplicação escrita aqui, e não deve haver: `sync_entities` tem
 * `UNIQUE (owner_uid, entity_type, entity_sync_id)`. A mesma sessão é **uma linha**, para sempre,
 * por mais vezes que o push seja reenviado ou o pull aconteça. Contar linhas já é contar sessões
 * distintas — e um `DISTINCT` aqui seria a defesa contra um problema que o schema não permite,
 * escondendo o dia em que essa garantia mudasse.
 */
export interface ChallengeProgressSource {
  /**
   * Quantas sessões **concluídas** este dono tem na janela `[startMs, endMsExclusive)`.
   *
   * Uma sessão canônica `COMPLETED` cujo `startedAt` cai na janela vale 1 ponto (§3).
   * `PLANNED`, `IN_PROGRESS`, `PAUSED` e `CANCELLED` não valem nada (§4).
   */
  countCompletedWorkouts(ownerUid: string, startMs: number, endMsExclusive: number): number;

  /**
   * Em quantos **dias de calendário** do desafio este dono concluiu pelo menos um treino (§7).
   *
   * Dois treinos no mesmo dia contam 1, e não 2. O dia é o dia local do fuso **do desafio** (§8/§9)
   * — o mesmo para todos os participantes.
   */
  countActiveDays(ownerUid: string, startDate: string, endDate: string, timeZoneId: string): number;
}

/** O token de injeção. Interface no ponto de injeção, como `SOCIAL_PROGRESS_SOURCE` (T17.2). */
export const CHALLENGE_PROGRESS_SOURCE = Symbol('CHALLENGE_PROGRESS_SOURCE');

/**
 * A implementação sobre o estado que de fato chega ao servidor.
 *
 * Ela fala com o `SqliteService` — infraestrutura compartilhada do processo —, e **não** com
 * `SyncRepository`, cuja superfície é o protocolo de sync inteiro. O `SocialModule` continua sem
 * importar `SyncModule`, `BackupModule` e `AiModule`, e há teste estrutural sobre isso.
 *
 * Backup permanece **inalcançável em qualquer forma**: `backup_snapshots`, `backup_items` e
 * `backup_payloads` não são lidos aqui. Um snapshot é a conta inteira em um documento, e abri-lo
 * para contar treinos seria abrir a caixa errada.
 */
@Injectable()
export class SyncedChallengeProgressSource implements ChallengeProgressSource {
  private readonly trainingSource: CanonicalTrainingSource;

  constructor(
    @Inject(CANONICAL_TRAINING_SOURCE)
    trainingSourceOrSqlite: CanonicalTrainingSource | SqliteService,
  ) {
    if (
      'countCompletedWorkouts' in trainingSourceOrSqlite &&
      'countActiveDays' in trainingSourceOrSqlite
    ) {
      this.trainingSource = trainingSourceOrSqlite;
    } else {
      this.trainingSource = new SyncedCanonicalTrainingSource(trainingSourceOrSqlite);
    }
  }

  /**
   * `WORKOUTS_COMPLETED`.
   *
   * Delega à fonte canônica de treino centralizada (T17.4.1).
   */
  countCompletedWorkouts(ownerUid: string, startMs: number, endMsExclusive: number): number {
    return this.trainingSource.countCompletedWorkouts(ownerUid, startMs, endMsExclusive);
  }

  /**
   * `ACTIVE_DAYS`.
   *
   * Delega à fonte canônica de treino centralizada (T17.4.1).
   */
  countActiveDays(
    ownerUid: string,
    startDate: string,
    endDate: string,
    timeZoneId: string,
  ): number {
    return this.trainingSource.countActiveDays(ownerUid, startDate, endDate, timeZoneId);
  }
}

/** A faixa `[início, fim)` de um dia de calendário, em epoch millis UTC. */
export interface ChallengeDayWindow {
  readonly startMs: number;
  readonly endMs: number;
}

/**
 * Os dias de calendário de um desafio, como faixas de instantes.
 *
 * Exportada porque é a regra de "que dias este desafio cobre", e o teste precisa poder afirmá-la
 * diretamente — inclusive nas viradas de horário de verão, onde uma faixa tem 23 ou 25 horas.
 *
 * A conversão é a **mesma** de `canonicalWeekWindow` (T17.2): as duas chamam
 * `localMidnightToInstant`, de `social-time.ts`. Uma segunda implementação de "meia-noite local"
 * divergiria da primeira, e a divergência apareceria como o perfil e o desafio discordando sobre o
 * dia de um treino.
 */
export function challengeDayWindows(
  startDate: string,
  endDate: string,
  timeZoneId: string,
): readonly ChallengeDayWindow[] {
  if (!isValidTimeZone(timeZoneId)) {
    return [];
  }
  const start = parseCalendarDate(startDate);
  const end = parseCalendarDate(endDate);
  if (!start || !end) {
    return [];
  }

  const startAsUtc = calendarDateAsUtc(start);
  const endAsUtc = calendarDateAsUtc(end);
  if (endAsUtc < startAsUtc) {
    return [];
  }

  const windows: ChallengeDayWindow[] = [];
  // A iteração é sobre **datas** (aritmética de calendário, sem fuso); a conversão para instante
  // acontece por dia. Iterar sobre instantes somando 24h é exatamente o erro de §203.
  for (let dateAsUtc = startAsUtc; dateAsUtc <= endAsUtc; dateAsUtc += DAY_MS) {
    windows.push({
      startMs: localMidnightToInstant(dateAsUtc, timeZoneId),
      endMs: localMidnightToInstant(dateAsUtc + DAY_MS, timeZoneId),
    });
  }
  return windows;
}
