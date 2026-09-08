import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';

/**
 * A fronteira estreita entre o Social e o estado canônico do Spark (T17.2 §9/§11).
 *
 * ```text
 * autoridades reais do Spark          SocialProgressSource          SocialProgressProjector
 * (Room do aparelho, e o que          ─────────────────────▶        ─────────────────────▶
 *  chega ao servidor por sync)        um escalar por métrica         projeção segura
 * ```
 *
 * ## Por que uma interface, e por que tão pobre
 *
 * Porque é ela que impede a alternativa: um `FriendProfileService` importando `SyncRepository` e
 * fazendo `SELECT payload`. Isso funcionaria no primeiro dia e seria irreversível no segundo —
 * a partir daí, um campo novo no snapshot de treino viraria campo novo na superfície social sem
 * que ninguém decidisse isso.
 *
 * Cada método devolve **um escalar** ou a declaração de que não há resposta. Nenhum devolve
 * payload, agregado, lista de sessões, série, carga, nota, medida ou timestamp de treino. Um
 * `getSessions()` aqui já seria a fuga que este arquivo existe para fechar (§12).
 *
 * ## O que ela **não** é
 *
 * Ela não calcula regra de domínio. Ela lê estado canônico já sincronizado, ou faz a derivação que
 * o próprio contrato canônico define (contar sessões concluídas numa semana). Ela não reimplementa
 * `XpCalculatorService`, `ConsistencyCalculator` nem `AchievementEvaluator` — se ela precisasse
 * fazê-lo, o Social teria virado uma segunda autoridade de progresso, e a resposta certa é
 * responder [SocialProgressValue] `UNSUPPORTED` (§3).
 */
export interface SocialProgressSource {
  /** O nível canônico do dono. */
  getLevel(ownerUid: string): SocialProgressValue<number>;

  /** A sequência **semanal** de consistência (§20). */
  getConsistencyStreak(ownerUid: string): SocialProgressValue<number>;

  /**
   * Quantos treinos **concluídos** o dono tem na semana canônica que contém [nowMs].
   *
   * @param weekTimeZone fuso IANA do dono. `null` responde `UNAVAILABLE`: sem ele a semana do
   * servidor não é a mesma semana do aparelho, e uma contagem em outra semana é uma contagem
   * errada — não uma aproximação.
   */
  getWeeklyWorkoutCount(
    ownerUid: string,
    weekTimeZone: string | null,
    nowMs: number,
  ): SocialProgressValue<number>;

  /** Os identificadores canônicos das conquistas que o dono realmente obteve. */
  getEarnedAchievementIds(ownerUid: string): SocialProgressValue<readonly string[]>;
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
  /** Suportado, e o servidor ainda não tem o que afirmar. Sincronizar resolve. */
  | { readonly kind: 'UNAVAILABLE' }
  /** Sem autoridade remota nesta versão do Spark. Sincronizar **não** resolve. */
  | { readonly kind: 'UNSUPPORTED' };

export const available = <T>(value: T): SocialProgressValue<T> => ({ kind: 'AVAILABLE', value });
export const unavailable = <T>(): SocialProgressValue<T> => ({ kind: 'UNAVAILABLE' });
export const unsupported = <T>(): SocialProgressValue<T> => ({ kind: 'UNSUPPORTED' });

/**
 * A implementação sobre o estado que **de fato** chega ao servidor (T17.2).
 *
 * ## O que existe remotamente, verificado no código e não na documentação
 *
 * | Métrica | Autoridade canônica | Chega ao servidor? |
 * | --- | --- | --- |
 * | nível | `XpTransactionRepositoryImpl` sobre `xp_transactions` (Room) | **não** — DERIVED na matriz da T16 |
 * | sequência semanal | `ConsistencyCalculator` sobre semanas, metas e `trackingStartedAt` | **não** — `WEEKLY_GOAL` fora do sync incremental, `trackingStartedAt` nunca sai do aparelho |
 * | treinos da semana | contagem de sessões `COMPLETED` na semana canônica | **sim** — `sync_entities` / `WORKOUT_SESSION` |
 * | conquistas | `AchievementEvaluator` + `achievement_unlocks` (Room) | **não** — DERIVED |
 *
 * Três das quatro respondem `UNSUPPORTED`, e isso é o resultado honesto do que o Spark
 * sincroniza hoje. As alternativas seriam aceitar o valor que o aparelho declara — o servidor
 * passaria a confiar no cliente sobre progresso — ou portar os três motores de domínio para
 * TypeScript, criando uma segunda autoridade que divergiria da primeira no primeiro ajuste de
 * regra. As duas são proibidas (§3, e os bloqueantes da tarefa).
 *
 * ## Por que ela lê `sync_entities` diretamente, e por que isso não é "sync virou API social"
 *
 * A T17.0 declarou `NO_CROSS_DOMAIN_READ` quando **nenhuma** projeção existia. A T17.2 precisa da
 * primeira, e §11 é explícito: um adapter interno pode ler estado sincronizado; o que não pode é
 * `sync_entities` virar API social. A fronteira que sustenta isso, e que é testada:
 *
 * - o `SocialModule` continua **sem importar** `SyncModule`, `BackupModule` e `AiModule`. Esta
 *   classe fala com o `SqliteService`, que é infraestrutura compartilhada do processo — e não com
 *   `SyncRepository`, cuja superfície é o protocolo de sync inteiro;
 * - **backup nunca**. `backup_snapshots`, `backup_items` e `backup_payloads` não são lidos por
 *   nada aqui: um snapshot é a conta inteira em um documento, e ler dele para responder "quantos
 *   treinos esta semana" seria abrir a caixa errada;
 * - **nenhum payload sai**. As duas consultas abaixo devolvem `COUNT(*)` e `1`. `json_extract`
 *   entra na cláusula `WHERE`, dentro do SQL, e o conteúdo de nenhuma sessão é materializado em
 *   JavaScript — nem carga, nem exercício, nem nota, nem horário;
 * - **`owner_uid` é o do alvo resolvido server-side**, e está na cláusula `WHERE` de toda consulta
 *   (§118). Não existe caminho em que o uid venha do corpo ou da URL.
 */
@Injectable()
export class SyncedSocialProgressSource implements SocialProgressSource {
  constructor(private readonly sqlite: SqliteService) {}

