import { Inject, Injectable, Optional } from '@nestjs/common';
import { PostgresService } from '../../database/postgres.service';
import {
  CANONICAL_TRAINING_SOURCE,
  type CanonicalTrainingSource,
  type LocalDayWindow,
  SyncedCanonicalTrainingSource,
} from './canonical-training.source';
import {
  calculateProgress,
  calculateWeeklyConsistencies,
  type ConsistencyParameters,
  localEpochDay,
  type WeeklyConsistency,
  weekStartEpochDay,
} from './social-consistency';
import { evaluateVerifiedAchievements, levelFor, projectVerifiedXp } from './social-gamification';
import type { SocialAvailabilityReason } from './social-profile.contract';
import { type TrainingTotals, trainingTotals } from './social-training-metrics';
import {
  SOCIAL_WORKOUT_FACTS_SOURCE,
  type SocialWorkoutFactsSource,
  SyncedSocialWorkoutFactsSource,
} from './social-workout-facts.source';
import {
  MAX_WEEKLY_SESSIONS_FOR_TRAINING_STATS,
  MIN_SOCIAL_TRACKING_EPOCH_DAY,
} from './social.limits';
import { DAY_MS, isValidTimeZone, localCalendarDate, localMidnightToInstant } from './social-time';

/**
 * A fronteira estreita entre o Social e o estado canônico do Spark (T17.2 §9/§11, T19.2).
 *
 * ```text
 * autoridades reais do Spark          SocialProgressSource          SocialProgressProjector
 * (Room do aparelho, e o que          ─────────────────────▶        ─────────────────────▶
 *  chega ao servidor por sync)        um valor por métrica           projeção segura
 * ```
 *
 * ## Por que uma interface, e por que tão pobre
 *
 * Porque é ela que impede a alternativa: um `FriendProfileService` importando `SyncRepository` e
 * fazendo `SELECT payload`. Isso funcionaria no primeiro dia e seria irreversível no segundo —
 * a partir daí, um campo novo no snapshot de treino viraria campo novo na superfície social sem
 * que ninguém decidisse isso.
 *
 * O que sai é **um valor por métrica**, com disponibilidade. Nenhum método devolve payload,
 * agregado bruto, lista de sessões, série, carga, nota, medida ou timestamp de treino.
 *
 * ## O que mudou na T19.2
 *
 * Até a T17.2 a fonte lia estado sincronizado e respondia `UNSUPPORTED` para o que exigia regra
 * de domínio. A T19.2 deu ao servidor uma **autoridade remota** própria: a regra canônica de
 * consistência (`social-consistency.ts`) e a matriz de gamificação reconstruível
 * (`social-gamification.ts`), as duas presas ao Android por fixtures em `contracts/social/v1/`.
 * A fonte continua não sendo autoridade sobre o que o **dono** vê no aparelho; ela é autoridade
 * sobre o que pode ser **publicado**, e só afirma o que reconstrói de fatos sincronizados.
 */
export interface SocialProgressSource {
  /**
   * O que o servidor consegue afirmar sobre o progresso deste dono, agora.
   *
   * Uma chamada, e não uma por métrica: as quatro métricas saem dos **mesmos** fatos agregados
   * (treinos por dia local, medições por dia local), e lê-los uma vez é o que mantém uma leitura
   * de perfil em poucas consultas.
   */
  project(ownerUid: string, context: SocialProgressContext): Promise<SocialProgressProjection>;
}

/**
 * O que a projeção precisa saber **além** dos fatos: o fuso e os parâmetros que o dono declarou,
 * e o relógio do servidor.
 *
 * O relógio é o do **servidor**: o relógio do aparelho não decide qual é a semana corrente de
 * ninguém — dois visitantes com relógios diferentes veriam semanas diferentes do mesmo perfil.
 */
export interface SocialProgressContext {
  readonly weekTimeZone: string | null;
  readonly consistency: ConsistencyParameters | null;
  readonly nowMs: number;
}

/**
 * O progresso de **um** dono, como o servidor consegue afirmá-lo — antes de qualquer privacidade.
 *
 * Cada campo é um [SocialProgressValue], nunca um número solto: é o tipo que carrega a diferença
 * entre "três treinos", "ainda não sei" e "esta versão não sabe". Um `number | null` colapsaria os
 * dois últimos, e o primeiro consumidor escreveria `?? 0`.
 */
