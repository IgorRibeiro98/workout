import { z } from 'zod';

/**
 * O contrato de um backup de DR do PostgreSQL no Object Storage (T18.3 §2).
 *
 * ```text
 * system/dr/postgres/<backupId>/
 *   database.dump     pg_dump --format=custom
 *   manifest.json     este documento — gravado por ÚLTIMO; sem ele o backup não existe
 * ```
 *
 * ## O manifesto é a declaração de validade
 *
 * A ordem de escrita é o invariante (a mesma lição de T18.1 §14): o dump sobe primeiro, o
 * manifesto por último. Uma pasta com `database.dump` e sem `manifest.json` é um backup que **não
 * terminou** — a retenção o ignora, o auditor o classifica como incompleto, e ninguém o restaura
 * por engano como se fosse válido. Um manifesto sem dump é uma inconsistência que o auditor
 * aponta (`MISSING_OBJECT`), nunca um backup.
 *
 * ## O que nunca entra aqui
 *
 * `DATABASE_URL`, senha, HMAC, chave do Gemini, token — o schema abaixo não tem campo para
 * nenhum deles, e `databaseIdentity` carrega só host, porta e nome do banco (o que
 * `postgres-url.ts` chama de identidade). Há teste que serializa um manifesto real e procura a
 * senha nele.
 */
export const DR_MANIFEST_FORMAT_VERSION = 1;

/** O namespace dos backups de DR dentro do bucket compartilhado. Nunca `social/` nem `backups/`. */
export const DR_POSTGRES_PREFIX = 'system/dr/postgres/';
export const DR_DUMP_OBJECT_NAME = 'database.dump';
export const DR_MANIFEST_OBJECT_NAME = 'manifest.json';

/** `2026-09-11T120000Z` — ordenável como texto, e sem `:` (que o nome de objeto não admite). */
export const DR_BACKUP_ID_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{6}Z$/;

export function drBackupIdFor(epochMs: number): string {
  const iso = new Date(epochMs).toISOString(); // 2026-09-11T12:00:00.000Z
  return `${iso.slice(0, 10)}T${iso.slice(11, 13)}${iso.slice(14, 16)}${iso.slice(17, 19)}Z`;
}

export function isDrBackupId(value: string): boolean {
  return DR_BACKUP_ID_PATTERN.test(value);
}

export function drDumpObjectName(backupId: string): string {
  return `${DR_POSTGRES_PREFIX}${backupId}/${DR_DUMP_OBJECT_NAME}`;
}

export function drManifestObjectName(backupId: string): string {
  return `${DR_POSTGRES_PREFIX}${backupId}/${DR_MANIFEST_OBJECT_NAME}`;
}

const migrationSchema = z.object({
  version: z.number().int().min(1),
  name: z.string().min(1),
  checksum: z
    .string()
    .regex(/^[0-9a-f]{64}$/)
    .nullable(),
});

export const drManifestSchema = z.object({
  formatVersion: z.literal(DR_MANIFEST_FORMAT_VERSION),
  backupId: z.string().regex(DR_BACKUP_ID_PATTERN),
  createdAt: z.string().datetime(),
  createdAtEpochMs: z.number().int().min(0),
  databaseIdentity: z.object({
    host: z.string().min(1),
    port: z.number().int().min(1).max(65535),
    database: z.string().min(1),
  }),
  /** O que produziu o backup, quando conhecido — o deploy injeta os dois no Job. */
  gitCommit: z
    .string()
    .regex(/^[0-9a-f]{7,40}$/)
    .nullable(),
  imageDigest: z.string().min(1).nullable(),
  dumpFormat: z.literal('pg_dump-custom'),
  dumpObject: z.string().min(1),
  dumpSizeBytes: z.number().int().min(1),
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
  /** `SELECT version()` do servidor, e a versão do `pg_dump` que gerou o arquivo. */
  postgresVersion: z.string().min(1),
  pgDumpVersion: z.string().min(1),
  /** Entradas do índice do arquivo (`pg_restore --list`), sem comentários. Prova que ele abre. */
  tocEntries: z.number().int().min(1),
  schema: z.object({
    /** A maior versão em `schema_migrations` no momento do dump. */
    schemaVersion: z.number().int().min(0),
    migrations: z.array(migrationSchema),
    /** As tabelas do schema `public`, ordenadas — o que o restore confere contra o destino. */
    tables: z.array(z.string().min(1)),
  }),
});

export type DrBackupManifest = z.infer<typeof drManifestSchema>;

export class DrManifestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'DrManifestError';
  }
}

/** Serialização canônica: sempre a mesma forma, para que um manifesto seja comparável byte a byte. */
export function serializeDrManifest(manifest: DrBackupManifest): Buffer {
  return Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
}

/** Faz o parse e valida. Lança [DrManifestError] — nunca devolve um manifesto parcial. */
export function parseDrManifest(bytes: Buffer): DrBackupManifest {
  let raw: unknown;
  try {
    raw = JSON.parse(bytes.toString('utf8'));
  } catch {
    throw new DrManifestError('manifest.json não é JSON válido');
  }
  const result = drManifestSchema.safeParse(raw);
  if (!result.success) {
    const first = result.error.issues[0];
    throw new DrManifestError(
      `manifest.json fora do contrato: ${first ? `${first.path.join('.')}: ${first.message}` : 'desconhecido'}`,
    );
  }
  return result.data;
}
