/**
 * Estatística mínima dos relatórios do Coach (`ai:usage-report`, `ai:benchmark`).
 *
 * Percentil por **nearest-rank** sobre os valores ordenados: com amostras pequenas (dezenas de
 * chamadas) ele devolve sempre um valor que de fato aconteceu, em vez de uma interpolação que
 * nenhuma chamada teve. p95 de 12 amostras é a maior delas — e o relatório mostra `n` ao lado para
 * que ninguém leia isso como mais do que é.
 */
export interface Distribution {
  readonly n: number;
  readonly avg: number;
  readonly p50: number;
  readonly p95: number;
  readonly max: number;
}

export function distribution(values: readonly number[]): Distribution | undefined {
  const finite = values.filter((value) => Number.isFinite(value));
  if (finite.length === 0) return undefined;
  const sorted = [...finite].sort((a, b) => a - b);
  const sum = sorted.reduce((total, value) => total + value, 0);
  return {
    n: sorted.length,
    avg: sum / sorted.length,
    p50: nearestRank(sorted, 50),
    p95: nearestRank(sorted, 95),
    max: sorted[sorted.length - 1],
  };
}

function nearestRank(sorted: readonly number[], percentile: number): number {
  const rank = Math.ceil((percentile / 100) * sorted.length);
  return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
}

/** Inteiro para tabela; `—` quando não há amostra. */
export function formatNumber(value: number | undefined): string {
  return value === undefined || !Number.isFinite(value) ? '—' : String(Math.round(value));
}

/** Percentual com uma casa; `—` quando o denominador é zero. */
export function formatPercent(part: number, whole: number): string {
  return whole === 0 ? '—' : `${((part / whole) * 100).toFixed(1)}%`;
}
