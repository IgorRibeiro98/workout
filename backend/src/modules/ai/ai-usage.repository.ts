import { Injectable } from '@nestjs/common';
import { PostgresService } from '../../database/postgres.service';
import type { AiCoachRequestType } from './ai-coach.contract';

export interface DailyUsage {
  readonly userRequests: number;
  readonly globalRequests: number;
}

/**
 * O registro de uso do Coach — a memória que sustenta a quota (T16.2 / T18.0 PostgreSQL).
 *
 * Só metadata técnica: uid, dia (UTC), tipo de request, contagem e tokens. Prompt, contexto,
 * resposta e qualquer texto do usuário **não passam por aqui** (§36, §37).
 */
@Injectable()
export class AiUsageRepository {
  constructor(private readonly db: PostgresService) {}

  /**
   * Registra uma tentativa e devolve como ficaram os contadores do dia.
   *
   * Tudo em uma transação atômica: o incremento e as duas leituras enxergam o mesmo estado.
   */
  async recordAttempt(
    uid: string,
    utcDate: string,
    requestType: AiCoachRequestType,
  ): Promise<DailyUsage> {
    const now = Date.now();

    return this.db.transaction(async (client) => {
      await client.query(
        `INSERT INTO ai_usage_daily (uid, utc_date, request_type, request_count, updated_at)
         VALUES ($1, $2, $3, 1, $4)
         ON CONFLICT (uid, utc_date, request_type) DO UPDATE SET
           request_count = ai_usage_daily.request_count + 1,
           updated_at = EXCLUDED.updated_at`,
        [uid, utcDate, requestType, now],
      );

      const userRes = await client.query<{ total: string | number }>(
        'SELECT COALESCE(SUM(request_count), 0) AS total FROM ai_usage_daily WHERE uid = $1 AND utc_date = $2',
        [uid, utcDate],
      );

      const globalRes = await client.query<{ total: string | number }>(
        'SELECT COALESCE(SUM(request_count), 0) AS total FROM ai_usage_daily WHERE utc_date = $1',
        [utcDate],
      );

      return {
        userRequests: Number(userRes.rows[0]?.total ?? 0),
        globalRequests: Number(globalRes.rows[0]?.total ?? 0),
      };
    });
  }

  /** Desfaz uma tentativa que **não** chegou ao provider. */
  async releaseAttempt(
    uid: string,
    utcDate: string,
    requestType: AiCoachRequestType,
  ): Promise<void> {
    await this.db.query(
      `UPDATE ai_usage_daily
       SET request_count = GREATEST(request_count - 1, 0), updated_at = $1
       WHERE uid = $2 AND utc_date = $3 AND request_type = $4`,
      [Date.now(), uid, utcDate, requestType],
    );
  }

  /** Soma os tokens que o provider informou. Metadata de custo, nunca conteúdo. */
  async recordTokens(
    uid: string,
    utcDate: string,
    requestType: AiCoachRequestType,
    tokens: { promptTokens: number; outputTokens: number; totalTokens: number },
  ): Promise<void> {
    await this.db.query(
      `UPDATE ai_usage_daily
       SET prompt_tokens = prompt_tokens + $1,
           output_tokens = output_tokens + $2,
           total_tokens = total_tokens + $3,
           updated_at = $4
       WHERE uid = $5 AND utc_date = $6 AND request_type = $7`,
      [
        tokens.promptTokens,
        tokens.outputTokens,
        tokens.totalTokens,
        Date.now(),
        uid,
        utcDate,
        requestType,
      ],
    );
  }

  async userTotal(utcDate: string, uid: string): Promise<number> {
    const res = await this.db.query<{ total: string | number }>(
      'SELECT COALESCE(SUM(request_count), 0) AS total FROM ai_usage_daily WHERE uid = $1 AND utc_date = $2',
      [uid, utcDate],
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  async globalTotal(utcDate: string): Promise<number> {
    const res = await this.db.query<{ total: string | number }>(
      'SELECT COALESCE(SUM(request_count), 0) AS total FROM ai_usage_daily WHERE utc_date = $1',
      [utcDate],
    );
    return Number(res.rows[0]?.total ?? 0);
  }
}

/** O dia da quota, sempre em UTC. O fuso do aparelho é entrada não confiável. */
export function utcDateOf(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10);
}
