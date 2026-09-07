/**
 * Janela fixa de contagem por chave, em memória (T16.8 §84/§86).
 *
 * Generalização do limitador que a T16.6 escreveu para o sync, agora compartilhada — a mesma
 * proteção precisava existir no backup e no download de restore, e duas cópias do mesmo laço
 * divergiriam na primeira correção.
 *
 * **Sem Redis, e isso é a decisão.** O Spark é um backend, uma VPS, um processo (ADR-0001).
 * Reiniciar o processo zera as janelas, e isso é aceitável: o teto existe para conter um cliente
 * em laço, não para cobrar cota. Quem contabiliza custo real — a IA — tem tabela própria e
 * durável (`ai_usage_daily`), justamente porque ali um reset seria dinheiro.
 *
 * A chave é sempre o `uid` autenticado, nunca o IP (§85): em rede móvel e atrás de NAT o IP é
 * compartilhado por gente que não tem nada a ver com o abuso, e o Caddy à frente faria todo mundo
 * parecer o mesmo cliente. Todas as rotas com limite aqui exigem Bearer, então o `uid` sempre
 * existe quando a contagem acontece.
 */
export interface RateLimitPolicy {
  readonly windowMs: number;
  readonly maxRequestsPerWindow: number;
}

/** Contas rastreadas antes de uma limpeza das janelas já expiradas. */
const MAX_TRACKED_KEYS = 1_000;

export class FixedWindowRateLimiter {
  private readonly windows = new Map<string, { start: number; count: number }>();

  constructor(private readonly policy: RateLimitPolicy) {}

  /** `true` quando a requisição cabe na janela desta chave. */
  tryAcquire(key: string): boolean {
    const now = Date.now();
    const window = this.windows.get(key);

    if (!window || now - window.start >= this.policy.windowMs) {
      this.windows.set(key, { start: now, count: 1 });
      // Uma limpeza barata: sem ela, um servidor de longa vida guardaria uma entrada por conta
      // que já usou a rota uma vez. O custo é O(n) só quando o mapa cresce demais.
      if (this.windows.size > MAX_TRACKED_KEYS) {
        this.forgetExpired(now);
      }
      return true;
    }

    if (window.count >= this.policy.maxRequestsPerWindow) {
      return false;
    }
    window.count += 1;
    return true;
  }

  private forgetExpired(now: number): void {
    for (const [key, window] of this.windows) {
      if (now - window.start >= this.policy.windowMs) {
        this.windows.delete(key);
      }
    }
  }
}
