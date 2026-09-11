/**
 * Os limiares operacionais de tamanho do PostgreSQL (T18.3 §13).
 *
 * ## Por que existe como política, e não como número num `if`
 *
 * O Neon (Free/Launch) tem um teto de armazenamento, e o Spark trata a aproximação a esse teto
 * como incidente **gradual**: primeiro atenção, depois investigação, depois planejamento, depois
 * ação obrigatória. Os quatro números vivem em `DATABASE_SIZE_THRESHOLDS_MB` (um único ponto de
 * configuração; default `300,350,400,450`) e a classificação vive aqui — nem o ciclo de
 * manutenção, nem o log, nem o alerta conhecem os números, só o nível.
 *
 * ```text
 * < 300 MB   NORMAL
 * ≥ 300 MB   ATTENTION        acompanhar
 * ≥ 350 MB   INVESTIGATE      entender o crescimento
 * ≥ 400 MB   PLAN             planejar ação (retenção, migração de plano, limpeza)
 * ≥ 450 MB   ACTION_REQUIRED  agir agora — o próximo passo é o provedor recusar escrita
 * ```
 *
 * Quem mede é `MaintenanceCoordinator` (`pg_database_size(current_database())`, na cadência de
 * `DATABASE_SIZE_CHECK_INTERVAL_MS`) — nunca uma requisição HTTP, nunca a cada minuto.
 */
export type DatabaseSizeLevel = 'NORMAL' | 'ATTENTION' | 'INVESTIGATE' | 'PLAN' | 'ACTION_REQUIRED';

/** Do menos ao mais grave, alinhado posicionalmente com os quatro limiares. */
const LEVELS_ABOVE_NORMAL: readonly DatabaseSizeLevel[] = [
  'ATTENTION',
  'INVESTIGATE',
  'PLAN',
  'ACTION_REQUIRED',
];

export const BYTES_PER_MB = 1024 * 1024;

export interface DatabaseSizeAssessment {
  readonly sizeBytes: number;
  readonly sizeMb: number;
  readonly level: DatabaseSizeLevel;
  /** O limiar (em MB) que foi ultrapassado, ou `null` em NORMAL — para o log e o alerta. */
  readonly thresholdMb: number | null;
}

/**
 * Classifica um tamanho em bytes contra os quatro limiares crescentes (em MB).
 *
 * Um tamanho negativo ou não finito é tratado como 0 — a medição que falhou não é responsabilidade
 * desta função; quem mede decide se loga `database_size_check_failed`.
 */
export function classifyDatabaseSize(
  sizeBytes: number,
  thresholdsMb: readonly number[],
): DatabaseSizeAssessment {
  if (thresholdsMb.length !== LEVELS_ABOVE_NORMAL.length) {
    throw new Error(`a política de tamanho exige ${LEVELS_ABOVE_NORMAL.length} limiares`);
  }
  const safeBytes = Number.isFinite(sizeBytes) && sizeBytes > 0 ? sizeBytes : 0;
  const sizeMb = safeBytes / BYTES_PER_MB;

  let level: DatabaseSizeLevel = 'NORMAL';
  let thresholdMb: number | null = null;
  thresholdsMb.forEach((threshold, index) => {
    if (sizeMb >= threshold) {
      level = LEVELS_ABOVE_NORMAL[index];
      thresholdMb = threshold;
    }
  });

  return { sizeBytes: safeBytes, sizeMb: Math.round(sizeMb * 100) / 100, level, thresholdMb };
}

/** Os níveis que merecem alerta, e não só registro. `PLAN` já pede uma pessoa olhando. */
export function isDatabaseSizeAlerting(level: DatabaseSizeLevel): boolean {
  return level === 'PLAN' || level === 'ACTION_REQUIRED';
}
