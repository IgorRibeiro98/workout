import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';

/**
 * As preferências de compartilhamento de progresso, como estão gravadas.
 *
 * Nenhum valor de progresso mora aqui — só o consentimento e o fuso da semana. `ownerUid` não faz
 * parte do tipo pela mesma razão de sempre: quem lê já sabe de quem é, porque foi ele quem passou
 * o uid na consulta, e carregá-lo adiante é o primeiro passo para ele acabar em um DTO.
 */
export interface StoredProgressSettings {
  readonly shareLevel: boolean;
  readonly shareConsistencyStreak: boolean;
  readonly shareWeeklyWorkoutCount: boolean;
  readonly shareHighlightedAchievements: boolean;
  readonly weekTimeZone: string | null;
  readonly updatedAt: number;
}

/**
 * A atualização parcial (§57): só o que veio no corpo é escrito.
 *
 * `undefined` significa "não mexa neste campo", e é diferente de `false`, que significa "desligue".
 * É por isso que os campos são opcionais em vez de terem default: um default transformaria "não
 * mandei" em "desligue", e um cliente que só quisesse ligar o nível desligaria os outros três sem
 * saber.
 */
export interface UpdateProgressSettingsInput {
  readonly shareLevel?: boolean;
  readonly shareConsistencyStreak?: boolean;
  readonly shareWeeklyWorkoutCount?: boolean;
  readonly shareHighlightedAchievements?: boolean;
  readonly weekTimeZone?: string;
  /** Relógio do **servidor** (§58/§93). Nunca o do cliente. */
  readonly now: number;
}

/**
 * Os defaults conservadores da T17.2 (§13/§14/§75).
 *
 * Declarados aqui **e** na migration, e isso não é duplicação acidental: a migration é o que vale
 * para as linhas que já existiam, e estas constantes são o que vale para um perfil criado a partir
 * de agora. Elas precisam concordar, e há teste comparando o que fica gravado nos dois caminhos.
 */
export const SOCIAL_PROGRESS_SHARING_DEFAULTS = {
  shareLevel: false,
  shareConsistencyStreak: false,
  shareWeeklyWorkoutCount: false,
  shareHighlightedAchievements: false,
  weekTimeZone: null,
} as const;

/**
 * A persistência das preferências de compartilhamento (T17.2).
 *
 * ## Duas garantias, e nenhuma delas é disciplina de quem chama
 *
 * 1. **isolamento por conta** — `owner_uid` está na cláusula `WHERE` de toda consulta e de toda
 *    escrita. Não existe método que liste preferências, e não existe método que leia por qualquer
 *    outra coluna: "quem compartilha nível?" seria enumeração de usuários, e a consulta não foi
 *    escrita;
 * 2. **a linha existe desde a criação do perfil** — a migration a criou para quem já existia, e o
 *    `SocialRepository.create` a cria na mesma transação do perfil. Ainda assim [find] devolve os
 *    defaults quando a linha falta, porque um perfil sem preferência não pode virar um perfil que
 *    **publica**: o modo de falhar precisa ser "não compartilha nada".
 */
