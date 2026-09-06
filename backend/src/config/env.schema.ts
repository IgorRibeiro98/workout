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

  /**
   * Credencial do Firebase Admin (T16.1), no mecanismo oficial para ambientes não-Google:
   * o **caminho** de um arquivo de service account que vive fora do repositório e é montado
   * somente-leitura no container. A chave privada nunca entra no Git, na imagem, no
   * `docker-compose.yml` nem neste schema — aqui só existe um caminho.
   *
   * Opcional: sem ela o processo sobe normalmente, `/health/*` continua público e as rotas
   * autenticadas respondem 503 (incapaz de verificar), nunca 200 sem verificação. Não existe
   * modo "autenticação desligada".
   */
  GOOGLE_APPLICATION_CREDENTIALS: z.string().min(1).optional(),

  /**
   * Projeto Firebase esperado pelo verificador. Opcional: normalmente vem do próprio arquivo de
   * credencial. Quando informado, é o `project_id` contra o qual o token precisa ter sido emitido.
   */
  FIREBASE_PROJECT_ID: z.string().min(1).optional(),
});

export type SparkEnv = z.infer<typeof envSchema>;
