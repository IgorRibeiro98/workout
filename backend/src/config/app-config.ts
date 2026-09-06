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
}

export const APP_CONFIG = Symbol('APP_CONFIG');