  /**
   * Nível — sem autoridade remota.
   *
   * O nível do Spark é `XpTransactionRepositoryImpl.calculateProgress` sobre `xp_transactions`, e
   * a matriz de dados da T16 classifica `xp_transactions` como **DERIVED**: não sincroniza, não
   * entra no backup, e cada aparelho o reconstrói do histórico. Recalcular a curva de XP aqui
   * seria criar a segunda autoridade que §19 proíbe explicitamente.
   */
  getLevel(): SocialProgressValue<number> {
    return unsupported();
  }

  /**
   * Sequência semanal — sem autoridade remota.
   *
   * `ConsistencyCalculator.calculateProgress` precisa de três coisas que o servidor não tem: o
   * histórico de metas semanais (`WEEKLY_GOAL` está fora do sync incremental — só backup), o
   * `trackingStartedAt` (DataStore, nunca sai do aparelho) e a regra de "semana não contada" que
   * decide quando uma semana quebra a sequência. Sem as três, qualquer número daqui seria uma
   * sequência **parecida**, calculada por outra regra — que é exatamente a segunda autoridade que
   * a tarefa proíbe.
   */
  getConsistencyStreak(): SocialProgressValue<number> {
    return unsupported();
  }

  /**
   * Treinos da semana — a única métrica com autoridade remota hoje.
   *
   * ```text
   * ConsistencyCalculator (Android)                    aqui
   * timestamps de sessões COMPLETED          sync_entities / WORKOUT_SESSION (deleted = 0)
   * agrupados por weekStart = segunda-feira  COUNT(*) na janela [segunda, próxima segunda)
   * na data LOCAL do treino                  na data local do dono, pelo fuso declarado
   * ```
   *
   * Isto **não** é uma regra paralela, e a diferença importa: a definição de semana é a canônica
   * (`ConsistencyCalculator.weekStart`, segunda-feira, exposta pelo próprio domínio para que
   * outras camadas a compartilhem em vez de recriarem), e a definição de "treino que conta" é a do
   * schema (`workoutSessionSchema` só aceita `status: 'COMPLETED'`). O que sobra é uma contagem.
   *
   * Só `COMPLETED` conta (§22). `PLANNED`, `IN_PROGRESS`, `PAUSED` e `CANCELLED` sequer chegam ao
   * servidor — o registry recusa —, e ainda assim a cláusula está escrita: ela é a declaração da
   * regra no lugar onde ela é aplicada, e é o que faz o teste falhar se algum dia o registry
   * passar a aceitar outro status.
   *
   * `UNAVAILABLE`, e nunca `0`, quando: não há fuso declarado, ou a conta nunca sincronizou uma
   * sessão concluída. A segunda condição é a que impede "0 treinos esta semana" de ser dito sobre
   * alguém que treina há três anos e nunca ligou a sincronização (§4/§74). Quem já tem histórico
   * no servidor e não treinou nesta semana recebe `0` — aí o zero é verdade comprovada.
   */
  getWeeklyWorkoutCount(
    ownerUid: string,
    weekTimeZone: string | null,
    nowMs: number,
  ): SocialProgressValue<number> {
    if (!weekTimeZone) {
      return unavailable();
    }
    const window = canonicalWeekWindow(nowMs, weekTimeZone);
    if (!window) {
      return unavailable();
    }
    if (!this.hasAnyCompletedSession(ownerUid)) {
      // Nunca sincronizou nada concluído: o servidor não sabe se são zero treinos ou zero
      // sincronizações, e afirmar zero seria inventar o que não foi comprovado.
      return unavailable();
    }

    const row = this.sqlite.connection
      .prepare(
        `SELECT COUNT(*) AS total
           FROM sync_entities
          WHERE owner_uid = ?
            AND entity_type = 'WORKOUT_SESSION'
            AND deleted = 0
            AND json_extract(payload, '$.status') = 'COMPLETED'
            AND json_extract(payload, '$.startedAt') >= ?
            AND json_extract(payload, '$.startedAt') < ?`,
      )
      .get(ownerUid, window.startMs, window.endMs) as { total: number } | undefined;

    return available(row?.total ?? 0);
  }

