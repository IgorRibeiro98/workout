import { z } from 'zod';

/**
 * Contrato de configuração do Spark Backend.
 *
 * Toda configuração vem do ambiente. Não existe valor secreto embutido no código, e não existe
 * inicialização parcialmente funcional: uma configuração obrigatória inválida derruba o processo
 * no startup (fail fast), antes de o servidor HTTP aceitar qualquer requisição.
 */
export const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),

  /** Porta HTTP. O TLS é responsabilidade do reverse proxy (Caddy), não deste processo. */
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),

  /**
   * Caminho do arquivo SQLite. Obrigatório e sem default: em container ele precisa apontar para
   * um volume persistente (ex.: `/data/spark.db`). Um default silencioso esconderia exatamente o
   * erro que queremos impossibilitar — banco vivendo só na camada efêmera do container.
   */
  DATABASE_PATH: z.string().min(1, 'DATABASE_PATH é obrigatório'),

  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),

  /**
   * `PRAGMA busy_timeout`. Centralizado aqui: nenhum outro ponto do código escolhe esse valor.
   */
  SQLITE_BUSY_TIMEOUT_MS: z.coerce.number().int().min(0).max(60_000).default(5_000),

  /** Tempo máximo para drenar conexões em SIGTERM/SIGINT antes de encerrar à força. */
  SHUTDOWN_TIMEOUT_MS: z.coerce.number().int().min(0).max(120_000).default(10_000),
});

export type SparkEnv = z.infer<typeof envSchema>;