export interface SocialProgressProjection {
  readonly level: SocialProgressValue<number>;
  readonly consistencyStreak: SocialProgressValue<number>;
  readonly weeklyWorkoutCount: SocialProgressValue<number>;
  readonly highlightedAchievementIds: SocialProgressValue<readonly string[]>;
  // ---- T19.H3 — estatísticas de treino (definições em `social-training-metrics.ts`)
  /** Minutos de treino (fim − início) das sessões da semana canônica. */
  readonly weeklyTrainingMinutes: SocialProgressValue<number>;
  /** Séries de trabalho concluídas na semana canônica. */
  readonly weeklyCompletedSets: SocialProgressValue<number>;
  /** `Σ peso × reps` da semana canônica, uma casa decimal. */
  readonly weeklyVolumeKg: SocialProgressValue<number>;
  /** Treinos concluídos desde sempre — não depende de fuso. */
  readonly totalWorkouts: SocialProgressValue<number>;
}

/**
 * O que a fonte consegue afirmar sobre uma métrica.
 *
 * Três casos, e nenhum deles é `0`. É aqui que a regra "ausência de dado não significa zero" (§4)
 * deixa de ser recomendação e vira tipo: quem consome não tem como escrever `value ?? 0`, porque
 * não existe `value` para ler quando a resposta não é `AVAILABLE`.
 */
export type SocialProgressValue<T> =
  | { readonly kind: 'AVAILABLE'; readonly value: T }
  /**
   * Suportado, e o servidor ainda não tem o que afirmar — e [reason] diz por quê (T19.H5). O tipo
   * exige o motivo: um `UNAVAILABLE` sem causa é exatamente o "Ainda não disponível" sem
   * explicação que a T19.H5 existe para acabar.
   */
  | { readonly kind: 'UNAVAILABLE'; readonly reason: SocialAvailabilityReason }
  /** Sem autoridade remota nesta versão do Spark. Sincronizar **não** resolve. */
  | { readonly kind: 'UNSUPPORTED' };

export const available = <T>(value: T): SocialProgressValue<T> => ({ kind: 'AVAILABLE', value });
export const unavailable = <T>(reason: SocialAvailabilityReason): SocialProgressValue<T> => ({
  kind: 'UNAVAILABLE',
  reason,
});
export const unsupported = <T>(): SocialProgressValue<T> => ({ kind: 'UNSUPPORTED' });

/**
 * O horizonte da projeção: nenhum dia anterior a 2020-01-01 é consultado.
 *
 * As janelas de dia entram na consulta como arrays, e o horizonte é o que mantém o array
 * limitado (~2,5 mil dias em 2026). É também o piso aceito para `trackingStartedAtEpochDay`.
 */
export const PROGRESS_HORIZON_EPOCH_DAY = MIN_SOCIAL_TRACKING_EPOCH_DAY;

