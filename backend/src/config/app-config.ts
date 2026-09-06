import { envSchema, SparkEnv } from './env.schema';

export class ConfigValidationError extends Error {
  constructor(readonly issues: string[]) {
    super(`Configuração inválida:\n${issues.map((i) => `  - ${i}`).join('\n')}`);
    this.name = 'ConfigValidationError';
  }
}

/**
 * Configuração validada da aplicação.
 *
 * É um valor imutável construído uma única vez no bootstrap. Ninguém lê `process.env` fora daqui.
 */
export class AppConfig {
  private constructor(private readonly env: SparkEnv) {}

  static fromEnv(source: NodeJS.ProcessEnv = process.env): AppConfig {
    const result = envSchema.safeParse(source);
    if (!result.success) {
      throw new ConfigValidationError(
        result.error.issues.map((issue) => `${issue.path.join('.') || '(raiz)'}: ${issue.message}`),
      );
    }
    return new AppConfig(result.data);
  }

  get nodeEnv(): SparkEnv['NODE_ENV'] {
    return this.env.NODE_ENV;
  }

  get isProduction(): boolean {
    return this.env.NODE_ENV === 'production';
  }

  get port(): number {
    return this.env.PORT;
  }

  get databasePath(): string {
    return this.env.DATABASE_PATH;
  }

  get logLevel(): SparkEnv['LOG_LEVEL'] {
    return this.env.LOG_LEVEL;
  }

  get sqliteBusyTimeoutMs(): number {
    return this.env.SQLITE_BUSY_TIMEOUT_MS;
  }

  get shutdownTimeoutMs(): number {
    return this.env.SHUTDOWN_TIMEOUT_MS;
  }

  /** Caminho do arquivo de service account do Firebase Admin, quando configurado. */
  get googleApplicationCredentials(): string | undefined {
    return this.env.GOOGLE_APPLICATION_CREDENTIALS;
  }

  get firebaseProjectId(): string | undefined {
    return this.env.FIREBASE_PROJECT_ID;
  }
  // --- Coach IA (T16.2) -----------------------------------------------------------------
  //
  // O backend é a única fronteira com o Gemini. Estes valores existem aqui, e só aqui: nenhum
  // controller, serviço ou gateway escolhe modelo, temperatura, timeout ou teto por conta.

  /** Credencial do Gemini. `undefined` = Coach indisponível neste servidor, nunca inseguro. */
  get geminiApiKey(): string | undefined {
    return this.env.GEMINI_API_KEY;
  }

  get geminiModel(): string {
    return this.env.GEMINI_MODEL;
  }

  get aiTimeoutMs(): number {
    return this.env.AI_TIMEOUT_MS;
  }

  get aiTemperature(): number {
    return this.env.AI_TEMPERATURE;
  }

  get aiMaxOutputTokens(): number {
    return this.env.AI_MAX_OUTPUT_TOKENS;
  }

  get aiThinkingLevel(): SparkEnv['AI_THINKING_LEVEL'] {
    return this.env.AI_THINKING_LEVEL;
  }

  get aiMaxRequestsPerUserDay(): number {
    return this.env.AI_MAX_REQUESTS_PER_USER_DAY;
  }

  get aiMaxRequestsGlobalDay(): number {
    return this.env.AI_MAX_REQUESTS_GLOBAL_DAY;
  }

  get aiMaxConcurrentRequestsPerUser(): number {
    return this.env.AI_MAX_CONCURRENT_REQUESTS_PER_USER;
  }

  // --- Backup (T16.4) -------------------------------------------------------------------

  /** Quantos snapshots guardar por conta antes de a retenção remover os mais antigos. */
  get backupRetentionCount(): number {
    return this.env.BACKUP_RETENTION_COUNT;
  }
}

export const APP_CONFIG = Symbol('APP_CONFIG');
