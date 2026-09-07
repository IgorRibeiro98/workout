import { Injectable } from '@nestjs/common';
import { SYNC_RATE_LIMIT } from './sync.limits';

/**
 * Proteção simples por conta contra um cliente em laço (T16.6 §99).
 *
 * Uma janela fixa e uma contagem por `uid`, em memória. Não é rate limiting distribuído, não
 * pretende ser, e não precisa ser: o Spark é um backend, uma VPS, um processo (ADR-0001). O
 * objetivo é único — um defeito no app não pode virar milhares de requisições por minuto.
 *
 * Reiniciar o processo zera a janela, e isso é aceitável: o teto existe para conter laço, não
 * para cobrar cota. Quem contabiliza custo real (a IA) tem tabela própria e durável.
 */
@Injectable()
export class SyncRateLimiter {
  private readonly windows = new Map<string, { start: number; count: number }>();

  /** `true` quando a requisição cabe na janela desta conta. */
  tryAcquire(uid: string): boolean {
    const now = Date.now();
    const window = this.windows.get(uid);

    if (!window || now - window.start >= SYNC_RATE_LIMIT.windowMs) {
      this.windows.set(uid, { start: now, count: 1 });
      // Uma limpeza barata: sem ela, um servidor de longa vida guardaria uma entrada por conta
      // que já sincronizou uma vez. O custo é O(n) só quando o mapa cresce demais.
      if (this.windows.size > MAX_TRACKED_ACCOUNTS) {
        this.forgetExpired(now);
      }
      return true;
    }

    if (window.count >= SYNC_RATE_LIMIT.maxRequestsPerWindow) {
      return false;
    }
    window.count += 1;
    return true;
  }

  private forgetExpired(now: number): void {
    for (const [uid, window] of this.windows) {
      if (now - window.start >= SYNC_RATE_LIMIT.windowMs) {
        this.windows.delete(uid);
      }
    }
  }
}

const MAX_TRACKED_ACCOUNTS = 1_000;
