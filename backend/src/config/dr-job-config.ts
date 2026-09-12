import { tmpdir } from 'node:os';
import { z } from 'zod';
import { envSchema } from './env.schema';

/**
 * A configuração dos Jobs de DR (`db-backup`, `db-restore-drill`) — deliberadamente **não** é
 * `AppConfig` (T18.3 §2).
 *
 * Pelo mesmo motivo de `migrate-database.ts` (T18.2 §7): a Service Account `spark-backend-backup`
 * só tem acesso ao secret direto do banco e ao prefixo de DR do bucket. Passar por `AppConfig`
 * exigiria `DATABASE_URL` (a pooled), e aceitaria `GEMINI_API_KEY`, `ACCOUNT_DELETION_HMAC_KEY` e
 * o resto — variáveis que este Job não tem, não precisa e não deve ter. Aqui só entra o que o
 * backup usa: a URL direta, onde os objetos moram, retenção e proveniência.
 *
 * Os campos de Object Storage são **os mesmos** do `envSchema` (`.pick`), para que a validação do
 * nome de bucket e do provider nunca divirja da API — e a escolha `local|gcs` continua morando na
 * factory, que recebe estas settings como recebe `AppConfig`.
 */
const objectStorageEnv = envSchema.pick({
  LOG_LEVEL: true,
  OBJECT_STORAGE_PROVIDER: true,
  GCS_BUCKET_NAME: true,
  OBJECT_STORAGE_TIMEOUT_MS: true,
  SOCIAL_MEDIA_ROOT: true,
});

const drEnv = objectStorageEnv.extend({
  /** A URL direta/admin. Sem fallback para a pooled — a mesma regra de `migrate:database`. */
  DATABASE_URL_DIRECT: z.preprocess(
    (value) => (typeof value === 'string' && value.trim() === '' ? undefined : value),
    z.string().min(1),
  ),
  /** Quantos backups válidos ficam. Mínimo 1: o último válido nunca é removido. */
  SPARK_DR_RETENTION_COUNT: z.coerce.number().int().min(1).max(365).default(7),
  /** Onde o dump nasce antes do upload. No Cloud Run é tmpfs (conta como memória do Job). */
  SPARK_DR_WORK_DIR: z.string().min(1).default(tmpdir()),
  /**
   * Teto do arquivo de dump. Acima disto o Job falha com uma mensagem clara em vez de estourar a
   * memória do container ao carregar o arquivo para o upload. Cresce junto com a memória do Job.
   *
   * 256 MiB, e o número vem da conta (T18.3.2). O Job roda com 1 GiB
   * (`SPARK_RUN_BACKUP_MEMORY` em `ops/gcp/lib.gcp.sh`), e um dump de N bytes ocupa **duas** vezes
   * N ao mesmo tempo: o arquivo em tmpfs (que no Cloud Run conta como memória) e o buffer que o
   * upload entrega ao provider. Somando o runtime do Node, o teto anterior de 768 MiB descrevia um
   * arquivo que o Job **não conseguiria processar** — ele morreria por OOM antes de conseguir
   * registrar `db_backup_failed`, que é o oposto de falhar com mensagem clara. 256 MiB cabe com
   * folga (2 × 256 MiB + runtime) e continua muito acima do dump real: o limiar mais grave de
   * tamanho do banco é 450 MB (`DATABASE_SIZE_THRESHOLDS_MB`), e o formato custom é comprimido.
   * Subir este valor exige subir `SPARK_RUN_BACKUP_MEMORY` junto.
   */
  SPARK_DR_MAX_DUMP_BYTES: z.coerce
    .number()
    .int()
    .min(1024 * 1024)
    .max(8 * 1024 * 1024 * 1024)
    .default(256 * 1024 * 1024),
  /** Proveniência, injetada pelo deploy. Opcional: um Job disparado à mão pode não saber. */
  SPARK_GIT_COMMIT: z.preprocess(
    (value) => (typeof value === 'string' && value.trim() === '' ? undefined : value),
    z
      .string()
      .regex(/^[0-9a-f]{7,40}$/)
      .optional(),
  ),
  SPARK_IMAGE_DIGEST: z.preprocess(
    (value) => (typeof value === 'string' && value.trim() === '' ? undefined : value),
    z.string().min(1).optional(),
  ),
});

