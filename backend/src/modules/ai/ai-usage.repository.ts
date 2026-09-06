import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type { AiCoachRequestType } from './ai-coach.contract';

/**
 * O registro de uso do Coach — a memória que sustenta a quota.
 *
 * Só metadata técnica: uid, dia (UTC), tipo de request, contagem e tokens. Prompt, contexto,
 * resposta e qualquer texto do usuário **não passam por aqui** (§36, §37).
 *
 * A contagem é incrementada **antes** da chamada ao provider, e não depois: uma chamada que
 * falhou no meio pode já ter custado, então ela precisa contar para a proteção de custo. Isso
 * também fecha a janela em que várias requisições simultâneas leriam a mesma contagem baixa.
 */
@Injectable()
export class AiUsageRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /**
   * Registra uma tentativa e devolve como ficaram os contadores do dia.
   *
   * Tudo em uma transação: o incremento e as duas leituras enxergam o mesmo estado, então dois
   * pedidos concorrentes nunca recebem o mesmo "você é o número 50".
   */
  recordAttempt(uid: string, utcDate: string, requestType: AiCoachRequestType): DailyUsage {
    const db = this.sqlite.connection;
    const now = Date.now();

    const transaction = db.transaction((): DailyUsage => {
      db.prepare(
        `INSERT INTO ai_usage_daily (uid, utc_date, request_type, request_count, updated_at)
         VALUES (?, ?, ?, 1, ?)
         ON CONFLICT(uid, utc_date, request_type) DO UPDATE SET
           request_count = request_count + 1,
           updated_at = excluded.updated_at`,
      ).run(uid, utcDate, requestType, now);

      return {
        userRequests: this.userTotal(utcDate, uid),
        globalRequests: this.globalTotal(utcDate),
      };
    });

    return transaction();
  }

  /** Desfaz uma tentativa que **não** chegou ao provider. Nunca é usado depois de uma chamada. */
  releaseAttempt(uid: string, utcDate: string, requestType: AiCoachRequestType): void {
    this.sqlite.connection
      .prepare(
        `UPDATE ai_usage_daily
         SET request_count = MAX(request_count - 1, 0), updated_at = ?
         WHERE uid = ? AND utc_date = ? AND request_type = ?`,
      )
      .run(Date.now(), uid, utcDate, requestType);
  }

  /** Soma os tokens que o provider informou. Metadata de custo, nunca conteúdo. */
  recordTokens(
    uid: string,
    utcDate: string,
    requestType: AiCoachRequestType,
    tokens: { promptTokens: number; outputTokens: number; totalTokens: number },
  ): void {
    this.sqlite.connection
      .prepare(
        `UPDATE ai_usage_daily
         SET prompt_tokens = prompt_tokens + ?,
             output_tokens = output_tokens + ?,
             total_tokens = total_tokens + ?,
             updated_at = ?
         WHERE uid = ? AND utc_date = ? AND request_type = ?`,
      )
      .run(
        tokens.promptTokens,
        tokens.outputTokens,
        tokens.totalTokens,
        Date.now(),
        uid,
        utcDate,
        requestType,
      );
  }

  userTotal(utcDate: string, uid: string): number {
    const row = this.sqlite.connection
      .prepare(
        'SELECT COALESCE(SUM(request_count), 0) AS total FROM ai_usage_daily WHERE uid = ? AND utc_date = ?',
      )
      .get(uid, utcDate) as { total: number } | undefined;
    return row?.total ?? 0;
  }

  globalTotal(utcDate: string): number {
    const row = this.sqlite.connection
      .prepare(
        'SELECT COALESCE(SUM(request_count), 0) AS total FROM ai_usage_daily WHERE utc_date = ?',
      )
      .get(utcDate) as { total: number } | undefined;
    return row?.total ?? 0;
  }
}

export interface DailyUsage {
  readonly userRequests: number;
  readonly globalRequests: number;
}

/** O dia da quota, sempre em UTC. O fuso do aparelho é entrada não confiável. */
export function utcDateOf(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10);
}
