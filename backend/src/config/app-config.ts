import { dirname, join } from 'node:path';
import { DEVELOPMENT_DELETION_HMAC_KEY, envSchema, SparkEnv } from './env.schema';

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
    const raw = { ...source };
    const issues: string[] = [];

    if (!raw.DATABASE_URL && !raw.DATABASE_PATH) {
      issues.push('DATABASE_PATH: DATABASE_PATH é obrigatório');
    } else if (raw.DATABASE_PATH !== undefined && raw.DATABASE_PATH.trim() === '') {
      issues.push('DATABASE_PATH: DATABASE_PATH não pode ser vazio');
    } else if (!raw.DATABASE_URL && raw.DATABASE_PATH) {
      raw.DATABASE_URL = raw.DATABASE_PATH.startsWith('postgres')
        ? raw.DATABASE_PATH
        : (process.env.DATABASE_URL || 'postgresql://spark:spark@localhost:5432/spark_dev');
    }
    const result = envSchema.safeParse(raw);
    if (!result.success) {
      issues.push(
        ...result.error.issues.map(
          (issue) => `${issue.path.join('.') || '(raiz)'}: ${issue.message}`,
        ),
      );
    }

    if (issues.length > 0 || !result.success) {
      throw new ConfigValidationError(issues);
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

  get databaseUrl(): string {
    return this.env.DATABASE_URL;
  }

  get databaseUrlDirect(): string {
    return this.env.DATABASE_URL_DIRECT ?? this.env.DATABASE_URL;
  }

  get logLevel(): SparkEnv['LOG_LEVEL'] {
    return this.env.LOG_LEVEL;
  }

  get databasePoolMin(): number {
    return this.env.DATABASE_POOL_MIN;
  }

  get databasePoolMax(): number {
    return this.env.DATABASE_POOL_MAX;
  }

  get databaseConnectionTimeoutMs(): number {
    return this.env.DATABASE_CONNECTION_TIMEOUT_MS;
  }

  get databaseIdleTimeoutMs(): number {
    return this.env.DATABASE_IDLE_TIMEOUT_MS;
  }

  get databaseStatementTimeoutMs(): number {
    return this.env.DATABASE_STATEMENT_TIMEOUT_MS;
  }

  get httpRequestTimeoutMs(): number {
    return this.env.HTTP_REQUEST_TIMEOUT_MS;
  }

  get httpKeepAliveTimeoutMs(): number {
    return this.env.HTTP_KEEP_ALIVE_TIMEOUT_MS;
  }

  get shutdownTimeoutMs(): number {
    return this.env.SHUTDOWN_TIMEOUT_MS;
  }

  // Compatibilidade legada
  get databasePath(): string {
    return (this.env as any).DATABASE_PATH ?? ':memory:';
  }

  get sqliteBusyTimeoutMs(): number {
    return (this.env as any).SQLITE_BUSY_TIMEOUT_MS ?? 5000;
  }

  get sqliteSynchronous(): string {
    return (this.env as any).SQLITE_SYNCHRONOUS ?? 'NORMAL';
  }

  get sqliteWalAutocheckpointPages(): number {
    return (this.env as any).SQLITE_WAL_AUTOCHECKPOINT_PAGES ?? 1000;
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

  // --- Prontidão de produção (T16.8) ----------------------------------------------------

  get requireFirebaseAdmin(): boolean {
    return this.env.REQUIRE_FIREBASE_ADMIN;
  }

  get requireGemini(): boolean {
    return this.env.REQUIRE_GEMINI;
  }

  /** `false` desliga o Coach neste servidor sem tocar em backup e sync. */
  get aiEnabled(): boolean {
    return this.env.AI_ENABLED;
  }

  /** `false` pausa `POST /v1/sync/push`; o pull, somente leitura, continua. */
  get syncWriteEnabled(): boolean {
    return this.env.SYNC_WRITE_ENABLED;
  }

  /** `true` faz toda rota `/v1` responder 503; `/health/*` continua respondendo. */
  get maintenanceMode(): boolean {
    return this.env.MAINTENANCE_MODE;
  }

  // --- Notificações sociais (T17.5) ----------------------------------------------------

  get socialPushEnabled(): boolean {
    return this.env.SOCIAL_PUSH_ENABLED;
  }

  get pushDispatchIntervalMs(): number {
    return this.env.PUSH_DISPATCH_INTERVAL_MS;
  }

  get pushMaxAttempts(): number {
    return this.env.PUSH_MAX_ATTEMPTS;
  }

  get pushBatchSize(): number {
    return this.env.PUSH_BATCH_SIZE;
  }

  // --- Hardening social e exclusão de conta (T17.6) -------------------------------------

  get accountDeletionHmacKey(): string {
    return this.env.ACCOUNT_DELETION_HMAC_KEY;
  }

  get deletionTombstonesFilePath(): string {
    return this.env.DELETION_TOMBSTONES_FILE_PATH;
  }

  // --- Mídia social (T17.9) -------------------------------------------------------------

  /**
   * Onde os arquivos de mídia social vivem (§22/§26/§28).
   *
   * Em produção o valor **precisa** vir do ambiente, e `missingRequirements()` derruba o startup
   * quando ele não vem. Fora de produção, o padrão é um diretório ao lado do banco: teste e
   * desenvolvimento precisam funcionar sem configuração nenhuma, e ali o armazenamento efêmero é
   * exatamente o que se quer. `:memory:` não tem diretório — nesse caso o fallback é um diretório
   * de trabalho local, que é onde o teste já escreve.
   */
  get socialMediaRoot(): string {
    const configured = this.env.SOCIAL_MEDIA_ROOT;
    if (configured) {
      return configured;
    }
    const dbPath = this.databasePath;
    if (dbPath && dbPath !== ':memory:' && !dbPath.startsWith('postgres')) {
      return join(dirname(dbPath), 'media');
    }
    return join(process.cwd(), '.spark-media');
  }

  /** `true` quando o operador declarou o caminho, e não quando ele foi derivado. */
  get socialMediaRootIsExplicit(): boolean {
    return this.env.SOCIAL_MEDIA_ROOT !== undefined;
  }

  get socialMediaMaxUploadBytes(): number {
    return this.env.SOCIAL_MEDIA_MAX_UPLOAD_BYTES;
  }

  get socialMediaMaxUserBytes(): number {
    return this.env.SOCIAL_MEDIA_MAX_USER_BYTES;
  }

  get socialMediaCleanupIntervalMs(): number {
    return this.env.SOCIAL_MEDIA_CLEANUP_INTERVAL_MS;
  }

  /**
   * As exigências que o operador declarou e o ambiente não cumpre.
   *
   * Separado da validação do schema porque não é uma configuração malformada: cada valor é
   * individualmente válido, e o que falta é uma **combinação** que aquele deploy declarou
   * obrigatória. Quem chama isto é o bootstrap, antes de abrir o banco — um servidor que promete
   * verificar identidade e não tem como fazê-lo precisa falhar de forma visível, não responder
   * 503 em cada requisição parecendo instabilidade.
   */
  missingRequirements(): string[] {
    const missing: string[] = [];
    if (this.requireFirebaseAdmin && !this.googleApplicationCredentials) {
      missing.push(
        'REQUIRE_FIREBASE_ADMIN=true, mas GOOGLE_APPLICATION_CREDENTIALS não está definido',
      );
    }
    if (this.requireGemini && !this.geminiApiKey) {
      missing.push('REQUIRE_GEMINI=true, mas GEMINI_API_KEY não está definido');
    }
    if (this.requireGemini && !this.aiEnabled) {
      missing.push('REQUIRE_GEMINI=true e AI_ENABLED=false são contraditórios');
    }
    // T17.9 §28 — produção não pode cair num diretório derivado para guardar mídia.
    //
    // O derivado é seguro em desenvolvimento e desastroso em produção: ele acompanharia
    // `DATABASE_PATH`, e um deploy que montasse o banco sem montar a mídia perderia todas as
    // fotos na primeira recriação de container — em silêncio, porque escrever num diretório
    // efêmero funciona perfeitamente até alguém reiniciar. Falhar no startup é visível.
    if (this.isProduction && !this.socialMediaRootIsExplicit) {
      missing.push(
        'NODE_ENV=production exige SOCIAL_MEDIA_ROOT apontando para um volume persistente',
      );
    }
    // T17.10 §136/§137 — a chave do tombstone não pode ser a de desenvolvimento em produção.
    //
    // Ela é o que liga o tombstone ao uid: subir com o default e trocá-lo depois faria **todas**
    // as exclusões já feitas deixarem de casar — conta excluída voltando a passar pelo guard, e a
    // reconciliação de DR deixando de reconhecê-la. Um default inseguro aqui é ressurreição
    // silenciosa esperando uma troca de configuração.
    if (this.isProduction && this.accountDeletionHmacKey === DEVELOPMENT_DELETION_HMAC_KEY) {
      missing.push(
        'NODE_ENV=production exige ACCOUNT_DELETION_HMAC_KEY própria (o default é de desenvolvimento)',
      );
    }
    return missing;
  }
}

export const APP_CONFIG = Symbol('APP_CONFIG');