const drillEnv = objectStorageEnv.extend({
  /** O backup a ensaiar. Ausente = o válido mais recente. */
  SPARK_DR_BACKUP_ID: z.preprocess(
    (value) => (typeof value === 'string' && value.trim() === '' ? undefined : value),
    z
      .string()
      .regex(/^\d{4}-\d{2}-\d{2}T\d{6}Z$/)
      .optional(),
  ),
  /** Conexão administrativa ao servidor do ensaio, num banco de manutenção (com CREATEDB). */
  SPARK_DRILL_ADMIN_URL: z.preprocess(
    (value) => (typeof value === 'string' && value.trim() === '' ? undefined : value),
    z.string().min(1, 'SPARK_DRILL_ADMIN_URL é obrigatório'),
  ),
  /** O banco descartável. Obrigatório e explícito — não existe default (T18.3 §5). */
  SPARK_DRILL_DATABASE: z.preprocess(
    (value) => (typeof value === 'string' && value.trim() === '' ? undefined : value),
    z
      .string()
      .regex(
        /^spark_drill_[a-z0-9_]{1,40}$/,
        'SPARK_DRILL_DATABASE precisa ter a forma spark_drill_<a-z0-9_>',
      ),
  ),
  SPARK_DRILL_KEEP_DATABASE: z.enum(['true', 'false']).default('false'),
  SPARK_DRILL_REPLACE_EXISTING: z.enum(['true', 'false']).default('false'),
  SPARK_DR_WORK_DIR: z.string().min(1).default(tmpdir()),
});

export class DrJobConfigError extends Error {
  constructor(readonly issues: string[]) {
    super(`Configuração do Job de DR inválida:\n${issues.map((i) => `  - ${i}`).join('\n')}`);
    this.name = 'DrJobConfigError';
  }
}

/** O que a factory de Object Storage e o logger precisam — `AppConfig` também satisfaz isto. */
export interface ObjectStorageSettings {
  readonly objectStorageProvider: 'local' | 'gcs';
  readonly gcsBucketName: string | undefined;
  readonly objectStorageTimeoutMs: number;
  readonly socialMediaRoot: string;
}

export interface LoggerSettings {
  readonly logLevel: 'fatal' | 'error' | 'warn' | 'info' | 'debug' | 'trace' | 'silent';
}

export class DrJobConfig implements ObjectStorageSettings, LoggerSettings {
  private constructor(private readonly env: z.infer<typeof drEnv>) {}

  static fromEnv(source: NodeJS.ProcessEnv = process.env): DrJobConfig {
    const result = drEnv.safeParse({ ...source });
    if (!result.success) {
      throw new DrJobConfigError(
        result.error.issues.map((issue) => `${issue.path.join('.') || '(raiz)'}: ${issue.message}`),
      );
    }
    const config = new DrJobConfig(result.data);
    const missing = config.missingRequirements();
    if (missing.length > 0) {
      throw new DrJobConfigError(missing);
    }
    return config;
  }

  get databaseUrlDirect(): string {
    return this.env.DATABASE_URL_DIRECT;
  }

  get logLevel(): LoggerSettings['logLevel'] {
    return this.env.LOG_LEVEL;
  }

  get objectStorageProvider(): 'local' | 'gcs' {
    return this.env.OBJECT_STORAGE_PROVIDER;
  }

  get gcsBucketName(): string | undefined {
    return this.env.GCS_BUCKET_NAME;
  }

  get objectStorageTimeoutMs(): number {
    return this.env.OBJECT_STORAGE_TIMEOUT_MS;
  }

  /**
   * A raiz do provider `local`. Obrigatória com `local` — um Job de DR gravando "em algum lugar"
   * derivado do diretório de trabalho seria um backup que ninguém encontra.
   */
  get socialMediaRoot(): string {
    return this.env.SOCIAL_MEDIA_ROOT ?? '';
  }

