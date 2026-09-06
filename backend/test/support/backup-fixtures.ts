import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * As fixtures canônicas do contrato de backup (T16.4).
 *
 * Elas vivem em `contracts/backup/v1/fixtures/`, fora de `backend/`, porque o mesmo arquivo é lido
 * pelo teste do Android (`BackupContractFixturesTest`). Um formato que mude de um lado só faz os
 * dois lados falharem juntos — que é exatamente o ponto de ter um diretório de contrato em vez de
 * duas definições independentes.
 */
export const FIXTURES_DIR = join(__dirname, '..', '..', '..', 'contracts', 'backup', 'v1', 'fixtures');

export type BackupFixtureName =
  | 'backup-v1-minimal'
  | 'backup-v1-complete'
  | 'backup-v1-invalid-id'
  | 'backup-v1-duplicate-item'
  | 'backup-v1-unsupported-version';

/** O texto cru da fixture — é o que o cliente enviaria, e é sobre ele que o hash é calculado. */
export function fixtureText(name: BackupFixtureName): string {
  return readFileSync(join(FIXTURES_DIR, `${name}.json`), 'utf8');
}

export function fixture(name: BackupFixtureName): Record<string, unknown> {
  return JSON.parse(fixtureText(name)) as Record<string, unknown>;
}

/** A mesma fixture com outro `clientBackupId`, para exercitar tentativas distintas. */
export function withClientBackupId(
  name: BackupFixtureName,
  clientBackupId: string,
): Record<string, unknown> {
  return { ...fixture(name), clientBackupId };
}