/**
 * A implementação sobre o estado que **de fato** chega ao servidor.
 *
 * ## O que existe remotamente, verificado no código e não na documentação (T19.2)
 *
 * | Métrica | Como o servidor afirma | Quando responde `UNAVAILABLE`, e com que motivo (T19.H5) |
 * | --- | --- | --- |
 * | treinos da semana | `COUNT(*)` de `WORKOUT_SESSION` `COMPLETED` na semana canônica | sem fuso (`WEEK_TIME_ZONE_MISSING`), nenhuma sessão (`NO_SYNCED_WORKOUTS`) |
 * | sequência semanal | `social-consistency.ts` sobre treinos por dia + parâmetros declarados | sem fuso, nenhuma sessão, sem parâmetros (`CONSISTENCY_PARAMETERS_MISSING`) — nesta ordem |
 * | nível | `social-gamification.ts`: XP reconstruível → curva canônica | idem |
 * | conquistas | `REMOTE_ACHIEVEMENTS` reconstruíveis (treino, consistência, corpo) | sem fuso, ou lista vazia — o que só acontece sem nenhuma sessão nem medição (`NO_SYNCED_WORKOUTS`) |
 * | treinos totais (T19.H3) | `COUNT(*)` de `WORKOUT_SESSION` `COMPLETED`, sem janela | nenhuma sessão (`NO_SYNCED_WORKOUTS`); **nunca** por fuso ou parâmetro |
 * | minutos / séries / volume da semana (T19.H3) | `SocialWorkoutFactsSource` na semana canônica → `social-training-metrics.ts` | sem fuso, nenhuma sessão, ou mais sessões na semana que o teto de leitura (`SOURCE_LIMIT_REACHED`) |
 *
 * Zero é valor, não ausência: com uma sessão sincronizada e fuso conhecido, uma semana sem treino
 * responde `AVAILABLE(0)` em treinos, minutos, séries e volume — e uma semana só de peso corporal,
 * volume `AVAILABLE(0)`.
 *
 * Nenhuma delas responde `UNSUPPORTED` em produção. O que continua sem autoridade remota é uma **parte**
 * de duas métricas — o XP de recorde pessoal e as conquistas de `PERFORMANCE` — e essa parte
 * simplesmente não entra no valor publicado (nível verificado ≤ nível local; conquistas de recorde
 * nunca na lista). Ver `docs/architecture/social-progress-authority.md`.
 *
 * ## Por que ela lê `sync_entities`, e por que isso não é "sync virou API social"
 *
 * - o `SocialModule` continua **sem importar** `SyncModule`, `BackupModule` e `AiModule`. Esta
 *   classe fala com a fonte canônica (`CanonicalTrainingSource`), que fala com o `PostgresService`;
 * - **backup nunca**. `backup_snapshots`, `backup_items` e `backup_payloads` não são lidos;
 * - **nenhum payload sai daqui**. As estatísticas da semana (T19.H3) leem séries e cargas pela
 *   porta tipada `SocialWorkoutFactsSource`, e o que esta classe devolve continua sendo **um número
 *   por métrica**. As demais consultas devolvem `COUNT(*)` — total, por semana, por dia. O campo
 *   de instante do payload entra na cláusula `WHERE`, dentro do SQL, e nenhum timestamp de treino
 *   ou valor de medida é materializado em JavaScript;
 * - **`owner_uid` é o do alvo resolvido server-side**, e está na cláusula `WHERE` de toda consulta
 *   (§118). Não existe caminho em que o uid venha do corpo ou da URL.
 */
@Injectable()
export class SyncedSocialProgressSource implements SocialProgressSource {
  private readonly trainingSource: CanonicalTrainingSource;
  /** Os fatos por sessão (T19.H3): só as estatísticas da semana os leem. */
  private readonly workoutFacts: SocialWorkoutFactsSource | null;

  constructor(
    @Inject(CANONICAL_TRAINING_SOURCE)
    trainingSourceOrDb: CanonicalTrainingSource | PostgresService,
    @Optional()
    @Inject(SOCIAL_WORKOUT_FACTS_SOURCE)
    workoutFacts?: SocialWorkoutFactsSource,
  ) {
    if (
      'countCompletedWorkouts' in trainingSourceOrDb &&
      'hasAnyCompletedSession' in trainingSourceOrDb
    ) {
      this.trainingSource = trainingSourceOrDb;
      this.workoutFacts = workoutFacts ?? null;
    } else {
      this.trainingSource = new SyncedCanonicalTrainingSource(trainingSourceOrDb);
      this.workoutFacts = workoutFacts ?? new SyncedSocialWorkoutFactsSource(trainingSourceOrDb);
    }
  }

