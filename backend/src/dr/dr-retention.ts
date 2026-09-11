import type { DrBackupStatus, DrValidBackup } from './dr-backup.store';

/**
 * A política de retenção dos backups de DR (T18.3 §8) — uma função pura sobre os status.
 *
 * ```text
 * válidos, do mais novo ao mais antigo
 *   ├── os `keepCount` primeiros      → ficam
 *   └── o resto                        → removidos
 * incompletos / sem dump / inválidos   → NUNCA removidos aqui (só reportados)
 * ```
 *
 * ## O que ela promete
 *
 * - **nunca remove o último válido**: `keepCount` é no mínimo 1, e um único válido fica sempre;
 * - **só remove o que provou ser válido e antigo**: um backup que não passou em `statusOf` não
 *   entra na contagem nem na lista de remoção — "na dúvida, preserva". Um dump sem manifesto pode
 *   ser um backup em andamento neste exato instante (o Job grava o manifesto por último); um
 *   manifesto que não faz parse pode ser um formato mais novo que este código; nada disso é
 *   motivo para apagar bytes;
 * - **idempotente**: a mesma lista de status produz o mesmo plano; executar o plano e recalcular
 *   produz um plano vazio.
 *
 * A ordem é por `createdAtEpochMs` do manifesto (o instante do dump), com o `backupId` como
 * desempate — nunca pelo `createdAt` do objeto, que o provider pode não saber (T18.1.1 §9).
 */
export interface DrRetentionPlan {
  readonly keep: readonly DrValidBackup[];
  readonly remove: readonly DrValidBackup[];
  /** Pastas que não são backups válidos — reportadas, nunca tocadas. */
  readonly ignored: readonly Exclude<DrBackupStatus, { kind: 'valid' }>[];
}

export function planDrRetention(
  statuses: readonly DrBackupStatus[],
  keepCount: number,
): DrRetentionPlan {
  const keep = Math.max(1, Math.trunc(keepCount));
  const valid = statuses
    .flatMap((status) => (status.kind === 'valid' ? [status.backup] : []))
    .sort(
      (a, b) =>
        b.manifest.createdAtEpochMs - a.manifest.createdAtEpochMs ||
        b.backupId.localeCompare(a.backupId),
    );
  const ignored = statuses.flatMap((status) => (status.kind === 'valid' ? [] : [status]));
  return {
    keep: valid.slice(0, keep),
    remove: valid.slice(keep),
    ignored,
  };
}