@Injectable()
export class SocialProgressSettingsRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /**
   * As preferências daquela conta.
   *
   * A ausência de linha devolve os defaults — todos desligados —, e não `null`. É a escolha
   * segura: qualquer caminho que esquecesse de tratar o `null` publicaria, e o pior modo de falhar
   * de uma feature de privacidade é falhar para o lado de mostrar.
   */
  find(ownerUid: string): StoredProgressSettings {
    const row = this.sqlite.connection
      .prepare(
        `SELECT share_level, share_consistency_streak, share_weekly_workout_count,
                share_highlighted_achievements, week_time_zone, updated_at
           FROM social_progress_settings
          WHERE owner_uid = ?`,
      )
      .get(ownerUid) as SettingsRow | undefined;

    if (!row) {
      return { ...SOCIAL_PROGRESS_SHARING_DEFAULTS, updatedAt: 0 };
    }
    return {
      shareLevel: row.share_level === 1,
      shareConsistencyStreak: row.share_consistency_streak === 1,
      shareWeeklyWorkoutCount: row.share_weekly_workout_count === 1,
      shareHighlightedAchievements: row.share_highlighted_achievements === 1,
      weekTimeZone: row.week_time_zone,
      updatedAt: row.updated_at,
    };
  }

  /** Cria a linha com os defaults. Chamado dentro da transação que cria o perfil social. */
  createDefaults(ownerUid: string, now: number): void {
    this.sqlite.connection
      .prepare(
        `INSERT INTO social_progress_settings
           (owner_uid, share_level, share_consistency_streak, share_weekly_workout_count,
            share_highlighted_achievements, week_time_zone, updated_at)
         VALUES (?, 0, 0, 0, 0, NULL, ?)`,
      )
      .run(ownerUid, now);
  }

  /**
   * Atualização parcial.
   *
   * ```text
   * PATCH { shareLevel: true }   →   share_level = 1, updated_at = agora
   *                                  os outros três continuam exatamente como estavam
   * ```
   *
   * `INSERT ... ON CONFLICT DO UPDATE` em vez de `UPDATE` puro: a linha deveria sempre existir, e
   * ainda assim um `UPDATE` que não encontrasse nada responderia `200` sem ter gravado. Um
   * `PATCH` que responde sucesso sem gravar é o pior desfecho possível numa tela de privacidade —
   * a pessoa acredita que desligou.
   *
   * **Concorrência (§92):** dois aparelhos alterando ao mesmo tempo — a última requisição que o
   * servidor aceitar vence, e `updated_at` registra qual foi. É aceitável porque isto é
   * configuração server-authoritative, e não histórico: não há um estado anterior que precise
   * sobreviver, e nenhum dos dois valores é "mais verdadeiro" que o outro. A ordem é a de chegada
   * no servidor, nunca a do relógio de quem enviou (§93).
   */
  update(ownerUid: string, input: UpdateProgressSettingsInput): void {
    const assignments: string[] = [];
    const values: unknown[] = [];

    const set = (column: string, value: unknown): void => {
      assignments.push(`${column} = ?`);
      values.push(value);
    };

    if (input.shareLevel !== undefined) set('share_level', input.shareLevel ? 1 : 0);
    if (input.shareConsistencyStreak !== undefined) {
      set('share_consistency_streak', input.shareConsistencyStreak ? 1 : 0);
    }
    if (input.shareWeeklyWorkoutCount !== undefined) {
      set('share_weekly_workout_count', input.shareWeeklyWorkoutCount ? 1 : 0);
    }
    if (input.shareHighlightedAchievements !== undefined) {
      set('share_highlighted_achievements', input.shareHighlightedAchievements ? 1 : 0);
    }
    if (input.weekTimeZone !== undefined) set('week_time_zone', input.weekTimeZone);

    if (assignments.length === 0) {
      return;
    }
    assignments.push('updated_at = ?');
    values.push(input.now);

    this.sqlite.connection
      .prepare(
        `INSERT INTO social_progress_settings
           (owner_uid, share_level, share_consistency_streak, share_weekly_workout_count,
            share_highlighted_achievements, week_time_zone, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (owner_uid) DO UPDATE SET ${assignments.join(', ')}`,
      )
      .run(
        ownerUid,
        input.shareLevel === true ? 1 : 0,
        input.shareConsistencyStreak === true ? 1 : 0,
        input.shareWeeklyWorkoutCount === true ? 1 : 0,
        input.shareHighlightedAchievements === true ? 1 : 0,
        input.weekTimeZone ?? null,
        input.now,
        ...values,
      );
  }
}

interface SettingsRow {
  share_level: number;
  share_consistency_streak: number;
  share_weekly_workout_count: number;
  share_highlighted_achievements: number;
  week_time_zone: string | null;
  updated_at: number;
}