  async project(
    ownerUid: string,
    context: SocialProgressContext,
  ): Promise<SocialProgressProjection> {
    const timeZone =
      context.weekTimeZone && isValidTimeZone(context.weekTimeZone) ? context.weekTimeZone : null;

    const hasAnySession = await this.trainingSource.hasAnyCompletedSession(ownerUid);

    // Total de sessões: a contagem direta, e não a soma por dia — um treino anterior ao horizonte
    // continua sendo um treino concluído para "N treinos" e para o XP de conclusão. É a única
    // métrica que não depende de fuso (T19.H3): "quantos treinos, desde sempre" não tem semana — e
    // por isso ela fica disponível com uma sessão sincronizada, com ou sem fuso e parâmetros.
    const completedWorkouts = hasAnySession
      ? await this.trainingSource.countCompletedWorkouts(ownerUid, 0, Number.MAX_SAFE_INTEGER)
      : 0;
    const totalWorkouts = hasAnySession
      ? available(completedWorkouts)
      : unavailable<number>('NO_SYNCED_WORKOUTS');

    // Sem fuso, o servidor não sabe que dia é para este dono — e nenhuma das métricas de semana,
    // sequência, nível ou conquista é definível sem isso. Supor UTC produziria números plausíveis
    // e errados. O fuso é conferido antes de tudo nestas métricas (T19.H5): é a primeira coisa que
    // falta no caminho, e o app a declara sozinho ao abrir a tela.
    if (!timeZone) {
      const noTimeZone = <T>(): SocialProgressValue<T> => unavailable('WEEK_TIME_ZONE_MISSING');
      const weeklyStat = <T>(): SocialProgressValue<T> =>
        this.workoutFacts ? noTimeZone<T>() : unsupported<T>();
      return {
        level: noTimeZone(),
        consistencyStreak: noTimeZone(),
        weeklyWorkoutCount: noTimeZone(),
        highlightedAchievementIds: noTimeZone(),
        weeklyTrainingMinutes: weeklyStat(),
        weeklyCompletedSets: weeklyStat(),
        weeklyVolumeKg: weeklyStat(),
        totalWorkouts,
      };
    }

    const todayEpochDay = localEpochDay(context.nowMs, timeZone);
    // Até o domingo da semana corrente, e não até hoje: a semana canônica da T17.2 é a janela
    // inteira `[segunda, segunda)`, e um treino registrado com relógio adiantado não pode sumir
    // da contagem por cair "amanhã".
    const currentMonday = weekStartEpochDay(todayEpochDay);
    const days = localDayWindows(
      weekStartEpochDay(PROGRESS_HORIZON_EPOCH_DAY),
      currentMonday + 6,
      timeZone,
    );

    const [sessionsPerDay, measurementsPerDay] = await Promise.all([
      hasAnySession
        ? this.trainingSource.countCompletedWorkoutsPerDay(ownerUid, days)
        : Promise.resolve(new Map<number, number>()),
      this.trainingSource.countBodyMeasurementsPerDay(ownerUid, days),
    ]);

    const weeks: WeeklyConsistency[] | null = context.consistency
      ? calculateWeeklyConsistencies(sessionsPerDay, context.consistency, todayEpochDay)
      : null;
    const progress = weeks ? calculateProgress(weeks, todayEpochDay) : null;

    // ---- treinos da semana (T17.2, inalterado): a semana canônica que contém "agora".
    const weeklyWorkoutCount = hasAnySession
      ? available(weeklyCount(sessionsPerDay, weekStartEpochDay(todayEpochDay)))
      : unavailable<number>('NO_SYNCED_WORKOUTS');

    // ---- sequência semanal (T19.2A): sessões + parâmetros declarados, pela regra canônica.
    const consistencyStreak = fromSessionsAndParameters(hasAnySession, progress, (declared) =>
      available(declared.currentStreakWeeks),
    );

    // ---- nível (T19.2B/C): XP reconstruível → curva canônica. Só com parâmetros: sem eles a
    // meta semanal (150 XP + missão) ficaria fora, e o nível publicado seria mais baixo do que o
    // servidor já consegue defender.
    const level = fromSessionsAndParameters(hasAnySession, weeks, (declared) =>
      available(
        levelFor(projectVerifiedXp({ completedWorkouts, sessionsPerDay, weeks: declared }).total)
          .level,
      ),
    );

    // ---- conquistas (T19.2C): só as reconstruíveis; lista vazia é "nada a afirmar", não "zero".
    // Com uma sessão concluída, `first_workout` (alvo 1) sempre é obtida; com uma medição,
    // `first_measurement`. Lista vazia significa, portanto, que nada chegou por sync — e o motivo
    // é o mesmo das outras métricas. `social-progress-availability.spec.ts` prende essa premissa ao
    // catálogo: se um dia ela deixar de valer, o teste quebra antes de o motivo mentir.
    const measurementDays = [...measurementsPerDay.values()].filter((count) => count > 0).length;
    const earned = evaluateVerifiedAchievements({
      completedWorkouts,
      longestStreakWeeks: progress ? progress.longestStreakWeeks : null,
      measurementDays,
    });
    const highlightedAchievementIds =
      earned.length > 0
        ? available<readonly string[]>(earned)
        : unavailable<readonly string[]>('NO_SYNCED_WORKOUTS');

    // ---- estatísticas de treino da semana (T19.H3): a MESMA semana canônica de "treinos da
    // semana" — as mesmas janelas de dia local, pelo mesmo `startedAt` —, somada pelo helper único
    // de métricas que também monta o resumo de cada check-in.
    const week = await this.weeklyTrainingStats(ownerUid, hasAnySession, currentMonday, timeZone);
    const weeklyStat = (value: (totals: TrainingTotals) => number): SocialProgressValue<number> => {
      switch (week.kind) {
        case 'AVAILABLE':
          // Zero é valor (T19.H5 §20): uma semana só de peso corporal tem volume 0, e uma semana
          // sem treino ainda — com sessões em semanas anteriores — tem 0 minutos. Nenhum dos dois
          // vira "indisponível".
          return available(value(week.totals));
        case 'UNAVAILABLE':
          return unavailable(week.reason);
        case 'UNSUPPORTED':
          return unsupported();
      }
    };

    return {
      level,
      consistencyStreak,
      weeklyWorkoutCount,
      highlightedAchievementIds,
      weeklyTrainingMinutes: weeklyStat((totals) => totals.trainingMinutes),
      weeklyCompletedSets: weeklyStat((totals) => totals.completedSets),
      weeklyVolumeKg: weeklyStat((totals) => totals.volumeKg),
      totalWorkouts,
    };
  }