  /**
   * Conquistas obtidas — sem autoridade remota.
   *
   * `achievement_unlocks` é DERIVED na matriz da T16: reconstruído do histórico por
   * `AchievementReconciler` em cada aparelho, nunca sincronizado, nunca no backup. Sem esta
   * resposta o servidor **não consegue** validar "esta conquista foi conquistada" (§26), e sem
   * essa validação a seleção de destaques não pode existir: aceitar o id que o cliente manda seria
   * deixar qualquer um se declarar dono de `100_workouts`.
   */
  getEarnedAchievementIds(): SocialProgressValue<readonly string[]> {
    return unsupported();
  }

  /**
   * A conta tem **alguma** sessão concluída conhecida pelo servidor?
   *
   * É o sinal de que "zero nesta semana" é um fato, e não a ausência de sincronização. `LIMIT 1`
   * sobre o índice de identidade: a pergunta é de existência, não de contagem.
   */
  private hasAnyCompletedSession(ownerUid: string): boolean {
    const row = this.sqlite.connection
      .prepare(
        `SELECT 1 AS present
           FROM sync_entities
          WHERE owner_uid = ?
            AND entity_type = 'WORKOUT_SESSION'
            AND deleted = 0
            AND json_extract(payload, '$.status') = 'COMPLETED'
          LIMIT 1`,
      )
      .get(ownerUid) as { present: number } | undefined;
    return row !== undefined;
  }
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

/** O identificador é um fuso IANA que este runtime conhece? */
export function isValidTimeZone(timeZone: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone });
    return true;
  } catch {
    return false;
  }
}

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * A **data** local de um instante, escrita como `Date.UTC(ano, mês, dia)`.
 *
 * O valor devolvido não é o instante da meia-noite local: é a data local carregada num inteiro que
 * a aritmética de calendário pode manipular sem fuso. A conversão de volta para instante é
 * [localMidnightToInstant].
 */
function localCalendarDate(instantMs: number, timeZone: string): number {
  const fields = localFields(instantMs, timeZone);
  return Date.UTC(fields.year, fields.month - 1, fields.day);
}

/**
 * A meia-noite local do dia [dateAsUtc], em epoch millis.
 *
 * Duas passagens, e não uma: o deslocamento do fuso depende do instante, e o instante é o que se
 * está procurando. A primeira passagem usa o deslocamento na data escrita como UTC; a segunda o
 * corrige com o deslocamento no instante estimado, que é o que resolve as fronteiras de horário de
 * verão. Mais de duas passagens não acrescentam nada: o deslocamento é constante dentro de cada
 * lado da transição.
 */
function localMidnightToInstant(dateAsUtc: number, timeZone: string): number {
  const firstGuess = dateAsUtc - zoneOffsetMs(dateAsUtc, timeZone);
  return dateAsUtc - zoneOffsetMs(firstGuess, timeZone);
}

/**
 * Quanto o relógio de parede de [timeZone] está adiantado em relação ao UTC, naquele instante.
 *
 * Medido comparando o instante com o mesmo relógio de parede escrito como se fosse UTC. É a forma
 * portátil de obter deslocamento com horário de verão sem depender de biblioteca externa — o
 * Spark Backend não tem uma, e não precisa de uma.
 */
function zoneOffsetMs(instantMs: number, timeZone: string): number {
  const fields = localFields(instantMs, timeZone);
  const wallClockAsUtc = Date.UTC(
    fields.year,
    fields.month - 1,
    fields.day,
    fields.hour,
    fields.minute,
    fields.second,
  );
  return wallClockAsUtc - instantMs;
}

interface LocalFields {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
}

const formatterCache = new Map<string, Intl.DateTimeFormat>();

function localFields(instantMs: number, timeZone: string): LocalFields {
  let formatter = formatterCache.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    formatterCache.set(timeZone, formatter);
  }

  const parts = new Map(
    formatter.formatToParts(new Date(instantMs)).map((part) => [part.type, part.value]),
  );

  return {
    year: Number(parts.get('year')),
    month: Number(parts.get('month')),
    day: Number(parts.get('day')),
    // `h23` já produz `00` à meia-noite; a normalização é a defesa contra um ICU que devolva `24`.
    hour: Number(parts.get('hour')) % 24,
    minute: Number(parts.get('minute')),
    second: Number(parts.get('second')),
  };
}

/**
 * O token de injeção da fonte.
 *
 * Interface, e não classe concreta, no ponto de injeção: é o que permite a um teste provar a
 * projeção com uma fonte de mentira **sem** montar um banco com sync — e, mais importante, é o que
 * torna óbvio para quem lê o `SocialModule` que existe uma fronteira ali, e não uma dependência
 * qualquer do domínio social sobre o domínio de treino.
 */
export const SOCIAL_PROGRESS_SOURCE = Symbol('SOCIAL_PROGRESS_SOURCE');