  get retentionCount(): number {
    return this.env.SPARK_DR_RETENTION_COUNT;
  }

  get workDir(): string {
    return this.env.SPARK_DR_WORK_DIR;
  }

  get maxDumpBytes(): number {
    return this.env.SPARK_DR_MAX_DUMP_BYTES;
  }

  get gitCommit(): string | null {
    return this.env.SPARK_GIT_COMMIT ?? null;
  }

  get imageDigest(): string | null {
    return this.env.SPARK_IMAGE_DIGEST ?? null;
  }

  private missingRequirements(): string[] {
    const missing: string[] = [];
    if (this.objectStorageProvider === 'gcs' && this.gcsBucketName === undefined) {
      missing.push('OBJECT_STORAGE_PROVIDER=gcs exige GCS_BUCKET_NAME');
    }
    if (this.objectStorageProvider === 'local' && !this.env.SOCIAL_MEDIA_ROOT) {
      missing.push('OBJECT_STORAGE_PROVIDER=local exige SOCIAL_MEDIA_ROOT (a raiz dos objetos)');
    }
    return missing;
  }
}

/**
 * A configuração do ensaio de restauração (`db-restore-drill`). Não precisa de
 * `DATABASE_URL_DIRECT`: o ensaio nunca conecta a produção — ele lê o bucket e escreve num
 * servidor de ensaio, e é essa ausência que torna verificável "restore nunca aponta para produção".
 */
export class DrRestoreDrillConfig implements ObjectStorageSettings, LoggerSettings {
  private constructor(private readonly env: z.infer<typeof drillEnv>) {}

  static fromEnv(source: NodeJS.ProcessEnv = process.env): DrRestoreDrillConfig {
    const result = drillEnv.safeParse({ ...source });
    if (!result.success) {
      throw new DrJobConfigError(
        result.error.issues.map((issue) => `${issue.path.join('.') || '(raiz)'}: ${issue.message}`),
      );
    }
    const config = new DrRestoreDrillConfig(result.data);
    const missing: string[] = [];
    if (config.objectStorageProvider === 'gcs' && config.gcsBucketName === undefined) {
      missing.push('OBJECT_STORAGE_PROVIDER=gcs exige GCS_BUCKET_NAME');
    }
    if (config.objectStorageProvider === 'local' && !result.data.SOCIAL_MEDIA_ROOT) {
      missing.push('OBJECT_STORAGE_PROVIDER=local exige SOCIAL_MEDIA_ROOT (a raiz dos objetos)');
    }
    if (missing.length > 0) {
      throw new DrJobConfigError(missing);
    }
    return config;
  }

  get logLevel(): LoggerSettings['logLevel'] {
    return this.env.LOG_LEVEL;
  }

  get objectStorageProvider(): 'local' | 'gcs' {
    return this.env.OBJECT_STORAGE_PROVIDER;
  }

  get gcsBucketName(): string | undefined {
    return this.env.GCS_BUCKET_NAME;
  }

  get objectStorageTimeoutMs(): number {
    return this.env.OBJECT_STORAGE_TIMEOUT_MS;
  }

  get socialMediaRoot(): string {
    return this.env.SOCIAL_MEDIA_ROOT ?? '';
  }

  get backupId(): string | null {
    return this.env.SPARK_DR_BACKUP_ID ?? null;
  }

  get adminUrl(): string {
    return this.env.SPARK_DRILL_ADMIN_URL;
  }

  get drillDatabase(): string {
    return this.env.SPARK_DRILL_DATABASE;
  }

  get keepDatabase(): boolean {
    return this.env.SPARK_DRILL_KEEP_DATABASE === 'true';
  }

  get replaceExisting(): boolean {
    return this.env.SPARK_DRILL_REPLACE_EXISTING === 'true';
  }

  get workDir(): string {
    return this.env.SPARK_DR_WORK_DIR;
  }
}