  /**
   * Os totais da semana canônica, ou por que eles não são afirmáveis.
   *
   * - nenhuma sessão sincronizada → `NO_SYNCED_WORKOUTS`;
   * - mais sessões na semana que o teto de leitura → `SOURCE_LIMIT_REACHED` (ver
   *   `MAX_WEEKLY_SESSIONS_FOR_TRAINING_STATS`: a soma lê séries e cargas de cada sessão, e a
   *   definição única das métricas mora em TypeScript — reescrevê-la em SQL para agregar sem teto
   *   criaria uma segunda definição de volume e de série);
   * - fonte de fatos ausente → `UNSUPPORTED`: é a montagem com dublê de agregados, em que este
   *   servidor não tem de onde ler séries. Produção sempre injeta a fonte (`SocialModule`).
   */
  private async weeklyTrainingStats(
    ownerUid: string,
    hasAnySession: boolean,
    currentMonday: number,
    timeZone: string,
  ): Promise<WeeklyTrainingStats> {
    if (!this.workoutFacts) {
      return { kind: 'UNSUPPORTED' };
    }
    if (!hasAnySession) {
      return { kind: 'UNAVAILABLE', reason: 'NO_SYNCED_WORKOUTS' };
    }
    // `[segunda 00:00, próxima segunda 00:00)` no fuso do dono: as mesmas bordas das janelas de
    // dia de "treinos da semana" (`localDayWindows`), sem montar as sete.
    const sessions = await this.workoutFacts.findCompletedInWindow(
      ownerUid,
      localMidnightToInstant(currentMonday * DAY_MS, timeZone),
      localMidnightToInstant((currentMonday + 7) * DAY_MS, timeZone),
      MAX_WEEKLY_SESSIONS_FOR_TRAINING_STATS,
    );
    return sessions
      ? { kind: 'AVAILABLE', totals: trainingTotals(sessions) }
      : { kind: 'UNAVAILABLE', reason: 'SOURCE_LIMIT_REACHED' };
  }
}

/** O que a leitura da semana conseguiu afirmar (T19.H5): os totais, ou o motivo de não haver. */
type WeeklyTrainingStats =
  | { readonly kind: 'AVAILABLE'; readonly totals: TrainingTotals }
  | { readonly kind: 'UNAVAILABLE'; readonly reason: SocialAvailabilityReason }
  | { readonly kind: 'UNSUPPORTED' };

/**
 * Nível e sequência precisam de duas coisas além do fuso: sessões sincronizadas **e** os
 * parâmetros de consistência declarados (T19.2A). A precedência dos motivos é essa: sem sessão,
 * declarar parâmetro não publicaria nada; sem parâmetro, sincronizar mais não resolve.
 */
function fromSessionsAndParameters<P, T>(
  hasAnySession: boolean,
  parameters: P | null,
  project: (declared: P) => SocialProgressValue<T>,
): SocialProgressValue<T> {
  if (!hasAnySession) {
    return unavailable('NO_SYNCED_WORKOUTS');
  }
  if (parameters === null) {
    return unavailable('CONSISTENCY_PARAMETERS_MISSING');
  }
  return project(parameters);
}

/** Soma dos treinos dos sete dias locais da semana que começa em [weekStart]. */
function weeklyCount(sessionsPerDay: ReadonlyMap<number, number>, weekStart: number): number {
  let total = 0;
  for (let day = weekStart; day < weekStart + 7; day++) {
    total += sessionsPerDay.get(day) ?? 0;
  }
  return total;
}

