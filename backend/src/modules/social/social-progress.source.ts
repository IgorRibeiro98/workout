import { Inject, Injectable } from '@nestjs/common';
import { PostgresService } from '../../database/postgres.service';
import {
  CANONICAL_TRAINING_SOURCE,
  type CanonicalTrainingSource,
  SyncedCanonicalTrainingSource,
} from './canonical-training.source';
import { DAY_MS, isValidTimeZone, localCalendarDate, localMidnightToInstant } from './social-time';

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
  getLevel(ownerUid: string): SocialProgressValue<number> | Promise<SocialProgressValue<number>>;

  /** A sequência **semanal** de consistência (§20). */
  getConsistencyStreak(
    ownerUid: string,
  ): SocialProgressValue<number> | Promise<SocialProgressValue<number>>;

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
  ): SocialProgressValue<number> | Promise<SocialProgressValue<number>>;

  /** Os identificadores canônicos das conquistas que o dono realmente obteve. */
  getEarnedAchievementIds(
    ownerUid: string,
  ): SocialProgressValue<readonly string[]> | Promise<SocialProgressValue<readonly string[]>>;
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
 *   classe fala com o `PostgresService`, que é infraestrutura compartilhada do processo — e não com
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
  private readonly trainingSource: CanonicalTrainingSource;

  constructor(
    @Inject(CANONICAL_TRAINING_SOURCE)
    trainingSourceOrDb: CanonicalTrainingSource | PostgresService,
  ) {
    if (
      'countCompletedWorkouts' in trainingSourceOrDb &&
      'hasAnyCompletedSession' in trainingSourceOrDb
    ) {
      this.trainingSource = trainingSourceOrDb;
    } else {
      this.trainingSource = new SyncedCanonicalTrainingSource(trainingSourceOrDb);
    }
  }

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
   * Utiliza a fonte canônica unificada CanonicalTrainingSource (T17.4.1).
   */
  async getWeeklyWorkoutCount(
    ownerUid: string,
    weekTimeZone: string | null,
    nowMs: number,
  ): Promise<SocialProgressValue<number>> {
    if (!weekTimeZone) {
      return unavailable();
    }
    const window = canonicalWeekWindow(nowMs, weekTimeZone);
    if (!window) {
      return unavailable();
    }
    if (!(await this.trainingSource.hasAnyCompletedSession(ownerUid))) {
      // Nunca sincronizou nada concluído: o servidor não sabe se são zero treinos ou zero
      // sincronizações, e afirmar zero seria inventar o que não foi comprovado.
      return unavailable();
    }

    const total = await this.trainingSource.countCompletedWorkouts(
      ownerUid,
      window.startMs,
      window.endMs,
    );

    return available(total);
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
