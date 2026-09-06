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
  /**
   * Credencial do Gemini (T16.2). Vive **apenas** no servidor: nunca no APK, no BuildConfig, em
   * resource, em DataStore, no Git ou em teste. A partir da T16.2 o Android não fala com o Gemini.
   *
   * Opcional: sem ela o processo sobe, o núcleo do Spark continua intacto e `/v1/ai/coach`
   * responde 503 (`AI_PROVIDER_UNAVAILABLE`) — nunca 200 sem ter chamado o modelo.
   */
  GEMINI_API_KEY: z.string().min(1).optional(),

  /**
   * Modelo do Coach. O default preserva exatamente o que a T14 usava via Firebase AI Logic:
   * a T16.2 é migração de transporte, não troca oportunista de modelo.
   */
  GEMINI_MODEL: z.string().min(1).default('gemini-3.6-flash'),

  /** Teto de tempo de uma chamada ao provider. O default é o mesmo da T14 (30 s). */
  AI_TIMEOUT_MS: z.coerce.number().int().min(1_000).max(120_000).default(30_000),

  /** Temperatura: análise pede consistência, não criatividade. Mesmo valor da T14. */
  AI_TEMPERATURE: z.coerce.number().min(0).max(2).default(0.2),

  /** Teto de saída, o mesmo da T14: evita custo acidental sem cortar uma análise real. */
  AI_MAX_OUTPUT_TOKENS: z.coerce.number().int().min(256).max(32_768).default(2_048),

  /** Esforço de raciocínio pedido ao modelo. Mesmo nível da T14. */
  AI_THINKING_LEVEL: z.enum(['MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'OFF']).default('MEDIUM'),

  /**
   * Teto diário de chamadas ao provider **por conta**.
   *
   * Existe como configuração, e não como número espalhado no código, porque é a proteção de
   * custo que muda com o uso real.
   */
  AI_MAX_REQUESTS_PER_USER_DAY: z.coerce.number().int().min(0).max(100_000).default(50),

  /**
   * Teto diário de chamadas ao provider no **servidor inteiro**.
   *
   * Um bug em vários aparelhos não pode transformar um app pessoal em uma conta inesperada de IA.
   */
  AI_MAX_REQUESTS_GLOBAL_DAY: z.coerce.number().int().min(0).max(1_000_000).default(500),

  /**
   * Chamadas simultâneas ao provider por conta. 1 de propósito: reduz custo acidental, toque
   * duplo, corrida e resultado fora de ordem.
   */
  AI_MAX_CONCURRENT_REQUESTS_PER_USER: z.coerce.number().int().min(1).max(8).default(1),
});

export type SparkEnv = z.infer<typeof envSchema>;