/**
 * As janelas de todos os dias locais de `[fromEpochDay, toEpochDay]`.
 *
 * A meia-noite de cada dia é calculada uma vez e compartilhada entre o fim de um dia e o início
 * do seguinte — a iteração é sobre **datas**, e a conversão para instante é por dia
 * (`localMidnightToInstant`), nunca "anterior + 24h", que erraria nas viradas de horário de verão.
 */
export function localDayWindows(
  fromEpochDay: number,
  toEpochDay: number,
  timeZone: string,
): LocalDayWindow[] {
  if (toEpochDay < fromEpochDay) {
    return [];
  }
  const windows: LocalDayWindow[] = [];
  let start = localMidnightToInstant(fromEpochDay * DAY_MS, timeZone);
  for (let epochDay = fromEpochDay; epochDay <= toEpochDay; epochDay++) {
    const end = localMidnightToInstant((epochDay + 1) * DAY_MS, timeZone);
    windows.push({ epochDay, startMs: start, endMs: end });
    start = end;
  }
  return windows;
}

/** A janela `[início, fim)` de uma semana canônica, em epoch millis UTC. */
export interface CanonicalWeekWindow {
  readonly startMs: number;
  readonly endMs: number;
}

/**
 * A semana canônica do Spark que contém [nowMs], no fuso [timeZone].
 *
 * A definição é a de `ConsistencyCalculator.weekStart`: **segunda-feira** da semana que contém a
 * data local. Ela não foi escolhida aqui — ela foi lida do domínio, que a expõe exatamente para
 * que outras camadas compartilhem a regra em vez de criarem um segundo conceito de início de
 * semana.
 *
 * ```text
 * nowMs ──(fuso do dono)──▶ data local ──▶ segunda-feira ──▶ meia-noite local ──▶ epoch millis
 *                                             │                                       │
 *                                             └────── +7 dias ────────────────────────┘
 * ```
 *
 * O fim é a **meia-noite da segunda seguinte**, e não "início + 7×24h": em uma semana com
 * mudança de horário de verão elas diferem em uma hora, e a diferença apareceria como um treino
 * de domingo à noite contado na semana errada.
 *
 * `null` quando o fuso não é um identificador IANA válido. Nenhuma suposição de UTC: um palpite
 * produziria um número plausível e errado, que é pior do que campo ausente.
 */
export function canonicalWeekWindow(nowMs: number, timeZone: string): CanonicalWeekWindow | null {
  if (!isValidTimeZone(timeZone)) {
    return null;
  }

  const today = localCalendarDate(nowMs, timeZone);
  // `getUTCDay` sobre a data local escrita como se fosse UTC: 0 = domingo. A subtração leva
  // qualquer dia para a segunda-feira da mesma semana — a regra do `ConsistencyCalculator`.
  const dayOfWeek = new Date(today).getUTCDay();
  const daysSinceMonday = (dayOfWeek + 6) % 7;

  const mondayAsUtc = today - daysSinceMonday * DAY_MS;
  const nextMondayAsUtc = mondayAsUtc + 7 * DAY_MS;

  return {
    startMs: localMidnightToInstant(mondayAsUtc, timeZone),
    endMs: localMidnightToInstant(nextMondayAsUtc, timeZone),
  };
}

/**
 * Revalidação do fuso, reexportada.
 *
 * A implementação mora em `social-time.ts` desde a T17.3, porque a T17.3 precisa exatamente da
 * mesma conversão de meia-noite local para os dias de um desafio (§203/§204). O reexport mantém o
 * ponto de import da T17.2 — `social-profile.validator.ts` — sem transformar uma extração em
 * mudança de contrato.
 */
export { isValidTimeZone };

/**
 * O token de injeção da fonte.
 *
 * Interface, e não classe concreta, no ponto de injeção: é o que permite a um teste provar a
 * projeção com uma fonte de mentira **sem** montar um banco com sync — e, mais importante, é o que
 * torna óbvio para quem lê o `SocialModule` que existe uma fronteira ali, e não uma dependência
 * qualquer do domínio social sobre o domínio de treino.
 */
export const SOCIAL_PROGRESS_SOURCE = Symbol('SOCIAL_PROGRESS_SOURCE');
