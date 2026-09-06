import { Inject, Injectable } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../../config/app-config';

/**
 * Quem está falando com o provider agora.
 *
 * Duas proteções na mesma estrutura, ambas contra o mesmo tipo de acidente — pagar duas vezes
 * pela mesma intenção do usuário:
 *
 * - **deduplicação**: o mesmo `clientRequestId` da mesma conta não entra duas vezes. É o toque
 *   duplo chegando pela rede depois que o Android já deixou dois passarem;
 * - **concorrência**: uma conta tem no máximo `AI_MAX_CONCURRENT_REQUESTS_PER_USER` chamadas
 *   ativas (1 por padrão). Dez toques rápidos viram uma chamada ao Gemini, não dez.
 *
 * Em memória de propósito: o Spark Backend é um processo só em uma VPS (ADR-0001). Redis existiria
 * para coordenar réplicas que não existem, e a T16.2 declara fila/Redis fora de escopo.
 */
@Injectable()
export class AiRequestRegistry {
  /** uid → `clientRequestId`s em voo. */
  private readonly inFlight = new Map<string, Set<string>>();

  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  /**
   * Reserva uma vaga, ou diz por que não deu.
   *
   * Síncrono e sem `await` no meio: em Node, entre o `get` e o `set` daqui não roda mais nada, e
   * é isso que torna a reserva atômica sem lock.
   */
  tryAcquire(uid: string, clientRequestId: string): AcquireResult {
    const active = this.inFlight.get(uid);

    if (active?.has(clientRequestId)) {
      return { acquired: false, reason: 'DUPLICATE' };
    }
    if (active && active.size >= this.config.aiMaxConcurrentRequestsPerUser) {
      return { acquired: false, reason: 'CONCURRENCY_LIMIT' };
    }

    if (active) {
      active.add(clientRequestId);
    } else {
      this.inFlight.set(uid, new Set([clientRequestId]));
    }
    return { acquired: true };
  }

  release(uid: string, clientRequestId: string): void {
    const active = this.inFlight.get(uid);
    if (!active) return;
    active.delete(clientRequestId);
    if (active.size === 0) {
      this.inFlight.delete(uid);
    }
  }

  /** Quantas chamadas esta conta tem em voo. Existe para os testes verem o invariante. */
  activeFor(uid: string): number {
    return this.inFlight.get(uid)?.size ?? 0;
  }
}

export type AcquireResult =
  | { acquired: true }
  | { acquired: false; reason: 'DUPLICATE' | 'CONCURRENCY_LIMIT' };
